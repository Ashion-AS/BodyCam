package com.dsh.bodycam

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.core.SettingsStore
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
    private lateinit var rgDiskWarn: RadioGroup
    private lateinit var rgBatteryWarn: RadioGroup
    private lateinit var rgVideoSize: RadioGroup
    private lateinit var rgVideoFps: RadioGroup
    private lateinit var rgVideoQuality: RadioGroup
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
        rgDiskWarn = findViewById(R.id.rgDiskWarn)
        rgBatteryWarn = findViewById(R.id.rgBatteryWarn)
        rgVideoSize = findViewById(R.id.rgVideoSize)
        rgVideoFps = findViewById(R.id.rgVideoFps)
        rgVideoQuality = findViewById(R.id.rgVideoQuality)
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
     * 分段控件：往 [group] 里塞一排可选项。
     *
     * 设置页有六组同类选项（预录时长、补光、模式、质量、尺寸、连拍间隔/张数），
     * 观感必须一致，所以统一在这里生成：48dp 可点高度、单格宽度随文字、
     * 选中态由 bg_pre_record_option 的 state_checked 决定。
     *
     * @param options 每项的值（tag）与显示文字
     * @param current 当前选中的值
     * @param onPick 选中回调，只在该项真的变化时触发
     */
    private fun <T> buildSegment(
        group: RadioGroup,
        options: List<Pair<T, String>>,
        current: T,
        onPick: (T) -> Unit
    ) {
        group.orientation = RadioGroup.HORIZONTAL
        group.removeAllViews()
        options.forEach { (value, label) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                setTextColor(resources.getColorStateList(R.color.pre_record_option_text, theme))
                setButtonDrawable(null)
                setBackgroundResource(R.drawable.bg_pre_record_option)
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                minWidth = resources.getDimensionPixelSize(R.dimen.touch_target)
                gravity = android.view.Gravity.CENTER
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.space_sm),
                    0,
                    resources.getDimensionPixelSize(R.dimen.space_sm),
                    0
                )
                isChecked = value == current
                tag = value
            }
            val lp = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.WRAP_CONTENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = resources.getDimensionPixelSize(R.dimen.space_xs)
            group.addView(rb, lp)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val rb = findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            @Suppress("UNCHECKED_CAST")
            onPick(rb.tag as T)
        }
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
     * 四档标签较长，一行放不下四个，所以排成两列两行、每格等宽。
     * 行内用 LinearLayout 分权重，因此不能用 RadioGroup（它只认直接子项，
     * 嵌套一层就失去互斥），选中态自己维护。
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
        // 四档标签较长，一行放不下四个，改成两列两行
        buildOptionGrid(llPhotoSize, labels, current, columns = 2) { index ->
            SettingsStore.setPhotoSizeIndex(this, index)
        }
    }

    /**
     * 把一组选项按网格排进 [container]：每行 [columns] 格、每格等宽。
     *
     * 用于标签较长的档位（拍照尺寸、分段时长）：排成一行会被压扁变形。
     * 不能用 RadioGroup 做容器——它只认直接子项，嵌套一层的按钮就失去互斥，
     * 所以选中态在这里自己维护。
     *
     * 末行不满格时补一个透明占位（如 5 档 3 列 → 3+2，第二行右侧留白），
     * 否则剩下两格按权重摊满整行，会比上一行宽、边缘对不齐。
     */
    private fun buildOptionGrid(
        container: LinearLayout,
        labels: List<String>,
        current: Int,
        columns: Int,
        onPick: (Int) -> Unit
    ) {
        val buttons = mutableListOf<RadioButton>()
        val gap = resources.getDimensionPixelSize(R.dimen.space_xs)

        fun buildRow(start: Int): LinearLayout {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = gap }
            }
            for (slot in 0 until columns) {
                val index = start + slot
                val isLastSlot = slot == columns - 1
                // 只在格与格之间留缝：末格加右边距会让同一行的格子宽度不等
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (!isLastSlot) lp.marginEnd = gap

                if (index > labels.lastIndex) {
                    // 占位撑住最后一列的宽度，高度 1px 不参与测量
                    line.addView(View(this), lp.apply {
                        height = 1
                    })
                    continue
                }

                val rb = RadioButton(this).apply {
                    id = View.generateViewId()
                    text = labels[index]
                    textSize = 14f
                    setTextColor(
                        resources.getColorStateList(R.color.pre_record_option_text, theme)
                    )
                    setButtonDrawable(null)
                    setBackgroundResource(R.drawable.bg_pre_record_option)
                    minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                    gravity = android.view.Gravity.CENTER
                    isChecked = index == current
                    tag = index
                }
                rb.setOnClickListener {
                    // 互斥：其余取消选中，再写入设置
                    buttons.forEach { other -> other.isChecked = other === rb }
                    onPick(index)
                }
                buttons += rb
                line.addView(rb, lp)
            }
            return line
        }

        container.removeAllViews()
        for (start in labels.indices step columns) {
            container.addView(buildRow(start))
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

        val segments = AppConfig.SEGMENT_MINUTE_OPTIONS.map {
            getString(R.string.settings_segment_fmt, it)
        }
        // 5 档带「分钟」后缀，一行排不下会被压扁，改成每行 4 格、共 2 行。
        // segmentMinutes 读的时候已把档位外的旧值归一成默认档，这里 indexOf 不会返回 -1
        buildOptionGrid(
            llSegmentMinutes, segments,
            AppConfig.SEGMENT_MINUTE_OPTIONS.indexOf(SettingsStore.segmentMinutes(this)),
            columns = 4
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
        buildOptionGrid(
            llAudioSegmentMinutes, segments,
            AppConfig.SEGMENT_MINUTE_OPTIONS.indexOf(SettingsStore.audioSegmentMinutes(this)),
            columns = 4
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
            buildOptionGrid(
                container, labels,
                WATERMARK_FIELD_IDS.indexOfFirst { it.first == current },
                columns = 2
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
        rgDuration.isEnabled = preOn
        for (i in 0 until rgDuration.childCount) {
            rgDuration.getChildAt(i).isEnabled = preOn
        }

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
