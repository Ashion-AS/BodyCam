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
    const val WATERMARK_LEFT = 0.070f
    const val WATERMARK_BOTTOM = 0.045f

    /** 水印上的设备编号，可按需改成警号 */
    const val DEVICE_ID = "DEV-0001"

    /**
     * 分段长度（秒）。到点后编码器不中断，只换一个新的 muxer 继续写，
     * 所以分段处不丢画面，音频最多丢约 1 帧。
     */
    const val SEGMENT_SECONDS = 30 * 60

    /**
     * 录音分段长度（秒）。到点后编码器不中断，只换一个新的 muxer 继续写，
     * 所以分段处不打断录音，只在文件边界切一刀（最多丢约一帧 AAC）。
     */
    const val AUDIO_SEGMENT_SECONDS = 30 * 60

    /** true = 常亮；执法记录仪一般设为 false，让屏幕可以自动熄灭继续录 */
    const val KEEP_SCREEN_ON = false

    /**
     * 拍照补光模式：关闭 / 自动（光线不足才闪）/ 强制（每次都闪）。
     * 运行期以设置页选择为准（持久化在 [SettingsStore]），这里是默认值。
     */
    const val PHOTO_FLASH_OFF = 0
    const val PHOTO_FLASH_AUTO = 1
    const val PHOTO_FLASH_ALWAYS = 2

    // ------------------------------------------------------------ 拍照

    /** 单拍：按一次拍一张 */
    const val PHOTO_MODE_SINGLE = 0

    /** 连拍：按一次连续拍 N 张（间隔与张数见设置页） */
    const val PHOTO_MODE_BURST = 1

    /** JPEG 质量档位，对应三种压缩质量 */
    const val PHOTO_QUALITY_HIGH = 0
    const val PHOTO_QUALITY_MEDIUM = 1
    const val PHOTO_QUALITY_LOW = 2

    /** 各档对应的 `JPEG_QUALITY` 值 */
    fun jpegQuality(quality: Int): Int = when (quality) {
        PHOTO_QUALITY_MEDIUM -> 80
        PHOTO_QUALITY_LOW -> 65
        else -> 95
    }

    /**
     * 可选拍照尺寸（长边 × 短边）。设备不支持时由 [com.dsh.bodycam.camera.CameraController]
     * 回退到面积最接近的可用档。
     *
     * 只列 16:9 的四档，与视频同比例——照片与预览构图一致，不会出现
     * 「预览看到的比拍下来的窄」。
     */
    fun photoSizeOptions(): List<Pair<Int, Int>> = listOf(
        1280 to 720,
        1920 to 1080,
        2560 to 1440,
        3840 to 2160
    )

    /** 尺寸档位下标；持久化的是下标，不是像素，设备换机后仍能落到合理档位 */
    const val PHOTO_SIZE_DEFAULT_INDEX = 1

    /** 连拍间隔（毫秒）与张数的可选档位 */
    val BURST_INTERVAL_OPTIONS = intArrayOf(1, 2, 3, 5)
    val BURST_COUNT_OPTIONS = intArrayOf(3, 5, 10, 20)

    const val DEFAULT_BURST_INTERVAL_SECONDS = 1
    const val DEFAULT_BURST_COUNT = 3

    /**
     * 固定节点的灯光通路（sysfs）。
     *
     * 整机把补光灯 / 红外灯接在 GPIO 或 LED 驱动上时，填上对应节点路径，
     * 应用会直接写 `1`（开）/ `0`（关）。**留空表示该灯未接入**，代码会自动
     * 跳过这条通路、回退到相机闪光灯或隐藏入口。
     *
     * 典型路径：
     *   - 白光补光：`/sys/class/leds/flashlight/brightness`
     *   - 红外灯：  `/sys/class/leds/ir_led/brightness`
     *
     * 注意：写 sysfs 需要 root 或该节点对应用开放了写权限（SELinux 放行），
     * 否则写入会失败——失败只记日志，不影响其它功能。
     */
    const val LIGHT_NODE_TORCH = ""
    const val LIGHT_NODE_IR = ""

    /**
     * 调试校准行（左上角 S/D/R 与流/输出尺寸，可点校准方向）的**默认**显示状态。
     * 运行期以设置页开关为准（持久化在 [SettingsStore]），这里只作为未设置过时的默认值。
     */
    const val DEBUG_OVERLAY = false

    /**
     * 强制画面旋转角度：-1 = 按传感器自动计算，0/90/180/270 = 强制使用。
     * 如果真机上预览/成片整体差 90°，把自动算出来的 R 取反方向填进来即可
     * （例如 R=90 时填 270，画幅仍是竖屏，不会变横）。
     */
    const val ROTATION_OVERRIDE = -1

    /**
     * 外接触发：拍照 / 录像 / 录音的物理按键 keycode。
     * 默认 `KEYCODE_UNKNOWN` = 不绑定，不会抢占任何按键（音量键照常调音量）。
     * 接入外设时把它改成目标键的 keycode 即可，也可以在运行期调用
     * `SettingsStore.setTriggerKeycodeXxx()` 覆盖（持久化，优先于这里的默认值）。
     */
    const val TRIGGER_KEYCODE_SHUTTER = android.view.KeyEvent.KEYCODE_UNKNOWN
    const val TRIGGER_KEYCODE_RECORD = android.view.KeyEvent.KEYCODE_UNKNOWN
    const val TRIGGER_KEYCODE_AUDIO = android.view.KeyEvent.KEYCODE_UNKNOWN

    /**
     * 外接触发：拍照 / 录像 / 录音的广播 action。
     * 默认空串 = 不监听任何广播。接入 DVR / 车机时填上对方发的 action，
     * 同样可由 `SettingsStore.setTriggerActionXxx()` 在运行期覆盖。
     */
    const val TRIGGER_ACTION_SHUTTER = ""
    const val TRIGGER_ACTION_RECORD = ""
    const val TRIGGER_ACTION_AUDIO = ""

    const val NOTIFICATION_CHANNEL_ID = "bodycam_recording"
    const val NOTIFICATION_ID = 1001
    const val ACTION_STOP_RECORD = "com.dsh.bodycam.action.STOP_RECORD"

    /**
     * 拉起独立录音页并「进入即开录」的 action。
     * 预留给按键快捷开始录音：拿到按键回调后用它 startActivity 即可跳到录音页开录。
     */
    const val ACTION_START_AUDIO_RECORD = "com.dsh.bodycam.action.START_AUDIO_RECORD"

    /**
     * 录音峰值低于此值视为「基本没录到声音」（16bit 满量程 32767）。
     * 静音、被其它应用占用、权限问题都会落在几十以下，录完给个提示。
     */
    const val AUDIO_SILENT_THRESHOLD = 50
}
