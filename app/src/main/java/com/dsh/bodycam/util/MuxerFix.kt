package com.dsh.bodycam.util

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * media3 `FragmentedMp4Muxer` 的补丁：回填 moov 里的 duration 字段。
 *
 * ## 为什么需要
 *
 * muxer 在写首个样本之前就把 ftyp+moov 落盘（这是 fMP4 强杀可播的前提），
 * 那时不可能知道总时长，于是三处 duration 一律写 0，`mvex` 里也不带 `mehd`：
 *
 * - `mvhd.duration`
 * - `tkhd.duration`
 * - `mdhd.duration`
 *
 * media3 的 `FragmentedMp4Extractor` 解析 moov 时按这个顺序取时长：
 * `mehd` → 各 track 的 `durationUs`（本身就来自 tkhd）。两者都是 0/缺失，
 * `durationUs` 就成了 `TIME_UNSET`，`SeekMap` 退化为 `Unseekable(TIME_UNSET)`。
 * ExoPlayer 拿到 `C.TIME_UNSET` 的流时长后认为这条流没有内容，`READY` 之后
 * 立刻 `ENDED`，一个样本都不送进解码器 —— 表现就是「点了播放没反应、进度条
 * 一直 00:00」。
 *
 * 单轨文件必然踩到（本工程的录音就是单轨）。视频能放纯属另一条轨兜底，
 * 并不代表文件本身没问题。PC 端播放器（ffmpeg/VLC）会自己扫分片累计出时长，
 * 所以同一个文件在电脑上正常。
 *
 * ## 补什么
 *
 * 关文件前扫一遍所有 `moof/traf/trun`，按 track 累计样本 duration，
 * 把结果写回上述三处。只覆盖 4 字节整型字段，文件长度不变，也不动
 * `tfhd` 里存的绝对偏移（`base_data_offset`），因此对已写入的分片零影响。
 */
internal object MuxerFix {

    private const val TAG = "MuxerFix"

    /** sample_flags 里的 `sample_is_non_sync_sample` 位（bit16） */
    private const val NON_SYNC_FLAG = 0x10000L

    fun patchDurations(file: File) {
        if (!file.exists() || file.length() < 64) return
        patchSampleSyncFlags(file)
        RandomAccessFile(file, "rw").use { raf ->
            val moov = findBox(raf, 0, raf.length(), "moov") ?: return
            val mvhd = findChild(raf, moov.bodyStart, moov.end, "mvhd") ?: return
            val movieTimescale = u32(raf, mvhd.bodyStart + 12)
            if (movieTimescale <= 0) return

            // 每轨的 media duration（以各自 mdhd.timescale 计）
            val trackTicks = accumulateTrunDurations(raf)

            var maxMovieDur = 0L
            var index = 0
            var pos = moov.bodyStart
            while (pos < moov.end) {
                val box = readBox(raf, pos, moov.end) ?: break
                if (box.type == "trak") {
                    val mdia = findChild(raf, box.bodyStart, box.end, "mdia")
                    val mdhd = mdia?.let { findChild(raf, it.bodyStart, it.end, "mdhd") }
                    val tkhd = findChild(raf, box.bodyStart, box.end, "tkhd")
                    if (mdhd != null) {
                        val mediaTimescale = u32(raf, mdhd.bodyStart + 12)
                        val ticks = trackTicks[trackKey(raf, box, index)] ?: 0L
                        if (mediaTimescale > 0) {
                            val durUs = ticks * 1_000_000L / mediaTimescale
                            val movieDur = durUs * movieTimescale / 1_000_000L
                            maxMovieDur = maxOf(maxMovieDur, movieDur)
                            writeU32(raf, mdhd.bodyStart + 16, ticks)
                            if (tkhd != null) writeU32(raf, tkhd.bodyStart + 20, movieDur)
                            Log.d(TAG, "track$index ticks=$ticks durUs=$durUs")
                        }
                    }
                    index++
                }
                pos = box.end
            }
            writeU32(raf, mvhd.bodyStart + 16, maxMovieDur)
            Log.d(TAG, "mvhd duration <- $maxMovieDur (timescale $movieTimescale)")
        }
    }

    /** 用第 index 条 trak 的 track_ID 做 key；读不到退化成 index+1 */
    private fun trackKey(raf: RandomAccessFile, trak: Box, index: Int): Int {
        val tkhd = findChild(raf, trak.bodyStart, trak.end, "tkhd") ?: return index + 1
        // tkhd body(v0): version/flags(4) creation(4) modification(4) track_ID(4) ...
        val id = u32(raf, tkhd.bodyStart + 12)
        return if (id > 0) id else index + 1
    }

    /**
     * 遍历所有 moof，按 track 累计 trun 里的样本时长。
     * trun flags 的 bit0 是 data_offset、bit2 是 first_sample_flags、
     * bit8/9/10/11 依次是 duration/size/flags/composition_offset。
     */
    private fun accumulateTrunDurations(raf: RandomAccessFile): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        val fileLen = raf.length()
        var pos = 0L
        while (pos + 8 <= fileLen) {
            val box = readBox(raf, pos, fileLen) ?: break
            if (box.type == "moof") {
                var tpos = box.bodyStart
                while (tpos < box.end) {
                    val traf = readBox(raf, tpos, box.end) ?: break
                    if (traf.type == "traf") {
                        readTrafDurations(raf, traf, out)
                    }
                    tpos = traf.end
                }
            }
            if (box.end <= pos) break
            pos = box.end
        }
        return out
    }

    private fun readTrafDurations(raf: RandomAccessFile, traf: Box, out: MutableMap<Int, Long>) {
        var trackId = 0
        var sum = 0L
        var pos = traf.bodyStart
        while (pos < traf.end) {
            val box = readBox(raf, pos, traf.end) ?: break
            when (box.type) {
                "tfhd" -> trackId = u32(raf, box.bodyStart + 4)
                "trun" -> sum += trunDurationSum(raf, box)
            }
            pos = box.end
        }
        if (trackId != 0) out[trackId] = (out[trackId] ?: 0L) + sum
    }

    private fun trunDurationSum(raf: RandomAccessFile, trun: Box): Long {
        val flags = u32(raf, trun.bodyStart) and 0xFFFFFF
        val count = u32(raf, trun.bodyStart + 4)
        var p = trun.bodyStart + 8L
        if (flags and 0x1 != 0) p += 4          // data_offset
        if (flags and 0x4 != 0) p += 4          // first_sample_flags
        val hasDuration = flags and 0x100 != 0
        val hasSize = flags and 0x200 != 0
        val hasFlags = flags and 0x400 != 0
        val hasCts = flags and 0x800 != 0
        var sum = 0L
        if (!hasDuration) return 0L
        for (i in 0 until count) {
            sum += u32(raf, p)
            p += 4
            if (hasSize) p += 4
            if (hasFlags) p += 4
            if (hasCts) p += 4
        }
        return sum
    }

    /**
     * 清掉音频轨样本 flags 里的 `sample_is_non_sync_sample`（bit16）。
     *
     * 老版本 muxer 构造音频 `Format` 时没设 codecs，media3 判不出 AAC 是
     * all-sync，于是把每个样本都标成非同步。`FragmentedMp4Extractor` 据此
     * 认为整条音轨没有可解码起点，直接丢弃 —— 表现就是「有画面没声音」。
     * AAC 每帧自带解码信息，这个位本就该是 0，清掉不影响解码。
     *
     * 只覆盖 4 字节整型字段，不动文件长度与 chunk 偏移，重复执行幂等。
     *
     * @return 实际被清掉的样本数
     */
    private fun patchSampleSyncFlags(file: File): Int {
        if (!file.exists() || file.length() < 64) return 0
        RandomAccessFile(file, "rw").use { raf ->
            val audioTracks = audioTrackIds(raf) ?: return 0
            if (audioTracks.isEmpty()) return 0
            var fixed = 0
            val fileLen = raf.length()
            var pos = 0L
            while (pos + 8 <= fileLen) {
                val box = readBox(raf, pos, fileLen) ?: break
                if (box.type == "moof") fixed += patchMoofSyncFlags(raf, box, audioTracks)
                if (box.end <= pos) break
                pos = box.end
            }
            if (fixed > 0) {
                Log.d(TAG, "cleared non-sync flag on $fixed audio samples (${file.name})")
            }
            return fixed
        }
    }

    /** moov 里所有 handler_type == "soun" 的 trak 的 track_ID */
    private fun audioTrackIds(raf: RandomAccessFile): Set<Int>? {
        val moov = findBox(raf, 0, raf.length(), "moov") ?: return null
        val out = HashSet<Int>()
        var index = 0
        var pos = moov.bodyStart
        while (pos < moov.end) {
            val trak = readBox(raf, pos, moov.end) ?: break
            if (trak.type == "trak") {
                val mdia = findChild(raf, trak.bodyStart, trak.end, "mdia")
                val hdlr = mdia?.let { findChild(raf, it.bodyStart, it.end, "hdlr") }
                // hdlr body: version/flags(4) pre_defined(4) handler_type(4)
                if (hdlr != null && fourCC(raf, hdlr.bodyStart + 8) == "soun") {
                    out += trackKey(raf, trak, index)
                }
                index++
            }
            pos = trak.end
        }
        return out
    }

    private fun patchMoofSyncFlags(raf: RandomAccessFile, moof: Box, audioTracks: Set<Int>): Int {
        var fixed = 0
        var pos = moof.bodyStart
        while (pos < moof.end) {
            val traf = readBox(raf, pos, moof.end) ?: break
            if (traf.type == "traf") fixed += patchTrafSyncFlags(raf, traf, audioTracks)
            pos = traf.end
        }
        return fixed
    }

    private fun patchTrafSyncFlags(raf: RandomAccessFile, traf: Box, audioTracks: Set<Int>): Int {
        var trackId = 0
        var defaultFlagsAt = -1L
        val truns = ArrayList<Box>()
        var pos = traf.bodyStart
        while (pos < traf.end) {
            val box = readBox(raf, pos, traf.end) ?: break
            when (box.type) {
                "tfhd" -> {
                    val flags = u32(raf, box.bodyStart) and 0xFFFFFF
                    trackId = u32(raf, box.bodyStart + 4)
                    var p = box.bodyStart + 8L
                    if (flags and 0x1 != 0) p += 8          // base_data_offset
                    if (flags and 0x2 != 0) p += 4          // sample_description_index
                    if (flags and 0x8 != 0) p += 4          // default_sample_duration
                    if (flags and 0x10 != 0) p += 4         // default_sample_size
                    if (flags and 0x20 != 0) defaultFlagsAt = p  // default_sample_flags
                }
                "trun" -> truns.add(box)
            }
            pos = box.end
        }
        if (trackId == 0 || trackId !in audioTracks) return 0

        var fixed = 0
        var anyPerSampleFlags = false
        for (trun in truns) {
            val flags = u32(raf, trun.bodyStart) and 0xFFFFFF
            val count = u32(raf, trun.bodyStart + 4).toLong() and 0xFFFFFFFFL
            var p = trun.bodyStart + 8L
            if (flags and 0x1 != 0) p += 4              // data_offset
            if (flags and 0x4 != 0) {                   // first_sample_flags
                fixed += clearNonSync(raf, p)
                p += 4
            }
            val hasDuration = flags and 0x100 != 0
            val hasSize = flags and 0x200 != 0
            val hasFlags = flags and 0x400 != 0
            val hasCts = flags and 0x800 != 0
            if (hasFlags) anyPerSampleFlags = true
            var i = 0L
            while (i < count) {
                if (hasDuration) p += 4
                if (hasSize) p += 4
                if (hasFlags) {
                    fixed += clearNonSync(raf, p)
                    p += 4
                }
                if (hasCts) p += 4
                i++
            }
        }
        if (!anyPerSampleFlags && defaultFlagsAt >= 0) fixed += clearNonSync(raf, defaultFlagsAt)
        return fixed
    }

    /** bit16 清零；返回 1 表示确有改动 */
    private fun clearNonSync(raf: RandomAccessFile, at: Long): Int {
        val value = u32(raf, at).toLong() and 0xFFFFFFFFL
        if (value and NON_SYNC_FLAG == 0L) return 0
        writeU32(raf, at, value and NON_SYNC_FLAG.inv())
        return 1
    }

    private fun fourCC(raf: RandomAccessFile, at: Long): String {
        raf.seek(at)
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    // ---------------------------------------------------------------- box 基础

    private class Box(
        val type: String,
        val start: Long,
        val bodyStart: Long,
        val end: Long
    )

    /** size==1 用 64 位 largesize；size==0 表示延伸到末尾 */
    private fun readBox(raf: RandomAccessFile, start: Long, limit: Long): Box? {
        if (start + 8 > limit) return null
        raf.seek(start)
        val sizeRaw = raf.readInt().toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        raf.readFully(typeBytes)
        val type = String(typeBytes, Charsets.US_ASCII)
        var bodyStart = start + 8
        val size: Long
        when (sizeRaw) {
            0L -> size = limit - start
            1L -> {
                if (start + 16 > limit) return null
                size = raf.readLong()
                bodyStart = start + 16
            }
            else -> size = sizeRaw
        }
        if (size < bodyStart - start) return null
        val end = start + size
        if (end > limit) return null
        return Box(type, start, bodyStart, end)
    }

    private fun findBox(raf: RandomAccessFile, start: Long, end: Long, type: String): Box? =
        walkTo(raf, start, end, type)

    private fun findChild(raf: RandomAccessFile, start: Long, end: Long, type: String): Box? =
        walkTo(raf, start, end, type)

    private fun walkTo(raf: RandomAccessFile, start: Long, end: Long, type: String): Box? {
        var pos = start
        while (pos < end) {
            val box = readBox(raf, pos, end) ?: return null
            if (box.type == type) return box
            if (box.end <= pos) return null
            pos = box.end
        }
        return null
    }

    private fun u32(raf: RandomAccessFile, at: Long): Int {
        raf.seek(at)
        return raf.readInt()
    }

    private fun writeU32(raf: RandomAccessFile, at: Long, value: Long) {
        raf.seek(at)
        raf.writeInt((value and 0xFFFFFFFFL).toInt())
    }
}