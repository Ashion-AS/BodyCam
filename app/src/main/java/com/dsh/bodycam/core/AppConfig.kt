package com.dsh.bodycam.core

/** 集中放置可调参数：改分辨率、码率、水印位置都只需要动这里 */
object AppConfig {
    const val VIDEO_FPS = 30
    const val VIDEO_BITRATE = 12_000_000
    const val AUDIO_BITRATE = 128_000
    const val AUDIO_SAMPLE_RATE = 48_000

    /** 水印宽度占画面宽度的比例 */
    const val WATERMARK_WIDTH = 0.60f

    /** 水印左下角距离画面左边 / 下边的比例 */
    const val WATERMARK_LEFT = 0.045f
    const val WATERMARK_BOTTOM = 0.045f

    /** 水印上的设备编号，可按需改成警号 */
    const val DEVICE_ID = "DEV-0001"

    /**
     * 分段长度（秒）。到点后编码器不中断，只换一个新的 muxer 继续写，
     * 所以分段处不丢画面，音频最多丢约 1 帧。
     */
    const val SEGMENT_SECONDS = 30 * 60

    /** true = 常亮；执法记录仪一般设为 false，让屏幕可以自动熄灭继续录 */
    const val KEEP_SCREEN_ON = false

    /** 调试：左上角显示 S/D/R 与流/输出尺寸，定位设备方向问题用；正式使用可关掉 */
    const val DEBUG_OVERLAY = true

    /**
     * 强制画面旋转角度：-1 = 按传感器自动计算，0/90/180/270 = 强制使用。
     * 如果真机上预览/成片整体差 90°，把自动算出来的 R 取反方向填进来即可
     * （例如 R=90 时填 270，画幅仍是竖屏，不会变横）。
     */
    const val ROTATION_OVERRIDE = -1

    const val NOTIFICATION_CHANNEL_ID = "bodycam_recording"
    const val NOTIFICATION_ID = 1001
    const val ACTION_STOP_RECORD = "com.dsh.bodycam.action.STOP_RECORD"

    /**
     * 录音峰值低于此值视为「基本没录到声音」（16bit 满量程 32767）。
     * 静音、被其它应用占用、权限问题都会落在几十以下，录完给个提示。
     */
    const val AUDIO_SILENT_THRESHOLD = 50
}
