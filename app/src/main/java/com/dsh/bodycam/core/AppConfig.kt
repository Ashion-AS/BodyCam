package com.dsh.bodycam.core

/** 集中放置可调参数：改分辨率、码率、水印位置都只需要动这里 */
object AppConfig {
    /** 水印四角的边距，占画面宽度的比例 */
    const val WATERMARK_MARGIN = 0.020f

    /** 右下角水印：登录平台后是平台下发的分组名/用户名，未登录时用这两个默认值 */
    const val DEFAULT_GROUP_NAME = "默认组"
    const val DEFAULT_USER_NAME = "user1"

    // ------------------------------------------------------------ 录像参数档位

    /**
     * 录像分辨率档位（长边 × 短边）。相机不一定支持全部档位，
     * 选到不支持的一档时由 [com.dsh.bodycam.camera.CameraSpec.resolveVideoSize]
     * 按面积就近回退。竖屏输出为 `短边 × 长边`，宽高由现有画幅逻辑交换。
     */
    fun videoSizeOptions(): List<Pair<Int, Int>> = listOf(
        3840 to 2160,   // 4K
        1920 to 1080,   // 1080P
        1280 to 720,    // 720P
        720 to 480      // D1
    )

    /** 分辨率档位的显示名；顺序与 [videoSizeOptions] 一一对应 */
    val VIDEO_SIZE_LABELS = listOf("4K", "1080P", "720P", "D1")

    /** 默认档位下标：1080P */
    const val VIDEO_SIZE_DEFAULT_INDEX = 1

    // ------------------------------------------------------------ 视频编码格式

    /**
     * 视频编码格式档位。
     *
     * H.265（HEVC）在同等画质下比 H.264 省约一半码率，但**不是所有设备都有
     * 编码器**——旧机、部分低端机只有解码器没有编码器。选择项由
     * [com.dsh.bodycam.record.CodecSupport.supportedVideoCodecs] 按设备能力
     * 过滤，不支持的格式不会出现在设置页。
     */
    const val VIDEO_CODEC_AVC = 0
    const val VIDEO_CODEC_HEVC = 1

    /** 视频编码档位的显示名；顺序与 [VIDEO_CODEC_*] 对应 */
    val VIDEO_CODEC_LABELS = listOf("H.264", "H.265")

    /** 默认视频编码：H.264，兼容性最好 */
    const val VIDEO_CODEC_DEFAULT = VIDEO_CODEC_AVC

    // ------------------------------------------------------------ 音频参数

    /**
     * 音频采样率档位（Hz）。
     *
     * 16k 是语音场景的常用档：文件小、编码开销低，人声清晰度足够；
     * 48k 是系统默认档，音乐/环境声更完整。16k 由 48k 直接降采样得到，
     * 不改变采集链路本身。
     */
    val AUDIO_SAMPLE_RATE_OPTIONS = intArrayOf(16_000, 48_000)

    /** 默认采样率：16k（语音优先，文件更小） */
    const val AUDIO_SAMPLE_RATE_DEFAULT = 16_000

    /** 声道档位：单声道 / 双声道 */
    const val AUDIO_CHANNELS_MONO = 1
    const val AUDIO_CHANNELS_STEREO = 2

    /** 声道档位列表；顺序与 [AUDIO_CHANNELS_LABELS] 对应 */
    val AUDIO_CHANNELS_OPTIONS = intArrayOf(AUDIO_CHANNELS_MONO, AUDIO_CHANNELS_STEREO)

    /** 声道档位的显示名；顺序与 [AUDIO_CHANNELS_*] 对应 */
    val AUDIO_CHANNELS_LABELS = listOf("单声道", "双声道")

    /** 默认声道：单声道 */
    const val AUDIO_CHANNELS_DEFAULT = AUDIO_CHANNELS_MONO

    /** 录像帧率档位 */
    val VIDEO_FPS_OPTIONS = intArrayOf(15, 20, 25, 30, 60)
    const val DEFAULT_VIDEO_FPS = 30

    /**
     * 画质档位：同一档位在不同分辨率下取不同码率。
     *
     * 码率按「每像素每帧多少比特」估算而不是写死常数：4K 用 1080P 的码率
     * 会糊成一片，720P 用 4K 的码率只是白白占空间。系数取 0.10 / 0.06 / 0.035
     * 三档（H.264 常见取值），再按实测习惯向下取整到 0.5Mbps。
     */
    const val VIDEO_QUALITY_HIGH = 0
    const val VIDEO_QUALITY_MEDIUM = 1
    const val VIDEO_QUALITY_LOW = 2

    /** 各画质档对应的「每像素每帧比特数」 */
    private val QUALITY_BPP = floatArrayOf(0.10f, 0.06f, 0.035f)

    /**
     * HEVC 相对 H.264 的码率系数。
     *
     * 同码率下 HEVC 的画质明显更好，反过来要拿到与 H.264 相当的画质，
     * 大约只需 0.6~0.7 的码率。这里取 0.65，让用户切到 H.265 时
     * 体积能实打实地降下来，而不是跟 H.264 一样大。
     */
    private const val HEVC_BITRATE_FACTOR = 0.65f

    /** 码率下限 / 上限，避免极端组合算出离谱的值 */
    private const val BITRATE_MIN = 1_000_000
    private const val BITRATE_MAX = 60_000_000

    /**
     * 按分辨率、帧率、画质档与编码格式算目标码率。
     *
     * 结果按 0.5Mbps 取整，同档位在不同分辨率/帧率下都落在合理区间：
     * 1080P30 高档 ≈ 6.2Mbps，4K30 高档 ≈ 24.9Mbps，720P30 低档 ≈ 1.8Mbps。
     * HEVC 在同样参数下乘 [HEVC_BITRATE_FACTOR]，体积约为 H.264 的 65%。
     */
    fun videoBitrate(width: Int, height: Int, fps: Int, quality: Int, codecType: Int): Int {
        val bpp = QUALITY_BPP.getOrElse(quality) { QUALITY_BPP[VIDEO_QUALITY_HIGH] }
        val codecFactor = if (codecType == VIDEO_CODEC_HEVC) HEVC_BITRATE_FACTOR else 1f
        // 先算整数像素×帧率，最后再乘浮点系数：Long×Float 的结果是 Float，
        // 直接参与 coerceIn(Long, Long) 会类型不匹配
        val pixels = width.toLong() * height * fps
        val raw = (pixels * bpp * codecFactor).toLong()
        val clamped = raw.coerceIn(BITRATE_MIN.toLong(), BITRATE_MAX.toLong())
        // 向下取整到 0.5Mbps，避免出现 6234567 这种没有意义的精度
        val halfMb = 500_000L
        return ((clamped / halfMb) * halfMb).toInt().coerceAtLeast(BITRATE_MIN)
    }

    /** 画质档位的显示名；顺序与 [VIDEO_QUALITY_*] 对应 */
    val VIDEO_QUALITY_LABELS = listOf("高", "中", "低")

    /** 默认画质：高 */
    const val VIDEO_QUALITY_DEFAULT = VIDEO_QUALITY_HIGH

    /** 分段时长可选档位（分钟） */
    val SEGMENT_MINUTE_OPTIONS = intArrayOf(5, 10, 15, 20, 30)

    /** 默认分段时长（分钟） */
    const val DEFAULT_SEGMENT_MINUTES = 30

    /**
     * 录音分段长度（秒）的默认值。到点后编码器不中断，只换一个新的 muxer 继续写，
     * 所以分段处不打断录音，只在文件边界切一刀（最多丢约一帧 AAC）。
     * 运行期以设置页选择为准（持久化在 [SettingsStore]），这里是默认值。
     */
    const val DEFAULT_AUDIO_SEGMENT_SECONDS = 30 * 60

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

    // ------------------------------------------------------------ 循环录像 / 磁盘

    /**
     * 空间告警阈值的可选档位（MB），默认 [DEFAULT_DISK_WARN_MB]。
     *
     * 剩余空间低于该值时：循环录像开 → 删最早的分段腾空间；关 → 只提示。
     */
    val DISK_WARN_OPTIONS_MB = intArrayOf(300, 500, 1024, 2048)
    const val DEFAULT_DISK_WARN_MB = 500

    /**
     * 强制停止阈值 = 告警阈值 / [DISK_STOP_DIVISOR]，且不低于 [DISK_STOP_MIN_MB]。
     *
     * 默认 500MB 告警 → 100MB 强制停，与需求里的「500MB 告警、100MB 强制停」一致；
     * 调大告警档位时停止线同步抬高，仍留出足够余量把当前分段正常收尾。
     */
    const val DISK_STOP_DIVISOR = 5
    const val DISK_STOP_MIN_MB = 100

    /** 磁盘巡检间隔（毫秒）：录制中按这个节奏查剩余空间，2 秒一次开销可忽略 */
    const val DISK_CHECK_MS = 2000L

    // ------------------------------------------------------------ 电量

    /**
     * 电量告警阈值的可选档位（百分比），默认 [DEFAULT_BATTERY_WARN_PERCENT]。
     *
     * 低于该值：提示用户电量不足；充电中不提示（充电即解除低电量状态）。
     */
    val BATTERY_WARN_OPTIONS_PERCENT = intArrayOf(10, 15, 20, 30)
    const val DEFAULT_BATTERY_WARN_PERCENT = 15

    /**
     * 强制停录阈值 = 告警阈值 / [BATTERY_STOP_DIVISOR]，且不低于
     * [BATTERY_STOP_MIN_PERCENT]。默认 15% 告警 → 5% 强制停。
     *
     * 与磁盘同理：调大告警档位时停止线同步抬高，留出余量把当前分段正常收尾。
     * 电量百分比是整数，所以最低取 1%，不会出现 0。
     */
    const val BATTERY_STOP_DIVISOR = 3
    const val BATTERY_STOP_MIN_PERCENT = 5

    /**
     * 电量巡检间隔（毫秒）。电量变化比磁盘慢得多，且读的是 sticky 广播
     * （不注册回调），30 秒一次足够，接近没开销。
     */
    const val BATTERY_CHECK_MS = 30_000L

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
