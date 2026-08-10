// ===========================================================================
// SetupButtonCollisionTest.kt -- decides where the setup button may live
// ===========================================================================
// The clearance in the top-right corner is one pixel, so this is a test rather
// than a comment. Every scene is rasterised across a matrix of data states and
// the reserved rectangle is asserted empty BEFORE the button is composited.
//
// The Sun & Moon scene is still a placeholder at M4, so its binding widget --
// the sunrise value -- is reproduced here directly from the firmware's
// coordinates. When the real scene lands in M6 it inherits the same assertion
// through `allScenes`.
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.data.UNITS_IMPERIAL
import ca.garionhk.cydclock.render.Datum
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.render.Framebuffer
import ca.garionhk.cydclock.scenes.ClockScene
import ca.garionhk.cydclock.scenes.PlaceholderScene
import ca.garionhk.cydclock.scenes.Scene
import ca.garionhk.cydclock.scenes.SceneContext
import ca.garionhk.cydclock.scenes.SetupButton
import ca.garionhk.cydclock.scenes.StatusStrip
import ca.garionhk.cydclock.scenes.StripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private val allScenes: List<Scene> = listOf(
    ClockScene,
    PlaceholderScene("Weather"),
    PlaceholderScene("Sun & Moon"),
    PlaceholderScene("Air Quality"),
)

/** Worst cases for width: long values, negatives, extra digits, every flag on. */
private fun dataMatrix(): List<AppData> = listOf(
    AppData(),
    AppData(
        weatherValid = true, dailyValid = true, uvValid = true, aqiValid = true,
        tempC = -18.6f, feelsLikeC = -25.4f, tempMaxC = -10.2f, tempMinC = -22.9f,
        weatherCode = 95, cloudCoverPct = 100, humidityPct = 100,
        windKph = 128.4f, pressureHpa = 1043.7f, pressureTrend = 1.2f,
        aqi = 1250, pm25 = 999, uvIndex = 12.5f,
        weatherUpdatedAt = 1_000_000L,
    ),
    AppData(
        weatherValid = true, dailyValid = true, uvValid = true, aqiValid = true,
        tempC = 41.8f, feelsLikeC = 48.2f, tempMaxC = 44.0f, tempMinC = 30.5f,
        weatherCode = 0, cloudCoverPct = 0, humidityPct = 8,
        windKph = 3.2f, pressureHpa = 968.1f, pressureTrend = -0.9f,
        aqi = 7, pm25 = 2, uvIndex = 0.4f,
        showingNextDay = true, moonPhase = 0.5f, moonIlluminationPct = 100f,
        weatherUpdatedAt = 1_000_000L,
    ),
)

private fun contexts(): List<SceneContext> = buildList {
    val zone = ZoneId.of("Europe/London")
    for (data in dataMatrix()) {
        for (units in intArrayOf(0, UNITS_IMPERIAL)) {
            for (online in booleanArrayOf(true, false)) {
                // 23:58 exercises the widest clock digits and the longest date.
                val now = ZonedDateTime.of(2026, 12, 31, 23, 58, 0, 0, zone)
                add(
                    SceneContext(
                        data = data,
                        settings = AppSettings(units = units),
                        now = now,
                        nowEpoch = now.toEpochSecond(),
                        online = online,
                    )
                )
            }
        }
    }
}

private fun Framebuffer.reservedInk(): List<String> = buildList {
    for (y in SetupButton.RESERVED_TOP..SetupButton.RESERVED_BOTTOM) {
        for (x in SetupButton.RESERVED_LEFT..SetupButton.RESERVED_RIGHT) {
            if (pixelAt(x, y) != Theme.COL_BG) add("$x,$y")
        }
    }
}

class SetupButtonCollisionTest {

    private val fonts = loadFonts()

    @Test
    fun `no scene paints inside the reserved rectangle`() {
        for (scene in allScenes) {
            for (ctx in contexts()) {
                val fb = GoldenRender.blank()
                scene.draw(DeviceCanvas(fb, fonts), ctx)
                val ink = fb.reservedInk()
                assertTrue(
                    "scene '${scene.name}' painted into the setup button's rect at $ink",
                    ink.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `the sun and moon sunrise value clears the reserved rectangle`() {
        // The binding constraint, reproduced from scenes.cpp: right column rx=170,
        // values at rx+62 = 232 in Font 4 with an ML datum, sunrise at y=34.
        // Checked directly so M6 cannot regress it silently.
        val fb = GoldenRender.blank()
        val c = DeviceCanvas(fb, fonts)
        c.setTextColor(Theme.COL_TEXT, Theme.COL_BG)
        c.setTextDatum(Datum.ML)
        c.setTextFont(4)
        c.drawString("05:42", 232, 34)     // sunrise
        c.drawString("21:21", 232, 64)     // sunset
        c.drawString("12", 232, 94)        // UV

        assertTrue(
            "the sunrise row reaches the setup button at ${fb.reservedInk()}",
            fb.reservedInk().isEmpty(),
        )
    }

    @Test
    fun `the status strip clears the reserved rectangle`() {
        // Belt and braces: the strip lives at y >= 196, so it cannot reach the
        // corner, but the scene dots are the widest strip widget and worth pinning.
        val fb = GoldenRender.blank()
        StatusStrip.draw(
            DeviceCanvas(fb, fonts),
            StripState(
                timeText = "23:58", wifiLevel = 4, weatherValid = true,
                weatherUpdatedAt = 1L, nowEpoch = 2L, pinned = true, frozen = true,
                heldMs = 0, sceneIndex = 3, sceneCount = 4,
            ),
        )
        assertTrue(fb.reservedInk().isEmpty())
    }

    @Test
    fun `the glyph stays inside its reserved rectangle and on screen`() {
        val fb = GoldenRender.blank()
        SetupButton.draw(DeviceCanvas(fb, fonts))

        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        var ink = 0
        for (y in 0 until fb.height) {
            for (x in 0 until fb.width) {
                if (fb.pixelAt(x, y) != Theme.COL_BG) {
                    ink++
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }
        assertTrue("the gear drew nothing", ink > 0)
        assertTrue("gear starts at x=$minX, must be >= ${SetupButton.RESERVED_LEFT}",
            minX >= SetupButton.RESERVED_LEFT)
        assertTrue("gear ends at x=$maxX, must be <= ${SetupButton.RESERVED_RIGHT}",
            maxX <= SetupButton.RESERVED_RIGHT)
        assertTrue("gear ends at y=$maxY, must be <= ${SetupButton.RESERVED_BOTTOM}",
            maxY <= SetupButton.RESERVED_BOTTOM)
        assertTrue("gear must stay on screen", maxX < Theme.SCREEN_W && minY >= 0)
    }

    @Test
    fun `the hit rect covers the glyph and nothing outside the corner`() {
        assertTrue(SetupButton.hits(SetupButton.CX, SetupButton.CY))
        assertTrue("the whole glyph must be tappable",
            SetupButton.hits(SetupButton.RESERVED_LEFT, SetupButton.RESERVED_TOP))
        assertTrue(SetupButton.hits(SetupButton.RESERVED_RIGHT, SetupButton.RESERVED_BOTTOM))

        // Must not swallow presses meant for the scene body or the strip.
        assertTrue(!SetupButton.hits(160, 100))
        assertTrue(!SetupButton.hits(300, Theme.STATUS_CY))   // the scene dots
        assertTrue(!SetupButton.hits(231, 34))                // the sunrise value
        assertTrue(!SetupButton.hits(0, 0))
    }

    @Test
    fun `render every scene with the button composited`() {
        val zone = ZoneId.of("Europe/London")
        val now = ZonedDateTime.of(2026, 8, 8, 14, 5, 0, 0, zone)
        allScenes.forEachIndexed { i, scene ->
            val fb = GoldenRender.blank()
            val c = DeviceCanvas(fb, fonts)
            scene.draw(
                c,
                SceneContext(AppData(), AppSettings(), now, now.toEpochSecond(), online = true),
            )
            StatusStrip.draw(
                c,
                StripState(
                    timeText = "14:05", wifiLevel = 3, weatherValid = false,
                    weatherUpdatedAt = 0, nowEpoch = now.toEpochSecond(),
                    pinned = false, frozen = false, heldMs = 0,
                    sceneIndex = i, sceneCount = 4,
                ),
            )
            SetupButton.draw(c)
            assertEquals(
                "scene $i should render",
                true,
                GoldenRender.write(fb, "m4_scene${i}_with_button", scale = 3).length() > 0,
            )
        }
    }
}
