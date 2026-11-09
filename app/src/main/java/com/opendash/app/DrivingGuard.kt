package com.opendash.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/** Uses GPS speed to tell whether the car is moving. */
class DrivingGuard(
    private val ctx: Context,
    private val onChange: () -> Unit,
) : LocationListener {

    var moving = false
        private set

    private val lm = ctx.getSystemService(LocationManager::class.java)

    fun start() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
        } catch (_: Exception) {}
    }

    fun stop() {
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        if (moving) { moving = false; onChange() }
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasSpeed()) return
        val mps = location.speed
        // Hysteresis: moving above ~10 mph, stopped below ~3 mph.
        val now = if (moving) mps > 1.4f else mps > 4.5f
        if (now != moving) {
            moving = now
            onChange()
        }
    }

    // Overridden explicitly: these are abstract on Android 10.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
