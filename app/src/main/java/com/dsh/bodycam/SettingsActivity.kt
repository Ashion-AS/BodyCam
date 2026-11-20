package com.dsh.bodycam

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dsh.bodycam.core.AppConfig
import com.dsh.bodycam.core.SettingsStore
import com.dsh.bodycam.util.StoragePermission

/**
 * 设置页：预录开关 + 预录时长（5/10/20/30s）。
 * 改动即时写入 SharedPreferences，并回调通知主页面（如刷新黄点）。
 */
class SettingsActivity : Activity() {

    private lateinit var swPreRecord: Switch
    private lateinit var swDebugOverlay: Switch
    private lateinit var rgPhotoFlash: RadioGroup
    private lateinit var durationSection: View
    private lateinit var rgDuration: RadioGroup
    private lateinit var storageRow: View
    private lateinit var storageStatus: TextView
    private lateinit var storageAction: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        swPreRecord = findViewById(R.id.swPreRecord)
        swDebugOverlay = findViewById(R.id.swDebugOverlay)
        rgPhotoFlash = findViewById(R.id.rgPhotoFlash)
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

        buildDurationOptions()
        buildPhotoFlashOptions()
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

    /** 5/10/20/30s 四个单选按钮，横排 */
    private fun buildDurationOptions() {
        val current = SettingsStore.preRecordSeconds(this)
        rgDuration.orientation = RadioGroup.HORIZONTAL
        for (seconds in SettingsStore.PRE_RECORD_OPTIONS) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(R.string.settings_pre_record_duration_fmt, seconds)
                textSize = 14f
                setTextColor(resources.getColorStateList(R.color.pre_record_option_text, theme))
                setButtonDrawable(null)
                setBackgroundResource(R.drawable.bg_pre_record_option)
                // 分段控件的一个格子：48dp 高保证可点，视觉宽度随文字
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                minWidth = resources.getDimensionPixelSize(R.dimen.touch_target)
                gravity = android.view.Gravity.CENTER
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.space_sm),
                    0,
                    resources.getDimensionPixelSize(R.dimen.space_sm),
                    0
                )
                isChecked = seconds == current
                tag = seconds
            }
            val lp = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.WRAP_CONTENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = resources.getDimensionPixelSize(R.dimen.space_xs)
            rgDuration.addView(rb, lp)
        }
        rgDuration.setOnCheckedChangeListener { _, checkedId ->
            val rb = findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            val seconds = rb.tag as Int
            SettingsStore.setPreRecordSeconds(this, seconds)
            Toast.makeText(
                this,
                getString(R.string.settings_pre_record_duration_fmt, seconds),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 拍照补光：关闭 / 自动 / 强制，三选一，横排 */
    private fun buildPhotoFlashOptions() {
        val modes = intArrayOf(
            AppConfig.PHOTO_FLASH_OFF,
            AppConfig.PHOTO_FLASH_AUTO,
            AppConfig.PHOTO_FLASH_ALWAYS
        )
        val labels = intArrayOf(
            R.string.settings_photo_flash_off,
            R.string.settings_photo_flash_auto,
            R.string.settings_photo_flash_always
        )
        val current = SettingsStore.photoFlashMode(this)
        rgPhotoFlash.orientation = RadioGroup.HORIZONTAL
        modes.forEachIndexed { i, mode ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(labels[i])
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
                isChecked = mode == current
                tag = mode
            }
            val lp = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.WRAP_CONTENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = resources.getDimensionPixelSize(R.dimen.space_xs)
            rgPhotoFlash.addView(rb, lp)
        }
        rgPhotoFlash.setOnCheckedChangeListener { _, checkedId ->
            val rb = findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            SettingsStore.setPhotoFlashMode(this, rb.tag as Int)
        }
    }

    /** 关闭预录时，时长区降到 40% 不透明度并整体不可点 */
    private fun updateSections() {
        val on = swPreRecord.isChecked
        durationSection.alpha = if (on) 1f else 0.4f
        durationSection.isEnabled = on
        rgDuration.isEnabled = on
        for (i in 0 until rgDuration.childCount) {
            rgDuration.getChildAt(i).isEnabled = on
        }
    }
}
