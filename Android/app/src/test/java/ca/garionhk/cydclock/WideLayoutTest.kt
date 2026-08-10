// ===========================================================================
// WideLayoutTest.kt -- scenes must use a wider grid, not sit in a puddle in it
// ===========================================================================
// Fill mode widens the grid rather than scaling past the screen edges. Centring
// the old 320-wide layout inside that would have left a third of a 20:9 phone
// black down each side, so the scenes spread into it.
//
// 533 is the grid width fill mode produces on a 2400x1080 phone.
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.render.Framebuffer
import ca.garionhk.cydclock.scenes.AirQualityScene
import ca.garionhk.cydclock.scenes.ClockScene
import ca.garionhk.cydclock.scenes.Scene
import ca.garionhk.cydclock.scenes.SceneContext
import ca.garionhk.cydclock.scenes.SetupButton
import ca.garionhk.cydclock.scenes.StatusStrip
import ca.garionhk.cydclock.scenes.StripState
import ca.garionhk.cydclock.scenes.SunMoonScene
import ca.garionhk.cydclock.scenes.WeatherScene
import ca.garionhk.cydclock.time.SunMoon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private const val WIDE = 533          // 2400x1080 in fill mode
private val ZONE: ZoneId = ZoneId.of("Europe/London")
private val NOW: ZonedDateTime = ZonedDateTime.of(2026, 8, 9, 14, 5, 0, 0, ZONE)

private fun populated(): AppData {
    val base = AppData(
        tempC = 19.4f, feelsLikeC = 18.8f, tempMaxC = 24.2f, tempMinC = 13.7f,
        dailyValid = true, weatherCode = 3, cloudCoverPct = 88, humidityPct = 64,
        windKph = 14.8f, pressureHpa = 1012.6f, pressureTrend = 0.8f,
        uvIndex = 5.4f, uvValid = true, weatherValid = true,
        weatherUpdatedAt = NOW.toEpochSecond() - 300,
        aqi = 38, pm25 = 10, aqiValid = true, aqiUpdatedAt = NOW.toEpochSecond() - 600,
    )
    return SunMoon.recompute(base, AppSettings(), ZONE, NOW.toEpochSecond())
}

private fun ctx(width: Int) = SceneContext(
    data = populated(),
    settings = AppSettings(),
    now = NOW,
    nowEpoch = NOW.toEpochSecond(),
    online = true,
    width = width,
)

private val scenes: List<Scene> = listOf(ClockScene, WeatherScene, SunMoonScene, AirQualityScene)

/** Columns that carry any ink above the status strip. */
private fun Framebuffer.inkColumns(): List<Int> =
    (0 until width).filter { x ->
        (0 until Theme.STATUS_Y).any { y -> pixelAt(x, y) != Theme.COL_BG }
    }

class WideLayoutTest {

    private val fonts = loadFonts()

    private fun render(scene: Scene, width: Int): Framebuffer {
        val fb = Framebuffer(width, Theme.SCREEN_H)
        fb.fillScreen(Theme.COL_BG)
        scene.draw(DeviceCanvas(fb, fonts), ctx(width))
        return fb
    }

    @Test
    fun `a 320 grid is byte-identical to before -- the firmware layout is untouched`() {
        // sx() is the identity at 320. This is the guarantee that making the
        // scenes width-aware did not quietly reflow the 4:3 layout.
        for (scene in scenes) {
            val fb = render(scene, Theme.SCREEN_W)
            val cols = fb.inkColumns()
            assertTrue("${scene.name} drew nothing", cols.isNotEmpty())
            assertTrue("${scene.name} painted outside the grid", cols.all { it < Theme.SCREEN_W })
        }
    }

    @Test
    fun `every scene stays inside a wide grid`() {
        for (scene in scenes) {
            val fb = render(scene, WIDE)
            val cols = fb.inkColumns()
            assertTrue("${scene.name} drew nothing", cols.isNotEmpty())
            assertTrue("${scene.name} painted past the right edge", cols.max() < WIDE)
            assertTrue("${scene.name} painted past the left edge", cols.min() >= 0)
        }
    }

    @Test
    fun `data scenes spread into the extra width instead of bunching in the middle`() {
        // The failure this pins: a centred 320-wide island on a 533 grid leaves
        // 106 units of dead black down each side. Content must reach the outer
        // fifths.
        val leftFifth = WIDE / 5
        val rightFifth = WIDE - WIDE / 5

        for (scene in listOf(WeatherScene, SunMoonScene, AirQualityScene)) {
            val cols = render(scene, WIDE).inkColumns()
            assertTrue(
                "${scene.name} leaves the left of a wide screen empty (starts at ${cols.min()})",
                cols.min() < leftFifth,
            )
            assertTrue(
                "${scene.name} leaves the right of a wide screen empty (ends at ${cols.max()})",
                cols.max() > rightFifth,
            )
        }
    }

    @Test
    fun `the clock stays a tight block and is centred`() {
        // The four digits are one number. Spreading them would render "03 : 23"
        // with holes in it, so this scene re-centres rather than spreads.
        val cols = render(ClockScene, WIDE).inkColumns()
        val left = cols.min()
        val right = cols.max()
        val margins = left to (WIDE - 1 - right)
        assertTrue(
            "clock is not centred: ${margins.first} left, ${margins.second} right",
            kotlin.math.abs(margins.first - margins.second) <= 4,
        )
        // And it must NOT have spread to the edges.
        assertTrue("clock digits should not spread across a wide screen", left > WIDE / 8)
    }

    @Test
    fun `no scene paints the status strip or the setup button at any width`() {
        for (width in intArrayOf(320, 400, WIDE, 640)) {
            for (scene in scenes) {
                val fb = render(scene, width)
                for (y in Theme.STATUS_Y until Theme.SCREEN_H) {
                    for (x in 0 until width) {
                        assertEquals(
                            "${scene.name} at width $width painted the strip",
                            Theme.COL_BG, fb.pixelAt(x, y),
                        )
                    }
                }
                for (y in SetupButton.RESERVED_TOP..SetupButton.RESERVED_BOTTOM) {
                    for (x in SetupButton.reservedLeft(width)..SetupButton.reservedRight(width)) {
                        assertEquals(
                            "${scene.name} at width $width reached the setup button",
                            Theme.COL_BG, fb.pixelAt(x, y),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the moon caption still clears the right column on a wide grid`() {
        val c = DeviceCanvas(Framebuffer(WIDE, Theme.SCREEN_H), fonts)
        c.setTextFont(2)
        for (n in listOf("New Moon", "Waxing Crescent", "Waning Crescent", "Waning Gibbous")) {
            val x = SunMoonScene.moonTextX(c, n, WIDE)
            val end = x + c.textWidth(n)
            // The right column starts at sx(170) on this grid.
            val columnX = 170 * WIDE / Theme.SCREEN_W
            assertTrue("'$n' ends at $end, into the column at $columnX", end <= columnX)
        }
    }

    @Test
    fun `content margins stay proportional at every real phone ratio`() {
        // "Space evenly at all ratios" concretely means: the share of the width
        // left blank at each side should not grow as the screen gets wider. A
        // centred fixed-width layout fails this badly -- on a 21:9 phone it would
        // leave 22% blank down each side.
        //
        // Grid widths are what computeLetterbox produces in fill mode for each
        // real device, at scale = height / 240.
        val grids = listOf(
            "4:3" to 320,
            "16:10 tablet" to 384,
            "16:9" to 426,
            "19.5:9" to 520,
            "20:9 (S20 FE)" to 533,
            "21:9" to 560,
        )

        for (scene in listOf(WeatherScene, SunMoonScene, AirQualityScene)) {
            for ((name, width) in grids) {
                val cols = render(scene, width).inkColumns()
                val leftPct = cols.min() * 100 / width
                val rightPct = (width - 1 - cols.max()) * 100 / width
                assertTrue(
                    "${scene.name} on $name leaves $leftPct% blank at the left",
                    leftPct <= 16,
                )
                assertTrue(
                    "${scene.name} on $name leaves $rightPct% blank at the right",
                    rightPct <= 16,
                )
            }
        }
    }

    @Test
    fun `stat columns land where an even distribution puts them`() {
        // The Air Quality row is four cells. Measured on the rendered pixels so
        // this describes what is on screen, not what the constants say.
        val fb = render(AirQualityScene, WIDE)
        // Each cell's ink, found by splitting the row into four bands.
        val row = 140..162
        val centres = (0 until 4).map { i ->
            val from = i * WIDE / 4
            val until = (i + 1) * WIDE / 4
            val cols = (from until until).filter { x ->
                row.any { y -> fb.pixelAt(x, y) != Theme.COL_BG }
            }
            assertTrue("no ink in cell $i", cols.isNotEmpty())
            (cols.min() + cols.max()) / 2
        }
        // Gaps between adjacent cells should be close to equal.
        //
        // Not exactly equal, for two reasons that are both by design: the
        // firmware's own anchors were 42/116/190/268, so the last gap was
        // already 78 against 74, and the pressure cell carries a trend glyph to
        // the right of its value which pulls its measured centre out. The
        // tolerance describes that rather than pretending the row is perfectly
        // regular.
        val gaps = centres.zipWithNext { a, b -> b - a }
        assertTrue(
            "stat cells are not evenly spaced: centres $centres, gaps $gaps",
            gaps.max() - gaps.min() <= 16,
        )
    }

    @Test
    fun `render every scene wide for inspection`() {
        for (scene in scenes) {
            val fb = Framebuffer(WIDE, Theme.SCREEN_H)
            fb.fillScreen(Theme.COL_BG)
            val c = DeviceCanvas(fb, fonts)
            scene.draw(c, ctx(WIDE))
            StatusStrip.draw(
                c,
                StripState(
                    timeText = "14:05", wifiLevel = 3, weatherValid = true,
                    weatherUpdatedAt = NOW.toEpochSecond() - 300,
                    nowEpoch = NOW.toEpochSecond(), pinned = false, frozen = false,
                    heldMs = 0, sceneIndex = scenes.indexOf(scene), sceneCount = 4,
                ),
                WIDE,
            )
            SetupButton.draw(c, WIDE)
            val name = scene.name.lowercase().replace(" & ", "_").replace(" ", "_")
            assertTrue(GoldenRender.write(fb, "wide_$name", scale = 2).length() > 0)
        }
    }
}
