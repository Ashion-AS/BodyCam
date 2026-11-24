package com.dsh.bodycam.record

import android.content.Context
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.util.Storage
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 纯音频录音器：AudioRecord → AAC 编码 → fMP4，落成 .m4a，支持**无缝分段**。
 *
 * 采集与编码参数直接复用 [CodecSupport]（48k / 单声道 / PCM16 / AAC 128kbps），
 * 与录像里的音轨完全一致，不引入第二套音频事实来源。
 *
 * 封装与 [SegmentRecorder] 共用 [Fmp4Segment]（内部是 media3 的
 * `FragmentedMp4Muxer`）：ftyp+moov 在首个样本前落盘，数据按 moof+mdat 分片，
 * 进程被强杀时已写入的分片仍可直接播放。MediaMuxer 的普通 MP4 把 moov 留到
 * stop() 才写，强杀后文件永久不可播放。
 *
 * 分段方式与 [SegmentRecorder] 同一思路：编码器自始至终不停，只在分段点
 * 换一个新的分段继续写，所以分段处不打断录音，也不丢可听内容
 * （只在文件边界丢掉不足一帧的尾部，AAC 一帧约 23ms）。
 *
 * 线程模型只有一个音频线程：读 PCM → 送编码器 → 排空编码器 → 写分段。
 * 每读到一段 PCM 顺便算一次电平，通过 [levelListener] 回调给波形 UI
 * （回调发生在音频线程，调用方需自行切到主线程）。
 */
class AudioRecorder(private val context: Context) {

    /**
     * 最近一次分段创建失败的原因；[start] 失败后由调用方读取以给出准确提示。
     *
     * 放实例字段而不是 Storage 的全局状态：全局会被其他并发写入覆盖。
     */
    @Volatile
    var lastCreateFailReason: Storage.FailReason? = null
        private set

    companion object {
        private const val TAG = "AudioRecorder"
        private const val STOP_TIMEOUT_MS = 4_000L

        /** 麦克风读取失败后的退避间隔；不退避会把 while 打成空转死循环 */
        private const val READ_RETRY_BACKOFF_MS = 20L

        /** 连续读取失败达到这个次数（约 2 秒）就判定麦克风失效，主动收尾 */
        private const val READ_FAILURE_LIMIT = 100
    }

    private val lock = Object()

    @Volatile
    private var running = false

    @Volatile
    private var stopping = false

    private var codec: MediaCodec? = null
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    private var muxer: Fmp4Segment? = null
    private var audioFormat: MediaFormat? = null

    /** 分段时长与序号；PTS 相对每段起点重新计算，保证单文件内从 0 单调递增 */
    private var segmentDurationUs = AppConfig.DEFAULT_AUDIO_SEGMENT_SECONDS * US_PER_SECOND
    private var segmentIndex = 0
    private var nameBase = ""
    private var ptsBaseUs = 0L

    private val entries = ArrayList<Storage.Entry>()

    /** 停止后回收的成品条目列表；没录到任何样本时为空 */
    private var resultEntries: List<Storage.Entry> = emptyList()

    /** 本次录音期间麦克风最大峰值（0~32767）；很小说明基本没录到声音 */
    @Volatile
    var lastMicPeak = 0
        private set

    /** 波形电平回调，值域 0~1。在音频线程调用 */
    @Volatile
    var levelListener: ((Float) -> Unit)? = null

    /**
     * 麦克风读取连续失败、录音被迫中止时回调一次（在音频线程调用）。
     * 收尾走的是与正常停止相同的路径，已写入的分段照常提交。
     */
    @Volatile
    var micFailureListener: (() -> Unit)? = null

    /** 是否正在录音 */
    val isRecording: Boolean get() = running

    /**
     * 开始录音。
     * @param segmentSeconds 单个文件的分段时长（秒），默认 [AppConfig.DEFAULT_AUDIO_SEGMENT_SECONDS]。
     * @return 权限不足或初始化失败返回 false，失败时已建资源释放干净。
     */
    fun start(segmentSeconds: Int = AppConfig.DEFAULT_AUDIO_SEGMENT_SECONDS): Boolean {
        synchronized(lock) {
            if (running) return false
            entries.clear()
            resultEntries = emptyList()
            segmentIndex = 0
            ptsBaseUs = 0L
            audioFormat = null
            lastCreateFailReason = null
            segmentDurationUs = segmentSeconds.coerceAtLeast(1) * US_PER_SECOND
            nameBase = Storage.stamp()
        }

        val (newCodec, newRecord) = try {
            openAudioCapture(context)
        } catch (e: Exception) {
            Log.e(TAG, "open audio capture failed", e)
            return false
        }

        synchronized(lock) {
            codec = newCodec
            record = newRecord
            if (!createMuxerLocked()) {
                runCatching { newRecord.release() }
                runCatching { newCodec.stop() }
                runCatching { newCodec.release() }
                codec = null
                record = null
                return false
            }
        }

        stopping = false
        running = true
        worker = thread(name = "bodycam-audio-only") { loop() }
        return true
    }

    /**
     * 停止录音并返回本次产生的所有分段（可能多个）。
     * 一帧没写进去的空分段已在收尾时撤掉，不会出现在结果里。
     */
    fun stop(): List<Storage.Entry> {
        if (!running) return emptyList()
        running = false
        stopping = true
        try {
            record?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "audioRecord.stop", e)
        }
        worker?.join(STOP_TIMEOUT_MS)
        worker = null
        releaseAll()
        return synchronized(lock) {
            val result = resultEntries
            resultEntries = emptyList()
            result
        }
    }

    /** 释放资源；正在录音则先收尾。Activity 销毁时兜底用 */
    fun release() {
        if (running) stop() else releaseAll()
    }

    // ---------------------------------------------------------------- 音频线程

    private fun loop() {
        val record = record ?: return
        val codec = codec ?: return
        val pcm = ByteArray(PCM_BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()

        var frames = 0L
        var lastPtsUs = 0L
        var eosQueued = false
        var stopDeadline = 0L
        var readFailures = 0
        var micFailed = false

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
                    readFailures = 0
                    // 送进编码器的 PTS 用绝对时基（随采样帧数单调递增），
                    // 输出端再按每段起点换算成单文件内的相对时间轴。
                    val ptsUs = frames * US_PER_SECOND / SAMPLE_RATE
                    frames += read.toLong() / PCM_BYTES_PER_FRAME
                    feedAudio(codec, pcm, read, ptsUs)
                    lastPtsUs = ptsUs
                    emitLevel(pcm, read)
                } else {
                    // 麦克风失效时 read 会立刻返回错误码而不是阻塞等待，
                    // 不退避的话这一轮 while 就变成空转：CPU 打满、电平回调
                    // 再也不触发，波形表现为直接冻结但计时器照走。
                    readFailures++
                    if (readFailures >= READ_FAILURE_LIMIT) {
                        Log.w(TAG, "mic read failed $readFailures times, abort recording")
                        micFailed = true
                        stopping = true
                    } else {
                        try {
                            Thread.sleep(READ_RETRY_BACKOFF_MS)
                        } catch (e: InterruptedException) {
                            break
                        }
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
                    synchronized(lock) {
                        audioFormat = codec.outputFormat
                        startMuxerLocked()
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

        // 麦克风失效导致的自行收尾：running 保持 true，让后续 stop()/release()
        // 仍然走完整的资源释放路径，避免 codec / AudioRecord 泄漏。
        if (micFailed) micFailureListener?.invoke()
    }

    /** 收到一帧编码后的音频：到点先换分段，再写进当前 muxer（调用方持锁） */
    private fun onAudioFrameLocked(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        // 相对当前分段起点的 PTS；到分段时长就切文件（换 muxer，编码器不停）
        var relPts = info.presentationTimeUs - ptsBaseUs
        if (relPts >= segmentDurationUs) {
            switchSegmentLocked(info.presentationTimeUs)
            relPts = info.presentationTimeUs - ptsBaseUs
        }
        if (relPts < 0) return
        val segment = muxer ?: return
        segment.writeSample(segment.audioTrack, buffer, relPts, info.offset, info.size, info.flags)
    }

    /** 从一段 16bit PCM 算 RMS，开方压一压动态范围后作为波形电平 */
    private fun emitLevel(data: ByteArray, size: Int) {
        var sumSq = 0.0
        var peak = 0
        var count = 0
        var i = 0
        while (i + 1 < size) {
            val v = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort().toInt()
            sumSq += v.toDouble() * v
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            count++
            i += 2
        }
        if (peak > lastMicPeak) lastMicPeak = peak
        if (count == 0) return
        val rms = sqrt(sumSq / count).toFloat()
        val level = sqrt((rms / 32768f).coerceIn(0f, 1f))
        levelListener?.invoke(level)
    }

    // ---------------------------------------------------------------- 分段 / 封装

    /** 建一个新分段的 muxer 与相册条目（调用方持锁） */
    private fun createMuxerLocked(): Boolean {
        val name = "AUD_${nameBase}_part${String.format(Locale.US, "%02d", segmentIndex)}.m4a"
        val result = Fmp4Segment.open(TAG, name) { Storage.createAudio(context, it) }
        val segment = result.segment ?: run {
            lastCreateFailReason = result.failReason ?: Storage.FailReason.IO_ERROR
            return false
        }
        muxer = segment
        // 首个分段要等编码器吐格式才能建轨；后续分段格式已知，直接建轨追上写入
        if (audioFormat != null) startMuxerLocked()
        return true
    }

    /** 有输出格式且尚未建轨时，建轨（调用方持锁） */
    private fun startMuxerLocked() {
        val segment = muxer ?: return
        if (segment.audioTrack >= 0) return
        val format = audioFormat ?: return
        try {
            segment.addAudioTrack(format.toAudioFormat())
        } catch (e: Exception) {
            Log.e(TAG, "start muxer failed", e)
        }
    }

    /** 分段切换：收掉当前文件，从 nextPtsUs 起开新文件（调用方持锁） */
    private fun switchSegmentLocked(nextPtsUs: Long) {
        closeMuxerLocked()
        segmentIndex++
        ptsBaseUs = nextPtsUs
        if (createMuxerLocked()) {
            Log.i(TAG, "segment $segmentIndex started at ${nextPtsUs / 1000}ms")
        }
    }

    // ---------------------------------------------------------------- 清理

    private fun releaseAll() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { record?.release() }
        record = null
        synchronized(lock) {
            closeMuxerLocked()
            resultEntries = entries.toList()
        }
    }

    /** 收尾当前 muxer：写过样本就 commit 并入列表，否则撤掉空壳（调用方持锁） */
    private fun closeMuxerLocked() {
        val segment = muxer ?: return
        muxer = null
        if (segment.close()) entries.add(segment.entry)
    }
}
