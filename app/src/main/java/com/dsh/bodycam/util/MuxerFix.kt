package com.dsh.bodycam.util

import android.util.Log
import com.dsh.bodycam.core.TriggerRouter
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

    /**
     * 进程级串行锁。录制收尾的后台修补、加密与播放前的修补可能同时针对同一个
     * 文件（刚停录就去播放），而 [patchTfdt] 用固定的 `<name>.tfdt` 临时文件 +
     * rename，[VideoCrypto.encryptInPlace] 也会整体替换文件；并发跑会互相覆盖，
     * 甚至让 rename 落成半截内容。修补本身很快，串行不损性能。
     */
    internal val patchLock = Any()

    /** sample_flags 里的 `sample_is_non_sync_sample` 位（bit16） */
    private const val NON_SYNC_FLAG = 0x10000L

    /**
     * 修补一个 fMP4 文件，四件事都是**幂等**的，已修好的文件不会被重写，
     * 因此可以放心在录制收尾和播放前各调一次：
     *  1. [patchSampleSyncFlags] 清音频轨的 non-sync 标志（无改动则只读）；
     *  2. [patchTfdt] 注入 tfdt（已有则跳过，不重写文件）；
     *  3. [patchMfra] 追写 mfra 索引（已有可用索引则跳过）；
     *  4. [patchDurationFields] 回填 mvhd/tkhd/mdhd 的 duration
     *     （逐字段比对，值相同就不写）。
     *
     * 幂等不只是省 IO：播放一个已修好的视频如果还重写文件，
     * 它的 lastModified 会被刷成「刚刚」，文件列表的顺序就乱了。
     */
    fun patchDurations(file: File) {
        if (!file.exists() || file.length() < 64) return
        // 兜底：正在写入的分段绝不能碰。patchTfdt 会 tmp.renameTo 换掉 inode，
        // 而 muxer 还持有旧 FD，之后所有写入都落进已 unlink 的文件里，
        // 这一段就永久定格在重写那一刻的数据量（不可恢复）。
        if (TriggerRouter.isRecordingSegment(file.name)) {
            Log.w(TAG, "${file.name}: still recording, skip patch")
            return
        }
        synchronized(patchLock) {
            patchDurationsLocked(file)
        }
    }

    private fun patchDurationsLocked(file: File) {
        patchSampleSyncFlags(file)
        // 先补 tfdt 再建索引：注入会改变各 moof 的偏移，mfra 必须按新偏移重建
        patchTfdt(file)
        patchMfra(file)
        patchDurationFields(file)
    }

    /**
     * 把 moov 里为 0 的 duration 回填成真实值（谁读谁写）。
     *
     * 幂等性在这里格外重要：播放前也会跑一遍修补，如果每次都无条件写，
     * 光是「打开一个已修好的视频」就会刷新文件的 lastModified。
     * 所以先读现值，与算出来的目标一致就完全不碰文件——
     * 连 [RandomAccessFile] 的写句柄都不会打开。
     */
    private fun patchDurationFields(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val moov = findBox(raf, 0, raf.length(), "moov") ?: return
            val mvhd = findChild(raf, moov.bodyStart, moov.end, "mvhd") ?: return
            val movieTimescale = u32(raf, mvhd.bodyStart + 12)
            if (movieTimescale <= 0) return

            // 每轨的 media duration（以各自 mdhd.timescale 计）
            val trackTicks = accumulateTrunDurations(raf)

            var maxMovieDur = 0L
            var index = 0
            var applied = 0
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
                            if (writeU32IfChanged(raf, mdhd.bodyStart + 16, ticks)) applied++
                            if (tkhd != null && writeU32IfChanged(raf, tkhd.bodyStart + 20, movieDur)) {
                                applied++
                            }
                        }
                    }
                    index++
                }
                pos = box.end
            }
            if (writeU32IfChanged(raf, mvhd.bodyStart + 16, maxMovieDur)) applied++
            if (applied > 0) {
                Log.i(TAG, "${file.name}: backfilled $applied duration field(s), mvhd=$maxMovieDur")
            }
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
     * 同上，但允许用 `tfhd.default_sample_duration` 兜底。
     *
     * @return 总时长；无法确定（无逐样本时长且无默认值时）返回 -1
     */
    private fun trunDurationSum(
        raf: RandomAccessFile,
        trun: Box,
        defaultSampleDuration: Long
    ): Long {
        val flags = u32(raf, trun.bodyStart) and 0xFFFFFF
        val count = u32(raf, trun.bodyStart + 4).toLong() and 0xFFFFFFFFL
        val hasDuration = flags and 0x100 != 0
        if (!hasDuration) {
            return if (defaultSampleDuration > 0) defaultSampleDuration * count else -1L
        }
        var p = trun.bodyStart + 8L
        if (flags and 0x1 != 0) p += 4          // data_offset
        if (flags and 0x4 != 0) p += 4          // first_sample_flags
        val hasSize = flags and 0x200 != 0
        val hasFlags = flags and 0x400 != 0
        val hasCts = flags and 0x800 != 0
        var sum = 0L
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

    // ---------------------------------------------------------------- tfdt 注入

    /**
     * 给每个 `traf` 补上 `tfdt`（track fragment base media decode time）。
     *
     * ## 为什么需要（seek 后画面冻结的真因）
     *
     * `tfdt` 记录分片首样本的**绝对**解码时间。media3 的 `FragmentedMp4Muxer`
     * 完全不写这个盒，`FragmentedMp4Extractor` 于是只能靠 `nextFragmentDecodeTime`
     * 跨分片累加推算时间。这个累加值会在 `seek()` 时被清零：
     *
     * ```java
     * // FragmentedMp4Extractor.seek()
     * trackBundles.valueAt(i).resetFragmentInfo();   // → TrackFragment.reset()
     * // TrackFragment.reset()
     * nextFragmentDecodeTime = 0;                    // 累加基准丢了
     * ```
     *
     * 有 `tfdt` 时 `parseTraf` 直接用盒里的绝对值覆盖它，累加基准从分片真实时间
     * 恢复；没有 `tfdt` 就只能沿用被清零的 0，样本 PTS 从 0 重新数：
     * 拖到 1:30 的位置，画面时间戳却从 0 开始，渲染器判定全部迟到而丢弃，
     * **进度条照常走，画面冻在最后一帧**。mfra 索引只解决「跳到哪个字节」，
     * 解决不了「跳过去之后时间从哪算」，两者缺一不可。
     *
     * ## 怎么补
     *
     * 盒结构是 `moof(mfhd, traf(tfhd, trun), ...)`，`tfdt` 按规范必须紧跟在
     * `tfhd` 之后。插入会让该 `moof` 变长，因此要顺序重写整个文件：
     * 每遇到一个 `moof` 就重算尺寸、把后续 `tfhd.base_data_offset`（绝对偏移）
     * 往前推 [INSERT_BYTES] × 已插入数。`mdat` 及其内容原样搬运。
     *
     * 原文件带 mfra 时尾部索引的偏移全部失效，这里截掉重建（[patchMfra] 随后
     * 按新偏移重写）。幂等：每个 `traf` 已有 `tfdt` 就直接跳过。
     */
    private fun patchTfdt(file: File) {
        if (!file.exists() || file.length() < 64) return
        val plan = planTfdt(file)
        if (plan == null) {
            Log.w(TAG, "patchTfdt ${file.name}: plan failed")
            return
        }
        if (plan.insertCount == 0) {
            Log.d(TAG, "patchTfdt ${file.name}: nothing to insert")
            return
        }
        Log.i(TAG, "patchTfdt ${file.name}: inserting ${plan.insertCount} boxes")

        val tmp = File(file.parentFile, file.name + ".tfdt")
        try {
            rewriteWithTfdt(file, tmp, plan)
            if (!tmp.renameTo(file)) {
                // 跨文件系统等情况：退化为拷贝覆盖
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "${file.name}: injected ${plan.insertCount} tfdt boxes")
        } catch (e: Exception) {
            Log.w(TAG, "patchTfdt ${file.name}", e)
            tmp.delete()
        }
    }

    /** tfdt 盒固定 16 字节：size(4) + type(4) + version/flags(4) + 时间(4) */
    private const val TFDT_BOX_SIZE = 16

    /** 扫描结果：哪些 moof 的哪些 traf 缺 tfdt，以及该轨分片的起始解码时间 */
    private class TfdtPlan(
        /** moof 起点 → 该 moof 内待插入的 (traf 序号 → 起始 ticks) */
        val inserts: Map<Long, Map<Int, Long>>,
        val insertCount: Int,
        val hasMfra: Boolean
    )

    /**
     * 顺序扫一遍文件，算出每个缺 `tfdt` 的 traf 应写入的 base media decode time。
     *
     * 时间基准按 `FragmentedMp4Extractor` 的规则累加：每片首样本时间 = 上一片的
     * 首样本时间 + 该片所有 trun 的样本时长之和（在各自 mdhd.timescale 下）。
     * 已有 `tfdt` 的分片直接以其值为准，避免历史补丁文件算错。
     */
    private fun planTfdt(file: File): TfdtPlan? = RandomAccessFile(file, "r").use { raf ->
        val fileLen = raf.length()
        val inserts = HashMap<Long, Map<Int, Long>>()
        val decodeTicks = HashMap<Int, Long>()
        var insertCount = 0
        var hasMfra = false
        var pos = 0L

        while (pos + 8 <= fileLen) {
            // moof 里要改写子盒，必须整块读进内存；mdat 只跳过不读
            val header = readBoxHeader(raf, pos, fileLen) ?: break
            when (header.type) {
                "mfra" -> {
                    hasMfra = true
                    break // 索引之后没有媒体数据
                }
                "moof" -> {
                    if (header.size > MAX_MOOF_BYTES) return null
                    raf.seek(header.start)
                    val moof = ByteArray(header.size.toInt())
                    raf.readFully(moof)
                    val pending = planMoofTfdt(moof, decodeTicks)
                    if (pending.isNotEmpty()) {
                        inserts[header.start] = pending
                        insertCount += pending.size
                    }
                }
            }
            if (header.end <= pos) break
            pos = header.end
        }

        TfdtPlan(inserts, insertCount, hasMfra)
    }

    /** 单个 moof 可达的上限，防止坏文件让内存爆掉 */
    private const val MAX_MOOF_BYTES = 8L * 1024 * 1024

    /**
     * 分析一个 moof 的字节内容，返回 `traf 序号 → 应写入的起始 ticks`。
     * 同时把 [decodeTicks] 推进到下一片。
     */
    private fun planMoofTfdt(moof: ByteArray, decodeTicks: MutableMap<Int, Long>): Map<Int, Long> {
        val pending = LinkedHashMap<Int, Long>()
        var trafIndex = 0
        var pos = 8 // 跳过 moof 头
        while (pos + 8 <= moof.size) {
            val size = u32(moof, pos).toLong() and 0xFFFFFFFFL
            val type = String(moof, pos + 4, 4, Charsets.US_ASCII)
            if (size < 8 || pos + size > moof.size) break
            if (type == "traf") {
                trafIndex++
                planTrafTfdt(moof, pos + 8, pos + size.toInt(), trafIndex, decodeTicks, pending)
            }
            pos += size.toInt()
        }
        return pending
    }

    /**
     * 处理单个 traf：算出起始 ticks 推进累加器，缺 tfdt 就记进 [pending]。
     * 返回该 traf 的 trackId（0 表示解析失败）。
     */
    private fun planTrafTfdt(
        moof: ByteArray,
        bodyStart: Int,
        end: Int,
        trafIndex: Int,
        decodeTicks: MutableMap<Int, Long>,
        pending: MutableMap<Int, Long>
    ): Int {
        var trackId = 0
        var tfdtTicks = -1L
        var defaultSampleDuration = 0L
        var durationSum = 0L
        var durationKnown = false

        var pos = bodyStart
        while (pos + 8 <= end) {
            val size = u32(moof, pos).toLong() and 0xFFFFFFFFL
            val type = String(moof, pos + 4, 4, Charsets.US_ASCII)
            if (size < 8 || pos + size > end) break
            val boxEnd = pos + size.toInt()
            when (type) {
                "tfhd" -> {
                    val flags = u32(moof, pos + 8) and 0xFFFFFF
                    trackId = u32(moof, pos + 12)
                    var p = pos + 16
                    if (flags and 0x1 != 0) p += 8
                    if (flags and 0x2 != 0) p += 4
                    if (flags and 0x8 != 0) {
                        defaultSampleDuration = u32(moof, p).toLong() and 0xFFFFFFFFL
                    }
                }
                "tfdt" -> {
                    val version = (u32(moof, pos + 8) ushr 24) and 0xFF
                    tfdtTicks = if (version == 1) {
                        readU64(moof, pos + 12)
                    } else {
                        u32(moof, pos + 12).toLong() and 0xFFFFFFFFL
                    }
                }
                "trun" -> {
                    val d = trunDurationSum(moof, pos, boxEnd, defaultSampleDuration)
                    if (d >= 0) {
                        durationSum += d
                        durationKnown = true
                    }
                }
            }
            pos = boxEnd
        }
        if (trackId == 0) return 0

        val startTicks = if (tfdtTicks >= 0) tfdtTicks else (decodeTicks[trackId] ?: 0L)
        if (tfdtTicks < 0) pending[trafIndex] = startTicks
        decodeTicks[trackId] = startTicks + if (durationKnown) durationSum else 0L
        return trackId
    }

    /** ByteArray 版 trun 时长求和；无法确定时返回 -1 */
    private fun trunDurationSum(
        buf: ByteArray,
        trunStart: Int,
        trunEnd: Int,
        defaultSampleDuration: Long
    ): Long {
        val flags = u32(buf, trunStart + 8) and 0xFFFFFF
        val count = u32(buf, trunStart + 12)
        var p = trunStart + 16
        if (flags and 0x1 != 0) p += 4  // data_offset
        if (flags and 0x4 != 0) p += 4  // first_sample_flags
        val hasDuration = flags and 0x100 != 0
        var sum = 0L
        for (i in 0 until count) {
            if (hasDuration) {
                if (p + 4 > trunEnd) return if (sum > 0) sum else -1
                sum += u32(buf, p).toLong() and 0xFFFFFFFFL
                p += 4
            } else {
                if (defaultSampleDuration <= 0) return -1
                sum += defaultSampleDuration
            }
            if (flags and 0x200 != 0) p += 4  // sample_size
            if (flags and 0x400 != 0) p += 4  // sample_flags
            if (flags and 0x800 != 0) p += 4  // composition_time_offset
        }
        return sum
    }

    /**
     * 顺序重写文件：每个 moof 重建（插入 tfdt、更新尺寸与 base_data_offset），
     * 其余盒原样搬运，末尾索引丢弃后由 [patchMfra] 重建。
     */
    private fun rewriteWithTfdt(src: File, dst: File, plan: TfdtPlan) {
        RandomAccessFile(src, "r").use { input ->
            RandomAccessFile(dst, "rw").use { output ->
                output.setLength(0)
                val fileLen = input.length()
                val buffer = ByteArray(COPY_BUFFER)
                var pos = 0L
                // 已插入的 tfdt 总字节数：后续所有绝对偏移都要加上它
                var shift = 0L

                while (pos + 8 <= fileLen) {
                    val header = readBoxHeader(input, pos, fileLen) ?: break
                    if (header.type == "mfra") break // 索引重建，不搬

                    val pending = plan.inserts[header.start]
                    if (header.type == "moof" && pending != null) {
                        input.seek(header.start)
                        val moof = ByteArray(header.size.toInt())
                        input.readFully(moof)
                        output.write(rebuildMoof(moof, pending, shift))
                        shift += pending.size * TFDT_BOX_SIZE
                    } else {
                        copyRange(input, output, header.start, header.size, buffer)
                    }
                    if (header.end <= pos) break
                    pos = header.end
                }

                // 带上 moov（在文件头）已经搬过；mfra 留空，patchMfra 会补
                output.fd.sync()
            }
        }
    }

    /** 把 src 的 [start, start+len) 原样拷到 dst 当前位置 */
    private fun copyRange(
        input: RandomAccessFile,
        output: RandomAccessFile,
        start: Long,
        len: Long,
        buffer: ByteArray
    ) {
        input.seek(start)
        var remaining = len
        while (remaining > 0) {
            val n = minOf(remaining, buffer.size.toLong()).toInt()
            input.readFully(buffer, 0, n)
            output.write(buffer, 0, n)
            remaining -= n
        }
    }

    private const val COPY_BUFFER = 256 * 1024

    /**
     * 重建一个 moof：对需要补 tfdt 的 traf，在 `tfhd` 之后插入 16 字节 tfdt；
     * 同时更新 traf 与 moof 的 size、tfhd 里的 base_data_offset。
     *
     * @param shift 本 moof 之前已插入的字节总数（用于修正绝对偏移）
     */
    private fun rebuildMoof(moof: ByteArray, pending: Map<Int, Long>, shift: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(moof.size + pending.size * TFDT_BOX_SIZE)
        val data = java.io.DataOutputStream(out)

        var trafIndex = 0
        var pos = 8 // 跳过 moof 头
        // 本 moof 总共插入的字节数：mdat 整体后移这么多，
        // 每个 traf 的 data_offset 都要加同一个值（不是逐个累加）
        val moofInsertBytes = pending.size * TFDT_BOX_SIZE
        val trafBlocks = ArrayList<ByteArray>()

        while (pos + 8 <= moof.size) {
            val size = u32(moof, pos).toLong() and 0xFFFFFFFFL
            val type = String(moof, pos + 4, 4, Charsets.US_ASCII)
            if (size < 8 || pos + size > moof.size) break
            if (type == "traf") {
                trafIndex++
                val ticks = pending[trafIndex]
                // base_data_offset 是绝对偏移：只加本 moof 之前的累计插入量，
                // 同一 moof 内所有 traf 的 base 都指向 moof 起点，值相同。
                //
                // data_offset 是相对 moof 起点的偏移（base 指向 moof 起点时），
                // moof 整体变长多少，mdat 数据就后移多少，所以每个 traf 都加
                // 本 moof 的总插入量。
                val block = rebuildTraf(
                    moof, pos, pos + size.toInt(), ticks,
                    offsetShift = shift,
                    dataShift = moofInsertBytes.toLong()
                )
                trafBlocks.add(block)
            } else {
                trafBlocks.add(moof.copyOfRange(pos, pos + size.toInt()))
            }
            pos += size.toInt()
        }

        var moofBodySize = 0L
        for (b in trafBlocks) moofBodySize += b.size
        data.writeInt((8 + moofBodySize).toInt())
        data.writeBytes("moof")
        for (b in trafBlocks) data.write(b)
        data.flush()
        return out.toByteArray()
    }

    /**
     * 重建一个 traf，可选在 `tfhd` 后插入 tfdt，并按偏移修正数据定位。
     *
     * ## 两类偏移都要改，缺一不可
     *
     * 插入 tfdt 后 moof 变长，`mdat` 整体后移。样本数据的定位是
     * `数据位置 = base_data_offset + data_offset`，两个字段含义不同：
     *
     * - `tfhd.base_data_offset`（flags & 0x1）：**相对文件开头**的绝对偏移，
     *   指向 moof 起点。要加的是本 moof **之前**所有已插入字节的累计量
     *   [offsetShift]。同一 moof 内所有 traf 的这个值相同。
     * - `trun.data_offset`（flags & 0x1）：**相对 base_data_offset** 的偏移。
     *   base 指向 moof 起点时它等于「mdat 数据起点 − moof 起点」。moof 整体
     *   变长多少，mdat 就后移多少，所以要加的是本 moof 的**总**插入量
     *   [dataShift]——不是按 traf 逐个累加。
     *
     * 只改 base 不改 data_offset，解析器仍按旧偏移去读，实际落在 moof 内部或
     * mdat 头几字节上，样本数据整段错位。ExoPlayer 靠自身 extractor 的容错
     * 仍能播（甚至看起来正常），VLC / ffmpeg 这类严格实现直接判文件损坏。
     *
     * @param startTicks 非空则插入 tfdt（该 traf 分片的起始解码时间）
     * @param offsetShift 本 moof 之前已插入的字节总数
     * @param dataShift 修正 `trun.data_offset` 用的量（本 moof 的总插入量）
     */
    private fun rebuildTraf(
        moof: ByteArray,
        trafStart: Int,
        trafEnd: Int,
        startTicks: Long?,
        offsetShift: Long,
        dataShift: Long
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream(trafEnd - trafStart + TFDT_BOX_SIZE)
        val data = java.io.DataOutputStream(out)
        var bodySize = 0L
        var inserted = false

        var pos = trafStart + 8
        while (pos + 8 <= trafEnd) {
            val size = u32(moof, pos).toLong() and 0xFFFFFFFFL
            val type = String(moof, pos + 4, 4, Charsets.US_ASCII)
            if (size < 8 || pos + size > trafEnd) break
            val boxEnd = pos + size.toInt()

            if (type == "tfhd" && offsetShift > 0) {
                val box = moof.copyOfRange(pos, boxEnd)
                val flags = u32(box, 8) and 0xFFFFFF
                if (flags and 0x1 != 0) {
                    val at = 16 // size+type+version/flags+trackId
                    val oldOffset = readU64(box, at)
                    writeU64(box, at, oldOffset + offsetShift)
                }
                data.write(box)
                bodySize += box.size
            } else if (type == "trun" && dataShift > 0) {
                // data_offset 是相对 base_data_offset 的偏移，moof 变长后同样要加
                val box = moof.copyOfRange(pos, boxEnd)
                val flags = u32(box, 8) and 0xFFFFFF
                if (flags and 0x1 != 0) {
                    // size+type+version/flags+sampleCount
                    val at = 16
                    writeU32(box, at, readU32(box, at) + dataShift.toInt())
                }
                data.write(box)
                bodySize += box.size
            } else {
                data.write(moof, pos, boxEnd - pos)
                bodySize += boxEnd - pos
            }

            // 紧跟 tfhd 插入 tfdt
            if (type == "tfhd" && startTicks != null && !inserted) {
                val tfdt = ByteArray(TFDT_BOX_SIZE)
                tfdt[0] = 0; tfdt[1] = 0; tfdt[2] = 0; tfdt[3] = 16
                "tfdt".toByteArray(Charsets.US_ASCII).copyInto(tfdt, 4)
                tfdt[8] = 0; tfdt[9] = 0; tfdt[10] = 0; tfdt[11] = 0 // version 0
                tfdt[12] = (startTicks ushr 24).toByte()
                tfdt[13] = (startTicks ushr 16).toByte()
                tfdt[14] = (startTicks ushr 8).toByte()
                tfdt[15] = startTicks.toByte()
                data.write(tfdt)
                bodySize += TFDT_BOX_SIZE
                inserted = true
            }
            pos = boxEnd
        }

        data.flush()
        val body = out.toByteArray()
        val result = ByteArray(8 + body.size)
        result[0] = ((8 + bodySize) ushr 24).toByte()
        result[1] = ((8 + bodySize) ushr 16).toByte()
        result[2] = ((8 + bodySize) ushr 8).toByte()
        result[3] = (8 + bodySize).toByte()
        "traf".toByteArray(Charsets.US_ASCII).copyInto(result, 4)
        body.copyInto(result, 8)
        return result
    }

    // ---------------------------------------------------------------- mfra 索引

    /**
     * 为 fMP4 追写一个 `mfra`（movie fragment random access）索引盒。
     *
     * ## 为什么需要
     *
     * media3 的 `FragmentedMp4Muxer` 只写 `ftyp / moov / moof+mdat`，**不写任何索引**
     * （既无 `sidx` 也无 `mfra`）。而 `FragmentedMp4Extractor` 在读到第一个
     * `moof` 时如果拿不到索引，就直接把 SeekMap 定成 `Unseekable`，
     * 之后**永不更新**。30 分钟录音对应 900 个 2 秒分片，拖动进度条时
     * ExoPlayer 只能从头顺序解到目标位置——表现就是「拖不动」。
     *
     * `mfra` 是 fMP4 标准的随机访问索引：每个 `traf` 记一条「时间 → 文件偏移」，
     * 播放器读文件末尾的它就能直接跳。它本身在文件最后，追加写出不影响
     * 已有分片，也不需要改动任何既有偏移。
     *
     * ## 格式
     *
     * ```
     * mfra
     *   tfra (每轨一个)
     *     version/flags (4)          version=0
     *     track_ID (4)
     *     reserved (4)               低 2 位分别是 len(traf_number)/len(trun_number)/len(sample_number) 的位数
     *     number_of_entry (4)
     *     entries[]: time(v0=4B) moof_offset(v0=4B) traf_number trun_number sample_number
     *   mfro
     *     version/flags (4)
     *     size (4)                   整个 mfra 盒的字节数（含自身）
     * ```
     *
     * ## 幂等
     *
     * 已存在且**校验通过**的 `mfra` 直接返回。早期版本的补丁把条目长度和时间
     * 单位都写错了（见 [buildTfra]），那种坏索引必须重建，否则老文件永远拖不动：
     * 校验失败时把尾部坏索引整段截掉，再按当前格式重写。
     */
    private fun patchMfra(file: File) {
        if (!file.exists() || file.length() < 64) return
        RandomAccessFile(file, "rw").use { raf ->
            val scan = scanBoxes(raf, raf.length())
            val existing = scan.mfra
            if (existing != null && existing.end == raf.length() && isMfraUsable(raf, existing)) {
                return // 已有可用索引，幂等退出
            }

            if (scan.headers.isEmpty()) return
            // 无索引：mediaEnd == 文件尾，等价于纯追加。
            // 有坏索引：截到媒体数据末尾再重写（scan 遇到 mfra 即停，不会丢数据）。
            raf.setLength(scan.mediaEnd)
            raf.seek(scan.mediaEnd)
            raf.write(buildMfra(scan.headers))
        }
    }

    /**
     * 从文件头顺序扫一遍 box，收集 moof 与 mfra。
     *
     * `mediaEnd` 是最后一个合法 box 的结尾，也就是「真实媒体数据」的边界：
     * 它之后要么是空，要么是索引/补丁区。旧版坏索引的 `size` 字段比实际内容
     * 大 4N 字节，`readBox` 会因越过文件尾而失败并在此中止扫描，`mediaEnd`
     * 正好落在坏索引的起点，因此截断到它再重建是安全的。
     */
    private fun scanBoxes(raf: RandomAccessFile, fileLen: Long): ScanResult {
        val headers = ArrayList<MoofHeader>()
        var mfra: Box? = null
        var mediaEnd = 0L
        // trackId → 下一片首样本的解码时间（timescale 单位）。media3 的 muxer 不写 tfdt，
        // 只能像 FragmentedMp4Extractor 那样按 trun 时长逐片累加推算。
        val decodeTicks = HashMap<Int, Long>()
        var pos = 0L
        while (pos + 8 <= fileLen) {
            val box = readBox(raf, pos, fileLen) ?: break
            if (box.type == "mfra") {
                mfra = box
                break // mfra 之后不会再有媒体数据
            }
            if (box.type == "moof") {
                val entries = readMoofTracks(raf, box, decodeTicks)
                if (entries.isNotEmpty()) headers.add(MoofHeader(box.start, entries))
            }
            if (box.end <= pos) break
            pos = box.end
            mediaEnd = pos
        }
        return ScanResult(headers, mfra, mediaEnd)
    }

    private class ScanResult(
        val headers: List<MoofHeader>,
        val mfra: Box?,
        val mediaEnd: Long
    )

    /**
     * 校验文件末尾的 `mfra` 是否可被 media3 正确解析：
     * `mfra` 必须是最后一个盒，末尾 16 字节必须是 `mfro`，且它记录的
     * `mfra_size` 与实际长度一致（长度不一致正是早期坏索引的特征）。
     */
    private fun isMfraUsable(raf: RandomAccessFile, mfra: Box): Boolean {
        val fileLen = raf.length()
        if (mfra.end != fileLen || fileLen - mfra.start < 16) return false
        val mfro = readBox(raf, fileLen - 16, fileLen) ?: return false
        if (mfro.type != "mfro") return false
        val mfraSize = u32(raf, mfro.bodyStart + 4).toLong() and 0xFFFFFFFFL
        return mfraSize == fileLen - mfra.start
    }

    /** 一个 moof 的位置信息：起点偏移 + 各轨在 traf 里的时间与序号 */
    private class MoofHeader(
        val offset: Long,
        /** 该 moof 各 traf 的索引条目 */
        val tracks: List<TrackEntry>
    )

    private class TrackEntry(
        val trackId: Int,
        /** 首个样本的 presentation time，单位是该轨 mdhd.timescale（tfra 规范单位） */
        val timeTicks: Long,
        val trafNumber: Long,
        val trunNumber: Long,
        val sampleNumber: Long
    )

    /**
     * 读出一个 moof 里各 traf 的索引条目，并把 [decodeTicks] 推进到下一片。
     *
     * media3 的 `FragmentedMp4Muxer` **不写 `tfdt`**（也不给 trex 写默认样本时长），
     * 分片首样本的解码时间只能按 `trun` 里的样本时长逐片累加推算 —— 与
     * `FragmentedMp4Extractor` 在没有 `tfdt` 时的做法一致。遇到 `tfdt` 则优先采信。
     */
    private fun readMoofTracks(
        raf: RandomAccessFile,
        moof: Box,
        decodeTicks: MutableMap<Int, Long>
    ): List<TrackEntry> {
        val out = ArrayList<TrackEntry>()
        var pos = moof.bodyStart
        var trafIndex = 0L
        while (pos < moof.end) {
            val traf = readBox(raf, pos, moof.end) ?: break
            if (traf.type == "traf") {
                trafIndex++
                val entry = readTrafFirstSample(raf, traf, trafIndex, decodeTicks)
                if (entry != null) out.add(entry)
            }
            pos = traf.end
        }
        return out
    }

    /** 从一个 traf 里读出首个样本的 trackId / 解码时间（timescale 单位）/ 序号 */
    private fun readTrafFirstSample(
        raf: RandomAccessFile,
        traf: Box,
        trafNumber: Long,
        decodeTicks: MutableMap<Int, Long>
    ): TrackEntry? {
        var trackId = 0
        var tfdtTicks = -1L
        var defaultSampleDuration = -1L
        val truns = ArrayList<Box>()

        var pos = traf.bodyStart
        while (pos < traf.end) {
            val box = readBox(raf, pos, traf.end) ?: break
            when (box.type) {
                "tfhd" -> {
                    val tfhdFlags = u32(raf, box.bodyStart) and 0xFFFFFF
                    trackId = u32(raf, box.bodyStart + 4)
                    var p = box.bodyStart + 8L
                    if (tfhdFlags and 0x1 != 0) p += 8           // base_data_offset
                    if (tfhdFlags and 0x2 != 0) p += 4           // sample_description_index
                    if (tfhdFlags and 0x8 != 0) {                // default_sample_duration
                        defaultSampleDuration = u32(raf, p).toLong() and 0xFFFFFFFFL
                    }
                }
                "tfdt" -> {
                    // version 0 用 32 位 baseMediaDecodeTime，version 1 用 64 位
                    val version = u32(raf, box.bodyStart) ushr 24
                    tfdtTicks = if (version == 1) {
                        raf.seek(box.bodyStart + 4)
                        raf.readLong()
                    } else {
                        u32(raf, box.bodyStart + 4).toLong() and 0xFFFFFFFFL
                    }
                }
                "trun" -> truns.add(box)
            }
            pos = box.end
        }
        if (trackId == 0) return null

        val startTicks = if (tfdtTicks >= 0) tfdtTicks else (decodeTicks[trackId] ?: 0L)

        // 推进到下一片的首样本时间；算不出时长就停在原地（索引仍可定位，只是不够精确）
        var durationSum = 0L
        var durationKnown = false
        for (trun in truns) {
            val d = trunDurationSum(raf, trun, defaultSampleDuration)
            if (d >= 0) {
                durationSum += d
                durationKnown = true
            }
        }
        decodeTicks[trackId] = startTicks + if (durationKnown) durationSum else 0L
        if (!durationKnown) {
            Log.w(TAG, "track$trackId: cannot determine sample durations, index time may drift")
        }

        return TrackEntry(trackId, startTicks, trafNumber, 1L, 1L)
    }

    /** 组装 mfra 盒字节：mfra(tfra*, mfro) */
    private fun buildMfra(headers: List<MoofHeader>): ByteArray {
        // 按 trackId 归并条目，保持首次出现的顺序
        val byTrack = LinkedHashMap<Int, MutableList<TrackEntry>>()
        for (h in headers) {
            for (t in h.tracks) {
                byTrack.getOrPut(t.trackId) { ArrayList() }.add(t)
            }
        }

        val tfras = ArrayList<ByteArray>()
        for ((trackId, entries) in byTrack) {
            tfras.add(buildTfra(trackId, entries, headers))
        }

        // mfra 总长 = 8(头) + Σtfra + 16(mfro)
        var total = 8L + 16L
        for (t in tfras) total += t.size

        val out = java.io.ByteArrayOutputStream()
        val data = java.io.DataOutputStream(out)
        // mfra box 头
        data.writeInt(total.toInt())
        data.writeBytes("mfra")
        for (t in tfras) data.write(t)
        // mfro：version/flags + 整个 mfra 的长度
        data.writeInt(16)
        data.writeBytes("mfro")
        data.writeInt(0)               // version 0 + flags 0
        data.writeInt(total.toInt())   // mfra_size
        data.flush()
        return out.toByteArray()
    }

    /**
     * 单个 tfra 盒。
     *
     * reserved 字段的低 2 位分别编码三个序号字段的字节数减一；这里三者都用 1 字节，
     * 所以填 0。条目里的时间与 moof 偏移按 version 0 写成 32 位——单文件远小于 4GB。
     *
     * `time` 用的是该轨 `mdhd.timescale` 的时间单位（规范如此），不是微秒：
     * media3 的 `readMfra` 会再按 timescale 缩放一次，写成微秒会让跳转位置全错。
     */
    private fun buildTfra(
        trackId: Int,
        entries: List<TrackEntry>,
        headers: List<MoofHeader>
    ): ByteArray {
        // 条目数：每片一条，需要 moof 偏移，按 entries 对应回它的 moof
        val offsetOf = HashMap<TrackEntry, Long>()
        for (h in headers) for (t in h.tracks) offsetOf[t] = h.offset

        // 每条 11 字节：time(4) + moof_offset(4) + traf(1) + trun(1) + sample(1)
        val entrySize = 11
        val size = 8 + 4 + 4 + 4 + 4 + entries.size * entrySize

        val out = java.io.ByteArrayOutputStream()
        val data = java.io.DataOutputStream(out)
        data.writeInt(size)
        data.writeBytes("tfra")
        data.writeInt(0)                       // version 0 + flags 0
        data.writeInt(trackId)
        data.writeInt(0)                       // reserved：三个长度字段各 1 字节（0=1-1）
        data.writeInt(entries.size)
        for (e in entries) {
            data.writeInt(e.timeTicks.toInt())                 // presentation time（track timescale 单位）
            data.writeInt((offsetOf[e] ?: 0L).toInt())         // moof 文件偏移（v0 32 位）
            data.writeByte(1)                                  // traf_number
            data.writeByte(1)                                  // trun_number
            data.writeByte(1)                                  // sample_number
        }
        data.flush()
        return out.toByteArray()
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

    private fun u32(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or
            ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or
            (buf[at + 3].toInt() and 0xFF)

    private fun readU64(buf: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[at + i].toLong() and 0xFF)
        return v
    }

    private fun writeU64(buf: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) buf[at + i] = (value ushr (56 - i * 8)).toByte()
    }

    /** 读 buf 里的 32 位无符号值（trun.data_offset 是 signed，但偏移量恒为正） */
    private fun readU32(buf: ByteArray, at: Int): Int = u32(buf, at)

    private fun writeU32(buf: ByteArray, at: Int, value: Int) {
        buf[at] = (value ushr 24).toByte()
        buf[at + 1] = (value ushr 16).toByte()
        buf[at + 2] = (value ushr 8).toByte()
        buf[at + 3] = value.toByte()
    }

    /**
     * 只读盒头（不读 body），用于顺序扫大文件时跳过 mdat。
     * size==1 时读 64 位 largesize；size==0 表示延伸到文件末尾。
     */
    private fun readBoxHeader(raf: RandomAccessFile, start: Long, limit: Long): BoxHeader? {
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
        return BoxHeader(type, start, bodyStart, end)
    }

    private class BoxHeader(
        val type: String,
        val start: Long,
        val bodyStart: Long,
        val end: Long
    ) {
        val size: Long get() = end - start
    }

    private fun writeU32(raf: RandomAccessFile, at: Long, value: Long) {
        raf.seek(at)
        raf.writeInt((value and 0xFFFFFFFFL).toInt())
    }

    /**
     * 只有现值与目标不同才写，返回是否真的写了。
     *
     * 用于 duration 回填：已修好的文件再跑一遍修补时完全不产生写操作，
     * 文件的 lastModified 与内容都保持原样。
     */
    private fun writeU32IfChanged(raf: RandomAccessFile, at: Long, value: Long): Boolean {
        val current = u32(raf, at).toLong() and 0xFFFFFFFFL
        val target = value and 0xFFFFFFFFL
        if (current == target) return false
        raf.seek(at)
        raf.writeInt(target.toInt())
        return true
    }
}
