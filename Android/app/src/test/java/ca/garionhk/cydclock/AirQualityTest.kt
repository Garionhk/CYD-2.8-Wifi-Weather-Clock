// ===========================================================================
// AirQualityTest.kt -- AQI fetch semantics, bands, and the pressure fix
// ===========================================================================
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.data.AirQualityRepository
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.data.UNITS_IMPERIAL
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.scenes.AirQualityScene
import ca.garionhk.cydclock.scenes.SceneContext
import ca.garionhk.cydclock.scenes.SetupButton
import ca.garionhk.cydclock.scenes.StatusStrip
import ca.garionhk.cydclock.scenes.StripState
import ca.garionhk.cydclock.scenes.aqiBand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private val NOW: ZonedDateTime = ZonedDateTime.of(2026, 8, 8, 14, 5, 0, 0, ZoneId.of("Europe/London"))

private fun aqFetch(body: String?, previous: AppData = AppData()): AppData? =
    runBlocking { AirQualityRepository { body }.fetch(AppSettings(), previous, 1_000_000L) }

private fun aqCtx(data: AppData, units: Int = 0) = SceneContext(
    data = data,
    settings = AppSettings(units = units),
    now = NOW,
    nowEpoch = NOW.toEpochSecond(),
    online = true,
)

private fun sample(aqi: Int) = AppData(
    aqi = aqi, pm25 = 12, aqiValid = true, aqiUpdatedAt = NOW.toEpochSecond() - 600,
    humidityPct = 64, windKph = 14.8f, pressureHpa = 1012.6f, pressureTrend = 0.8f,
)

class AirQualityTest {

    private val fonts = loadFonts()

    // ---- fetch ------------------------------------------------------------

    @Test
    fun `a normal response populates aqi and pm25`() {
        val d = aqFetch("""{"current":{"us_aqi":42,"pm2_5":9.6}}""")!!
        assertEquals(42, d.aqi)
        assertEquals("pm2.5 rounds to an int", 10, d.pm25)
        assertTrue(d.aqiValid)
        assertEquals(1_000_000L, d.aqiUpdatedAt)
    }

    @Test
    fun `a null us_aqi is a hard reject, not a last-good merge`() {
        // Open-Meteo returns null where it has no model coverage. Showing a stale
        // AQI stamped with a fresh timestamp would be worse than showing nothing.
        val previous = AppData(aqi = 42, aqiValid = true, aqiUpdatedAt = 500L)
        assertNull(aqFetch("""{"current":{"us_aqi":null,"pm2_5":9.6}}""", previous))
        assertNull(aqFetch("""{"current":{"pm2_5":9.6}}""", previous))
        assertNull(aqFetch("""{}""", previous))
    }

    @Test
    fun `a failed request publishes nothing`() {
        assertNull(aqFetch(null))
        assertNull(aqFetch("not json"))
    }

    @Test
    fun `a missing pm25 keeps its last-good value`() {
        val previous = AppData(pm25 = 7)
        val d = aqFetch("""{"current":{"us_aqi":42}}""", previous)!!
        assertEquals(7, d.pm25)
    }

    @Test
    fun `the air quality fetch leaves weather fields untouched`() {
        val previous = AppData(tempC = 21.4f, weatherValid = true, weatherUpdatedAt = 900L)
        val d = aqFetch("""{"current":{"us_aqi":42,"pm2_5":9.6}}""", previous)!!
        assertEquals(21.4f, d.tempC, 1e-4f)
        assertTrue(d.weatherValid)
        assertEquals("the two fetchers must not stamp each other", 900L, d.weatherUpdatedAt)
    }

    // ---- bands ------------------------------------------------------------

    @Test
    fun `us aqi bands match the firmware`() {
        assertEquals("Good", aqiBand(0).name)
        assertEquals("Good", aqiBand(50).name)
        assertEquals("Moderate", aqiBand(51).name)
        assertEquals("Moderate", aqiBand(100).name)
        assertEquals("Unhealthy (SG)", aqiBand(101).name)
        assertEquals("Unhealthy (SG)", aqiBand(150).name)
        assertEquals("Unhealthy", aqiBand(151).name)
        assertEquals("Unhealthy", aqiBand(200).name)
        assertEquals("Very Unhealthy", aqiBand(201).name)
        assertEquals("Very Unhealthy", aqiBand(300).name)
        assertEquals("Hazardous", aqiBand(301).name)

        assertEquals(Theme.C_GREEN, aqiBand(10).colour)
        assertEquals(Theme.C_YELLOW, aqiBand(75).colour)
        assertEquals(Theme.C_ORANGE, aqiBand(120).colour)
        assertEquals(Theme.C_RED, aqiBand(180).colour)
        assertEquals(Theme.C_PURPLE, aqiBand(250).colour)
        assertEquals(Theme.C_MAROON, aqiBand(400).colour)
    }

    // ---- the scene --------------------------------------------------------

    @Test
    fun `the loading state shows until the first fetch lands`() {
        val fb = GoldenRender.blank()
        AirQualityScene.draw(DeviceCanvas(fb, fonts), aqCtx(AppData()))
        var ink = 0
        for (y in 0 until Theme.CONTENT_H) for (x in 0 until Theme.SCREEN_W) {
            if (fb.pixelAt(x, y) != Theme.COL_BG) ink++
        }
        assertTrue("expected 'fetching air quality...'", ink > 100)
    }

    @Test
    fun `the headline takes the band colour`() {
        for ((aqi, colour) in listOf(
            25 to Theme.C_GREEN, 75 to Theme.C_YELLOW, 120 to Theme.C_ORANGE,
            180 to Theme.C_RED, 250 to Theme.C_PURPLE, 400 to Theme.C_MAROON,
        )) {
            val fb = GoldenRender.blank()
            AirQualityScene.draw(DeviceCanvas(fb, fonts), aqCtx(sample(aqi)))
            var n = 0
            for (y in 20..100) for (x in 0 until Theme.SCREEN_W) if (fb.pixelAt(x, y) == colour) n++
            assertTrue("aqi $aqi should be drawn in its band colour", n > 100)
        }
    }

    @Test
    fun `the band name sits midway between the number and the stats row`() {
        // Measured on the rendered pixels, not on the constants -- the point is
        // that "Good" belongs to neither neighbour more than the other. The
        // firmware's fixed y=112 put it three units under the number and
        // twenty-one above the stats, so it read as part of the number.
        val fb = GoldenRender.blank()
        AirQualityScene.draw(DeviceCanvas(fb, fonts), aqCtx(sample(38)))

        fun rowsWithInk(from: Int, until: Int): IntRange? {
            var top = -1
            var bottom = -1
            for (y in from until until) {
                var any = false
                for (x in 0 until Theme.SCREEN_W) {
                    if (fb.pixelAt(x, y) != Theme.COL_BG) { any = true; break }
                }
                if (any) { if (top < 0) top = y; bottom = y }
            }
            return if (top < 0) null else top..bottom
        }

        // Three bands: the number, the band name, the stats values.
        val number = rowsWithInk(25, 100)!!
        val bandName = rowsWithInk(number.last + 2, 138)!!
        val stats = rowsWithInk(bandName.last + 2, 170)!!

        val gapAbove = bandName.first - number.last
        val gapBelow = stats.first - bandName.last
        assertTrue(
            "band name is not centred: $gapAbove above, $gapBelow below",
            kotlin.math.abs(gapAbove - gapBelow) <= 2,
        )
    }

    @Test
    fun `imperial pressure keeps two decimals and still fits`() {
        // The firmware printed inHg with %d, so 29.92 became "30" -- losing the
        // only digit that moves. Two decimals measure 63 px in Font 4, so the
        // trend glyph lands at 305..313 on a 320 px screen.
        val fb = GoldenRender.blank()
        AirQualityScene.draw(DeviceCanvas(fb, fonts), aqCtx(sample(42), UNITS_IMPERIAL))

        var rightMost = 0
        for (y in 135..165) for (x in 0 until Theme.SCREEN_W) {
            if (fb.pixelAt(x, y) != Theme.COL_BG) rightMost = maxOf(rightMost, x)
        }
        assertTrue("the pressure row runs off the screen at $rightMost", rightMost < Theme.SCREEN_W)
        assertTrue("the pressure row should reach into the last column group", rightMost > 290)
    }

    @Test
    fun `the trend glyph reflects rising, falling and steady`() {
        fun glyphColours(trend: Float): Set<Int> {
            val fb = GoldenRender.blank()
            AirQualityScene.draw(
                DeviceCanvas(fb, fonts), aqCtx(sample(42).copy(pressureTrend = trend)),
            )
            val seen = mutableSetOf<Int>()
            for (y in 142..158) for (x in 295..319) {
                val p = fb.pixelAt(x, y)
                if (p != Theme.COL_BG) seen += p
            }
            return seen
        }
        assertTrue("rising should be green", glyphColours(1.5f).contains(Theme.C_GREEN))
        assertTrue("falling should be blue", glyphColours(-1.5f).contains(Theme.C_RAIN))
        assertTrue("steady should be a dim bar", glyphColours(0.1f).contains(Theme.COL_DIM))
        // The threshold is +/-0.3, so 0.3 exactly is still steady.
        assertTrue(glyphColours(0.3f).contains(Theme.COL_DIM))
        assertTrue(glyphColours(0.31f).contains(Theme.C_GREEN))
    }

    @Test
    fun `the scene never paints the strip or the setup button`() {
        for (aqi in intArrayOf(0, 42, 99, 150, 250, 999, 1250)) {
            for (units in intArrayOf(0, UNITS_IMPERIAL)) {
                val fb = GoldenRender.blank()
                AirQualityScene.draw(DeviceCanvas(fb, fonts), aqCtx(sample(aqi), units))
                for (y in Theme.STATUS_Y until Theme.SCREEN_H) for (x in 0 until Theme.SCREEN_W) {
                    assertEquals("aqi $aqi painted the strip", Theme.COL_BG, fb.pixelAt(x, y))
                }
                for (y in SetupButton.RESERVED_TOP..SetupButton.RESERVED_BOTTOM) {
                    for (x in SetupButton.RESERVED_LEFT..SetupButton.RESERVED_RIGHT) {
                        assertEquals(
                            "aqi $aqi reached the setup button at $x,$y",
                            Theme.COL_BG, fb.pixelAt(x, y),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `render air quality for inspection`() {
        for ((aqi, label) in listOf(42 to "good", 120 to "unhealthy_sg", 250 to "very_unhealthy")) {
            val fb = GoldenRender.blank()
            val c = DeviceCanvas(fb, fonts)
            AirQualityScene.draw(c, aqCtx(sample(aqi)))
            StatusStrip.draw(
                c,
                StripState(
                    timeText = "14:05", wifiLevel = 3, weatherValid = true,
                    weatherUpdatedAt = NOW.toEpochSecond() - 600, nowEpoch = NOW.toEpochSecond(),
                    pinned = false, frozen = false, heldMs = 0,
                    sceneIndex = 3, sceneCount = 4,
                ),
            )
            SetupButton.draw(c)
            assertTrue(GoldenRender.write(fb, "m7_aqi_$label", scale = 3).length() > 0)
        }
    }
}
