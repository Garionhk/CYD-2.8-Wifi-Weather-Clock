// ===========================================================================
// WeatherRepositoryTest.kt -- fetch semantics, especially the last-good rule
// ===========================================================================
// The fetch function takes its HTTP call as a parameter, so every one of these
// runs offline and deterministically.
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.data.FetchScheduler
import ca.garionhk.cydclock.data.OpenMeteoApi
import ca.garionhk.cydclock.data.WeatherRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FULL = """
{"current":{"temperature_2m":21.4,"apparent_temperature":20.1,"weather_code":3,
"cloud_cover":88,"relative_humidity_2m":64,"wind_speed_10m":14.8,
"surface_pressure":1012.6,"uv_index":3.4},
"daily":{"temperature_2m_max":[24.2],"temperature_2m_min":[13.7]}}
"""

private fun repo(body: String?) = WeatherRepository { body }

private fun fetch(body: String?, previous: AppData = AppData(), now: Long = 1_000_000L): AppData? =
    runBlocking { repo(body).fetch(AppSettings(), previous, now) }

class WeatherRepositoryTest {

    @Test
    fun `a full response populates everything`() {
        val d = fetch(FULL)!!
        assertEquals(21.4f, d.tempC, 1e-4f)
        assertEquals(20.1f, d.feelsLikeC, 1e-4f)
        assertEquals(3, d.weatherCode)
        assertEquals(88, d.cloudCoverPct)
        assertEquals(64, d.humidityPct)
        assertEquals(14.8f, d.windKph, 1e-4f)
        assertEquals(1012.6f, d.pressureHpa, 1e-4f)
        assertEquals(3.4f, d.uvIndex, 1e-4f)
        assertEquals(24.2f, d.tempMaxC, 1e-4f)
        assertEquals(13.7f, d.tempMinC, 1e-4f)
        assertTrue(d.weatherValid && d.dailyValid && d.uvValid)
        assertEquals(1_000_000L, d.weatherUpdatedAt)
        assertEquals(1_000_000L, d.uvUpdatedAt)
    }

    // ---- the invariant ----------------------------------------------------

    @Test
    fun `a failed request returns null so nothing is published`() {
        assertNull("a network failure must not produce a model", fetch(null))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(fetch("not json at all"))
        assertNull(fetch("""{"current":"a string, not an object"}"""))
    }

    @Test
    fun `a response with no current block is rejected`() {
        assertNull(fetch("""{"daily":{"temperature_2m_max":[24.2]}}"""))
    }

    @Test
    fun `missing fields keep their last-good values`() {
        // ArduinoJson's `cur["x"] | g_data.tempC` idiom, reproduced by nullable
        // DTO fields. A server that omits humidity must not zero the display.
        val previous = AppData(
            tempC = 18.0f, humidityPct = 55, windKph = 9.9f,
            weatherCode = 61, cloudCoverPct = 40, pressureHpa = 1004.0f,
        )
        val partial = """{"current":{"temperature_2m":19.5}}"""
        val d = fetch(partial, previous)!!

        assertEquals("the field that was sent updates", 19.5f, d.tempC, 1e-4f)
        assertEquals("humidity was absent, so it holds", 55, d.humidityPct)
        assertEquals(9.9f, d.windKph, 1e-4f)
        assertEquals(61, d.weatherCode)
        assertEquals(40, d.cloudCoverPct)
    }

    @Test
    fun `an explicit null keeps the last-good value too`() {
        val previous = AppData(tempC = 18.0f, uvIndex = 5.5f)
        val d = fetch("""{"current":{"temperature_2m":null,"uv_index":null}}""", previous)!!
        assertEquals(18.0f, d.tempC, 1e-4f)
        assertEquals(5.5f, d.uvIndex, 1e-4f)
    }

    @Test
    fun `an absent daily block leaves dailyValid alone`() {
        val fresh = fetch("""{"current":{"temperature_2m":19.5}}""")!!
        assertTrue("no daily block means no high/low yet", !fresh.dailyValid)

        val previous = AppData(tempMaxC = 24.0f, tempMinC = 12.0f, dailyValid = true)
        val d = fetch("""{"current":{"temperature_2m":19.5}}""", previous)!!
        assertTrue("a previously seen daily block stays valid", d.dailyValid)
        assertEquals(24.0f, d.tempMaxC, 1e-4f)
    }

    // ---- pressure trend ---------------------------------------------------

    @Test
    fun `pressure trend is the delta between successive fetches`() {
        val first = fetch(FULL)!!
        assertEquals("nothing to compare against on a cold start", 0f, first.pressureTrend, 1e-4f)

        val rising = """{"current":{"surface_pressure":1015.1}}"""
        val second = fetch(rising, first)!!
        assertEquals(2.5f, second.pressureTrend, 1e-3f)

        val falling = """{"current":{"surface_pressure":1013.0}}"""
        val third = fetch(falling, second)!!
        assertEquals(-2.1f, third.pressureTrend, 1e-3f)
    }

    // ---- URLs -------------------------------------------------------------

    /** The value of a single query parameter. Commas are legal elsewhere in the
     *  URL -- the `current=` list is comma separated -- so only the coordinates
     *  can be checked for a decimal comma. */
    private fun param(url: String, name: String): String =
        url.substringAfter("$name=").substringBefore('&')

    @Test
    fun `urls use four decimals and a dot, whatever the default locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            // Germany formats decimals with a comma. If that leaked into a
            // coordinate, every fetch would 400 and the device could never say why.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)

            val url = OpenMeteoApi.forecastUrl(51.4779, -0.0015)
            assertEquals("51.4779", param(url, "latitude"))
            assertEquals("-0.0015", param(url, "longitude"))
            assertTrue("timezone=auto aligns daily to the local day", url.contains("timezone=auto"))
            assertTrue(url.contains("forecast_days=1"))
            assertTrue(url.contains("uv_index"))

            val aq = OpenMeteoApi.airQualityUrl(-33.8688, 151.2093)
            assertEquals("-33.8688", param(aq, "latitude"))
            assertEquals("151.2093", param(aq, "longitude"))
            assertTrue(aq.startsWith("https://air-quality-api.open-meteo.com/"))
            assertTrue(aq.contains("us_aqi") && aq.contains("pm2_5"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `coordinates are rounded to four decimals, not truncated or widened`() {
        val url = OpenMeteoApi.forecastUrl(51.47791234, -0.00154999)
        assertEquals("51.4779", param(url, "latitude"))
        assertEquals("-0.0015", param(url, "longitude"))
        assertEquals("0.0000", param(OpenMeteoApi.forecastUrl(0.0, 0.0), "latitude"))
    }

    @Test
    fun `no api key appears anywhere in the urls`() {
        // Open-Meteo needs none, which is why this app has no credential plumbing.
        val url = OpenMeteoApi.forecastUrl(0.0, 0.0)
        for (token in listOf("apikey", "api_key", "appid", "token", "key=")) {
            assertTrue("unexpected '$token' in $url", !url.lowercase().contains(token))
        }
    }

    // ---- scheduling -------------------------------------------------------

    @Test
    fun `success schedules the next fetch one interval out`() {
        val s = FetchScheduler(FetchScheduler.WEATHER_INTERVAL_MS)
        s.begin(0)
        assertTrue("the first fetch is due immediately", s.isDue(0))
        s.onSuccess(1_000)
        assertTrue(!s.isDue(1_000 + 15 * 60_000 - 1))
        assertTrue(s.isDue(1_000 + 15 * 60_000))
    }

    @Test
    fun `air quality starts five seconds behind weather`() {
        val s = FetchScheduler(
            FetchScheduler.AQI_INTERVAL_MS,
            initialDelayMs = FetchScheduler.AQI_INITIAL_DELAY_MS,
        )
        s.begin(0)
        assertTrue("staggered so the two fetches do not collide at boot", !s.isDue(4_999))
        assertTrue(s.isDue(5_000))
    }

    @Test
    fun `failures back off 1 2 4 8 15 and hold`() {
        val s = FetchScheduler(FetchScheduler.WEATHER_INTERVAL_MS)
        s.begin(0)
        val expected = listOf(1, 2, 4, 8, 15, 15, 15)
        val seen = expected.indices.map { s.onFailure(0); s.backoffMinutes }
        assertEquals(expected, seen)
    }

    @Test
    fun `a success clears the backoff`() {
        val s = FetchScheduler(FetchScheduler.WEATHER_INTERVAL_MS)
        s.begin(0)
        s.onFailure(0); s.onFailure(0); s.onFailure(0)
        assertEquals(4, s.backoffMinutes)
        s.onSuccess(0)
        assertEquals(0, s.backoffMinutes)
    }

    @Test
    fun `being offline earns no backoff`() {
        // No attempt was made, so there is nothing to back off from. Airplane mode
        // for an hour must not leave the device waiting fifteen minutes after the
        // network returns.
        val s = FetchScheduler(FetchScheduler.WEATHER_INTERVAL_MS)
        s.begin(0)
        repeat(10) { s.onOffline(it * 30_000L) }
        assertEquals(0, s.backoffMinutes)
        assertTrue("offline retries every 30 s", s.isDue(9 * 30_000L + 30_000L))
    }

    @Test
    fun `sleep slices are capped so doze cannot hide lateness`() {
        val s = FetchScheduler(FetchScheduler.WEATHER_INTERVAL_MS)
        s.begin(0)
        s.onSuccess(0)
        assertEquals(FetchScheduler.MAX_SLEEP_MS, s.sleepMs(0))
        assertEquals(0L, s.sleepMs(15 * 60_000L))
        assertEquals("never negative", 0L, s.sleepMs(99 * 60_000L))
    }

    @Test
    fun `requestNow makes the next poll fetch immediately`() {
        val s = FetchScheduler(FetchScheduler.WEATHER_INTERVAL_MS)
        s.begin(0)
        s.onSuccess(0)
        assertTrue(!s.isDue(1_000))
        s.requestNow(1_000)
        assertTrue("a location change should not wait out the interval", s.isDue(1_000))
    }
}
