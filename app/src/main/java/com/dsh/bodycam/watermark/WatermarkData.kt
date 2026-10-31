package com.dsh.bodycam.watermark

/** 一帧水印要显示的内容 */
data class WatermarkData(
    val dateText: String,
    val timeText: String,
    val gpsText: String,
    val statusText: String,
    val deviceId: String
)
