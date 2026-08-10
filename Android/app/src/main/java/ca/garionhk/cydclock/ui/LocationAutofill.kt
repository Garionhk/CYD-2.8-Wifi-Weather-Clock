// ===========================================================================
// LocationAutofill.kt -- fills lat/lon from the device, when it can
// ===========================================================================
// Platform LocationManager, not play-services-location. This asks for one coarse
// fix, once, at setup; Fused Location needs Google Play Services, which cheap and
// AOSP wall tablets frequently do not have, and adds several megabytes for a
// value the firmware itself only kept to four decimals.
//
// The governing rule is docs/decisions.md's: this is an appliance that reboots
// after a power cut with nobody in the room. So nothing here blocks. Permission
// denied, no providers, no fix inside the timeout -- all of them fall through to
// the manual fields, which already hold usable defaults.
package ca.garionhk.cydclock.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object LocationAutofill {

    val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /** A live fix can take a while outdoors and forever indoors. Cap it. */
    private const val TIMEOUT_MS = 20_000L

    fun hasPermission(context: Context): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Latitude to longitude, or null if no fix could be had. Never throws. */
    @SuppressLint("MissingPermission")
    suspend fun fetch(context: Context): Pair<Double, Double>? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null

        lastKnown(lm)?.let { return it.latitude to it.longitude }

        val provider = firstEnabledProvider(lm) ?: return null
        val fix = withTimeoutOrNull(TIMEOUT_MS) { currentLocation(lm, provider) }
        return fix?.let { it.latitude to it.longitude }
    }

    /** Newest cached fix across every provider -- usually instant and good enough. */
    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? {
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return providers.mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun firstEnabledProvider(lm: LocationManager): String? =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { runCatching { signal.cancel() } }
                runCatching {
                    lm.getCurrentLocation(
                        provider,
                        signal,
                        Runnable::run,
                    ) { location -> if (cont.isActive) cont.resume(location) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("required by the pre-API-29 interface")
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
                    override fun onProviderDisabled(p: String) {
                        runCatching { lm.removeUpdates(this) }
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                runCatching {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
}
