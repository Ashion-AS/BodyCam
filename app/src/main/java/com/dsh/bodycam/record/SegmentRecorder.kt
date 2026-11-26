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
 * 时间戳：统一成「相对本次录制零点」的微秒。
 *   视频：相机 PTS − 首帧视频 PTS（零点由第一帧视频自己锚定）
 *   音频：首个采样时刻 + 累计帧数换算，再减去「音频-视频时基差」
 *
 * **不要假设两条轨同源**：相机 PTS 可能是 CLOCK_MONOTONIC（MTK），音频 PTS 走
 * `elapsedRealtimeNanos()`（CLOCK_BOOTTIME）。设备休眠过就相差一个休眠时长，
 * 所以音频时基差在运行时实测（见 [audioPtsOffsetUs]），而不是靠时钟假设。
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
        private const val MAX_PENDING_REALTIME_FRAMES = 180

    /** 交接块写入期间缓存的实时音频帧上限（每帧几百字节，约 12 秒） */
    private const val MAX_PENDING_REALTIME_AUDIO_FRAMES = 400

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

    /**
     * 视频时间轴是否已锚定。
     *
     * 零点不能取墙钟：相机帧时间戳的时基各平台不一致——Pixel 走
     * CLOCK_BOOTTIME（含休眠），MTK 的 DSJ-D100 走 CLOCK_MONOTONIC（不含休眠）。
     * 只要设备休眠过，两者就差出休眠总时长，拿 elapsedRealtime 当零点会把
     * 所有视频帧算成负 PTS 全部丢掉。所以零点一律取「第一条要写入的视频帧」。
     */
    private var videoClockAnchored = false

    /**
     * 音频相对视频的固定时基差（µs，= 音频 PTS − 视频 PTS），-1 = 尚未测出。
     *
     * 音频 PTS 由本类用 `elapsedRealtimeNanos()` 生成（BOOTTIME），视频 PTS 来自
     * 相机（MTK 上是 MONOTONIC）。首帧视频到达时用 [Timebase.detect] 判定并算出差值，
     * 之后所有音频都按这个偏移平移；交接带来的音频沿用预录侧测好的同一个值。
     */
    private var audioPtsOffsetUs = -1L

    /**
     * 预录交接过来时，音频时间轴要继续的起点（原始时基，µs）；-1 = 自己起算。
     *
     * 交接把预录的 AudioRecord 与 AAC 编码器整体接了过来，采样计数必须接着数，
     * 否则要么断一截（空洞）要么比实际短（音画永久漂移）。
     */
    private var audioStartUsSeed = -1L

    /** 预录交接的帧，muxer 建好轨后由 [flushHandoverLocked] 一次性写入 */
    private var pendingVideoHandover: List<PreRecordBuffer.Frame>? = null
    private var pendingAudioHandover: List<PreRecordBuffer.Frame>? = null

    /**
     * true = 交接块还没写完，两条轨的实时帧都只能先缓存。
     *
     * 交接块有 5 秒、几 MB，写它要几十上百毫秒（存储慢的机器上更久）。这段时间
     * 若让编码器停在那里等，输出队列一满 GL 就卡在 eglSwapBuffers 上，相机会
     * 丢帧，成片里就是交接处那段空洞。所以：视频/音频线程一启动就开始排空、
     * 帧先进内存，交接块由单独的线程一次写完，写完再按序补上实时帧。
     */
    private var handoverPending = false

    /** 交接块写入线程，stop() 时要 join */
    private var handoverWriterThread: Thread? = null

    /** start() 入口时刻与「首帧视频到达」日志标记，用于量交接耗时 */
    private var startAtMs = 0L
    private var firstFrameLogged = false

    /**
     * muxer 就绪前到达的实时视频帧。
     *
     * 交接后视频线程立刻开始出帧，但 muxer 要等新起的音频编码器吐出格式才
     * 能 start()（150~250ms）。这段时间的帧若直接丢弃，成片里就会留下一个
     * 几百毫秒的空洞（播放器在这一段只有花屏/冻结）。所以先缓存，等 muxer
     * 起来后紧跟交接帧顺序写入。
     */
    private val pendingRealtimeVideo = ArrayList<PreRecordBuffer.Frame>()

    /** 交接块写入期间到达的实时音频帧（每帧几百字节，随便存） */
    private val pendingRealtimeAudio = ArrayDeque<PreRecordBuffer.Frame>()

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
            startAtMs = SystemClock.elapsedRealtime()
            firstFrameLogged = false
            files.clear()
            segmentIndex = 0
            basePtsUs = -1L
            segmentStartPtsUs = 0L
            pendingSwitch = false
            pendingRealtimeVideo.clear()
            pendingRealtimeAudio.clear()
            handoverPending = false
            handoverWriterThread = null
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
            // 视频零点等第一帧视频到达时再定（普通录像），或者用交接首帧（预录）。
            // 不能在这里取墙钟：相机时间戳的时基可能是 MONOTONIC 也可能是
            // BOOTTIME，两者差一个休眠时长，用墙钟当基准会整段丢弃。
            sessionStartUs = 0L
            videoClockAnchored = false
            audioPtsOffsetUs = -1L
            audioStartUsSeed = -1L
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
            if (alignedVideo.isNotEmpty() && preHandover != null) {
                sessionStartUs = alignedVideo.first().ptsUs
                videoClockAnchored = true
                basePtsUs = 0L
                segmentStartPtsUs = 0L
                // 交接带来的音频帧还在音频自己的时钟上，用预录侧按视频尾帧判定的
                // 时基差平移，再按视频区间裁剪；否则 MTK 上会一帧都裁不出来
                // （音频 PTS 比视频大一个休眠时长，全部落在区间之外）。
                //
                // **无条件赋值**（不能只在这批音频帧非空时赋值）：预录音频队列
                // 恰好为空的场合，偏移会停留在 -1，后面就只能拿音频帧去猜时钟；
                // 而音频恒为 BOOTTIME，猜出来必然是「不用平移」——MTK 上又会
                // 把声音整体排到几百秒之后。这个值只取决于视频时钟，与有没有
                // 音频帧无关。
                audioPtsOffsetUs = preHandover.audioPtsOffsetUs
                val offset = preHandover.audioPtsOffsetUs
                val startPts = alignedVideo.first().ptsUs
                // 只保留下限：音频尾部允许越过视频末帧一小截。上限卡死会把这
                // 几帧丢掉，而实时音频是同一编码器紧接着来的，中间就空出 64~128ms。
                pendingAudioHandover = preHandover?.audioFrames
                    ?.filter { it.ptsUs - offset >= startPts }
                    ?.takeIf { it.isNotEmpty() }
                // 立刻接管编码器并起排空线程：建 muxer、建轨还要几十毫秒，
                // 而编码器的输出队列只有几帧余量——晚起线程 → 输出队列满 →
                // GL 卡在 eglSwapBuffers → 相机丢帧，交接处就是一段空洞。
                // 此刻 muxer 还没建好，帧先走 onVideoFrameLocked 的缓存分支。
                videoCodec = preHandover.videoCodec
                videoFormat = preHandover.videoFormat
                stopping = false
                started = true
                videoThread = thread(name = "bodycam-video-enc") { videoLoop() }
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
                // 这里必须自己收拾：先让排空线程停手，再 release，否则编码器
                // 与会话一起泄漏，下次开录会因「编码器已被占用」直接失败。
                stopping = true
                started = false
                joinQuietly(videoThread)
                videoThread = null
                if (preHandover != null) {
                    videoCodec = preHandover.videoCodec
                    releaseInternal()
                }
                return null
            }
            if (preHandover != null) {
                Log.i(
                    TAG,
                    "muxer created ${SystemClock.elapsedRealtime() - startAtMs}ms after start()"
                )
            }
        }

        return try {
            val surface: Surface
            if (preHandover != null) {
                // 编码器与排空线程已在上面接管好了（不能等到这里，否则
                // 建 muxer/建轨这几十毫秒里编码器没人取输出、相机就丢帧）
                surface = preHandover.videoSurface
                // 音频：把预录那套「AudioRecord + AAC 编码器」整体接过来，
                // 既不拆也不重建。拆一次重建要 0.4~0.9 秒（最坏实测 15 秒），
                // 这期间主线程被占着 → 视频编码器没人取输出 → GL 卡在
                // eglSwapBuffers → 相机丢帧；而音频则整段没采，成片里画面
                // 和声音各空一截（就是「卡一下然后跳过去」的成因）。
                val hCodec = preHandover.audioCodec
                val hRecord = preHandover.audioRecord
                if (audioEnabled && hCodec != null && hRecord != null) {
                    audioCodec = hCodec
                    audioRecord = hRecord
                    audioFormat = preHandover.audioFormat
                    audioStartUsSeed = preHandover.audioNextPtsUs
                    Log.i(
                        TAG,
                        "audio capture transferred, timeline continues at " +
                                "${audioStartUsSeed / 1000}ms"
                    )
                } else {
                    // 预录没带音频，或本次关声音：把移交过来的资源收掉再按需重建
                    releaseQuietly(hCodec, hRecord)
                    if (audioEnabled) startAudioCodec()
                }
                // 两条轨的格式此刻都齐了。建轨与交接块都交给后台线程：
                // 视频线程已经在排空，主线程这几十毫秒的 I/O 会把它憋住。
                synchronized(lock) {
                    handoverPending = pendingVideoHandover != null
                }
                if (handoverPending) {
                    handoverWriterThread = thread(name = "bodycam-handover") {
                        writeHandoverBlock()
                    }
                } else {
                    synchronized(lock) { startMuxerIfReadyLocked() }
                }
            } else {
                surface = startVideoCodec(width, height, fps, bitrate)
                if (audioEnabled) startAudioCodec()
                stopping = false
                started = true
                videoThread = thread(name = "bodycam-video-enc") { videoLoop() }
            }
            if (audioEnabled) {
                audioThread = thread(name = "bodycam-audio-enc") { audioLoop() }
            }
            surface
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            // 交接路径下视频线程可能已经起来了：先让它在 stopping 上退出，
            // 再释放编码器，避免「释放后线程还在 dequeue」的窗口
            stopping = true
            started = false
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
        // 交接块写入线程也要等：它还在往 muxer 写，提前 close 会撞上已关闭的通道
        joinQuietly(handoverWriterThread)
        handoverWriterThread = null
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

    /** 收掉移交过来但本次用不上的音频资源 */
    private fun releaseQuietly(codec: MediaCodec?, record: AudioRecord?) {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
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
                // 这里不再有「负 PTS 直接丢弃」：零点由第一帧视频自己锚定
                // （见 onVideoFrameLocked），相机时间戳是哪种时钟都不影响。
                //
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
                    synchronized(lock) {
                        onVideoFrameLocked(buffer, info, info.presentationTimeUs, isKey)
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
        if (audioPtsOffsetUs < 0) return
        for (f in frames) {
            val ptsUs = f.ptsUs - audioPtsOffsetUs - sessionStartUs
            if (ptsUs < 0) continue
            if (segment.writeSample(segment.audioTrack, f.data, ptsUs, f.flags)) {
                audioSamples++
                audioBytes += f.data.size
            }
        }
    }

    /** 补写交接块写入期间缓存的实时音频帧（调用方持锁） */
    private fun flushPendingAudioLocked() {
        if (pendingRealtimeAudio.isEmpty()) return
        val segment = muxer ?: return
        var n = 0
        while (pendingRealtimeAudio.isNotEmpty()) {
            val f = pendingRealtimeAudio.removeFirst()
            val ptsUs = f.ptsUs - audioPtsOffsetUs - sessionStartUs
            if (ptsUs < 0) continue
            if (segment.writeSample(segment.audioTrack, f.data, ptsUs, f.flags)) {
                audioSamples++
                audioBytes += f.data.size
                n++
            }
        }
        Log.i(TAG, "wrote $n buffered realtime audio frames")
    }

    private fun onVideoFrameLocked(
        buffer: java.nio.ByteBuffer,
        info: MediaCodec.BufferInfo,
        rawPtsUs: Long,
        isKey: Boolean
    ) {
        // 第一帧视频即时间零点。相机 PTS 走 MONOTONIC 还是 BOOTTIME 都无所谓，
        // 因为这里不做任何跨时钟运算。
        if (!videoClockAnchored) {
            sessionStartUs = rawPtsUs
            videoClockAnchored = true
            // 顺手判定视频时间戳的时基：音频（BOOTTIME）要按这个差值平移，
            // 否则在休眠过的设备上会整体错开几百秒。
            val base = Timebase.detect(rawPtsUs)
            audioPtsOffsetUs = base.audioOffsetUs
            Log.i(
                TAG,
                "video clock anchored at ${rawPtsUs / 1000}ms, camera=${base.cameraClock}, " +
                        "sleep=${base.sleepUs / 1000}ms -> audio offset=${audioPtsOffsetUs / 1000}ms"
            )
        }
        val ptsUs = rawPtsUs - sessionStartUs

        if (!firstFrameLogged) {
            firstFrameLogged = true
            Log.i(
                TAG,
                "first video frame drained ${SystemClock.elapsedRealtime() - startAtMs}ms " +
                        "after start(), pts=${ptsUs / 1000}ms"
            )
        }

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
        if (segment == null || segment.videoTrack < 0 || handoverPending) {
            // 轨还没建好，或交接块还没写完：先缓存，别丢帧、也别抢在交接块前面写。
            // 丢了成片里就是一段空洞 → 播放到那里花屏/冻结。
            pendingRealtimeVideo.add(
                PreRecordBuffer.Frame(copyOut(buffer, info), ptsUs, info.flags)
            )
            // 上限兜底：存储慢的机器上交接块要写几百毫秒，5 秒预录约 150 帧。
            // 万一起不来，也不能无限增长（12Mbps 下每帧约 50KB）。
            while (pendingRealtimeVideo.size > MAX_PENDING_REALTIME_FRAMES) {
                pendingRealtimeVideo.removeAt(0)
            }
            if (pendingRealtimeVideo.size == 1) {
                Log.i(TAG, "muxer not ready, buffering realtime video from ${ptsUs / 1000}ms")
            }
            // 超时判据：这么久还建不起 muxer，只能是存储写不进去。
            // 不处理的话会一直缓存-丢弃到用户按停止，最后交出 0 个文件。
            // 只判「muxer/轨建不起来」；交接块还在写属于正常耗时，不能当故障。
            if (segment == null || segment.videoTrack < 0) {
                val now = SystemClock.elapsedRealtime()
                if (pendingSinceMs == NO_PENDING) pendingSinceMs = now
                if (now - pendingSinceMs > MUXER_READY_TIMEOUT_MS) {
                    reportFatal(lastCreateFailReason ?: Storage.FailReason.IO_ERROR)
                }
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
        // 交接过来的音频接着预录的时间轴往下走（audioStartUsSeed），
        // 自己起的采集才用墙钟起算
        var audioStartUs = audioStartUsSeed
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
                    // 送进编码器的 PTS 用本类自己的时钟（elapsedRealtime，BOOTTIME）。
                    // 它和视频（相机时间戳）不一定同源，输出端由 audioPtsOffsetUs
                    // 平移回视频时基，不能直接与视频 PTS 相减。
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
        // 视频零点还没定：开头这几十毫秒的音频先丢，等第一帧视频到达
        if (!videoClockAnchored) return

        val rawPtsUs = info.presentationTimeUs
        if (audioPtsOffsetUs < 0) {
            // 正常到不了这里：普通录像在首帧视频锚定时就一起算出了偏移，
            // 预录交接则无条件沿用预录侧按视频尾帧判定的值。
            //
            // 万一真的没算出来，**不能用这帧音频去猜**——音频恒为 BOOTTIME，
            // 猜出来必然是「不用平移」，在 MTK（相机 MONOTONIC）上就会把整条
            // 音轨排到几百秒之后，正是最早那三个症状的成因。宁可丢掉开头这
            // 几帧音频（几十毫秒），也不能让时间轴错位几百秒。
            Log.w(TAG, "audio pts offset unknown, dropping frame at ${rawPtsUs / 1000}ms")
            return
        }
        val ptsUs = rawPtsUs - audioPtsOffsetUs - sessionStartUs
        if (ptsUs < 0) return
        val segment = muxer
        if (segment == null || segment.audioTrack < 0 || handoverPending) {
            // 交接块还在写：先缓存（每帧几百字节），等它写完再按序补上，
            // 否则实时音频会抢在交接音频前面写进音轨，时间轴倒序。
            pendingRealtimeAudio.addLast(
                PreRecordBuffer.Frame(copyOut(buffer, info), rawPtsUs, info.flags)
            )
            while (pendingRealtimeAudio.size > MAX_PENDING_REALTIME_AUDIO_FRAMES) {
                pendingRealtimeAudio.removeFirst()
            }
            return
        }
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

    /**
     * 建轨（视频轨必须有，带声音时再加上音频轨）。不写样本，很轻。
     * @return true = 两条轨都建好了
     */
    private fun createTracksLocked(): Boolean {
        val segment = muxer ?: return false
        val tracksReady = segment.videoTrack >= 0 &&
                (!audioEnabledThisRun || segment.audioTrack >= 0)
        if (tracksReady) return true
        val vf = videoFormat ?: return false
        // 关掉声音时不需要 audioFormat，否则会被它卡住不建轨
        val af = if (audioEnabledThisRun) (audioFormat ?: return false) else null
        try {
            if (segment.videoTrack < 0) segment.addVideoTrack(vf.toVideoFormat(orientationHint))
            if (audioEnabledThisRun && af != null && segment.audioTrack < 0) {
                segment.addAudioTrack(af.toAudioFormat())
            }
        } catch (e: Exception) {
            Log.e(TAG, "start muxer failed", e)
            return false
        }
        return segment.videoTrack >= 0 && (!audioEnabledThisRun || segment.audioTrack >= 0)
    }

    private fun startMuxerIfReadyLocked() {
        if (!createTracksLocked()) return
        // 交接块还没写完：由 bodycam-handover 线程写完后统一补，别在这里抢先写
        if (handoverPending) return
        // 没有交接块（普通录像，或交接块已写完）：把就绪前缓存的实时帧补上
        flushPendingRealtimeLocked()
        flushPendingAudioLocked()
    }

    /**
     * 把一个交接块（最多 N 秒、几 MB）写进 muxer。
     *
     * **刻意不持锁**：这期间视频/音频线程只把实时帧往内存里缓存、不碰 muxer，
     * muxer 归本线程独占。若全程持锁，就轮到两个采集线程被卡住——编码器输出
     * 队列一满，GL 卡在 eglSwapBuffers 上、相机丢帧，交接处照样空一段
     * （实测持锁写法会空出 100ms，不持锁则一帧不缺）。
     *
     * 写完后短暂上锁：清标志并按序补写缓存下来的实时帧。
     */
    private fun writeHandoverBlock() {
        val video: List<PreRecordBuffer.Frame>?
        val audio: List<PreRecordBuffer.Frame>?
        val segment: Fmp4Segment?
        synchronized(lock) {
            video = pendingVideoHandover
            audio = pendingAudioHandover
            pendingVideoHandover = null
            pendingAudioHandover = null
            // 建轨也放在这个线程：media3 建轨要写一次 moov，在 FUSE 上是几十毫秒。
            // 放在主线程（start() 里）会把视频线程那口气憋住，编码器再次反压。
            createTracksLocked()
            segment = muxer
        }
        if (segment == null || segment.videoTrack < 0) {
            synchronized(lock) { handoverPending = false }
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        var n = 0
        try {
            if (video != null) {
                for (f in video) {
                    val ptsUs = f.ptsUs - sessionStartUs
                    if (ptsUs < 0) continue
                    if (!segment.acceptVideoSample(f.flags)) continue
                    if (segment.writeSample(segment.videoTrack, f.data, ptsUs, f.flags)) n++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "write handover video block failed", e)
        }
        Log.i(
            TAG,
            "wrote $n handover video frames in ${SystemClock.elapsedRealtime() - t0}ms"
        )
        synchronized(lock) {
            if (audio != null && audioEnabledThisRun) writeHandoverAudioLocked(audio)
            handoverPending = false
            flushPendingRealtimeLocked()
            flushPendingAudioLocked()
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
