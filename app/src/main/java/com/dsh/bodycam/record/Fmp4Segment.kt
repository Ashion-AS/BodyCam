@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.dsh.bodycam.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.dsh.bodycam.util.Storage
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * 一个 fMP4 分段：文件条目 + muxer + 输出流，负责建流、建轨、写样本与收尾。
 *
 * 视频（[SegmentRecorder]）与音频（[AudioRecorder]）共用这一份样板。
 * 用 media3 的 [FragmentedMp4Muxer] 而不是 `MediaMuxer`：ftyp+moov 在首个
 * 样本写入前就落盘，数据按 moof+mdat 分片自包含，进程被强杀时已写入的分片
 * 仍可直接播放；`MediaMuxer` 的普通 MP4 把 moov 留到 stop() 才写，强杀后
 * 文件永久不可播放。
 *
 * 线程安全由调用方负责：两个录制器都在各自锁内访问本类。
 */
internal class Fmp4Segment private constructor(
    private val tag: String,
    val entry: Storage.Entry,
    private val fd: ParcelFileDescriptor,
    private val out: FileOutputStream,
    private val muxer: Muxer
) {
    /** 轨 id；-1 = 该轨尚未建立 */
    var videoTrack = -1
        private set
    var audioTrack = -1
        private set

    /** 本段已写入的样本数；为 0 说明只是建了个空壳 */
    var samples = 0L
        private set

    private var videoKeyframeSeen = false

    fun addVideoTrack(format: Format) {
        videoTrack = muxer.addTrack(format)
        videoKeyframeSeen = false
    }

    fun addAudioTrack(format: Format) {
        audioTrack = muxer.addTrack(format)
    }

    /**
     * 视频轨首个样本必须是关键帧：media3 在还没见过关键帧时会丢弃样本，
     * 随后在 peekFirst() 上抛 NPE。关键帧之前的样本在这里直接拒绝。
     */
    fun acceptVideoSample(flags: Int): Boolean {
        if (videoKeyframeSeen) return true
        if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return false
        videoKeyframeSeen = true
        return true
    }

    /** 写一个连续字节的样本 */
    fun writeSample(track: Int, data: ByteArray, ptsUs: Long, flags: Int): Boolean {
        if (track < 0) return false
        return write(track, ByteBuffer.wrap(data), ptsUs, data.size, flags)
    }

    /** 写一个已定位好的样本区间（offset 起 size 字节） */
    fun writeSample(
        track: Int,
        buffer: ByteBuffer,
        ptsUs: Long,
        offset: Int,
        size: Int,
        flags: Int
    ): Boolean {
        if (track < 0) return false
        buffer.position(offset)
        buffer.limit(offset + size)
        return write(track, buffer, ptsUs, size, flags)
    }

    private fun write(
        track: Int,
        buffer: ByteBuffer,
        ptsUs: Long,
        size: Int,
        flags: Int
    ): Boolean = try {
        muxer.writeSampleData(track, buffer, androidx.media3.muxer.BufferInfo(ptsUs, size, flags))
        samples++
        true
    } catch (e: Exception) {
        Log.w(tag, "write sample", e)
        false
    }

    /**
     * 收尾：写出最后一个分片并关闭文件句柄。
     * @return 写过样本返回 true（已 commit）；一帧没写的空壳已撤掉，返回 false
     */
    fun close(): Boolean {
        // media3 的 close() 会写出收尾分片并 flush；没有 stop()/release()
        try {
            muxer.close()
        } catch (e: Exception) {
            Log.w(tag, "muxer.close", e)
        }
        runCatching { out.flush() }
        runCatching { out.close() }
        runCatching { fd.close() }
        return if (samples == 0L) {
            entry.abort()
            false
        } else {
            // 修补会重扫全文件（大分段上百 MB），绝不能在录制线程/持锁路径上同步做：
            // 那样每次切段都会卡住编码线程，丢帧甚至 ANR。
            // 丢到单线程队列里串行补，顺序与切段顺序一致，不会并发改同一个文件；
            // 即使进程这时被杀，文件也已经是可播放的 fMP4，只是缺 duration/mfra
            // （下次播放前 ViewerActivity 还会再补一遍）。
            finishAsync(entry)
            true
        }
    }

    companion object {
        /** 分片时长：强杀时最多丢这么久的数据 */
        const val FRAGMENT_DURATION_MS = 2_000L

        /**
         * 收尾修补串行队列：一次只改一个文件，避免并发重写抢 I/O。
         * 进程级 daemon 线程，随进程退出，不需要显式关闭。
         */
        private val patchExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "bodycam-muxer-fix").apply { isDaemon = true }
        }

        /**
         * 先补 duration/mfra、再让 MediaScanner 索引：顺序不能反，
         * 否则媒体库会把 duration=0 的版本缓存下来。
         */
        private fun finishAsync(entry: Storage.Entry) {
            patchExecutor.execute {
                runCatching { entry.patchDurations() }
                    .onFailure { Log.w("Fmp4Segment", "patch ${entry.displayName}", it) }
                entry.commit()
            }
        }

        /**
         * 建一个分段，任何一步失败都清理干净并返回 null。
         * @param createEntry 决定落盘目录：[Storage.createVideo] 或 [Storage.createAudio]
         */
        fun open(
            tag: String,
            name: String,
            createEntry: (String) -> Storage.Entry?
        ): Fmp4Segment? {
            val entry = createEntry(name)
            if (entry == null) {
                Log.e(tag, "create entry failed: $name")
                return null
            }
            val fd = entry.openReadWrite()
            if (fd == null) {
                Log.e(tag, "open fd failed: $name")
                entry.abort()
                return null
            }
            val out = FileOutputStream(fd.fileDescriptor)
            val muxer = try {
                FragmentedMp4Muxer.Builder(SeekableMuxerOutput.of(out))
                    .setFragmentDurationMs(FRAGMENT_DURATION_MS)
                    .build()
            } catch (e: Exception) {
                Log.e(tag, "create muxer failed", e)
                runCatching { out.close() }
                runCatching { fd.close() }
                entry.abort()
                return null
            }
            return Fmp4Segment(tag, entry, fd, out, muxer)
        }
    }
}

// ------------------------------------------------------- MediaFormat → media3 Format

/** 视频轨格式。SPS/PPS 两类 csd 必须齐全，见 [buildCsd] */
internal fun MediaFormat.toVideoFormat(rotationDegrees: Int): Format =
    Format.Builder()
        .setSampleMimeType(getString(MediaFormat.KEY_MIME))
        .setWidth(getInteger(MediaFormat.KEY_WIDTH))
        .setHeight(getInteger(MediaFormat.KEY_HEIGHT))
        .setRotationDegrees(rotationDegrees)
        .apply { buildCsd(this@toVideoFormat)?.let(::setInitializationData) }
        .build()

/**
 * 音频轨格式。esds 盒只需要 csd-0。
 *
 * `codecs` 必须填：media3 的 `FragmentedMp4Muxer` 用
 * `MimeTypes.allSamplesAreSyncSamples(mimeType, codecs)` 决定 trun 里每个样本的
 * `sample_is_non_sync_sample` 位。AAC 分支在 codecs 为 null 时返回 false，于是
 * 整条音轨的样本都被标成「非同步」，ExoPlayer 找不到可解码起点，整段音频被丢弃
 * —— 表现就是「能播、有进度、但完全没声音」，而系统相册/电脑播放器不看这个位，
 * 所以照常有声。
 */
internal fun MediaFormat.toAudioFormat(): Format =
    Format.Builder()
        .setSampleMimeType(getString(MediaFormat.KEY_MIME))
        .setCodecs(AAC_CODECS_LC)
        .setChannelCount(getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        .setSampleRate(getInteger(MediaFormat.KEY_SAMPLE_RATE))
        .apply { buildCsd(this@toAudioFormat)?.let(::setInitializationData) }
        .build()

/**
 * 取 csd-0 / csd-1 作为 initializationData。
 *
 * 有些编码器把 SPS+PPS 打包在 csd-0 里（没有 csd-1），视频轨必须按起始码
 * 拆成两个元素，否则 avcC 盒会因 initializationData.size < 2 抛异常；
 * 音频轨的 esds 盒只需要 csd-0，原样返回。
 */
private fun buildCsd(fmt: MediaFormat): List<ByteArray>? {
    val csd0 = fmt.getByteBuffer("csd-0")?.toBytes() ?: return null
    val csd1 = fmt.getByteBuffer("csd-1")?.toBytes()
    if (csd1 != null) return listOf(csd0, csd1)
    if (!MimeTypes.isVideo(fmt.getString(MediaFormat.KEY_MIME))) return listOf(csd0)
    return splitNalUnits(csd0).takeIf { it.size >= 2 } ?: listOf(csd0)
}

private fun ByteBuffer.toBytes(): ByteArray {
    val copy = duplicate()
    val out = ByteArray(copy.remaining())
    copy.get(out)
    return out
}

/** 按 Annex-B 起始码把一段 CSD 拆成独立 NAL 单元 */
private fun splitNalUnits(data: ByteArray): List<ByteArray> {
    val starts = ArrayList<Int>()
    var i = 0
    while (i + 3 < data.size) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
            if (data[i + 2] == 1.toByte()) {
                starts.add(i)
                i += 3
                continue
            }
            if (data[i + 3] == 1.toByte()) {
                starts.add(i)
                i += 4
                continue
            }
        }
        i++
    }
    if (starts.isEmpty()) return listOf(data)
    return List(starts.size) { n ->
        val start = starts[n]
        val end = if (n + 1 < starts.size) starts[n + 1] else data.size
        data.copyOfRange(start, end)
    }
}
