package com.dsh.bodycam

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.core.SettingsStore
import com.dsh.bodycam.record.supportedAudioChannels
import com.dsh.bodycam.record.supportedAudioSampleRates
import com.dsh.bodycam.record.supportedVideoCodecs
import com.dsh.bodycam.ui.ToastBar
import com.dsh.bodycam.util.StoragePermission
import com.dsh.bodycam.util.VideoCrypto
import com.dsh.bodycam.watermark.WatermarkCorner
import com.dsh.bodycam.watermark.WatermarkField

/**
 * 设置页：预录开关 + 预录时长（5/10/20/30s）、循环录像与空间告警阈值。
 * 改动即时写入 SharedPreferences，并回调通知主页面（如刷新黄点）。
 */
class SettingsActivity : Activity() {

    private lateinit var swPreRecord: Switch
    private lateinit var swLoopRecord: Switch
    private lateinit var swDebugOverlay: Switch
    private lateinit var swTopBarRule: Switch
    private lateinit var swDockRule: Switch
    private lateinit var rgZoomSliderLength: RadioGroup
    private lateinit var rgStorageMode: RadioGroup
    private lateinit var rgDiskWarn: RadioGroup
    private lateinit var rgBatteryWarn: RadioGroup
    private lateinit var rgVideoSize: RadioGroup
    private lateinit var rgVideoFps: RadioGroup
    private lateinit var rgVideoQuality: RadioGroup
    private lateinit var rgVideoCodec: RadioGroup
    private lateinit var rgAudioSampleRate: RadioGroup
    private lateinit var rgAudioChannels: RadioGroup
    private lateinit var tvVideoCodecHint: TextView
    private lateinit var tvAudioChannelsHint: TextView
    private lateinit var llSegmentMinutes: LinearLayout
    private lateinit var llAudioSegmentMinutes: LinearLayout
    private lateinit var llWmTopLeft: LinearLayout
    private lateinit var llWmTopRight: LinearLayout
    private lateinit var llWmBottomLeft: LinearLayout
    private lateinit var llWmBottomRight: LinearLayout
    private lateinit var etWmGroupName: EditText
    private lateinit var etWmUserName: EditText
    private lateinit var swVideoAudio: Switch
    private lateinit var swVideoEncrypt: Switch
    private lateinit var etVideoEncryptKey: EditText
    private lateinit var rgPhotoFlash: RadioGroup
    private lateinit var rgPhotoMode: RadioGroup
    private lateinit var rgPhotoQuality: RadioGroup
    private lateinit var llPhotoSize: LinearLayout
    private lateinit var rgBurstInterval: RadioGroup
    private lateinit var rgBurstCount: RadioGroup
    private lateinit var photoModeSummary: TextView
    private lateinit var burstSection: View
    private lateinit var durationSection: View
    private lateinit var rgDuration: RadioGroup
    private lateinit var rgPostRecord: RadioGroup
    private lateinit var storageRow: View
    private lateinit var storageStatus: TextView
    private lateinit var storageAction: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        swPreRecord = findViewById(R.id.swPreRecord)
        swLoopRecord = findViewById(R.id.swLoopRecord)
        swDebugOverlay = findViewById(R.id.swDebugOverlay)
        swTopBarRule = findViewById(R.id.swTopBarRule)
        swDockRule = findViewById(R.id.swDockRule)
        rgZoomSliderLength = findViewById(R.id.rgZoomSliderLength)
        rgStorageMode = findViewById(R.id.rgStorageMode)
        rgDiskWarn = findViewById(R.id.rgDiskWarn)
        rgBatteryWarn = findViewById(R.id.rgBatteryWarn)
        rgVideoSize = findViewById(R.id.rgVideoSize)
        rgVideoFps = findViewById(R.id.rgVideoFps)
        rgVideoQuality = findViewById(R.id.rgVideoQuality)
        rgVideoCodec = findViewById(R.id.rgVideoCodec)
        rgAudioSampleRate = findViewById(R.id.rgAudioSampleRate)
        rgAudioChannels = findViewById(R.id.rgAudioChannels)
        tvVideoCodecHint = findViewById(R.id.tvVideoCodecHint)
        tvAudioChannelsHint = findViewById(R.id.tvAudioChannelsHint)
        llSegmentMinutes = findViewById(R.id.llSegmentMinutes)
        llAudioSegmentMinutes = findViewById(R.id.llAudioSegmentMinutes)
        llWmTopLeft = findViewById(R.id.llWmTopLeft)
        llWmTopRight = findViewById(R.id.llWmTopRight)
        llWmBottomLeft = findViewById(R.id.llWmBottomLeft)
        llWmBottomRight = findViewById(R.id.llWmBottomRight)
        etWmGroupName = findViewById(R.id.etWmGroupName)
        etWmUserName = findViewById(R.id.etWmUserName)
        swVideoAudio = findViewById(R.id.swVideoAudio)
        swVideoEncrypt = findViewById(R.id.swVideoEncrypt)
        etVideoEncryptKey = findViewById(R.id.etVideoEncryptKey)
        rgPhotoFlash = findViewById(R.id.rgPhotoFlash)
        rgPhotoMode = findViewById(R.id.rgPhotoMode)
        rgPhotoQuality = findViewById(R.id.rgPhotoQuality)
        llPhotoSize = findViewById(R.id.llPhotoSize)
        rgBurstInterval = findViewById(R.id.rgBurstInterval)
        rgBurstCount = findViewById(R.id.rgBurstCount)
        photoModeSummary = findViewById(R.id.photoModeSummary)
        burstSection = findViewById(R.id.burstSection)
        durationSection = findViewById(R.id.durationSection)
        rgDuration = findViewById(R.id.rgDuration)
        rgPostRecord = findViewById(R.id.rgPostRecord)
        storageRow = findViewById(R.id.storageRow)
        storageStatus = findViewById(R.id.storageStatus)
        storageAction = findViewById(R.id.storageAction)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        storageRow.setOnClickListener {
            if (!StoragePermission.isGranted(this)) StoragePermission.openSettings(this)
        }

        swPreRecord.isChecked = SettingsStore.isPreRecordEnabled(this)
        swPreRecord.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setPreRecordEnabled(this, checked)
            updateSections()
        }

        swDebugOverlay.isChecked = SettingsStore.isDebugOverlayVisible(this)
        swDebugOverlay.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setDebugOverlayVisible(this, checked)
        }

        swLoopRecord.isChecked = SettingsStore.isLoopRecordEnabled(this)
        swLoopRecord.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setLoopRecordEnabled(this, checked)
        }

        swTopBarRule.isChecked = SettingsStore.isTopBarRuleEnabled(this)
        swTopBarRule.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setTopBarRuleEnabled(this, checked)
        }

        swDockRule.isChecked = SettingsStore.isDockRuleEnabled(this)
        swDockRule.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setDockRuleEnabled(this, checked)
        }

        buildDurationOptions()
        buildPostRecordOptions()
        buildPhotoFlashOptions()
        buildPhotoModeOptions()
        buildPhotoQualityOptions()
        buildPhotoSizeOptions()
        buildBurstOptions()
        buildDiskWarnOptions()
        buildBatteryWarnOptions()
        buildVideoOptions()
        buildAudioOptions()
        buildWatermarkOptions()
        buildZoomSliderLengthOptions()
        buildStorageModeOptions()
        updateSections()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回后刷新存储权限那一行
        updateStorageRow()
    }

    /** 刷新存储权限行：已授权显示「已开启」，否则给出「去开启」并可点跳转 */
    private fun updateStorageRow() {
        val granted = StoragePermission.isGranted(this)
        storageStatus.text = getString(
            if (granted) R.string.settings_storage_granted else R.string.settings_storage_missing
        )
        storageAction.text = getString(
            if (granted) R.string.settings_storage_done else R.string.settings_storage_open
        )
        storageRow.isClickable = !granted
    }

    /**
     * 设备能力相关的选项行：只有一个可选项就说明该能力本机不支持，
     * 提示文字换成 [unsupportedRes]（讲清原因），否则用 [normalRes]。
     *
     * 编码格式、声道两行都是这个形态。
     */
    private fun hintResFor(count: Int, normalRes: Int, unsupportedRes: Int): Int =
        if (count <= 1) unsupportedRes else normalRes

    /** 每行下拉框本体，按原选项容器的 id 索引：updateSections 需要单独置灰某一行 */
    private val dropdownRowOf = HashMap<Int, View>()

    /**
     * 分段选项：把「标题 + 一排按钮」就地改造成一行下拉框。
     *
     * 改造成本集中在 [bindDropdownRow] 一处，设置页 18 组选项的 XML 与各自的
     * 持久化逻辑都不用动——各 buildXxxOptions() 只是换了个渲染方式。
     */
    private fun <T> buildSegment(
        group: RadioGroup,
        options: List<Pair<T, String>>,
        current: T,
        onPick: (T) -> Unit
    ) {
        bindDropdownRow(
            anchor = group,
            labels = options.map { it.second },
            selectedIndex = options.indexOfFirst { it.first == current },
            onPick = { index -> onPick(options[index].first) }
        )
    }

    /**
     * 把 [anchor] 所在的那一行改造成下拉行：标题居左，当前值 + ▾ 居右。
     *
     * 标题就是布局里 anchor 前面那个 TextView，直接搬进新行复用（样式、文案不变）；
     * 原选项容器设为 GONE，只作定位锚点。这样 XML 一行不用改，行内的说明文字
     * 也仍然排在下拉行下面。
     *
     * @param labels        下拉项文案
     * @param selectedIndex 当前值在 [labels] 中的下标（-1 表示没有匹配，显示空值）
     * @param onPick        选中回调，参数为下标
     */
    private fun bindDropdownRow(
        anchor: View,
        labels: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        val parent = anchor.parent as? ViewGroup ?: return
        val anchorIndex = parent.indexOfChild(anchor)
        val labelView = if (anchorIndex > 0) parent.getChildAt(anchorIndex - 1) as? TextView else null
        // 标题原本的上下留白要跟着搬到新行（连拍那行的第二组带 marginTop）
        val topMargin =
            (labelView?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0

        var current = selectedIndex

        val valueView = TextView(this).apply {
            text = labels.getOrElse(current) { "" }
            setTextColor(getColor(R.color.ink_2))
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_md)
            )
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.space_xs) }
        }

        val arrowSize = resources.getDimensionPixelSize(R.dimen.space_md)
        val arrow = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_drop_down)
            layoutParams = LinearLayout.LayoutParams(arrowSize, arrowSize)
            // 纯装饰，读屏时应与整行合并朗读
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.dropdown_row_height)
            isClickable = true
            isFocusable = true
        }
        // 水波纹交给主题的 selectableItemBackground，不自己造 drawable
        val ripple = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId)
        }

        if (labelView != null) {
            parent.removeView(labelView)
            row.addView(
                labelView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        row.addView(valueView)
        row.addView(arrow)

        parent.addView(
            row,
            if (labelView != null) anchorIndex - 1 else anchorIndex,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { this.topMargin = topMargin }
        )
        anchor.visibility = View.GONE
        dropdownRowOf[anchor.id] = row

        // 上下留白对齐：行高收到 40dp 后，文字上方的剩余空隙约 13dp；
        // 卡片底边距也取 13dp，两边视觉间距才相等（原先容器上下都留 16dp，
        // 加上行内居中留白，上边会比下边多出十几 dp）。
        parent.setPadding(
            parent.paddingLeft,
            0,
            parent.paddingRight,
            resources.getDimensionPixelSize(R.dimen.dropdown_row_inset)
        )

        row.setOnClickListener {
            showDropdown(row, labels, current) { index ->
                current = index
                valueView.text = labels[index]
                onPick(index)
            }
        }
    }

    /**
     * 下拉弹层：右对齐到行尾，配色/圆角/间距全部走设计令牌。
     *
     * 不用系统 `PopupMenu` 的原因有两条：
     * 1. 它固定从锚点左边缘弹出——本页视觉重心在右侧（值 + ▾ 都在右），
     *    弹层跑到最左边，离当前值隔了大半屏；
     * 2. 它的列表样式（背景、选中态）由系统主题决定，和 paper / ink / accent
     *    这套令牌对不上，放进深色页面里像另一个 App 的控件。
     */
    private fun showDropdown(
        anchor: View,
        labels: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        val hPad = resources.getDimensionPixelSize(R.dimen.space_md)
        val vPad = resources.getDimensionPixelSize(R.dimen.space_sm)
        val panelPad = resources.getDimensionPixelSize(R.dimen.space_2xs)
        val minWidthPx = resources.getDimensionPixelSize(R.dimen.dropdown_min_width)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dropdown_panel)
            setPadding(0, panelPad, 0, panelPad)
        }

        val ripple = TypedValue()
        val hasRipple =
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)

        val popup = PopupWindow(
            panel,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = resources.getDimension(R.dimen.elevation_popup)
            isOutsideTouchable = true
        }

        labels.forEachIndexed { index, label ->
            val item = TextView(this).apply {
                text = label
                // 选中项用强调色，其余用正文色：一眼能看出当前档位
                setTextColor(getColor(if (index == selectedIndex) R.color.accent else R.color.ink))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_md))
                setPadding(hPad, vPad, hPad, vPad)
                minWidth = minWidthPx
                gravity = Gravity.START
                isClickable = true
                if (hasRipple) setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    popup.dismiss()
                    onPick(index)
                }
            }
            panel.addView(item)
        }

        // Gravity.END：弹层右边缘贴住行尾，正好落在「值 + ▾」下方
        popup.showAsDropDown(
            anchor,
            0,
            resources.getDimensionPixelSize(R.dimen.space_2xs),
            Gravity.END
        )
    }

    /** 5/10/20/30s 四个单选按钮，横排 */
    private fun buildDurationOptions() {
        val options = SettingsStore.PRE_RECORD_OPTIONS.map {
            it to getString(R.string.settings_pre_record_duration_fmt, it)
        }
        buildSegment(rgDuration, options, SettingsStore.preRecordSeconds(this)) { seconds ->
            SettingsStore.setPreRecordSeconds(this, seconds)
            toast(getString(R.string.settings_pre_record_duration_fmt, seconds))
        }
    }

    /** 0/5/10/15/30s 五个单选按钮，横排；0 单独显示为「关闭」 */
    private fun buildPostRecordOptions() {
        val options = SettingsStore.POST_RECORD_OPTIONS.map {
            it to if (it == 0) getString(R.string.settings_post_record_off)
            else getString(R.string.settings_post_record_fmt, it)
        }
        buildSegment(
            rgPostRecord, options, SettingsStore.postRecordSeconds(this)
        ) { seconds ->
            SettingsStore.setPostRecordSeconds(this, seconds)
        }
    }

    /** 缩放条竖向长度：短 / 中 / 长三档，长是历史值 */
    private fun buildZoomSliderLengthOptions() {
        val labels = mapOf(
            0 to getString(R.string.settings_zoom_slider_length_short),
            1 to getString(R.string.settings_zoom_slider_length_medium),
            2 to getString(R.string.settings_zoom_slider_length_long)
        )
        val options = SettingsStore.ZOOM_SLIDER_LENGTH_OPTIONS.map { it to labels.getValue(it) }
        buildSegment(
            rgZoomSliderLength, options, SettingsStore.zoomSliderLength(this)
        ) { level ->
            SettingsStore.setZoomSliderLength(this, level)
        }
    }

    /** 存储位置：默认存储 / 兼容存储 */
    private fun buildStorageModeOptions() {
        val options = listOf(
            false to getString(R.string.settings_storage_mode_default),
            true to getString(R.string.settings_storage_mode_compat)
        )
        buildSegment(
            rgStorageMode, options, SettingsStore.isCompatStorage(this)
        ) { compat ->
            SettingsStore.setCompatStorage(this, compat)
            // 权限要求随存储模式变化：兼容模式不需要「所有文件访问」，
            // 默认存储则需要。切完立刻刷新下面那行权限状态，避免误导
            updateStorageRow()
            toast(getString(R.string.settings_storage_mode_switched))
        }
    }

    /** 全局提示条，位置与互斥由 [ToastBar] 统一管理 */
    private fun toast(message: String) {
        ToastBar.show(this, message)
    }

    /** 失焦保存之外再兜一次：用户直接在输入框里按返回键时不会触发失焦 */
    override fun onPause() {
        super.onPause()
        if (::etWmGroupName.isInitialized) {
            SettingsStore.setWatermarkGroupName(this, etWmGroupName.text.toString().trim())
        }
        if (::etWmUserName.isInitialized) {
            SettingsStore.setWatermarkUserName(this, etWmUserName.text.toString().trim())
        }
    }

    override fun onDestroy() {
        ToastBar.release(this)
        super.onDestroy()
    }

    /** 拍照补光：关闭 / 自动 / 强制，三选一，横排 */
    private fun buildPhotoFlashOptions() {
        val options = listOf(
            AppConfig.PHOTO_FLASH_OFF to getString(R.string.settings_photo_flash_off),
            AppConfig.PHOTO_FLASH_AUTO to getString(R.string.settings_photo_flash_auto),
            AppConfig.PHOTO_FLASH_ALWAYS to getString(R.string.settings_photo_flash_always)
        )
        buildSegment(rgPhotoFlash, options, SettingsStore.photoFlashMode(this)) { mode ->
            SettingsStore.setPhotoFlashMode(this, mode)
        }
    }

    /** 拍照模式：单拍 / 连拍；选连拍时展开间隔与张数 */
    private fun buildPhotoModeOptions() {
        val options = listOf(
            AppConfig.PHOTO_MODE_SINGLE to getString(R.string.settings_photo_mode_single),
            AppConfig.PHOTO_MODE_BURST to getString(R.string.settings_photo_mode_burst)
        )
        buildSegment(rgPhotoMode, options, SettingsStore.photoMode(this)) { mode ->
            SettingsStore.setPhotoMode(this, mode)
            updateSections()
        }
    }

    /** 拍照质量：高 / 中 / 低 */
    private fun buildPhotoQualityOptions() {
        val options = listOf(
            AppConfig.PHOTO_QUALITY_HIGH to getString(R.string.settings_photo_quality_high),
            AppConfig.PHOTO_QUALITY_MEDIUM to getString(R.string.settings_photo_quality_medium),
            AppConfig.PHOTO_QUALITY_LOW to getString(R.string.settings_photo_quality_low)
        )
        buildSegment(rgPhotoQuality, options, SettingsStore.photoQuality(this)) { quality ->
            SettingsStore.setPhotoQuality(this, quality)
        }
    }

    /**
     * 拍照尺寸：1280×720 / 1920×1080 / 2560×1440 / 3840×2160（标 M 数）。
     *
     * 四档文案较长，原来排成两列两行的网格；现在统一收进一行下拉框，
     * 当前尺寸显示在标题右边，点开再选。
     */
    private fun buildPhotoSizeOptions() {
        val sizes = AppConfig.photoSizeOptions()
        val current = SettingsStore.photoSizeIndex(this)
        // 1280×720 是 0.92M，四舍五入到 1M，不能整除截断成 0M
        val labels = sizes.map { (w, h) ->
            getString(
                R.string.settings_photo_size_px_fmt,
                w, h, Math.round(w.toLong() * h / 1_000_000.0).toInt()
            )
        }
        bindDropdownRow(llPhotoSize, labels, current) { index ->
            SettingsStore.setPhotoSizeIndex(this, index)
        }
    }

    /** 连拍：间隔（秒）与张数 */
    private fun buildBurstOptions() {
        val intervals = AppConfig.BURST_INTERVAL_OPTIONS.map {
            it to getString(R.string.settings_burst_interval_fmt, it)
        }
        buildSegment(
            rgBurstInterval, intervals, SettingsStore.burstIntervalSeconds(this)
        ) { seconds ->
            SettingsStore.setBurstIntervalSeconds(this, seconds)
        }

        val counts = AppConfig.BURST_COUNT_OPTIONS.map {
            it to getString(R.string.settings_burst_count_fmt, it)
        }
        buildSegment(rgBurstCount, counts, SettingsStore.burstCount(this)) { count ->
            SettingsStore.setBurstCount(this, count)
        }
    }

    /** 空间告警阈值：档位来自 [AppConfig.DISK_WARN_OPTIONS_MB] */
    private fun buildDiskWarnOptions() {
        val options = AppConfig.DISK_WARN_OPTIONS_MB.map {
            it to getString(R.string.settings_disk_warn_fmt, it)
        }
        buildSegment(rgDiskWarn, options, SettingsStore.diskWarnMb(this)) { mb ->
            SettingsStore.setDiskWarnMb(this, mb)
        }
    }

    /** 电量告警阈值：档位来自 [AppConfig.BATTERY_WARN_OPTIONS_PERCENT] */
    private fun buildBatteryWarnOptions() {
        val options = AppConfig.BATTERY_WARN_OPTIONS_PERCENT.map {
            it to getString(R.string.settings_battery_warn_fmt, it)
        }
        buildSegment(
            rgBatteryWarn, options, SettingsStore.batteryWarnPercent(this)
        ) { percent ->
            SettingsStore.setBatteryWarnPercent(this, percent)
        }
    }

    /**
     * 录像参数四项：分辨率 / 帧率 / 画质 / 分段时长，外加声音开关。
     *
     * 分辨率存的是档位下标而不是像素：换机器后由相机层按面积就近回退
     * （见 `CameraSpec.resolveVideoSize`）。
     */
    private fun buildVideoOptions() {
        // 分辨率档位的标签是 4K / 1080P / 720P / D1，值用下标
        val sizes = AppConfig.videoSizeOptions().mapIndexed { index, _ ->
            index to AppConfig.VIDEO_SIZE_LABELS[index]
        }
        buildSegment(rgVideoSize, sizes, SettingsStore.videoSizeIndex(this)) { index ->
            SettingsStore.setVideoSizeIndex(this, index)
        }

        val fpsOptions = AppConfig.VIDEO_FPS_OPTIONS.map {
            it to getString(R.string.settings_video_fps_fmt, it)
        }
        buildSegment(rgVideoFps, fpsOptions, SettingsStore.videoFps(this)) { fps ->
            SettingsStore.setVideoFps(this, fps)
        }

        val qualities = AppConfig.VIDEO_QUALITY_LABELS.mapIndexed { index, label ->
            index to label
        }
        buildSegment(
            rgVideoQuality, qualities, SettingsStore.videoQuality(this)
        ) { quality ->
            SettingsStore.setVideoQuality(this, quality)
        }

        // 编码格式按设备能力过滤：没有 H.265 编码器的机器上不出现该项，
        // 避免选了根本编不了的档位（开录即失败）
        val codecs = supportedVideoCodecs().map { type ->
            type to AppConfig.VIDEO_CODEC_LABELS[type]
        }
        buildSegment(rgVideoCodec, codecs, SettingsStore.videoCodec(this)) { type ->
            SettingsStore.setVideoCodec(this, type)
        }
        tvVideoCodecHint.setText(
            hintResFor(
                codecs.size,
                R.string.settings_video_codec_summary,
                R.string.settings_video_codec_unsupported
            )
        )

        val rates = supportedAudioSampleRates().map { rate ->
            rate to getString(R.string.settings_audio_sample_rate_fmt, rate / 1000)
        }
        buildSegment(rgAudioSampleRate, rates, SettingsStore.audioSampleRate(this)) { rate ->
            SettingsStore.setAudioSampleRate(this, rate)
        }

        // 声道同理：没有立体声麦克风的机器只显示单声道
        val channels = supportedAudioChannels().map { ch ->
            ch to AppConfig.AUDIO_CHANNELS_LABELS[ch - 1]
        }
        buildSegment(rgAudioChannels, channels, SettingsStore.audioChannels(this)) { ch ->
            SettingsStore.setAudioChannels(this, ch)
        }
        tvAudioChannelsHint.setText(
            hintResFor(
                channels.size,
                R.string.settings_audio_channels_summary,
                R.string.settings_audio_channels_unsupported
            )
        )

        val segments = AppConfig.SEGMENT_MINUTE_OPTIONS.map {
            getString(R.string.settings_segment_fmt, it)
        }
        // 5 档带「分钟」后缀，原先 4 格一行排两行；现在统一收进一行下拉框。
        // segmentMinutes 读的时候已把档位外的旧值归一成默认档，这里 indexOf 不会返回 -1
        bindDropdownRow(
            llSegmentMinutes, segments,
            AppConfig.SEGMENT_MINUTE_OPTIONS.indexOf(SettingsStore.segmentMinutes(this))
        ) { index ->
            SettingsStore.setSegmentMinutes(this, AppConfig.SEGMENT_MINUTE_OPTIONS[index])
        }

        swVideoAudio.isChecked = SettingsStore.isVideoAudioEnabled(this)
        swVideoAudio.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setVideoAudioEnabled(this, checked)
        }

        buildVideoEncryptOptions()
    }

    /**
     * 录像加密：开关 + 32 位密钥。
     *
     * 开关不能单独生效——没有合法密钥时不加密，否则录出来的文件谁也打不开。
     * 所以打开开关时先校验密钥，缺失就提示并回弹，逼用户先把密钥填好。
     */
    private fun buildVideoEncryptOptions() {
        etVideoEncryptKey.setText(SettingsStore.videoEncryptPassphrase(this))
        swVideoEncrypt.isChecked = SettingsStore.isVideoEncryptEnabled(this)

        swVideoEncrypt.setOnCheckedChangeListener { _, checked ->
            if (checked && !VideoCrypto.isValidPassphrase(etVideoEncryptKey.text.toString())) {
                swVideoEncrypt.isChecked = false
                toast(getString(R.string.settings_video_encrypt_need_key))
                return@setOnCheckedChangeListener
            }
            SettingsStore.setVideoEncryptEnabled(this, checked)
        }

        findViewById<View>(R.id.btnVideoEncryptKeySave).setOnClickListener {
            val key = etVideoEncryptKey.text.toString()
            if (!VideoCrypto.isValidPassphrase(key)) {
                toast(getString(R.string.settings_video_encrypt_key_invalid))
                return@setOnClickListener
            }
            SettingsStore.setVideoEncryptPassphrase(this, key)
            // 输入框收起键盘，顺便给出「已保存」的确认
            etVideoEncryptKey.clearFocus()
            toast(getString(R.string.settings_video_encrypt_key_saved))
        }
    }

    /**
     * 录音分段时长，档位与录像共用 [AppConfig.SEGMENT_MINUTE_OPTIONS]，
     * 但持久化在独立的 key 上（[SettingsStore.audioSegmentMinutes]），两处互不影响。
     */
    private fun buildAudioOptions() {
        val segments = AppConfig.SEGMENT_MINUTE_OPTIONS.map {
            getString(R.string.settings_segment_fmt, it)
        }
        bindDropdownRow(
            llAudioSegmentMinutes, segments,
            AppConfig.SEGMENT_MINUTE_OPTIONS.indexOf(SettingsStore.audioSegmentMinutes(this))
        ) { index ->
            SettingsStore.setAudioSegmentMinutes(this, AppConfig.SEGMENT_MINUTE_OPTIONS[index])
        }
    }

    /**
     * 水印四角配置 + 右下角的分组名/用户名。
     *
     * 四角可选内容一样（[WatermarkField] 全列表 + 「不显示」），用 2 列网格：
     * 5 个选项排 3 行（3+2），与设置页其他多选项区的观感一致。
     * 不限制四角选同一类内容——重复显示是合法用法（例如四个角都想放时间）。
     */
    private fun buildWatermarkOptions() {
        val labels = WATERMARK_FIELD_IDS.map { getString(it.second) }

        val corners = listOf(
            WatermarkCorner.TOP_LEFT to llWmTopLeft,
            WatermarkCorner.TOP_RIGHT to llWmTopRight,
            WatermarkCorner.BOTTOM_LEFT to llWmBottomLeft,
            WatermarkCorner.BOTTOM_RIGHT to llWmBottomRight
        )
        for ((corner, container) in corners) {
            val current = SettingsStore.watermarkField(this, corner)
            bindDropdownRow(
                container, labels,
                WATERMARK_FIELD_IDS.indexOfFirst { it.first == current }
            ) { index ->
                SettingsStore.setWatermarkField(this, corner, WATERMARK_FIELD_IDS[index].first)
            }
        }

        etWmGroupName.setText(SettingsStore.watermarkGroupName(this))
        etWmUserName.setText(SettingsStore.watermarkUserName(this))
        // 焦点变化时才落盘：逐字保存会在输入过程中反复触发录制侧的重绘与重载
        etWmGroupName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                SettingsStore.setWatermarkGroupName(this, etWmGroupName.text.toString().trim())
            }
        }
        etWmUserName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                SettingsStore.setWatermarkUserName(this, etWmUserName.text.toString().trim())
            }
        }
    }

    /**
     * 关闭预录时，时长区降到 40% 不透明度并整体不可点；
     * 单拍模式下，连拍区同理置灰（选项值保留，切回连拍即恢复）。
     */
    private fun updateSections() {
        val preOn = swPreRecord.isChecked
        durationSection.alpha = if (preOn) 1f else 0.4f
        durationSection.isEnabled = preOn
        // 选项已收进下拉行：置灰要落在那一行上（RadioGroup 现在只是隐藏的定位锚点）
        dropdownRowOf[R.id.rgDuration]?.isEnabled = preOn

        val burst = SettingsStore.photoMode(this) == AppConfig.PHOTO_MODE_BURST
        photoModeSummary.text = getString(
            if (burst) R.string.settings_photo_mode_summary_burst
            else R.string.settings_photo_mode_summary_single
        )
        // 连拍区在单拍模式下整个隐藏：它没有可调的语义，留在页面上只会误导
        burstSection.visibility = if (burst) View.VISIBLE else View.GONE
    }

    private companion object {
        /** 四角可选的内容，顺序即网格顺序 */
        val WATERMARK_FIELD_IDS: List<Pair<WatermarkField, Int>> = listOf(
            WatermarkField.GPS to R.string.settings_watermark_field_gps,
            WatermarkField.TIME to R.string.settings_watermark_field_time,
            WatermarkField.DEVICE_STATUS to R.string.settings_watermark_field_device_status,
            WatermarkField.GROUP_USER to R.string.settings_watermark_field_group_user,
            WatermarkField.NONE to R.string.settings_watermark_field_none
        )
    }
}
