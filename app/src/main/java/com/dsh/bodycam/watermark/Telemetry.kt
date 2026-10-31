package com.dsh.bodycam.watermark

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import java.util.Locale

/**
 * 水印里的定位信息。没有权限或没有定位时给占位文本，不影响录像。
 */
class Telemetry(private val context: Context) {

    @Volatile
    var gpsText: String = "定位中…"
        private set

    private var listener: LocationListener? = null

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            gpsText = "无定位权限"
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = update(location)
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listener = l
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 5_000L, 5f, l, Looper.getMainLooper()
            )
        } catch (_: Exception) {
        }
        try {
            manager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 10_000L, 20f, l, Looper.getMainLooper()
            )
        } catch (_: Exception) {
        }
        try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { update(it) }
        } catch (_: SecurityException) {
        }
    }

    private fun update(location: Location) {
        val ns = if (location.latitude >= 0) "N" else "S"
        val ew = if (location.longitude >= 0) "E" else "W"
        gpsText = String.format(
            Locale.US, "%s %.6f°  %s %.6f°  ±%dm",
            ns, kotlin.math.abs(location.latitude),
            ew, kotlin.math.abs(location.longitude),
            location.accuracy.toInt()
        )
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            manager.removeUpdates(l)
        } catch (_: Exception) {
        }
    }
}
