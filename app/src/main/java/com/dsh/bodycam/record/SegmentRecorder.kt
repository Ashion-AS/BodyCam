package com.dsh.bodycam.record

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
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
import kotlin.math.max

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
 */
class SegmentRecorder(private val context: Context) {

    companion object {
        private const val TAG = "SegmentRecorder"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 1
        private const val AUDIO_BITRATE = 128_000
        private const val PCM_BYTES_PER_FRAME = 2 * CHANNELS
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_MS = 4_000L
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

    /** 音轨整体平移量；-1 表示还没确定 */
    private var audioShiftUs = -1L
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
     * @return 编码器的输入 Surface（交给 OpenGL 渲染），失败返回 null
     */
    fun start(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        orientation: Int,
        segmentSeconds: Int
    ): Surface? {
        synchronized(lock) {
            if (started) return null
            files.clear()
            segmentIndex = 0
            basePtsUs = -1L
            audioShiftUs = -1L
            segmentStartPtsUs = 0L
            pendingSwitch = false
            muxerStarted = false
            videoFormat = null
            audioFormat = null
            videoTrack = -1
            audioTrack = -1
            segmentDurationUs = segmentSeconds.coerceAtLeast(1) * 1_000_000L
            orientationHint = orientation
            // 必须用 CLOCK_BOOTTIME：相机帧时间戳（SENSOR_TIMESTAMP）就是这个时基。
            // 用 System.nanoTime()（CLOCK_MONOTONIC）的话，机器休眠过后两者会差出
            // 休眠总时长，音频 PTS 会全部小于视频基准，被当成「早于起点」全部丢弃。
            sessionStartUs = SystemClock.elapsedRealtimeNanos() / 1000
            nameBase = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            createMuxerLocked()
        }

        return try {
            val surface = startVideoCodec(width, height, fps, bitrate)
            startAudioCodec()
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
            val valid = files.filter { it.exists() && it.length() > 0 }
            // 清掉没写进任何帧的空文件（例如刚开始录就立刻停止）
            files.filter { !it.exists() || it.length() == 0L }.forEach { it.delete() }
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

    @SuppressLint("MissingPermission")  // 权限在 MainActivity 启动录像前已校验，这里再兜一层
    private fun startAudioCodec() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("缺少录音权限")
        }
        val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(AUDIO_MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, 8192) * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord 初始化失败")
        }
        record.startRecording()
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
                // 编码器启动前相机可能已经在缓冲帧，这些帧的时间戳早于本次录制起点，
                // 直接丢掉，否则会把 basePtsUs 拉成负数、让音轨整体后移（A/V 时长不一致）
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
        if (!muxerStarted || ptsUs < basePtsUs) return

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
        val pcm = ByteArray(8192)
        val info = MediaCodec.BufferInfo()

        var frames = 0L
        var audioStartUs = -1L
        var lastPtsUs = 0L
        var eosQueued = false
        var stopDeadline = 0L

        while (true) {
            if (stopping) {
                if (!eosQueued) eosQueued = queueAudioEos(codec, lastPtsUs)
            } else {
                val read = try {
                    record.read(pcm, 0, pcm.size)
                } catch (e: Exception) {
                    -1
                }
                if (read > 0) {
                    if (audioStartUs < 0) {
                        audioStartUs = SystemClock.elapsedRealtimeNanos() / 1000 - sessionStartUs
                    }
                    val ptsUs = audioStartUs + frames * 1_000_000L / SAMPLE_RATE
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
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun onAudioFrameLocked(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!muxerStarted || basePtsUs < 0) return

        val rawPts = info.presentationTimeUs
        if (audioShiftUs < 0L) {
            // 兜底：万一音视频时基还是对不上（个别机型时钟源不同），
            // 把整条音轨平移到视频起点，而不是把每一帧都当「早于起点」丢掉。
            audioShiftUs = if (rawPts < basePtsUs) basePtsUs - rawPts else 0L
            if (audioShiftUs > 0) {
                Log.w(TAG, "audio shifted by ${audioShiftUs}us to align with video")
            }
        }

        val ptsUs = rawPts + audioShiftUs
        if (ptsUs < basePtsUs) return
        val outInfo = MediaCodec.BufferInfo().apply {
            set(info.offset, info.size, ptsUs - basePtsUs, info.flags)
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

    private fun feedAudio(codec: MediaCodec, data: ByteArray, size: Int, ptsUs: Long) {
        var offset = 0
        var pts = ptsUs
        while (offset < size) {
            val index = try {
                codec.dequeueInputBuffer(DRAIN_TIMEOUT_US)
            } catch (e: Exception) {
                return
            }
            if (index < 0) return
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            val count = minOf(buffer.remaining(), size - offset)
            buffer.put(data, offset, count)
            codec.queueInputBuffer(index, 0, count, pts, 0)
            offset += count
            pts += count.toLong() * 1_000_000L / (SAMPLE_RATE * PCM_BYTES_PER_FRAME)
        }
    }

    private var micPeakMax = 0
    private var micLogCounter = 0

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
        if (++micLogCounter >= 12) {
            Log.i(TAG, "mic peak(1s)=$micPeakMax")
            micPeakMax = 0
            micLogCounter = 0
        }
    }

    private fun queueAudioEos(codec: MediaCodec, ptsUs: Long): Boolean {        return try {
            val index = codec.dequeueInputBuffer(DRAIN_TIMEOUT_US)
            if (index < 0) return false
            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- 分段 / 封装

    private fun createMuxerLocked() {
        val dir = Storage.videoDir(context)
        val name = "VID_${nameBase}_part${String.format(Locale.US, "%02d", segmentIndex)}.mp4"
        val file = File(dir, name)
        val muxer = try {
            MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
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
        try {
            videoCodec?.stop()
        } catch (e: Exception) {
        }
        try {
            videoCodec?.release()
        } catch (e: Exception) {
        }
        videoCodec = null

        try {
            audioCodec?.stop()
        } catch (e: Exception) {
        }
        try {
            audioCodec?.release()
        } catch (e: Exception) {
        }
        audioCodec = null

        try {
            audioRecord?.release()
        } catch (e: Exception) {
        }
        audioRecord = null

        synchronized(lock) { closeMuxerLocked() }
    }
}
