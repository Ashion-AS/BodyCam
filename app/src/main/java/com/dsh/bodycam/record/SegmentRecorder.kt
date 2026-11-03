package com.dsh.bodycam.record

import android.content.Context
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.dsh.bodycam.util.Storage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 录像器：MediaCodec 编码 + MediaMuxer 封装，支持**无缝分段**。
 *
 * 为什么不用 MediaRecorder：它一个实例只能写一个文件，到点必须 stop/start，
 * 每次会丢几百毫秒画面和声音。这里编码器自始至终不停，只在分段点换一个
 * MediaMuxer（重新 addTrack 后接着写），所以：
 *   - 视频不丢帧：切换发生在 I 帧上，前一段以 I 帧收尾，后一段以 I 帧开头；
 *   - 音频只丢约 1 帧（<100ms）：切换瞬间还没写进新文件的采样被丢弃；
 *   - 每个文件都能独立播放（第一帧是 I 帧）。
 *
 * 线程模型：
 *   video 线程：dequeue 视频编码器 → 写 muxer（分段判断也在这里做）
 *   audio 线程：AudioRecord → 编码器 → 写 muxer
 *   两个线程共用一把锁保护 muxer，切换文件时互斥。
 *
 * 时间戳：统一成「相对本次录制开始」的微秒。
 *   视频：SurfaceTexture 时间戳(ns) / 1000 - sessionStartUs
 *   音频：首个采样时刻 + 累计帧数换算
 * 两条轨同源，A/V 不会漂。
 *
 * 预录交接：`start(preHandover = ...)` 时接管预录的视频编码器，并把预录阶段
 * 已编码的音视频帧在 muxer 启动瞬间（[flushHandoverLocked]）一次性写入，
 * 时间零点取交接视频首帧。所以成片开头就是按下录像键之前的画面与声音，
 * 并且仍是**一个文件**。
 */
class SegmentRecorder(private val context: Context) {

    companion object {
        private const val TAG = "SegmentRecorder"
        private const val STOP_TIMEOUT_MS = 4_000L

        /** muxer 就绪前最多缓存多少实时视频帧（约 2 秒 @30fps） */
        private const val MAX_PENDING_REALTIME_FRAMES = 60
    }

    private val lock = Object()

    @Volatile
    private var started = false

    @Volatile
    private var stopping = false

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    private var sessionStartUs = 0L
    private var basePtsUs = -1L

    /** 预录交接的帧，muxer 启动时由 [flushHandoverLocked] 一次性写入 */
    private var pendingVideoHandover: List<PreRecordBuffer.Frame>? = null
    private var pendingAudioHandover: List<PreRecordBuffer.Frame>? = null

    /**
     * muxer 就绪前到达的实时视频帧。
     *
     * 交接后视频线程立刻开始出帧，但 muxer 要等新起的音频编码器吐出格式才
     * 能 start()（150~250ms）。这段时间的帧若直接丢弃，成片里就会留下一个
     * 几百毫秒的空洞（播放器在这一段只有花屏/冻结）。所以先缓存，等 muxer
     * 起来后紧跟交接帧顺序写入。
     */
    private val pendingRealtimeVideo = ArrayList<PreRecordBuffer.Frame>()

    private var segmentStartPtsUs = 0L
    private var segmentDurationUs = 30 * 60 * 1_000_000L
    private var pendingSwitch = false
    private var orientationHint = 0
    private var segmentIndex = 0
    private var nameBase = ""
    private val files = ArrayList<File>()

    /** 当前分段写入了多少个样本；为 0 说明只是建了个空壳文件 */
    private var segmentSamples = 0L
    private var audioSamples = 0L
    private var audioBytes = 0L
    private var currentFile: File? = null

    /** 本次录制期间麦克风的最大峰值（0~32767）；很小说明基本没录到声音 */
    @Volatile
    var lastMicPeak = 0
        private set

    private var sessionMicPeak = 0

    /**
     * 启动录制。
     * @param preHandover 预录交接（视频编码器 + 已编码的最近 N 秒音视频帧），
     *   null = 普通录像。传入时 [start] 接管预录的视频编码器与其输入 Surface
     *   （GL 全程不换 Surface，交接不丢帧），音频用同样配置另起一套编码器，
     *   并把交接的 AAC 帧先写进新音轨。
     * @return 编码器的输入 Surface（交给 OpenGL 渲染），失败返回 null
     */
    fun start(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        orientation: Int,
        segmentSeconds: Int,
        preHandover: PreRecordBuffer.Handover? = null
    ): Surface? {
        synchronized(lock) {
            if (started) return null
            files.clear()
            segmentIndex = 0
            basePtsUs = -1L
            segmentStartPtsUs = 0L
            pendingSwitch = false
            muxerStarted = false
            pendingRealtimeVideo.clear()
            videoFormat = null
            audioFormat = null
            videoTrack = -1
            audioTrack = -1
            segmentDurationUs = segmentSeconds.coerceAtLeast(1) * US_PER_SECOND
            orientationHint = orientation
            // 必须用 CLOCK_BOOTTIME：相机帧时间戳（SENSOR_TIMESTAMP）就是这个时基。
            // 用 System.nanoTime()（CLOCK_MONOTONIC）的话，机器休眠过后两者会差出
            // 休眠总时长，音频 PTS 会全部小于视频基准，被当成「早于起点」全部丢弃。
            sessionStartUs = SystemClock.elapsedRealtimeNanos() / 1000
            nameBase = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            // 预录交接：时间零点取交接视频首帧，成片开头即预录画面。
            // 交接块必须从关键帧起（GOP 对齐），否则解码器从缺参考帧的 P 帧
            // 开始解码，开局必花屏并持续到下一个 IDR。这里再做一次兜底裁剪：
            // 丢掉开头所有非关键帧。
            val rawVideo = preHandover?.videoFrames ?: emptyList()
            val alignedVideo = rawVideo.dropWhile {
                it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0
            }
            if (preHandover != null) {
                Log.i(
                    TAG,
                    "handover video ${rawVideo.size} -> ${alignedVideo.size} frames " +
                            "(dropped ${rawVideo.size - alignedVideo.size} pre-GOP)"
                )
            }
            // 交接块没有关键帧就没法干净解码，宁可整段丢掉走普通起点
            pendingVideoHandover = alignedVideo.takeIf { it.isNotEmpty() }
            if (alignedVideo.isNotEmpty()) {
                sessionStartUs = alignedVideo.first().ptsUs
                basePtsUs = 0L
                segmentStartPtsUs = 0L
                // 音频只能落在交接视频的时间范围内，否则音轨会伸进交接空档
                val startPts = alignedVideo.first().ptsUs
                val endPts = alignedVideo.last().ptsUs
                pendingAudioHandover = preHandover?.audioFrames
                    ?.filter { it.ptsUs in startPts..endPts }
                    ?.takeIf { it.isNotEmpty() }
            } else {
                pendingAudioHandover = null
            }
            // 交接块之后的实时帧来自同一编码器，与交接块尾部在编码流上连续，
            // 不需要等 IDR，也不需要丢弃（丢弃会在成片里留下音画空洞）。
            createMuxerLocked()
        }

        return try {
            val surface: Surface
            if (preHandover != null) {
                videoCodec = preHandover.videoCodec
                videoFormat = preHandover.videoFormat
                surface = preHandover.videoSurface
                // 音频用同样配置另起一套；交接的 AAC 帧由音频线程先写进新音轨
                startAudioCodec()
                synchronized(lock) { startMuxerIfReadyLocked() }
            } else {
                surface = startVideoCodec(width, height, fps, bitrate)
                startAudioCodec()
            }
            stopping = false
            started = true
            videoThread = thread(name = "bodycam-video-enc") { videoLoop() }
            audioThread = thread(name = "bodycam-audio-enc") { audioLoop() }
            surface
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            releaseInternal()
            null
        }
    }

    /** 停止录制，返回本次产生的所有分段文件 */
    fun stop(): List<File> {
        if (!started) return emptyList()
        started = false
        stopping = true

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "audioRecord.stop", e)
        }
        try {
            videoCodec?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream", e)
        }

        videoThread?.join(STOP_TIMEOUT_MS)
        audioThread?.join(STOP_TIMEOUT_MS)
        videoThread = null
        audioThread = null

        Log.i(
            TAG,
            "audio summary: samples=$audioSamples avgBytes=" +
                    (if (audioSamples > 0) audioBytes / audioSamples else 0L) +
                    " micPeak=$sessionMicPeak"
        )
        lastMicPeak = sessionMicPeak
        sessionMicPeak = 0
        audioSamples = 0
        audioBytes = 0

        releaseInternal()

        return synchronized(lock) {
            // 清掉没写进任何帧的空文件（例如刚开始录就立刻停止）
            val (valid, empty) = files.partition { it.exists() && it.length() > 0 }
            empty.forEach { it.delete() }
            valid
        }
    }

    fun release() {
        if (started) {
            stop()
        } else {
            releaseInternal()
        }
    }

    // ---------------------------------------------------------------- 编码器

    private fun startVideoCodec(width: Int, height: Int, fps: Int, bitrate: Int): Surface {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // 1 秒一个 I 帧：分段切换时最多等 1 秒就能拿到关键帧
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(VIDEO_MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        videoCodec = codec
        return surface
    }

    private fun startAudioCodec() {
        val (codec, record) = openAudioCapture(context)
        audioCodec = codec
        audioRecord = record
    }

    // ---------------------------------------------------------------- 视频线程

    private fun videoLoop() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        var stopDeadline = 0L

        while (true) {
            if (stopping && stopDeadline == 0L) {
                stopDeadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS
            }
            if (stopDeadline > 0 && SystemClock.elapsedRealtime() > stopDeadline) break

            val index = try {
                codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            } catch (e: Exception) {
                Log.w(TAG, "video dequeue", e)
                break
            }

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) {
                    videoFormat = codec.outputFormat
                    startMuxerIfReadyLocked()
                }
                continue
            }
            if (index < 0) continue

            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

            if (!isConfig && info.size > 0) {
                val ptsUs = info.presentationTimeUs - sessionStartUs
                // 预录交接场景：sessionStartUs 已回退到交接首帧，实时帧的
                // ptsUs 必然 ≥ 0 且衔接在交接帧之后（同一编码器同一时基）
                if (ptsUs >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) {
                        synchronized(lock) { onVideoFrameLocked(buffer, info, ptsUs, isKey) }
                    }
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (eos) break
        }
    }

    /**
     * 把预录交接的视频帧写入当前 muxer（调用方需持有 lock 且 muxerStarted）。
     * 时间零点 = 交接首帧（sessionStartUs），所以首帧 PTS 恰好为 0。
     */
    private fun writeHandoverFramesLocked(frames: List<PreRecordBuffer.Frame>) {
        var n = 0
        for (f in frames) {
            val ptsUs = f.ptsUs - sessionStartUs
            if (ptsUs < 0) continue
            if (writeSampleLocked(videoTrack, f.data, ptsUs, f.flags)) {
                segmentSamples++
                n++
            }
        }
        Log.i(TAG, "wrote $n handover video frames")
        // 紧接着写入 muxer 就绪前缓存下来的实时帧，保证同一轨 PTS 连续
        if (pendingRealtimeVideo.isNotEmpty()) {
            var m = 0
            for (f in pendingRealtimeVideo) {
                val ptsUs = f.ptsUs - basePtsUs
                if (ptsUs < 0) continue
                if (writeSampleLocked(videoTrack, f.data, ptsUs, f.flags)) {
                    segmentSamples++
                    m++
                }
            }
            Log.i(TAG, "wrote $m buffered realtime video frames")
            pendingRealtimeVideo.clear()
        }
    }

    /** 预录交接的音频帧（在音频线程里、muxer 起来后写入） */
    private fun writeHandoverAudioLocked(frames: List<PreRecordBuffer.Frame>) {
        if (!muxerStarted) return
        for (f in frames) {
            val ptsUs = f.ptsUs - sessionStartUs
            if (ptsUs < 0) continue
            if (writeSampleLocked(audioTrack, f.data, ptsUs, f.flags)) {
                segmentSamples++
                audioSamples++
                audioBytes += f.data.size
            }
        }
    }

    /** 写一个已是连续字节的样本到指定轨 */
    private fun writeSampleLocked(
        track: Int,
        data: ByteArray,
        ptsUs: Long,
        flags: Int
    ): Boolean {
        val outInfo = MediaCodec.BufferInfo().apply { set(0, data.size, ptsUs, flags) }
        return try {
            muxer?.writeSampleData(track, java.nio.ByteBuffer.wrap(data), outInfo)
            true
        } catch (e: Exception) {
            Log.w(TAG, "write sample", e)
            false
        }
    }

    private fun onVideoFrameLocked(
        buffer: java.nio.ByteBuffer,
        info: MediaCodec.BufferInfo,
        ptsUs: Long,
        isKey: Boolean
    ) {
        if (pendingSwitch && isKey) {
            switchSegmentLocked(ptsUs)
        } else if (!pendingSwitch && muxerStarted &&
            ptsUs - segmentStartPtsUs >= segmentDurationUs
        ) {
            // 到点了：请求一个 I 帧，等它到来再做切换
            pendingSwitch = true
            requestKeyFrameLocked()
        }

        if (basePtsUs < 0) {
            basePtsUs = ptsUs
            segmentStartPtsUs = ptsUs
        }
        if (ptsUs < basePtsUs) return
        if (!muxerStarted) {
            // muxer 还没起来（等音频编码器吐格式）：先缓存，别丢帧。
            // 丢了成片里就是一段空洞 → 播放到那里花屏/冻结。
            pendingRealtimeVideo.add(
                PreRecordBuffer.Frame(copyOut(buffer, info), ptsUs, info.flags)
            )
            // 上限兜底：正常情况下 muxer 一两百毫秒就起来了，缓存不会超过十几帧。
            // 万一起不来，也不能无限增长（12Mbps 下每帧约 50KB）。
            while (pendingRealtimeVideo.size > MAX_PENDING_REALTIME_FRAMES) {
                pendingRealtimeVideo.removeAt(0)
            }
            if (pendingRealtimeVideo.size == 1) {
                Log.i(TAG, "muxer not ready, buffering realtime video from ${ptsUs / 1000}ms")
            }
            return
        }

        val outInfo = MediaCodec.BufferInfo().apply {
            set(info.offset, info.size, ptsUs - basePtsUs, info.flags)
        }
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        try {
            muxer?.writeSampleData(videoTrack, buffer, outInfo)
            segmentSamples++
        } catch (e: Exception) {
            Log.w(TAG, "write video sample", e)
        }
    }

    private fun requestKeyFrameLocked() {
        try {
            videoCodec?.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            )
        } catch (e: Exception) {
            Log.w(TAG, "request sync frame", e)
        }
    }

    // ---------------------------------------------------------------- 音频线程

    private fun audioLoop() {
        val record = audioRecord ?: return
        val codec = audioCodec ?: return
        val pcm = ByteArray(PCM_BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()

        // 预录交接的音频帧由 muxer 启动时统一写入（flushHandoverLocked），
        // 这里不需要额外处理。
        var frames = 0L
        var audioStartUs = -1L
        var lastPtsUs = 0L
        var eosQueued = false
        var stopDeadline = 0L

        while (true) {
            if (stopping) {
                if (!eosQueued) eosQueued = queueEos(codec, lastPtsUs)
            } else {
                val read = try {
                    record.read(pcm, 0, pcm.size)
                } catch (e: Exception) {
                    -1
                }
                if (read > 0) {
                    if (audioStartUs < 0) {
                        // 音轨起点（原始时基，微秒）。预录交接时由交接带来的
                        // audioStartUs 接管，不会走到这里。
                        audioStartUs = SystemClock.elapsedRealtimeNanos() / 1000
                    }
                    // 送进编码器的 PTS 始终用绝对时基，和视频（相机时间戳）
                    // 同源；输出端再统一减 sessionStartUs 换成成片时间轴。
                    val ptsUs = audioStartUs + frames * US_PER_SECOND / SAMPLE_RATE
                    frames += read.toLong() / PCM_BYTES_PER_FRAME
                    feedAudio(codec, pcm, read, ptsUs)
                    lastPtsUs = ptsUs
                    logMicLevel(pcm, read)
                }
            }

            var eos = false
            while (true) {
                val index = try {
                    codec.dequeueOutputBuffer(info, 0)
                } catch (e: Exception) {
                    -1
                }
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(lock) {
                        audioFormat = codec.outputFormat
                        startMuxerIfReadyLocked()
                    }
                    continue
                }
                if (index < 0) break

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!isConfig && info.size > 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) synchronized(lock) { onAudioFrameLocked(buffer, info) }
                }
                codec.releaseOutputBuffer(index, false)
            }

            if (eos) break
            if (stopping) {
                if (stopDeadline == 0L) {
                    stopDeadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS
                }
                if (SystemClock.elapsedRealtime() > stopDeadline) break
                try {
                    Thread.sleep(STOP_POLL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun onAudioFrameLocked(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        // basePtsUs 是视频零点（普通录像时为第一条视频帧 PTS；预录交接时为 0）。
        // 编码器输出的 PTS 是绝对时基（与视频同源），减 sessionStartUs 换成
        // 成片相对时间轴，再减 basePtsUs 对齐视频起点。
        if (!muxerStarted || basePtsUs < 0) return

        val ptsUs = info.presentationTimeUs - sessionStartUs - basePtsUs
        if (ptsUs < 0) return
        val outInfo = MediaCodec.BufferInfo().apply {
            set(info.offset, info.size, ptsUs, info.flags)
        }
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        try {
            muxer?.writeSampleData(audioTrack, buffer, outInfo)
            segmentSamples++
            audioSamples++
            audioBytes += info.size
        } catch (e: Exception) {
            Log.w(TAG, "write audio sample", e)
        }
    }

    private var micPeakMax = 0
    private var micSamplesSinceLog = 0

    /**
     * 每秒打印一次麦克风峰值（16bit PCM 的绝对最大值，满量程 32767）。
     * 全 0 就说明根本没采到声音（静音/被占用/权限问题），便于现场定位。
     */
    private fun logMicLevel(data: ByteArray, size: Int) {
        var peak = 0
        var i = 0
        while (i + 1 < size) {
            val v = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort().toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            i += 2
        }
        if (peak > micPeakMax) micPeakMax = peak
        if (peak > sessionMicPeak) sessionMicPeak = peak
        if (++micSamplesSinceLog >= 12) {
            Log.i(TAG, "mic peak(1s)=$micPeakMax")
            micPeakMax = 0
            micSamplesSinceLog = 0
        }
    }

    // ---------------------------------------------------------------- 分段 / 封装

    /**
     * fMP4 的 `MUXER_OUTPUT_FRAGMENTED_MPEG_4` 是 @hide 常量（SDK 桩里没有），
     * 只能反射拿。它从 API 26 起在运行时必然存在（本工程 minSdk 就是 26），
     * 拿不到就退回传统 MP4。
     * 返回的 MediaMuxer.OutputFormat 常量可安全传给 MediaMuxer 构造。
     */
    private fun fragmentedFormat(): Int {
        return try {
            MediaMuxer.OutputFormat::class.java
                .getDeclaredField("MUXER_OUTPUT_FRAGMENTED_MPEG_4")
                .getInt(null)
        } catch (e: Exception) {
            Log.w(TAG, "FMP4 unavailable, fallback to MP4", e)
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }
    }

    private fun createMuxerLocked() {
        val dir = Storage.videoDir(context)
        val name = "VID_${nameBase}_part${String.format(Locale.US, "%02d", segmentIndex)}.mp4"
        val file = File(dir, name)
        val muxer = try {
            // 用 fMP4 而非传统 MP4：moov/mvex 前置、数据按 moof+mdat 分片自包含，
            // 进程被强杀时已写入的 fragment 仍可直接播放（只有最后不到 1 秒可能丢）。
            // 传统 MP4 的 moov 要等 stop() 才落盘，强杀就整段报废。
            MediaMuxer(file.absolutePath, fragmentedFormat()).apply {
                setOrientationHint(orientationHint)
            }
        } catch (e: Exception) {
            Log.e(TAG, "create muxer failed", e)
            return
        }
        this.muxer = muxer
        muxerStarted = false
        videoTrack = -1
        audioTrack = -1
        segmentSamples = 0L
        currentFile = file
        files.add(file)
    }

    private fun startMuxerIfReadyLocked() {
        if (muxerStarted) return
        val muxer = muxer ?: return
        val vf = videoFormat ?: return
        val af = audioFormat ?: return
        try {
            videoTrack = muxer.addTrack(vf)
            audioTrack = muxer.addTrack(af)
            muxer.start()
            muxerStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "start muxer failed", e)
            return
        }
        // muxer 刚起来：立刻写入预录交接帧，必须排在所有实时帧之前，
        // 否则同一轨 PTS 会倒序（MediaMuxer 要求非递减）。
        flushHandoverLocked()
    }

    /** 写入预录交接的音视频帧（调用方持锁且 muxerStarted） */
    private fun flushHandoverLocked() {
        pendingVideoHandover?.let { frames ->
            writeHandoverFramesLocked(frames)
            pendingVideoHandover = null
        }
        pendingAudioHandover?.let { frames ->
            writeHandoverAudioLocked(frames)
            pendingAudioHandover = null
        }
    }

    private fun switchSegmentLocked(nextPtsUs: Long) {
        closeMuxerLocked()
        segmentIndex++
        createMuxerLocked()
        startMuxerIfReadyLocked()
        basePtsUs = nextPtsUs
        segmentStartPtsUs = nextPtsUs
        pendingSwitch = false
        Log.i(TAG, "segment $segmentIndex started at ${nextPtsUs / 1000}ms")
    }

    private fun closeMuxerLocked() {
        val muxer = muxer ?: return
        this.muxer = null
        try {
            if (muxerStarted) muxer.stop()
        } catch (e: Exception) {
            Log.w(TAG, "muxer.stop", e)
        }
        try {
            muxer.release()
        } catch (e: Exception) {
            Log.w(TAG, "muxer.release", e)
        }
        muxerStarted = false
        videoTrack = -1
        audioTrack = -1

        // 一帧都没写进去的分段（点了就停）直接删掉，避免留下几 KB 的空壳文件
        val file = currentFile
        if (segmentSamples == 0L && file != null) {
            runCatching { file.delete() }
            files.remove(file)
        }
        currentFile = null
        segmentSamples = 0L
    }

    // ---------------------------------------------------------------- 清理

    private fun releaseInternal() {
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        videoCodec = null
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        synchronized(lock) { closeMuxerLocked() }
    }
}
