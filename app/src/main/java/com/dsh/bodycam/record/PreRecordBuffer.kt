package com.dsh.bodycam.record

import android.content.Context
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.dsh.bodycam.core.AppConfig

/**
 * 预录环形缓存（视频 + 音频）。
 *
 * 开启预录后，视频与音频编码器一直挂着：
 *   - GL 管线持续往 [start] 返回的 Surface 画帧（带水印），视频线程把编码
 *     后的 H.264 帧存进视频环形队列；
 *   - AudioRecord 持续采集，音频线程把编码后的 AAC 帧存进音频环形队列。
 *
 * 两个队列都只保留「最近 bufferSeconds 秒」，旧帧丢弃。
 * 内存占用：视频 30s@12Mbps ≈ 45MB，音频 30s@128kbps ≈ 0.5MB。
 *
 * 正式录像开始时走「交接」：[beginHandover] 把视频编码器、输入 Surface 和
 * 两个队列里最近 N 秒的帧一并交给 [SegmentRecorder]。录制器继续用同一个视频
 * 编码器编码，并另起一套音频编码器，先把交接帧写入再写实时帧——成片是
 * **一个文件**，开头就是按下录像键之前的画面与声音，交接瞬间不丢帧。
 *
 * 时间基：视频帧 PTS 来自相机 SurfaceTexture 时间戳，音频 PTS 用
 * `elapsedRealtimeNanos()/1000` 起算。**两者未必同源**——相机可能走
 * CLOCK_MONOTONIC（MTK），而 elapsedRealtime 是 CLOCK_BOOTTIME，设备休眠过
 * 就会差出一个休眠时长。交接时由 [Timebase] 判定相机时钟并算出
 * `audioPtsOffsetUs`，录制器把音频平移回视频时基，任何地方都不再假设可直接相减。
 *
 * 线程模型：视频线程只碰视频编码器，音频线程只碰音频编码器 + AudioRecord；
 * 交接时先各自 join，再移交视频编码器，音频编码器另行释放（录制器重起）。
 */
class PreRecordBuffer(private val context: Context) {

    companion object {
        private const val TAG = "PreRecordBuffer"
        private const val STOP_TIMEOUT_MS = 2_000L
    }

    /** 队列里的一个编码后样本（原始时基 PTS，微秒） */
    class Frame(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /** 编码器所有权 + 队列快照，交给 SegmentRecorder 接管 */
    class Handover(
        val videoCodec: MediaCodec,
        val videoSurface: Surface,
        val videoFormat: MediaFormat,
        val videoFrames: List<Frame>,
        /**
         * 预录已编码的音频帧（AAC 帧自带绝对 PTS）。
         * 录制器用同样的 AAC-LC 48k mono 配置另起编码器，把这些帧先写进
         * 音轨，再接着写实时音频——避免交接期间 AudioRecord 缓冲溢出导致
         * 音画永久漂移。
         */
        val audioFrames: List<Frame>,
        /**
         * 预录阶段一直在跑的 AAC 编码器，**整体移交给录制器继续用**（可能为 null）。
         *
         * 不重建：拆掉再建要几百毫秒（实测 0.4~0.9 秒，最坏一次 15 秒），
         * 这期间既没有音频采集（音轨出现无声空洞），又占着主线程，
         * 视频编码器没人取输出、GL 卡在 eglSwapBuffers 上、相机丢帧
         * （画面出现几百毫秒空洞）。移交后音频流在编码器层面就是连续的。
         */
        val audioCodec: MediaCodec?,
        /** 预录阶段一直在采的 AudioRecord，同样整体移交，采集一刻不停（可能为 null） */
        val audioRecord: AudioRecord?,
        /** 预录编码器已经吐出的音轨格式；有它录制器就不必等新编码器出格式 */
        val audioFormat: MediaFormat?,
        /** 移交后下一帧音频该用的 PTS（沿用预录的采样计数，保证时间轴连续） */
        val audioNextPtsUs: Long,
        /**
         * 音频相对视频的固定时基差（µs，= 音频 PTS − 视频 PTS）。
         *
         * 两条流的时钟未必同源（音频 PTS 由 `elapsedRealtimeNanos()` 生成，
         * 相机 PTS 在 MTK 上走 CLOCK_MONOTONIC），交接时由 [Timebase] 判定。
         * 录制器把这批音频平移回视频时基后再写盘。
         */
        val audioPtsOffsetUs: Long
    )

    private val lock = Object()

    private var videoCodec: MediaCodec? = null
    private var videoSurface: Surface? = null
    private var videoThread: Thread? = null
    private var videoFormat: MediaFormat? = null
    private val videoQueue = ArrayDeque<Frame>()

    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var audioFormat: MediaFormat? = null
    private val audioQueue = ArrayDeque<Frame>()

    /**
     * 音频时间轴的起点（µs，原始时基）与已送进编码器的采样帧数。
     *
     * 交接后由录制器接着数：PTS 必须沿同一条曲线往下走，否则音频要么断一截
     * （空洞），要么比实际短（音画永久漂移）。
     */
    @Volatile
    private var audioStartUs = -1L

    @Volatile
    private var audioFramesFed = 0L

    @Volatile
    private var capturing = false

    @Volatile
    private var stopping = false

    /** true = 本次停止是「交接给录制器」，线程不要给编码器发 EOS */
    @Volatile
    private var handoverMode = false

    /** 环形队列保留时长（秒），随设置实时生效 */
    @Volatile
    private var bufferSeconds = 10

    /** 编码帧率，用于把保留时长换算成帧数（帧数裁剪比时间裁剪可靠得多） */
    @Volatile
    private var fps = 30

    // 启动时的编码参数；对外只通过 matches() 判断是否需要重启，
// 不再逐个字段暴露——多点比对容易漏项（曾经就漏过编码格式与音频参数）

    /** 启动时的编码尺寸，旋转/画幅变化后由调用方重启 */
    private var startedWidth = 0
    private var startedHeight = 0

    /** 启动时的编码参数，设置页改了这些也要重启缓存（交接的帧必须与新编码器一致） */
    private var startedFps = 0
    private var startedBitrate = 0
    private var startedAudio = false

    /** 当前缓存用的视频编码档位；交接时必须与录制器一致 */
    private var startedCodecType = AppConfig.VIDEO_CODEC_DEFAULT

    /** 当前缓存用的音频参数；交接时必须与录制器一致 */
    private var startedAudioConfig: AudioConfig = AudioConfig.DEFAULT

    val isRunning: Boolean get() = capturing

    /**
     * 当前缓存参数与给定参数是否完全一致。
     *
     * 交接给录制器的编码器会被直接接管，尺寸/帧率/码率/编码格式/声音开关/
     * 音频参数任一项不一致都会让成片规格与设置不符、或让音轨参数错位，
     * 所以调用方只在参数一致时才允许交接，否则先 [release] 再重新 [start]。
     */
    fun matches(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        audio: Boolean,
        codecType: Int,
        audioConfig: AudioConfig
    ): Boolean = startedWidth == width && startedHeight == height &&
            startedFps == fps.coerceAtLeast(1) && startedBitrate == bitrate &&
            startedAudio == audio && startedCodecType == codecType &&
            startedAudioConfig.matches(audioConfig)

    /**
     * 启动缓存。返回视频编码器输入 Surface（GL 往这里画），失败返回 null。
     * 已在运行且尺寸相同则直接返回现有 Surface（幂等）。
     */
    fun start(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        keepSeconds: Int,
        withAudio: Boolean = true,
        codecType: Int = AppConfig.VIDEO_CODEC_DEFAULT,
        audioConfig: AudioConfig = AudioConfig.DEFAULT
    ): Surface? {
        synchronized(lock) {
            if (capturing) {
                // 已在跑：参数任一对不上就要求调用方先 release 再 start，
                // 否则交接给录制器的帧格式与录制器新起的编码器不一致
                val same = matches(
                    width, height, fps, bitrate, withAudio, codecType, audioConfig
                )
                return if (same) videoSurface else null
            }
            bufferSeconds = keepSeconds
            this.fps = fps.coerceAtLeast(1)
            this.startedFps = this.fps
            this.startedBitrate = bitrate
            this.startedAudio = withAudio
            this.startedCodecType = codecType
            this.startedAudioConfig = audioConfig
            videoQueue.clear()
            audioQueue.clear()
            videoFormat = null
            audioFormat = null
            audioStartUs = -1L
            audioFramesFed = 0L
            stopping = false
            handoverMode = false
        }
        return try {
            val surface = startVideoCodecInternal(width, height, fps, bitrate)
            if (withAudio) {
                try {
                    startAudioCodecInternal()
                } catch (e: Exception) {
                    // 没有录音权限或采集失败时退化为「只预录画面」，不影响主流程
                    Log.w(TAG, "audio pre-record unavailable", e)
                }
            }
            synchronized(lock) { capturing = true }
            videoThread = Thread({ videoLoop() }, "bodycam-pre-video").also { it.start() }
            if (audioCodec != null) {
                audioThread = Thread({ audioLoop() }, "bodycam-pre-audio").also { it.start() }
            }
            surface
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            releaseInternal()
            null
        }
    }

    /**
     * 交接：停止缓存线程，把两个编码器 + 队列里最近 keepSeconds 秒的帧
     * 移交给录制器。交接后本对象回到「未启动」状态，编码器与 AudioRecord
     * 归录制器所有（由它负责 release）。
     *
     * @return null = 缓存还没起量（连视频编码器输出格式都没拿到），走普通录像
     */
    fun beginHandover(keepSeconds: Int): Handover? {
        val vt = videoThread
        val at = audioThread
        synchronized(lock) {
            if (!capturing) return null
            capturing = false
            stopping = true
            // 交接时不能给编码器发 EOS，否则编码器进入终态无法再编码实时帧
            handoverMode = true
        }
        // 先等两个线程退出，之后编码器再无人触碰，才能安全移交
        vt?.join(STOP_TIMEOUT_MS)
        at?.join(STOP_TIMEOUT_MS)
        videoThread = null
        audioThread = null

        synchronized(lock) {
            val vc = videoCodec
            val vf = videoFormat
            if (vc == null || vf == null) {
                // 缓存还没起量（连视频格式都没拿到）：放弃交接，把资源收干净，
                // 让调用方走普通录像，避免编码器泄漏。
                Log.w(TAG, "handover: not ready, releasing buffer")
                synchronized(lock) {
                    videoCodec = null
                    videoSurface = null
                    audioCodec = null
                    audioRecord = null
                    videoQueue.clear()
                    audioQueue.clear()
                    stopping = false
                    handoverMode = false
                }
                releaseInternal()
                return null
            }
            val vFrames = keyAlignedTailLocked(keepSeconds)
            // 音频裁到视频区间之内：不能短于视频起点，也不能越过视频末帧，
            // 否则音轨会伸进交接空档，播放器在空档处等待画面 → 卡顿/花屏。
            //
            // 裁剪前必须先算出两条时钟的差：音频 PTS 走 elapsedRealtime（BOOTTIME），
            // 相机在 MTK 上走 MONOTONIC，休眠过就差一个休眠时长，不做平移的话
            // 区间裁剪一帧都留不下。
            //
            // 用「时钟判定」而不是「两队尾帧相减」：交接时两个线程的停止时刻并不同步，
            // 尾帧能差出几百毫秒（实测 428ms），那点误差会直接变成音画不同步。
            val startPts = vFrames.firstOrNull()?.ptsUs ?: 0L
            val endPts = vFrames.lastOrNull()?.ptsUs ?: 0L
            val timebase = vFrames.lastOrNull()?.let { Timebase.detect(it.ptsUs) }
            val audioPtsOffsetUs = timebase?.audioOffsetUs ?: 0L
            if (timebase != null) {
                Log.i(
                    TAG,
                    "timebase: camera=${timebase.cameraClock}, sleep=${timebase.sleepUs / 1000}ms, " +
                            "audio offset=${audioPtsOffsetUs / 1000}ms"
                )
            }
            val aFrames = audioQueue.filter {
                it.ptsUs - audioPtsOffsetUs >= startPts
            }

            // 音频采集与编码器**整体移交**，不在这里拆。
            // 拆掉重建要几百毫秒（实测 0.4~0.9s，最坏一次 15s）：这段时间主线程
            // 被占着，视频编码器没人取输出 → GL 卡在 eglSwapBuffers → 相机丢帧，
            // 画面就是交接处那 400ms 的空洞；音频更是整段没采，出现近 1 秒无声。
            // 移交后：AudioRecord 一直在采，编码器一直在编，时间轴天然连续。
            val adoptedCodec = audioCodec
            val adoptedRecord = audioRecord
            val adoptedFormat = audioFormat
            val nextAudioPts = audioNextPtsLocked()

            val h = Handover(
                videoCodec = vc,
                videoSurface = videoSurface ?: vc.createInputSurface(),
                videoFormat = vf,
                videoFrames = vFrames,
                audioFrames = aFrames,
                audioCodec = adoptedCodec,
                audioRecord = adoptedRecord,
                audioFormat = adoptedFormat,
                audioNextPtsUs = nextAudioPts,
                audioPtsOffsetUs = audioPtsOffsetUs
            )
            // 视频与音频的所有权都已转移，本对象不再持有（release() 不会再碰它们）
            videoCodec = null
            videoSurface = null
            audioCodec = null
            audioRecord = null
            audioFormat = null
            videoQueue.clear()
            audioQueue.clear()
            stopping = false
            handoverMode = false
            Log.i(
                TAG,
                "handover ${vFrames.size} video / ${aFrames.size} audio frames " +
                        "(${bufferSeconds}s window, audio offset ${audioPtsOffsetUs / 1000}ms, " +
                        "audio ${if (adoptedCodec != null) "transferred" else "not available"})"
            )
            return h
        }
    }

    /** 完全释放（编码器、Surface、AudioRecord、队列） */
    fun release() {
        val vt = videoThread
        val at = audioThread
        synchronized(lock) {
            capturing = false
            stopping = true
        }
        vt?.join(STOP_TIMEOUT_MS)
        at?.join(STOP_TIMEOUT_MS)
        videoThread = null
        audioThread = null
        releaseInternal()
        synchronized(lock) {
            videoQueue.clear()
            audioQueue.clear()
            videoFormat = null
            audioFormat = null
            startedWidth = 0
            startedHeight = 0
            startedFps = 0
            startedBitrate = 0
            startedAudio = false
        }
    }

    // ---------------------------------------------------------------- 编码器

    private fun startVideoCodecInternal(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int
    ): Surface {
        val (c, s) = openVideoEncoder(startedCodecType, width, height, fps, bitrate)
        synchronized(lock) {
            videoCodec = c
            videoSurface = s
            startedWidth = width
            startedHeight = height
        }
        return s
    }

    private fun startAudioCodecInternal() {
        val (c, record) = openAudioCapture(context, startedAudioConfig)
        synchronized(lock) {
            audioCodec = c
            audioRecord = record
        }
    }

    // ---------------------------------------------------------------- 视频线程

    private fun videoLoop() {
        val c = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        var idleRounds = 0
        while (true) {
            // 交接时立刻停手：编码器里还没取走的帧留给录制器的新线程去取，
            // 交接才不会丢帧。不能像普通停止那样等「连续若干轮拿不到输出」——
            // 交接期间 GL 仍在往这个 Surface 出帧，那个条件永远不成立，
            // join 必然超时，最后变成两个线程同时操作同一个编码器。
            if (stopping && handoverMode) break
            val index = try {
                c.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            } catch (e: Exception) {
                Log.w(TAG, "video dequeue", e)
                break
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (stopping) {
                    // 输入已停，等编码器把尾巴吐完（最多 ~200ms）再退出
                    if (++idleRounds > 20) break
                }
                continue
            }
            idleRounds = 0
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) { videoFormat = c.outputFormat }
                continue
            }
            if (index < 0) continue

            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (!isConfig && info.size > 0) {
                val buffer = c.getOutputBuffer(index)
                if (buffer != null) {
                    synchronized(lock) {
                        // 交接时也把停止前一瞬的帧收进来，成片开头更完整
                        videoQueue.addLast(
                            Frame(copyOut(buffer, info), info.presentationTimeUs, info.flags)
                        )
                        trimLocked()
                    }
                }
            }
            c.releaseOutputBuffer(index, false)
            if (eos) break
        }
    }

    // ---------------------------------------------------------------- 音频线程

    private fun audioLoop() {
        val record = audioRecord ?: return
        val codec = audioCodec ?: return
        val pcm = ByteArray(PCM_CHUNK_SIZE)
        val info = MediaCodec.BufferInfo()
        var eosQueued = false

        while (true) {
            if (stopping) {
                // 交接：立刻停手，剩下的采集全在 AudioRecord 的缓冲里，
                // 由录制器接着读，一帧都丢不了。这里绝不能发 EOS——编码器
                // 要原样移交给录制器继续用。
                if (handoverMode) break
                if (!eosQueued) {
                    eosQueued = queueEos(codec, audioNextPtsLocked())
                }
            } else {
                // 非阻塞读：阻塞读会一直等到凑满一整块（8192 字节 = 85ms），
                // 而交接时要 join 这个线程，它卡在读里 join 就得等 85ms——
                // 那段时间视频编码器没人取输出，GL 反压、相机丢帧。非阻塞 + 5ms
                // 轮询后，stopping 最多 5ms 就被看到，采集数据仍在 AudioRecord
                // 缓冲里，一帧都不会丢。
                val read = try {
                    record.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                } catch (e: Exception) {
                    -1
                }
                if (read > 0) {
                    if (audioStartUs < 0) {
                        // 音频 PTS 用 CLOCK_BOOTTIME 绝对时基，交接后由
                        // Timebase 判定与视频时钟的差再平移对齐
                        audioStartUs = SystemClock.elapsedRealtimeNanos() / 1000
                    }
                    val cfg = startedAudioConfig
                    val ptsUs = audioStartUs + audioFramesFed * US_PER_SECOND / cfg.sampleRate
                    audioFramesFed += read.toLong() / cfg.pcmBytesPerFrame
                    feedAudio(codec, pcm, read, ptsUs, cfg)
                } else {
                    try {
                        Thread.sleep(STOP_POLL_MS)
                    } catch (e: InterruptedException) {
                        break
                    }
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
                    synchronized(lock) { audioFormat = codec.outputFormat }
                    continue
                }
                if (index < 0) break
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!isConfig && info.size > 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) {
                        synchronized(lock) {
                            audioQueue.addLast(
                                Frame(
                                    copyOut(buffer, info),
                                    info.presentationTimeUs,
                                    info.flags
                                )
                            )
                            trimAudioLocked()
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)
            }
            if (eos) break
            if (stopping) {
                try {
                    Thread.sleep(STOP_POLL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 视频队列裁剪：按**帧数**保留，且队列头永远对齐到关键帧。
     *
     * 不用 PTS 时间窗口：编码器实测的输出 PTS 偶有跳变（同一 Surface 上
     * 相机时间戳与补帧混排），一旦有一帧异常大的 PTS 进来，按
     * `last().ptsUs - 10s` 算出的窗口就会把正常帧全部裁掉——这正是
     * 「10 秒窗口只交接 5 帧」的成因。按帧数裁剪则不受 PTS 跳变影响。
     *
     * 队列头保持为关键帧：否则切片会从参考帧已被裁掉的 P 帧开始，
     * 成片开局必花屏，一直花到下一个 IDR。
     */
    private fun trimLocked() {
        // 多留一个 GOP 的余量，之后按保留窗口切片时才有关键帧可用
        val maxFrames = (bufferSeconds + 1) * fps
        if (videoQueue.size <= maxFrames) return
        val drop = videoQueue.size - maxFrames
        // 丢弃点往后找最近的 I 帧，保证新队头是关键帧
        val keyAt = firstKeyFrameAtOrAfterLocked(drop)
        if (keyAt < 0) {
            // 后面没有关键帧：保守起见不裁，等下一轮
            return
        }
        repeat(keyAt) { videoQueue.removeFirst() }
    }

    /** 从 [from] 下标起第一个关键帧的下标；没有则 -1 */
    private fun firstKeyFrameAtOrAfterLocked(from: Int): Int {
        var idx = 0
        for (f in videoQueue) {
            if (idx >= from && f.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) return idx
            idx++
        }
        return -1
    }

    /** 音频队列按视频的时间跨度裁剪，保证音轨不会比视频多出一大截 */
    private fun trimAudioLocked() {
        if (audioQueue.isEmpty()) return
        val ref = newestVideoPtsLocked()
        val cutoff = if (ref > 0) ref - (bufferSeconds + 2) * US_PER_SECOND
        else audioQueue.last().ptsUs - (bufferSeconds + 2) * US_PER_SECOND
        while (audioQueue.isNotEmpty() && audioQueue.first().ptsUs < cutoff) {
            audioQueue.removeFirst()
        }
    }

    /** 队列里最大的视频 PTS。编码器输出可能乱序，不能用 last() */
    private fun newestVideoPtsLocked(): Long {
        var maxPts = 0L
        for (f in videoQueue) if (f.ptsUs > maxPts) maxPts = f.ptsUs
        return maxPts
    }

    /**
     * 交接后下一帧音频该用的 PTS（原始时基）。
     *
     * 沿用「起点 + 已送采样数」的同一条曲线，录制器接手后时间轴才严密连续；
     * 不要用墙钟重新起算——那会让交接处凭空多出一段空洞或让音轨变短。
     */
    private fun audioNextPtsLocked(): Long =
        if (audioStartUs < 0) 0L
        else audioStartUs + audioFramesFed * US_PER_SECOND / startedAudioConfig.sampleRate

    /**
     * 取最近 keepSeconds 秒的帧，起点落在**关键帧**上。
     *
     * 队列头本身就是关键帧（见 [trimLocked]），所以这里先用帧数定位窗口
     * 起点，再往后找最近的关键帧；找不到就退回队头（那一定是关键帧）。
     */
    private fun keyAlignedTailLocked(keepSeconds: Int): List<Frame> {
        if (videoQueue.isEmpty()) return emptyList()
        val target = videoQueue.size - keepSeconds * fps
        val from = target.coerceAtLeast(0)
        var start = firstKeyFrameAtOrAfterLocked(from)
        if (start < 0) {
            // 窗口内没有关键帧：退回队头（trimLocked 保证它通常是关键帧）
            if (videoQueue.first().flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) {
                Log.w(TAG, "keyAlignedTail: no key frame in window, head not key either")
            }
            start = 0
        }
        val out = videoQueue.drop(start)
        Log.i(
            TAG,
            "slice: queue=${videoQueue.size} -> ${out.size} frames, " +
                    "key=${out.first().flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0}, " +
                    "span=${(out.last().ptsUs - out.first().ptsUs) / 1000}ms"
        )
        return out
    }

    private fun releaseInternal() {
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        videoCodec = null
        // createInputSurface() 返回的 Surface 需要显式释放，否则 GC 时会打
        // "A resource failed to call Surface.release"。交接出去的走 Handover，
        // 那时 videoSurface 已被置空，不会在这里重复释放。
        runCatching { videoSurface?.release() }
        videoSurface = null
        releaseAudioInternal()
    }

    private fun releaseAudioInternal() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
    }
}
