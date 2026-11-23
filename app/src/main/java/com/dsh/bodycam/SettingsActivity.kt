package com.dsh.bodycam

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.core.SettingsStore
import com.dsh.bodycam.ui.ToastBar
import com.dsh.bodycam.util.StoragePermission

/**
 * 设置页：预录开关 + 预录时长（5/10/20/30s）、循环录像与空间告警阈值。
 * 改动即时写入 SharedPreferences，并回调通知主页面（如刷新黄点）。
 */
class SettingsActivity : Activity() {

    private lateinit var swPreRecord: Switch
    private lateinit var swLoopRecord: Switch
    private lateinit var swDebugOverlay: Switch
    private lateinit var rgDiskWarn: RadioGroup
    private lateinit var rgBatteryWarn: RadioGroup
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
    private lateinit var storageRow: View
    private lateinit var storageStatus: TextView
    private lateinit var storageAction: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        swPreRecord = findViewById(R.id.swPreRecord)
        swLoopRecord = findViewById(R.id.swLoopRecord)
        swDebugOverlay = findViewById(R.id.swDebugOverlay)
        rgDiskWarn = findViewById(R.id.rgDiskWarn)
        rgBatteryWarn = findViewById(R.id.rgBatteryWarn)
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

        buildDurationOptions()
        buildPhotoFlashOptions()
        buildPhotoModeOptions()
        buildPhotoQualityOptions()
        buildPhotoSizeOptions()
        buildBurstOptions()
        buildDiskWarnOptions()
        buildBatteryWarnOptions()
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

    /** 全局提示条，位置与互斥由 [ToastBar] 统一管理 */
    private fun toast(message: String) {
        ToastBar.show(this, message)
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
        val buttons = mutableListOf<RadioButton>()

        fun buildRow(indices: IntRange): LinearLayout {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.space_xs) }
            }
            indices.forEach { index ->
                val (w, h) = sizes[index]
                val rb = RadioButton(this).apply {
                    id = View.generateViewId()
                    // 1280×720 是 0.92M，四舍五入到 1M，不能整除截断成 0M
                    text = getString(
                        R.string.settings_photo_size_px_fmt,
                        w, h, Math.round(w.toLong() * h / 1_000_000.0).toInt()
                    )
                    textSize = 14f
                    setTextColor(resources.getColorStateList(R.color.pre_record_option_text, theme))
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
                    SettingsStore.setPhotoSizeIndex(this, index)
                }
                buttons += rb
                // 只在两格之间留缝：末格加右边距会让两格宽度不等
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (index != indices.last) {
                    lp.marginEnd = resources.getDimensionPixelSize(R.dimen.space_xs)
                }
                line.addView(rb, lp)
            }
            return line
        }

        llPhotoSize.removeAllViews()
        llPhotoSize.addView(buildRow(0..1))
        llPhotoSize.addView(buildRow(2..3))
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
}
