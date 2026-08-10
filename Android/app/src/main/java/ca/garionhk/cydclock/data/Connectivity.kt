// ===========================================================================
// Connectivity.kt -- replaces WiFi.status() / WiFi.RSSI() for the status strip
// ===========================================================================
// Callback-driven rather than polled: the tick loop runs at 10 Hz and asking
// ConnectivityManager ten times a second would be ten binder round trips a
// second for a value that changes a few times a day.
//
// "Online" means NET_CAPABILITY_VALIDATED, not merely connected. A tablet
// associated to an access point with no internet behind it should show the
// offline state, because that is what the weather fetch will experience.
package ca.garionhk.cydclock.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ca.garionhk.cydclock.core.NetworkStatus
import ca.garionhk.cydclock.scenes.StatusStrip

class ConnectivityMonitor(context: Context) : NetworkStatus {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    private var validated by mutableStateOf(false)
    private var rssi by mutableStateOf<Int?>(null)

    override val online: Boolean get() = validated
    override val wifiBars: Int get() = StatusStrip.wifiLevelFromRssi(validated, rssi)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            rssi = readRssi(caps)
        }

        override fun onLost(network: Network) {
            validated = false
            rssi = null
        }

        override fun onUnavailable() {
            validated = false
            rssi = null
        }
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm?.registerNetworkCallback(request, callback) }
        // Seed from the current network so the first frame is not wrongly offline.
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        rssi = caps?.let { readRssi(it) }
    }

    fun unregister() {
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    /**
     * Null means "no usable RSSI", which the strip renders as full bars rather
     * than none -- the link is up, we just cannot say how strong it is. That
     * happens on ethernet and cellular, and on WiFi at API 31+ where the value
     * is redacted to -127 without location permission.
     */
    private fun readRssi(caps: NetworkCapabilities): Int? {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info: WifiInfo? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                caps.transportInfo as? WifiInfo
            } else {
                @Suppress("DEPRECATION")
                wifi?.connectionInfo
            }
        val value = info?.rssi ?: return null
        return if (value == -127 || value == Int.MIN_VALUE) null else value
    }
}
