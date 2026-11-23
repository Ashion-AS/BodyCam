package com.dsh.bodycam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.dsh.bodycam.MainActivity
import com.dsh.bodycam.R
import com.dsh.bodycam.core.AppConfig

/**
 * 息屏继续录像的关键组件：
 *  - foregroundServiceType = camera|microphone：Android 11+ 只有带这两类前台服务，
 *    应用在后台（息屏）时才被允许继续使用摄像头和麦克风；
 *  - PARTIAL_WAKE_LOCK：保证 CPU 不被挂起，编码线程能持续跑（屏幕仍然可以熄灭）。
 */
class KeepAliveService : Service() {

    companion object {
        /** 附带空间告警文案的 intent extra；服务收到后更新通知正文 */
        const val EXTRA_LOW_DISK = "low_disk_text"

        /** 附带电量告警文案的 intent extra；服务收到后更新通知正文 */
        const val EXTRA_LOW_BATTERY = "low_battery_text"

        /** 延录中：正文换成「延录中，还剩 N 秒」 */
        const val EXTRA_POST_RECORD = "post_record_text"

        /** 退出延录：正文恢复默认 */
        const val EXTRA_POST_RECORD_CLEAR = "post_record_clear"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AppConfig.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(AppConfig.NOTIFICATION_ID, notification)
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BodyCam:recording")
            .apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 空间/电量告警：把通知正文换成告警文案（不带额外 action，保持常驻）
        intent?.getStringExtra(EXTRA_LOW_DISK)?.let { updateNotification(it) }
        intent?.getStringExtra(EXTRA_LOW_BATTERY)?.let { updateNotification(it) }
        // 延录：正文换成倒计时，动作按钮改成「立即停止」更贴合当前语义
        intent?.getStringExtra(EXTRA_POST_RECORD)?.let {
            updateNotification(it, stopNow = true)
        }
        if (intent?.getBooleanExtra(EXTRA_POST_RECORD_CLEAR, false) == true) {
            updateNotification(getString(R.string.notification_text))
        }
        return START_STICKY
    }

    /** 原地刷新常驻通知：换正文，可选把动作按钮换成「立即停止」 */
    private fun updateNotification(text: String, stopNow: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(AppConfig.NOTIFICATION_ID, buildNotification(text, stopNow))
    }

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            AppConfig.NOTIFICATION_CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        text: String = getString(R.string.notification_text),
        stopNow: Boolean = false
    ): Notification {
        val stopIntent = Intent(this, MainActivity::class.java).apply {
            action = AppConfig.ACTION_STOP_RECORD
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val stopPending = PendingIntent.getActivity(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPending = PendingIntent.getActivity(
            this, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(if (stopNow) R.string.action_stop_now else R.string.action_stop),
                    stopPending
                ).build()
            )
            .build()
    }
}
