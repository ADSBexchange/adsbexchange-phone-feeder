package com.adsbexchange.feeder.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Gets the receiver position from the phone. Runs in the UI process, while the app is open, so it
 * only needs foreground ("while in use") location permission.
 *
 * A feeder doesn't move, so rather than tracking continuously we take one careful fix: average
 * several readings, weighted by their accuracy, and stop once it's good enough.
 */
class StationLocator(private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun locationEnabled(): Boolean = lm != null && enabledProviders().isNotEmpty()

    /**
     * Averages fixes for up to [timeoutMs], finishing early once [MIN_FIXES] readings are within
     * [targetAccuracyM]. Returns null if no fix arrived at all.
     */
    suspend fun acquire(
        timeoutMs: Long = 90_000,
        targetAccuracyM: Float = 15f,
        onProgress: (bestAccuracyM: Float?, fixes: Int) -> Unit = { _, _ -> },
    ): StationLocation? {
        val fixes = mutableListOf<Location>()
        withTimeoutOrNull(timeoutMs) {
            listen { loc ->
                fixes += loc
                val good = fixes.count { it.hasAccuracy() && it.accuracy <= targetAccuracyM }
                onProgress(fixes.filter { it.hasAccuracy() }.minOfOrNull { it.accuracy }, fixes.size)
                good >= MIN_FIXES
            }
        }
        return average(fixes)
    }

    /** One reading, for "has this phone moved?" checks. */
    suspend fun quickFix(timeoutMs: Long = 20_000): Location? {
        var result: Location? = null
        withTimeoutOrNull(timeoutMs) {
            listen { loc ->
                result = loc
                true
            }
        }
        return result ?: lastKnown()
    }

    /** Registers for updates until [onFix] returns true or the coroutine is cancelled. */
    @SuppressLint("MissingPermission") // callers check hasPermission(); SecurityException is caught
    private suspend fun listen(onFix: (Location) -> Boolean) {
        val manager = lm ?: return
        val providers = enabledProviders()
        if (providers.isEmpty() || !hasPermission()) return
        suspendCancellableCoroutine { cont ->
            // All four methods are overridden: before API 30 they're abstract, and a missing one
            // would throw AbstractMethodError on old phones.
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (onFix(location) && cont.isActive) {
                        manager.removeUpdates(this)
                        cont.resume(Unit)
                    }
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            try {
                for (p in providers) manager.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { manager.removeUpdates(listener) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? = runCatching {
        enabledProviders().mapNotNull { lm?.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()

    private fun enabledProviders(): List<String> {
        val manager = lm ?: return emptyList()
        return buildList {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            ) add(LocationManager.GPS_PROVIDER)
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val MIN_FIXES = 5
        /** Beyond this, a stationary feeder has probably been taken somewhere else. */
        const val MOVED_THRESHOLD_M = 5_000f

        fun distanceM(a: StationLocation, lat: Double, lon: Double): Float {
            val r = FloatArray(1)
            Location.distanceBetween(a.lat, a.lon, lat, lon, r)
            return r[0]
        }

        /** Accuracy-weighted mean of the better readings (those within 2x of the best accuracy). */
        fun average(fixes: List<Location>): StationLocation? {
            if (fixes.isEmpty()) return null
            val withAcc = fixes.filter { it.hasAccuracy() && it.accuracy > 0 }
            if (withAcc.isEmpty()) {
                val l = fixes.last()
                return StationLocation(l.latitude, l.longitude, l.altitude.takeIf { l.hasAltitude() }, null,
                    l.time, source(l))
            }
            val best = withAcc.minOf { it.accuracy }
            val used = withAcc.filter { it.accuracy <= best * 2 }
            val weights = used.map { 1.0 / (it.accuracy * it.accuracy) }
            val sum = weights.sum()
            val lat = used.indices.sumOf { used[it].latitude * weights[it] } / sum
            val lon = used.indices.sumOf { used[it].longitude * weights[it] } / sum
            val alts = used.filter { it.hasAltitude() }.map { it.altitude }
            return StationLocation(
                lat = lat,
                lon = lon,
                altitudeM = alts.takeIf { it.isNotEmpty() }?.average(),
                accuracyM = best,
                timeMs = used.maxOf { it.time },
                source = if (used.any { it.provider == LocationManager.GPS_PROVIDER }) {
                    StationLocation.Source.GPS
                } else StationLocation.Source.NETWORK,
            )
        }

        private fun source(l: Location) =
            if (l.provider == LocationManager.GPS_PROVIDER) StationLocation.Source.GPS else StationLocation.Source.NETWORK
    }
}
