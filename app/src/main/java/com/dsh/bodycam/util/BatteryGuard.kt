package com.dsh.bodycam.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.core.SettingsStore

/**
 * 电量的兜底管理，与 [DiskGuard] 同构的两级模型：
 *  1. 按系统电量读数分成「告警线」与「强制停录线」两级；
 *  2. 告警线只提示，强制停录线触发安全停录（走正常 `stopRecording`，
 *     编码器尾帧排空、最后一个 moof+mdat 写出，文件仍是可播放的 fMP4）。
 *
 * **充电中一律视为 OK**：插上充电器即自动解除低电量状态，不需要额外的解除逻辑。
 *
 * **读不到电宁可不停录**：拿不到电量时返回 [Level.OK]，与 [DiskGuard.freeBytes]
 * 拿不到空间时返回 `Long.MAX_VALUE` 同一个取舍。
 *
 * 执法仪大部分时间息屏放置在胸前，电量耗尽会直接断电，最后未 flush 的部分
 * 有丢帧风险；停录线的意义就是在断电前留出足够余量把当前分段正常收尾。
 */
object BatteryGuard {

    private const val TAG = "BatteryGuard"

    /** 一次电量检查的结论 */
    enum class Level {
        /** 电量充足，或正在充电 */
        OK,

        /** 低于告警线：只提示 */
        WARN,

        /** 低于强制停录线：安全停录 */
        STOP
    }

    data class Status(
        val level: Level,
        /** 当前电量百分比；-1 表示没读到 */
        val percent: Int,
        val warnPercent: Int,
        val stopPercent: Int,
        val charging: Boolean
    )

    /** 告警阈值（百分比） */
    fun warnPercent(context: Context): Int =
        SettingsStore.batteryWarnPercent(context).coerceIn(1, 100)

    /**
     * 强制停录阈值（百分比）：告警线的 1/[AppConfig.BATTERY_STOP_DIVISOR]，
     * 且不低于 [AppConfig.BATTERY_STOP_MIN_PERCENT]。默认 15% → 5%。
     */
    fun stopPercent(context: Context): Int {
        val byDiv = warnPercent(context) / AppConfig.BATTERY_STOP_DIVISOR
        return maxOf(byDiv, AppConfig.BATTERY_STOP_MIN_PERCENT)
    }

    fun inspect(context: Context): Status {
        val reading = readBattery(context)
        val warn = warnPercent(context)
        val stop = stopPercent(context)
        // 充电中或读不到电量：一律 OK（充电自动解除低电量状态）
        val level = if (reading.charging || reading.percent < 0) {
            Level.OK
        } else {
            levelOf(reading.percent, warn, stop)
        }
        return Status(level, reading.percent, warn, stop, reading.charging)
    }

    /** 电量是整数百分比，用 `<=` 表示「低到该值就处理」，5% 停就是 5% 停 */
    private fun levelOf(percent: Int, warn: Int, stop: Int): Level = when {
        percent <= stop -> Level.STOP
        percent <= warn -> Level.WARN
        else -> Level.OK
    }

    private data class Reading(val percent: Int, val charging: Boolean)

    /**
     * 读当前电量的两种方式合一：`BATTERY_PROPERTY_CAPACITY` 在部分机型上
     * 返回 `Int.MIN_VALUE`，所以走 `ACTION_BATTERY_CHANGED` 的 sticky 广播
     * 取 `EXTRA_LEVEL / EXTRA_SCALE`，一次调用同时拿到百分比与充电状态。
     *
     * 传 `null` 的 receiver 只取当前 sticky 值、不注册回调，主线程调用开销可忽略。
     */
    @Suppress("DEPRECATION")
    private fun readBattery(context: Context): Reading = try {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        if (intent == null) {
            Reading(-1, false)
        } else {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            Reading(percent, charging)
        }
    } catch (e: Exception) {
        Log.w(TAG, "read battery failed", e)
        Reading(-1, false)
    }
}