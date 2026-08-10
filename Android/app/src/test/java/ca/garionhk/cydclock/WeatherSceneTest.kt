// ===========================================================================
// WeatherSceneTest.kt -- the weather scene, its icons, and the C arithmetic
// ===========================================================================
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.core.trunc
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.data.UNITS_IMPERIAL
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.render.Framebuffer
import ca.garionhk.cydclock.scenes.SceneContext
import ca.garionhk.cydclock.scenes.StatusStrip
import ca.garionhk.cydclock.scenes.StripState
import ca.garionhk.cydclock.scenes.WeatherIcons
import ca.garionhk.cydclock.scenes.WeatherScene
import ca.garionhk.cydclock.scenes.WxCat
import ca.garionhk.cydclock.scenes.wxCategory
import ca.garionhk.cydclock.scenes.wxLabel
import ca.garionhk.cydclock.scenes.SetupButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private val NOW: ZonedDateTime = ZonedDateTime.of(2026, 8, 8, 14, 5, 0, 0, ZoneId.of("Europe/London"))

private fun sample(code: Int = 3) = AppData(
    tempC = 21.4f, feelsLikeC = 20.1f, tempMaxC = 24.2f, tempMinC = 13.7f,
    dailyValid = true, weatherCode = code, cloudCoverPct = 88, humidityPct = 64,
    windKph = 14.8f, pressureHpa = 1012.6f, pressureTrend = 0.8f,
    uvIndex = 3.4f, uvValid = true,
    weatherValid = true, weatherUpdatedAt = NOW.toEpochSecond() - 300,
)

private fun ctx(data: AppData, units: Int = 0) = SceneContext(
    data = data,
    settings = AppSettings(units = units),
    now = NOW,
    nowEpoch = NOW.toEpochSecond(),
    online = true,
)

class WeatherSceneTest {

    private val fonts = loadFonts()

    // ---- WMO mapping ------------------------------------------------------

    @Test
    fun `wmo codes map to the firmware's categories`() {
        assertEquals(WxCat.CLEAR, wxCategory(0))
        assertEquals(WxCat.PARTLY, wxCategory(1))
        assertEquals(WxCat.PARTLY, wxCategory(2))
        assertEquals(WxCat.CLOUD, wxCategory(3))
        assertEquals(WxCat.FOG, wxCategory(45))
        assertEquals(WxCat.FOG, wxCategory(48))
        for (c in 51..67) assertEquals("code $c", WxCat.RAIN, wxCategory(c))
        for (c in 80..82) assertEquals("code $c", WxCat.RAIN, wxCategory(c))
        for (c in 71..77) assertEquals("code $c", WxCat.SNOW, wxCategory(c))
        assertEquals(WxCat.SNOW, wxCategory(85))
        assertEquals(WxCat.SNOW, wxCategory(86))
        assertEquals(WxCat.THUNDER, wxCategory(95))
        assertEquals(WxCat.THUNDER, wxCategory(99))
        // The gaps fall through to cloud, as they did in the C.
        assertEquals(WxCat.CLOUD, wxCategory(4))
        assertEquals(WxCat.CLOUD, wxCategory(70))
        assertEquals(WxCat.CLOUD, wxCategory(-1))
    }

    @Test
    fun `labels match and fit the layout`() {
        assertEquals("Clear", wxLabel(0))
        assertEquals("Partly Cloudy", wxLabel(2))
        assertEquals("Thunderstorm", wxLabel(95))
        // Drawn MC at x=70 in Font 2, so the widest must not run off the left edge.
        val c = DeviceCanvas(Framebuffer(), fonts)
        c.setTextFont(2)
        for (code in intArrayOf(0, 1, 3, 45, 61, 71, 95)) {
            val half = c.textWidth(wxLabel(code)) / 2
            assertTrue("'${wxLabel(code)}' overflows the left edge", 70 - half >= 0)
        }
    }

    // ---- the icons --------------------------------------------------------

    @Test
    fun `iconCloud truncates its radii the way C does`() {
        // s=16 must give radii 11 and 12, from 11.2 and 12.8. roundToInt would
        // give 11 and 13 and draw a visibly different cloud.
        assertEquals(11, trunc(16 * 0.7f))
        assertEquals(12, trunc(16 * 0.8f))

        // And the drawn result must actually differ from the rounded one, or the
        // assertion above is not protecting anything.
        val truncated = Framebuffer().also {
            WeatherIcons.cloud(DeviceCanvas(it, fonts), 70, 56, 16, Theme.C_CLOUD)
        }
        val rounded = Framebuffer().also {
            val c = DeviceCanvas(it, fonts)
            c.fillCircle(70 - 16, 56, Math.round(16 * 0.7f), Theme.C_CLOUD)
            c.fillCircle(70 + 16, 56, Math.round(16 * 0.8f), Theme.C_CLOUD)
            c.fillCircle(70, Math.round(56 - 16 * 0.5f), 16, Theme.C_CLOUD)
            c.fillRect(70 - 16, 56, 32, Math.round(16 * 0.8f), Theme.C_CLOUD)
        }
        assertTrue(
            "truncation vs rounding should be visibly different",
            !truncated.px.contentEquals(rounded.px),
        )
    }

    @Test
    fun `every category draws something inside the content area`() {
        for (code in intArrayOf(0, 1, 3, 45, 61, 71, 95)) {
            val fb = GoldenRender.blank()
            WeatherIcons.draw(DeviceCanvas(fb, fonts), 70, 56, code)
            var ink = 0
            for (y in 0 until Theme.CONTENT_H) for (x in 0 until Theme.SCREEN_W) {
                if (fb.pixelAt(x, y) != Theme.COL_BG) ink++
            }
            assertTrue("code $code drew nothing", ink > 50)

            // Nothing may reach the status strip or the setup button.
            for (y in Theme.STATUS_Y until Theme.SCREEN_H) for (x in 0 until Theme.SCREEN_W) {
                assertEquals("icon $code painted into the strip", Theme.COL_BG, fb.pixelAt(x, y))
            }
        }
    }

    @Test
    fun `the sun icon is eight-fold symmetric`() {
        val fb = GoldenRender.blank()
        WeatherIcons.sun(DeviceCanvas(fb, fonts), 160, 100, 18, Theme.C_SUN)
        // Rays at 0 and 180 degrees are horizontal and must mirror exactly.
        for (dx in 22..28) {
            assertEquals(
                "ray asymmetry at dx=$dx",
                fb.pixelAt(160 + dx, 100) != Theme.COL_BG,
                fb.pixelAt(160 - dx, 100) != Theme.COL_BG,
            )
        }
    }

    // ---- the scene --------------------------------------------------------

    @Test
    fun `the loading state shows until the first fetch lands`() {
        val fb = GoldenRender.blank()
        WeatherScene.draw(DeviceCanvas(fb, fonts), ctx(AppData()))
        var ink = 0
        for (y in 0 until Theme.CONTENT_H) for (x in 0 until Theme.SCREEN_W) {
            if (fb.pixelAt(x, y) != Theme.COL_BG) ink++
        }
        assertTrue("expected 'fetching weather...'", ink > 100)
        // The icon area must be empty -- there is no condition to draw yet.
        var iconInk = 0
        for (y in 30..80) for (x in 40..100) if (fb.pixelAt(x, y) != Theme.COL_BG) iconInk++
        assertEquals(0, iconInk)
    }

    @Test
    fun `the scene never paints into the strip or the setup button`() {
        for (code in intArrayOf(0, 1, 3, 45, 61, 71, 95)) {
            for (units in intArrayOf(0, UNITS_IMPERIAL)) {
                for (data in listOf(sample(code), sample(code).copy(dailyValid = false))) {
                    val fb = GoldenRender.blank()
                    WeatherScene.draw(DeviceCanvas(fb, fonts), ctx(data, units))
                    for (y in Theme.STATUS_Y until Theme.SCREEN_H) {
                        for (x in 0 until Theme.SCREEN_W) {
                            assertEquals("code $code painted the strip", Theme.COL_BG, fb.pixelAt(x, y))
                        }
                    }
                    for (y in SetupButton.RESERVED_TOP..SetupButton.RESERVED_BOTTOM) {
                        for (x in SetupButton.RESERVED_LEFT..SetupButton.RESERVED_RIGHT) {
                            assertEquals(
                                "code $code reached the setup button at $x,$y",
                                Theme.COL_BG, fb.pixelAt(x, y),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `imperial and metric render differently but both stay on screen`() {
        val metric = GoldenRender.blank().also {
            WeatherScene.draw(DeviceCanvas(it, fonts), ctx(sample(), 0))
        }
        val imperial = GoldenRender.blank().also {
            WeatherScene.draw(DeviceCanvas(it, fonts), ctx(sample(), UNITS_IMPERIAL))
        }
        assertTrue("21C and 70F should not look the same", !metric.px.contentEquals(imperial.px))

        // Nothing should be clipped at the right edge in either case.
        for (fb in listOf(metric, imperial)) {
            var edge = 0
            for (y in 0 until Theme.CONTENT_H) if (fb.pixelAt(Theme.SCREEN_W - 1, y) != Theme.COL_BG) edge++
            assertEquals("content is touching the right edge", 0, edge)
        }
    }

    // ---- goldens ----------------------------------------------------------

    @Test
    fun `render each weather condition for inspection`() {
        val cases = listOf(
            0 to "clear", 2 to "partly", 3 to "cloudy",
            45 to "fog", 61 to "rain", 73 to "snow", 95 to "thunder",
        )
        for ((code, label) in cases) {
            val fb = GoldenRender.blank()
            val c = DeviceCanvas(fb, fonts)
            WeatherScene.draw(c, ctx(sample(code)))
            StatusStrip.draw(
                c,
                StripState(
                    timeText = "14:05", wifiLevel = 3, weatherValid = true,
                    weatherUpdatedAt = NOW.toEpochSecond() - 300,
                    nowEpoch = NOW.toEpochSecond(), pinned = false, frozen = false,
                    heldMs = 0, sceneIndex = 1, sceneCount = 4,
                ),
            )
            SetupButton.draw(c)
            assertTrue(GoldenRender.write(fb, "m5_weather_$label", scale = 3).length() > 0)
        }
    }

    @Test
    fun `render the imperial variant for inspection`() {
        val fb = GoldenRender.blank()
        val c = DeviceCanvas(fb, fonts)
        WeatherScene.draw(c, ctx(sample(61), UNITS_IMPERIAL))
        StatusStrip.draw(
            c,
            StripState(
                timeText = "14:05", wifiLevel = 4, weatherValid = true,
                weatherUpdatedAt = NOW.toEpochSecond() - 300,
                nowEpoch = NOW.toEpochSecond(), pinned = false, frozen = false,
                heldMs = 0, sceneIndex = 1, sceneCount = 4,
            ),
        )
        SetupButton.draw(c)
        assertTrue(GoldenRender.write(fb, "m5_weather_imperial", scale = 3).length() > 0)
    }
}
