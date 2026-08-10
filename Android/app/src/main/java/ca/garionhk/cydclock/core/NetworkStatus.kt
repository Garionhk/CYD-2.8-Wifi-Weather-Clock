// ===========================================================================
// NetworkStatus.kt -- what the status strip and the clock footer need to know
// ===========================================================================
// An interface rather than a direct ConnectivityManager call, so the scenes stay
// free of android.* imports and can be rasterised under plain JUnit.
package ca.garionhk.cydclock.core

interface NetworkStatus {
    /** A network that actually reaches the internet, not merely an association. */
    val online: Boolean

    /**
     * 0..4, using the firmware's RSSI thresholds where an RSSI is available.
     *
     * DIVERGENCE: on the CYD this meant WiFi signal strength specifically.
     * getConnectionInfo() is deprecated at API 31 and may return a redacted
     * -127, and the tablet may be on ethernet or cellular, so an online link
     * with no usable RSSI reports 4.
     */
    val wifiBars: Int

    companion object {
        /** Used by tests and by the first frame, before any callback has fired. */
        val OFFLINE = object : NetworkStatus {
            override val online = false
            override val wifiBars = 0
        }
    }
}
