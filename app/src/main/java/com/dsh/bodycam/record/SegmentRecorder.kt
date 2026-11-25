package com.dsh.bodycam.record

import android.content.Context
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.core.TriggerRouter
import com.dsh.bodycam.util.Storage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 录像器：MediaCodec 编码 + fMP4 封装，支持**无缝分段**。
 *
 * 为什么不用 MediaRecorder：它一个实例只能写一个文件，到点必须 stop/start，
 * 每次会丢几百毫秒画面和声音。这里编码器自始至终不停，只在分段点换一个
 * muxer（重新 addTrack 后接着写），所以：
 *   - 视频不丢帧：切换发生在 I 帧上，前一段以 I 帧收尾，后一段以 I 帧开头；
 *   - 音频只丢约 1 帧（<100ms）：切换瞬间还没写进新文件的采样被丢弃；
 *   - 每个文件都能独立播放（第一帧是 I 帧）。
 *
 * 封装用 media3 的 [Fmp4Segment]（内部就是 `FragmentedMp4Muxer`）：
 * ftyp+moov 在首个样本写入前落盘，之后按 moof+mdat 分片，每片自包含。
 * 进程被强杀时已写入的分片仍可直接播放。之前用
 * `MediaMuxer.MUXER_OUTPUT_FRAGMENTED_MPEG_4` 是无效的——该常量在 AOSP 里
 * 根本不存在，反射必失败并静默回退普通 MP4（moov 要等 close 才写，
 * 强杀后文件永久不可播放）。
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

        /** join 在 STOP_TIMEOUT_MS 之外多等的余量，确保线程先于编码器释放退出 */
        private const val JOIN_GRACE_MS = 1_000L

        /** muxer 就绪前最多缓存多少实时视频帧（约 2 秒 @30fps） */
        private const val MAX_PENDING_REALTIME_FRAMES = 60

        /**
         * muxer 迟迟建不起来就判定为致命失败（毫秒）。
         *
         * 正常情况下一两百毫秒就绪（等音频编码器吐格式）；超过这个时限还建不起来，
         * 只可能是存储写不进去（权限被 ROM 拒、只读挂载、磁盘满）。
         * 不设这个判据的话，视频帧会被无限缓存-丢弃，用户录满一整段却拿到
         * 0 个文件（见 MTK 机型的 mkdirs 失败案例）。
         */
        private const val MUXER_READY_TIMEOUT_MS = 3_000L

        /** [pendingSinceMs] 的空值哨兵 */
        private const val NO_PENDING = -1L
    }

    private val lock = Object()

    @Volatile
    private var started = false

    @Volatile
    private var stopping = false

    /**
     * 录制因存储不可写而彻底失败时的回调（在工作线程触发）。
     *
     * 一旦触发，本次录制已经没有意义：编码器还在跑，但一个字节都落不了盘。
     * 调用方必须按「停录」处理（收尾、复位 UI、提示用户），否则就是假录。
     */
    @Volatile
    var onFatalError: ((Storage.FailReason) -> Unit)? = null

    /** 已经上报过致命错误，避免每帧重复触发 */
    @Volatile
    private var fatalReported = false

    /**
     * 开始等待 muxer 就绪的时刻（elapsedRealtime 毫秒）；[NO_PENDING] = 当前没有在等。
     *
     * 用墙钟而不是帧 PTS：预录交接会把 basePtsUs 归零、切段也会重置时间基准，
     * 而这里要判断的是「连续多久有帧却一帧都写不进去」，跟视频时间轴无关。
     */
    private var pendingSinceMs = NO_PENDING

    /**
     * 最近一次分段创建失败的原因；[start] 失败后由调用方读取以给出准确提示。
     */
    @Volatile
    var lastCreateFailReason: Storage.FailReason? = null
        private set

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private var muxer: Fmp4Segment? = null
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    /**
     * 本次录制是否带声音（由 [start] 的 `audioEnabled` 写入）。
     *
     * 关闭时不创建音频编码器与 AudioRecord，也不起音频线程；
     * [startMuxerIfReadyLocked] 因此只等视频轨，成片是单视频轨 fMP4。
     */
    private var audioEnabledThisRun = true

    /**
     * 本次录制的加密口令（null = 不加密）。
     *
     * 在 [start] 时一次性定下，之后每个分段都用它加密：中途改设置只影响
     * 下一次录制，避免同一段录制的多个 part 用不同口令（回放时要逐个试）。
     */
    private var encryptPassphraseThisRun: String? = null

    /** 本次录制的音频参数（采样率/声道）；[start] 时定下，中途改设置不影响本段 */
    private var audioConfigThisRun: AudioConfig = AudioConfig.DEFAULT

    /** 本次录制的视频编码档位（[AppConfig.VIDEO_CODEC_AVC] / HEVC） */
    private var videoCodecTypeThisRun: Int = AppConfig.VIDEO_CODEC_DEFAULT

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
    private val files = ArrayList<Storage.Entry>()

    private var audioSamples = 0L
    private var audioBytes = 0L

    /** 本次录制期间麦克风的最大峰值（0~32767）；很小说明基本没录到声音 */
    @Volatile
    var lastMicPeak = 0
        private set

    /**
     * 当前正在写入的分段文件名；未录制时为空。
     *
     * 值本身存在 [TriggerRouter.recordingSegmentName]，这里只是给录制器内部
     * （磁盘清理跳过当前段）留的只读视图，避免两处状态各存一份导致不同步。
     */
    val currentSegmentName: String
        get() = TriggerRouter.recordingSegmentName

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
        preHandover: PreRecordBuffer.Handover? = null,
        audioEnabled: Boolean = true,
        encryptPassphrase: String? = null,
        codecType: Int = AppConfig.VIDEO_CODEC_DEFAULT,
        audioConfig: AudioConfig = AudioConfig.DEFAULT
    ): Surface? {
        synchronized(lock) {
            if (started) return null
            files.clear()
            segmentIndex = 0
            basePtsUs = -1L
            segmentStartPtsUs = 0L
            pendingSwitch = false
            pendingRealtimeVideo.clear()
            pendingSinceMs = NO_PENDING
            fatalReported = false
            lastCreateFailReason = null
            videoFormat = null
            audioFormat = null
            audioEnabledThisRun = audioEnabled
            encryptPassphraseThisRun = encryptPassphrase
            audioConfigThisRun = audioConfig
            videoCodecTypeThisRun = codecType
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
            //
            // 首个分段建不出来就说明存储链路不可用，直接让 start() 失败：
            // 不能像以前那样只记一条日志就继续跑编码器，那会变成「界面在录、
            // 实际 0 文件」的假录（MTK 机型 mkdirs 被 FUSE 拒绝就踩这个）。
            if (!createMuxerLocked()) {
                // 预录交接路径下编码器已经归本类所有（调用方不会再释放），
                // 这里必须自己收拾：先接管再 release，否则编码器与会话一起泄漏，
                // 下次开录会因「编码器已被占用」直接失败。
                if (preHandover != null) {
                    videoCodec = preHandover.videoCodec
                    releaseInternal()
                }
                return null
            }
        }

        return try {
            val surface: Surface
            if (preHandover != null) {
                videoCodec = preHandover.videoCodec
                videoFormat = preHandover.videoFormat
                surface = preHandover.videoSurface
                // 音频用同样配置另起一套；交接的 AAC 帧由音频线程先写进新音轨
                if (audioEnabled) startAudioCodec()
                synchronized(lock) { startMuxerIfReadyLocked() }
            } else {
                surface = startVideoCodec(width, height, fps, bitrate)
                if (audioEnabled) startAudioCodec()
            }
            stopping = false
            started = true
            videoThread = thread(name = "bodycam-video-enc") { videoLoop() }
            if (audioEnabled) {
                audioThread = thread(name = "bodycam-audio-enc") { audioLoop() }
            }
            surface
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            releaseInternal()
            null
        }
    }

    /**
     * 运行期改分段时长：只影响下一个分段边界，当前正在写的分段照原计划收尾。
     *
     * 设置页改完立即生效（不必等下次开录）。判据在视频帧回调里，
     * 所以新值比当前段已录时长还短时，下一个关键帧就会切段。
     */
    fun setSegmentSeconds(seconds: Int) {
        val durationUs = seconds.coerceAtLeast(1) * US_PER_SECOND
        synchronized(lock) {
            segmentDurationUs = durationUs
        }
    }

    /** 停止录制，返回本次产生的所有分段 */
    fun stop(): List<Storage.Entry> {
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

        // 线程内部的退出截止同样是 STOP_TIMEOUT_MS：如果 join 也用这个值，
        // 两个计时器几乎同时到点，join 返回时线程往往还在 dequeue/getOutputBuffer
        // 的最后一轮里，紧接着 releaseInternal() 释放编码器就会撞上
        // IllegalStateException。所以 join 必须多留一段余量，超时再补一次中断。
        joinQuietly(videoThread)
        joinQuietly(audioThread)
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
            // 空分段在 closeMuxerLocked 里已经 abort 掉了，这里剩下的都写过帧
            files.toList()
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
        val (codec, surface) = openVideoEncoder(videoCodecTypeThisRun, width, height, fps, bitrate)
        videoCodec = codec
        return surface
    }

    private fun startAudioCodec() {
        val (codec, record) = openAudioCapture(context, audioConfigThisRun)
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
                    // getOutputBuffer 在编码器已被 stop()/release() 后调用会抛
                    // IllegalStateException。stop() 里 join 有超时，超时后主线程
                    // 仍会释放编码器，而本线程可能刚 dequeue 到索引还没取缓冲——
                    // 这个窗口必然存在，所以取缓冲必须自己兜住异常直接退出循环，
                    // 不能让它冒到线程顶层变成 FATAL。
                    val buffer = try {
                        codec.getOutputBuffer(index)
                    } catch (e: Exception) {
                        Log.w(TAG, "video getOutputBuffer, codec released", e)
                        break
                    }
                    if (buffer != null) {
                        synchronized(lock) { onVideoFrameLocked(buffer, info, ptsUs, isKey) }
                    }
                }
            }
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.w(TAG, "video releaseOutputBuffer, codec released", e)
                break
            }
            if (eos) break
        }
    }

    /**
     * 把预录交接的视频帧写入当前 muxer（调用方需持有 lock 且已建轨）。
     * 时间零点 = 交接首帧（sessionStartUs），所以首帧 PTS 恰好为 0。
     */
    private fun writeHandoverFramesLocked(frames: List<PreRecordBuffer.Frame>) {
        val segment = muxer ?: return
        var n = 0
        for (f in frames) {
            val ptsUs = f.ptsUs - sessionStartUs
            if (ptsUs < 0) continue
            if (!segment.acceptVideoSample(f.flags)) continue
            if (segment.writeSample(segment.videoTrack, f.data, ptsUs, f.flags)) n++
        }
        Log.i(TAG, "wrote $n handover video frames")
    }

    /**
     * 补写 muxer 就绪前缓存的实时视频帧（调用方持锁）。
     *
     * 必须紧跟在交接帧之后、任何后续实时帧之前写：这些帧的 PTS 与后继帧连续，
     * 晚写或漏写都会在成片开头留下空洞（播放器在那里花屏/冻结）。
     * 普通录像没有交接帧，同样要刷——否则开头 0.25~1 秒永远缺失。
     */
    private fun flushPendingRealtimeLocked() {
        if (pendingRealtimeVideo.isEmpty()) return
        val segment = muxer ?: return
        var n = 0
        for (f in pendingRealtimeVideo) {
            val ptsUs = f.ptsUs - basePtsUs
            if (ptsUs < 0) continue
            if (!segment.acceptVideoSample(f.flags)) continue
            if (segment.writeSample(segment.videoTrack, f.data, ptsUs, f.flags)) n++
        }
        Log.i(TAG, "wrote $n buffered realtime video frames")
        pendingRealtimeVideo.clear()
    }

    /** 预录交接的音频帧（在音频线程里、建轨后写入） */
    private fun writeHandoverAudioLocked(frames: List<PreRecordBuffer.Frame>) {
        val segment = muxer ?: return
        for (f in frames) {
            val ptsUs = f.ptsUs - sessionStartUs
            if (ptsUs < 0) continue
            if (segment.writeSample(segment.audioTrack, f.data, ptsUs, f.flags)) {
                audioSamples++
                audioBytes += f.data.size
            }
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
        } else if (!pendingSwitch && muxer != null &&
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
        // switchSegmentLocked 可能刚换过 muxer，这里重新取一次
        val segment = muxer
        if (segment == null || segment.videoTrack < 0) {
            // 新段还没建轨（等音频编码器吐格式）：先缓存，别丢帧。
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
            // 超时判据：这么久还建不起 muxer，只能是存储写不进去。
            // 不处理的话会一直缓存-丢弃到用户按停止，最后交出 0 个文件。
            val now = SystemClock.elapsedRealtime()
            if (pendingSinceMs == NO_PENDING) pendingSinceMs = now
            if (now - pendingSinceMs > MUXER_READY_TIMEOUT_MS) {
                reportFatal(lastCreateFailReason ?: Storage.FailReason.IO_ERROR)
            }
            return
        }
        pendingSinceMs = NO_PENDING
        if (!segment.acceptVideoSample(info.flags)) return
        segment.writeSample(
            segment.videoTrack,
            buffer,
            ptsUs - basePtsUs,
            info.offset,
            info.size,
            info.flags
        )
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
                    val cfg = audioConfigThisRun
                    val ptsUs = audioStartUs + frames * US_PER_SECOND / cfg.sampleRate
                    frames += read.toLong() / cfg.pcmBytesPerFrame
                    feedAudio(codec, pcm, read, ptsUs, cfg)
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
                    val buffer = try {
                        codec.getOutputBuffer(index)
                    } catch (e: Exception) {
                        Log.w(TAG, "audio getOutputBuffer, codec released", e)
                        break
                    }
                    if (buffer != null) synchronized(lock) { onAudioFrameLocked(buffer, info) }
                }
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    Log.w(TAG, "audio releaseOutputBuffer, codec released", e)
                    break
                }
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
        val segment = muxer ?: return
        if (basePtsUs < 0) return

        val ptsUs = info.presentationTimeUs - sessionStartUs - basePtsUs
        if (ptsUs < 0) return
        if (segment.writeSample(segment.audioTrack, buffer, ptsUs, info.offset, info.size, info.flags)) {
            audioSamples++
            audioBytes += info.size
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
     * 建一个 fMP4 分段。
     *
     * ftyp+moov 在首个样本前落盘，数据按 moof+mdat 分片；进程被强杀时
     * 已写分片仍可直接播放。失败时保持 muxer == null，后续帧走缓存等待。
     *
     * @return 是否建成。首个分段失败（files 为空）会同时上报致命错误——
     *   存储写不进去时不能继续假录。
     */
    private fun createMuxerLocked(): Boolean {
        val name = "VID_${nameBase}_part${String.format(Locale.US, "%02d", segmentIndex)}.mp4"
        val result = Fmp4Segment.open(TAG, name, encryptPassphraseThisRun) {
            Storage.createVideo(context, it)
        }
        val segment = result.segment
        if (segment == null) {
            TriggerRouter.recordingSegmentName = ""
            lastCreateFailReason = result.failReason ?: Storage.FailReason.IO_ERROR
            // 第一个分段就建不出来 = 存储链路根本不可用，立即上报。
            // 后续分段（切段时）失败则留给超时判据兜，因为此时已有成品文件，
            // 不该把整场录制判死。
            if (files.isEmpty() && segmentIndex == 0) {
                reportFatal(lastCreateFailReason!!)
            }
            return false
        }
        muxer = segment
        TriggerRouter.recordingSegmentName = name
        files.add(segment.entry)
        return true
    }

    /** 上报一次致命失败；同一次录制只报一次 */
    private fun reportFatal(reason: Storage.FailReason) {
        if (fatalReported) return
        fatalReported = true
        Log.e(TAG, "recording failed, storage unavailable: $reason")
        onFatalError?.invoke(reason)
    }

    private fun startMuxerIfReadyLocked() {
        val segment = muxer ?: return
        // 视频轨必须有；音频轨只在本次录制带声音时才等
        val tracksReady = segment.videoTrack >= 0 &&
                (!audioEnabledThisRun || segment.audioTrack >= 0)
        if (tracksReady) return
        val vf = videoFormat ?: return
        // 关掉声音时不需要 audioFormat，否则会被它卡住不建轨
        val af = if (audioEnabledThisRun) (audioFormat ?: return) else null
        try {
            if (segment.videoTrack < 0) segment.addVideoTrack(vf.toVideoFormat(orientationHint))
            if (audioEnabledThisRun && af != null && segment.audioTrack < 0) {
                segment.addAudioTrack(af.toAudioFormat())
            }
        } catch (e: Exception) {
            Log.e(TAG, "start muxer failed", e)
            return
        }
        // 轨刚建好：立刻写入预录交接帧，必须排在所有实时帧之前
        flushHandoverLocked()
    }

    /** 写入预录交接的音视频帧（调用方持锁且已建轨） */
    private fun flushHandoverLocked() {
        pendingVideoHandover?.let { frames ->
            writeHandoverFramesLocked(frames)
            pendingVideoHandover = null
        }
        // 本次录制不带声音时不写交接的 AAC 帧：预录可能是在「声音开」时启动的，
        // 它的 Handover 里会带着音频帧，而此时根本没有音轨（track = -1）
        pendingAudioHandover?.let { frames ->
            if (audioEnabledThisRun) writeHandoverAudioLocked(frames)
            pendingAudioHandover = null
        }
        // 交接帧之后立刻补就绪前缓存的实时帧。普通录像没有交接帧，
        // 走预录关闭分支也必须刷，否则成片开头会缺 0.25~1 秒画面。
        flushPendingRealtimeLocked()
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
        val segment = muxer ?: return
        muxer = null
        // 先清进程级状态再 close：close 会写最后一片并可能耗时，
        // 期间若有人来播放，不能再把它当成「正在写」而拒绝。
        TriggerRouter.recordingSegmentName = ""
        // close() 会写出最后一个分片；没写过样本的空壳会自己 abort
        if (!segment.close()) files.remove(segment.entry)
    }

    // ---------------------------------------------------------------- 清理

    /**
     * 等线程退出，超时后中断并再等一次。
     *
     * 线程自身有 STOP_TIMEOUT_MS 的退出截止，这里额外留出 JOIN_GRACE_MS，
     * 避免 join 返回时线程还活着，紧接着 releaseInternal() 把编码器释放掉。
     */
    private fun joinQuietly(thread: Thread?) {
        thread ?: return
        try {
            thread.join(STOP_TIMEOUT_MS + JOIN_GRACE_MS)
            if (thread.isAlive) {
                Log.w(TAG, "thread ${thread.name} still alive, interrupting")
                thread.interrupt()
                thread.join(JOIN_GRACE_MS)
            }
            if (thread.isAlive) Log.w(TAG, "thread ${thread.name} did not exit")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

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
