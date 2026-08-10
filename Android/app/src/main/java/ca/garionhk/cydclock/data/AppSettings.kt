// ===========================================================================
// AppSettings.kt -- user settings. Port of app/settings.h
// ===========================================================================
// wifiSsid/wifiPass are gone: Android owns connectivity, so there is nothing to
// provision and nothing to leak.
//
// tz changes shape. The firmware stored a POSIX TZ string ("PST8PDT,M3.2.0,
// M11.1.0") because the ESP32's newlib has no timezone database and the DST
// rules had to be spelled out inline. Android has the IANA database, so this is
// a zone id and null means "follow the device".
package ca.garionhk.cydclock.data

const val UNITS_METRIC = 0     // C, km/h, hPa
const val UNITS_IMPERIAL = 1   // F, mph, inHg

data class AppSettings(
    // Greenwich Observatory. Deliberately somewhere obviously not yours, so an
    // unconfigured unit reads as unconfigured rather than as subtly wrong.
    val latitude: Double = 51.4779,
    val longitude: Double = -0.0015,
    /** IANA zone id, or null to follow the device's own timezone. */
    val zoneId: String? = null,
    val units: Int = UNITS_METRIC,
    val provisioned: Boolean = false,
    /**
     * Off by default: the letterboxed 4:3 is the shape the scenes were laid out
     * for. On widens the grid to the display's aspect instead, spreading the
     * columns and pushing the status bar and setup button to the real edges.
     */
    val fillScreen: Boolean = false,
    /**
     * On by default: the point of the thing is to be readable across the room,
     * and a clock that vanishes the moment someone taps the power button is not.
     * This shows the app ABOVE the keyguard; it does not unlock anything.
     */
    val showOverLockScreen: Boolean = true,
)
