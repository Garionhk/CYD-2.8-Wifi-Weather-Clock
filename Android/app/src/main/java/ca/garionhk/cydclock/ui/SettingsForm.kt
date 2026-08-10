// ===========================================================================
// SettingsForm.kt -- form state and validation. Port of webconfig.cpp handleSave
// ===========================================================================
// Deliberately free of android.* imports so the validation is testable on the
// JVM. The Compose screen is a rendering of this; the rules live here.
//
// The messages are the firmware's, word for word (webconfig.cpp:218-223), minus
// the SSID check -- Android owns connectivity, so there is no network name to
// require. They are shown per field rather than as a page-level red banner,
// which is the one thing a native form does better than the served page did.
package ca.garionhk.cydclock.ui

import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.data.UNITS_IMPERIAL
import ca.garionhk.cydclock.data.UNITS_METRIC
import java.util.Locale

data class SettingsForm(
    val lat: String = "",
    val lon: String = "",
    val useDeviceZone: Boolean = true,
    val zoneId: String? = null,
    val units: Int = UNITS_METRIC,
    val fillScreen: Boolean = false,
    val showOverLockScreen: Boolean = true,
) {
    val latError: String? get() = when {
        lat.isBlank() || lon.isBlank() -> REQUIRED
        // toFloatOrNull, not the firmware's String::toFloat, which quietly
        // returned 0.0 for anything unparseable and relocated the device to the
        // Gulf of Guinea.
        lat.trim().toFloatOrNull() == null -> REQUIRED
        lat.trim().toFloat().let { it < -90f || it > 90f } -> LAT_RANGE
        else -> null
    }

    val lonError: String? get() = when {
        lat.isBlank() || lon.isBlank() -> null      // reported once, on latitude
        lon.trim().toFloatOrNull() == null -> REQUIRED
        lon.trim().toFloat().let { it < -180f || it > 180f } -> LON_RANGE
        else -> null
    }

    val valid: Boolean get() = latError == null && lonError == null

    /** Null when the form does not validate. */
    fun toSettings(existing: AppSettings): AppSettings? {
        if (!valid) return null
        return existing.copy(
            latitude = lat.trim().toDouble(),
            longitude = lon.trim().toDouble(),
            zoneId = if (useDeviceZone) null else zoneId,
            units = if (units == UNITS_IMPERIAL) UNITS_IMPERIAL else UNITS_METRIC,
            fillScreen = fillScreen,
            showOverLockScreen = showOverLockScreen,
            provisioned = true,
        )
    }

    companion object {
        const val REQUIRED = "Latitude and longitude are required."
        const val LAT_RANGE = "Latitude must be between -90 and 90."
        const val LON_RANGE = "Longitude must be between -180 and 180."

        /** Four decimals is what the firmware sent to Open-Meteo -- about 11 m. */
        fun coord(v: Double): String = String.format(Locale.US, "%.4f", v)

        fun from(s: AppSettings) = SettingsForm(
            lat = coord(s.latitude),
            lon = coord(s.longitude),
            useDeviceZone = s.zoneId == null,
            zoneId = s.zoneId,
            units = s.units,
            fillScreen = s.fillScreen,
            showOverLockScreen = s.showOverLockScreen,
        )
    }
}
