// ===========================================================================
// HttpGet.kt -- replaces HTTPClient + WiFiClientSecure
// ===========================================================================
// Plain HttpURLConnection, deliberately. This app makes two unauthenticated GETs
// of under two kilobytes each, 144 times a day, against one host. OkHttp or Ktor
// would add roughly a megabyte and a configuration surface to buy connection
// pooling and interceptors that nothing here would use. If that ever changes,
// swapping the implementation touches this file and no other.
//
// One thing improves for free: the firmware called client.setInsecure() because
// carrying a CA bundle on an ESP32 is painful. Android validates certificates
// properly, and usesCleartextTraffic is off, so the port is strictly safer here
// rather than merely equivalent.
package ca.garionhk.cydclock.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object HttpGet {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** Body on HTTP 200, null on anything else. Never throws. */
    suspend fun text(url: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                // Open-Meteo's free tier asks that clients identify themselves.
                setRequestProperty("User-Agent", "CydClock/1.0 (Android)")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            // Buffer then parse. The firmware learned the same lesson the hard
            // way: parsing straight from the stream mis-reads chunked responses.
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
