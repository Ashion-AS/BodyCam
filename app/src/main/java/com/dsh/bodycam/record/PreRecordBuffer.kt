package com.dsh.bodycam.record

import android.content.Context
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface

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
 * `elapsedRealtimeNanos()/1000` 起算；两者都是 CLOCK_BOOTTIME 微秒，
 * 交接后可直接相减对齐。
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
        val audioFrames: List<Frame>
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

    /** 启动时的编码尺寸，旋转/画幅变化后由调用方重启 */
    var startedWidth = 0
        private set
    var startedHeight = 0
        private set

    val isRunning: Boolean get() = capturing

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
        withAudio: Boolean = true
    ): Surface? {
        synchronized(lock) {
            if (capturing) {
                return if (startedWidth == width && startedHeight == height) {
                    videoSurface
                } else {
                    null  // 尺寸变了，调用方应先 release 再 start
                }
            }
            bufferSeconds = keepSeconds
            this.fps = fps.coerceAtLeast(1)
            videoQueue.clear()
            audioQueue.clear()
            videoFormat = null
            audioFormat = null
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
            val startPts = vFrames.firstOrNull()?.ptsUs ?: 0L
            val endPts = vFrames.lastOrNull()?.ptsUs ?: 0L
            val aFrames = audioQueue.filter { it.ptsUs >= startPts && it.ptsUs <= endPts }

            // 音频编码器与 AudioRecord 不交接（交接间隙会溢出），这里释放掉，
            // 录制器用同样配置另起一套，并把 aFrames 先写进新音轨。
            releaseAudioInternal()

            val h = Handover(
                videoCodec = vc,
                videoSurface = videoSurface ?: vc.createInputSurface(),
                videoFormat = vf,
                videoFrames = vFrames,
                audioFrames = aFrames
            )
            // 视频所有权已转移，本对象不再持有
            videoCodec = null
            videoSurface = null
            videoQueue.clear()
            audioQueue.clear()
            stopping = false
            handoverMode = false
            Log.i(
                TAG,
                "handover ${vFrames.size} video / ${aFrames.size} audio frames " +
                        "(${bufferSeconds}s window)"
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
        }
    }

    // ---------------------------------------------------------------- 编码器

    private fun startVideoCodecInternal(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int
    ): Surface {
        val f = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // 1s 一个 I 帧：交接切片时保证第一帧是关键帧
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val c = MediaCodec.createEncoderByType(VIDEO_MIME)
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val s = c.createInputSurface()
        c.start()
        synchronized(lock) {
            videoCodec = c
            videoSurface = s
            startedWidth = width
            startedHeight = height
        }
        return s
    }

    private fun startAudioCodecInternal() {
        val (c, record) = openAudioCapture(context)
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
        val pcm = ByteArray(PCM_BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()
        var frames = 0L
        var startUs = -1L
        var eosQueued = false
        var idleRounds = 0

        while (true) {
            if (stopping) {
                // 交接时不给编码器发 EOS（编码器要交给录制器继续用）；
                // 录制器 stop 时才发。这里只把已采到的音频排空即可。
                if (!handoverMode && !eosQueued) {
                    eosQueued = queueEos(codec, SystemClock.elapsedRealtimeNanos() / 1000)
                }
            } else {
                val read = try {
                    record.read(pcm, 0, pcm.size)
                } catch (e: Exception) {
                    -1
                }
                if (read > 0) {
                    if (startUs < 0) {
                        // 音频 PTS 用 CLOCK_BOOTTIME 绝对时基，与视频帧
                        // （相机 SurfaceTexture 时间戳）同源，交接后可直接对齐
                        startUs = SystemClock.elapsedRealtimeNanos() / 1000
                    }
                    val ptsUs = startUs + frames * US_PER_SECOND / SAMPLE_RATE
                    frames += read.toLong() / PCM_BYTES_PER_FRAME
                    feedAudio(codec, pcm, read, ptsUs)
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
                if (handoverMode) {
                    // 没有 EOS 可等，连续几轮拿不到输出就认为排空完成
                    if (++idleRounds > 10) break
                }
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
