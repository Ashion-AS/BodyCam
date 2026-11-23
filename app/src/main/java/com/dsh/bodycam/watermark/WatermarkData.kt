package com.dsh.bodycam.watermark

/**
 * 一帧水印要显示的内容。
 *
 * 四个角各显示什么：
 *  - 左上：经纬度（不带 ± 精度、不带米数）
 *  - 右上：日期 + 时间
 *  - 左下：第一行 SN 号，第二行录像状态
 *  - 右下：第一行分组名，第二行用户名（登录平台后才有，未登录时用默认值）
 */
data class WatermarkData(
    val dateText: String,
    val timeText: String,
    val gpsText: String,
    val statusText: String,
    val deviceId: String,
    /** 右下第一行：平台分组名，默认「默认组」 */
    val groupName: String = "",
    /** 右下第二行：平台用户名，默认 user1 */
    val userName: String = ""
)

/** 水印可放置的四个角 */
enum class WatermarkCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** 每个角显示的内容类型 */
enum class WatermarkField { GPS, TIME, DEVICE_STATUS, GROUP_USER, NONE }