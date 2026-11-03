package com.dsh.bodycam

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dsh.bodycam.core.SettingsStore

/**
 * 设置页：预录开关 + 预录时长（5/10/20/30s）。
 * 改动即时写入 SharedPreferences，并回调通知主页面（如刷新黄点）。
 */
class SettingsActivity : Activity() {

    private lateinit var swPreRecord: Switch
    private lateinit var durationSection: View
    private lateinit var rgDuration: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        swPreRecord = findViewById(R.id.swPreRecord)
        durationSection = findViewById(R.id.durationSection)
        rgDuration = findViewById(R.id.rgDuration)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        swPreRecord.isChecked = SettingsStore.isPreRecordEnabled(this)
        swPreRecord.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setPreRecordEnabled(this, checked)
            updateSections()
        }

        buildDurationOptions()
        updateSections()
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
                setPadding(28, 18, 28, 18)
                isChecked = seconds == current
                tag = seconds
            }
            val lp = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.WRAP_CONTENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 20
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

    /** 关闭预录时长选择区变灰 */
    private fun updateSections() {
        durationSection.alpha = if (swPreRecord.isChecked) 1f else 0.4f
        durationSection.isEnabled = swPreRecord.isChecked
    }
}
