package com.dsh.bodycam.record

import android.os.SystemClock
import kotlin.math.abs

/**
 * 视频/音频时间戳的时基判定。
 *
 * 两条流未必同源：
 * - 音频 PTS 由本工程用 `SystemClock.elapsedRealtimeNanos()` 生成，恒定是
 *   **CLOCK_BOOTTIME**（含休眠）；
 * - 视频 PTS 来自相机帧时间戳，各平台不一：Pixel 一类是 CLOCK_BOOTTIME，
 *   MTK 的 DSJ-D100 是 **CLOCK_MONOTONIC**（不含休眠）。
 *
 * 设备休眠过之后两者相差的就是累计休眠时长（DSJ-D100 上实测约 586 秒）。
 * 以前直接相减，结果视频 PTS 全变负、音频被排到几百秒之后。
 *
 * 判定办法：同时读三条时间——新鲜视频帧的 PTS、此刻的 BOOTTIME、此刻的
 * MONOTONIC。相机 PTS 必然贴近它自己那条时钟（差一个编码延迟，几十毫秒），
 * 所以「离谁更近」就能确定时基；两者之差正好是要平移的量。
 */
internal object Timebase {

    /** 判定可靠所需的时钟差：小于它时两条时钟几乎重合，怎么取都对 */
    private const val MIN_DETECTABLE_SLEEP_US = 200_000L  // 0.2s

    /** 视频帧 PTS 与「此刻」的最大合理距离：编码延迟不会超过它 */
    private const val MAX_FRAME_AGE_US = 3_000_000L       // 3s

    /** 判定结果 */
    class Result(
        /** 音频 PTS 需要减掉的偏移（µs）：`audioPts - offset` 即视频时钟下的时间 */
        val audioOffsetUs: Long,
        /** 相机时间戳走的是 MONOTONIC 还是 BOOTTIME，仅用于日志 */
        val cameraClock: String,
        /** 累计休眠时长（µs），即 BOOTTIME − MONOTONIC */
        val sleepUs: Long
    )

    /**
     * @param videoPtsUs 刚拿到的视频帧 PTS（要求足够新鲜，距离现在 < [MAX_FRAME_AGE_US]）
     */
    fun detect(videoPtsUs: Long): Result {
        val bootUs = SystemClock.elapsedRealtimeNanos() / 1000
        val monoUs = System.nanoTime() / 1000
        val sleepUs = bootUs - monoUs

        // 两条时钟几乎重合：视频用哪条都一样，不需要平移
        if (sleepUs < MIN_DETECTABLE_SLEEP_US) {
            return Result(0L, "BOOTTIME", sleepUs)
        }
        val toBoot = abs(videoPtsUs - bootUs)
        val toMono = abs(videoPtsUs - monoUs)
        // 都不在合理范围：认不出来（可能是硬件时钟），退回「同源」假设
        if (minOf(toBoot, toMono) > MAX_FRAME_AGE_US) {
            return Result(0L, "UNKNOWN", sleepUs)
        }
        return if (toMono < toBoot) {
            // 相机走 MONOTONIC：音频（BOOTTIME）比它大一个休眠时长，要减掉
            Result(sleepUs, "MONOTONIC", sleepUs)
        } else {
            Result(0L, "BOOTTIME", sleepUs)
        }
    }
}
