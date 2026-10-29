package cz.astrolens.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

/** Tracks the device location (GPS / network) with the lowest possible overhead. */
class LocationHelper(
    private val context: Context,
    private val onLocation: (Location) -> Unit
) : LocationListener {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false
    var last: Location? = null
        private set

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        running = true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            try {
                if (!lm.isProviderEnabled(p)) continue
                val l = lm.getLastKnownLocation(p)
                if (l != null) deliver(l)
                lm.requestLocationUpdates(p, 30000L, 50f, this)
            } catch (e: Exception) {
                // provider not available
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            lm.removeUpdates(this)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun deliver(l: Location) {
        val prev = last
        if (prev == null || l.time >= prev.time || l.accuracy < prev.accuracy) {
            last = l
            onLocation(l)
        }
    }

    override fun onLocationChanged(location: Location) {
        deliver(location)
    }
}
