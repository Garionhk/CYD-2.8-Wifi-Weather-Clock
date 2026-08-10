// ===========================================================================
// ClockSceneTest.kt -- clock scene + status strip, rendered and inspected
// ===========================================================================
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.scenes.ClockScene
import ca.garionhk.cydclock.scenes.SceneContext
import ca.garionhk.cydclock.scenes.StatusStrip
import ca.garionhk.cydclock.scenes.StripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private val ZONE: ZoneId = ZoneId.of("Europe/London")

private fun ctxAt(h: Int, m: Int, s: Int, online: Boolean = true): SceneContext {
    val now = ZonedDateTime.of(2026, 8, 8, h, m, s, 0, ZONE)
    return SceneContext(
        data = AppData(),
        settings = AppSettings(),
        now = now,
        nowEpoch = now.toEpochSecond(),
        online = online,
    )
}

private fun strip(
    pinned: Boolean = false,
    frozen: Boolean = false,
    heldMs: Long = 0,
    bars: Int = 3,
    index: Int = 0,
    weatherValid: Boolean = true,
    ageSecs: Long = 300,
) = StripState(
    timeText = "14:05",
    wifiLevel = bars,
    weatherValid = weatherValid,
    weatherUpdatedAt = 1_000_000L,
    nowEpoch = 1_000_000L + ageSecs,
    pinned = pinned,
    frozen = frozen,
    heldMs = heldMs,
    sceneIndex = index,
    sceneCount = 4,
)

class ClockSceneTest {

    private val fonts = loadFonts()

    @Test
    fun `clock digits land in their cells`() {
        val fb = GoldenRender.blank()
        ClockScene.draw(DeviceCanvas(fb, fonts), ctxAt(14, 5, 0))

        // Font 8 digits are 55 px in a 60 px cell, MC datum, so each digit's ink
        // must sit inside its own cell and never bleed into the next.
        for (i in 0 until 4) {
            val x0 = Theme.DIGIT_X[i]
            var ink = 0
            for (y in Theme.DIGIT_TOP_Y until Theme.DIGIT_TOP_Y + Theme.DIGIT_H) {
                for (x in x0 until x0 + Theme.DIGIT_W) {
                    if (fb.pixelAt(x, y) == Theme.COL_TIME) ink++
                }
            }
            assertTrue("digit cell $i had no ink", ink > 0)
        }
    }

    @Test
    fun `colon blinks on even seconds`() {
        val on = GoldenRender.blank().also {
            ClockScene.draw(DeviceCanvas(it, fonts), ctxAt(14, 5, 0))
        }
        val off = GoldenRender.blank().also {
            ClockScene.draw(DeviceCanvas(it, fonts), ctxAt(14, 5, 1))
        }
        val cy = Theme.DIGIT_TOP_Y + 26
        assertEquals(Theme.COL_TIME, on.pixelAt(Theme.COLON_X, cy))
        assertEquals(Theme.COL_BG, off.pixelAt(Theme.COLON_X, cy))
        assertEquals(Theme.COL_TIME, on.pixelAt(Theme.COLON_X, Theme.DIGIT_TOP_Y + 56))
    }

    @Test
    fun `content never intrudes on the status strip`() {
        // The strip is drawn after the scene, but a scene that painted below
        // STATUS_Y would still be a bug -- it would flicker for one frame and
        // would break any future partial-redraw optimisation.
        val fb = GoldenRender.blank()
        ClockScene.draw(DeviceCanvas(fb, fonts), ctxAt(23, 59, 0))
        for (y in Theme.STATUS_Y until Theme.SCREEN_H) {
            for (x in 0 until Theme.SCREEN_W) {
                assertEquals("scene painted at $x,$y, inside the strip", Theme.COL_BG, fb.pixelAt(x, y))
            }
        }
    }

    @Test
    fun `offline footer appears only when offline`() {
        val onlineFb = GoldenRender.blank().also {
            ClockScene.draw(DeviceCanvas(it, fonts), ctxAt(14, 5, 0, online = true))
        }
        val offlineFb = GoldenRender.blank().also {
            ClockScene.draw(DeviceCanvas(it, fonts), ctxAt(14, 5, 0, online = false))
        }
        fun inkInFooter(fb: ca.garionhk.cydclock.render.Framebuffer): Int {
            var n = 0
            for (y in Theme.NET_Y - 8 until Theme.NET_Y + 8) {
                for (x in 0 until Theme.SCREEN_W) if (fb.pixelAt(x, y) != Theme.COL_BG) n++
            }
            return n
        }
        assertEquals("online should print nothing in the footer", 0, inkInFooter(onlineFb))
        assertTrue("offline should print something", inkInFooter(offlineFb) > 0)
    }

    @Test
    fun `date line uses two spaces after the weekday`() {
        // strftime("%a  %d %b %Y"). The double space is the firmware's, and it is
        // visible on the panel, so it is part of the layout.
        val fmt = java.time.format.DateTimeFormatter
            .ofPattern("EEE  dd MMM yyyy", java.util.Locale.ENGLISH)
        assertEquals("Sat  08 Aug 2026", ZonedDateTime.of(2026, 8, 8, 0, 0, 0, 0, ZONE).format(fmt))
    }

    // ---- status strip -----------------------------------------------------

    @Test
    fun `freshness dot follows the age bands`() {
        fun colourAt(valid: Boolean, age: Long): Int {
            val fb = GoldenRender.blank()
            StatusStrip.draw(DeviceCanvas(fb, fonts), strip(weatherValid = valid, ageSecs = age))
            return fb.pixelAt(152, Theme.STATUS_CY)
        }
        assertEquals(Theme.COL_FRESH_NONE, colourAt(false, 0))
        assertEquals(Theme.COL_FRESH_OK, colourAt(true, 1799))
        assertEquals(Theme.COL_FRESH_WARN, colourAt(true, 1800))
        assertEquals(Theme.COL_FRESH_WARN, colourAt(true, 7199))
        assertEquals(Theme.COL_FRESH_OLD, colourAt(true, 7200))
    }

    @Test
    fun `wifi bars follow the firmware rssi thresholds`() {
        assertEquals(0, StatusStrip.wifiLevelFromRssi(online = false, rssi = -50))
        assertEquals(4, StatusStrip.wifiLevelFromRssi(true, -60))
        assertEquals(3, StatusStrip.wifiLevelFromRssi(true, -61))
        assertEquals(3, StatusStrip.wifiLevelFromRssi(true, -68))
        assertEquals(2, StatusStrip.wifiLevelFromRssi(true, -69))
        assertEquals(2, StatusStrip.wifiLevelFromRssi(true, -75))
        assertEquals(1, StatusStrip.wifiLevelFromRssi(true, -76))
        // Online with no usable RSSI reads as full, not empty.
        assertEquals(4, StatusStrip.wifiLevelFromRssi(true, null))
        assertEquals(4, StatusStrip.wifiLevelFromRssi(true, -127))
    }

    @Test
    fun `pin glyph previews the hold before release`() {
        fun glyphColour(pinned: Boolean, held: Long): Int {
            val fb = GoldenRender.blank()
            StatusStrip.draw(DeviceCanvas(fb, fonts), strip(pinned = pinned, heldMs = held))
            return fb.pixelAt(188, Theme.STATUS_CY - 4)     // centre of the head
        }
        assertEquals("idle and unpinned shows nothing", Theme.COL_STRIP_BG, glyphColour(false, 0))
        assertEquals("pinned shows orange", Theme.COL_PIN, glyphColour(true, 0))
        assertEquals("past 800 ms: release would pin", Theme.COL_DIM, glyphColour(false, 800))
        assertEquals("past 4 s: release opens settings", Theme.COL_FREEZE, glyphColour(false, 4000))
    }

    @Test
    fun `scene dots mark the current scene`() {
        for (idx in 0 until 4) {
            val fb = GoldenRender.blank()
            StatusStrip.draw(DeviceCanvas(fb, fonts), strip(index = idx))
            for (i in 0 until 4) {
                val cx = 300 - (3 - i) * 16
                val centre = fb.pixelAt(cx, Theme.STATUS_CY)
                if (i == idx) assertEquals("dot $i should be filled", Theme.COL_ACCENT, centre)
                else assertEquals("dot $i should be hollow", Theme.COL_STRIP_BG, centre)
            }
        }
    }

    // ---- goldens ----------------------------------------------------------

    @Test
    fun `render the clock scene for inspection`() {
        val fb = GoldenRender.blank()
        val c = DeviceCanvas(fb, fonts)
        ClockScene.draw(c, ctxAt(14, 5, 0, online = true))
        StatusStrip.draw(c, strip(bars = 3, index = 0))
        assertTrue(GoldenRender.write(fb, "m3_clock", scale = 3).length() > 0)
    }

    @Test
    fun `render the strip states and a placeholder scene`() {
        val fb = GoldenRender.blank()
        val c = DeviceCanvas(fb, fonts)
        ca.garionhk.cydclock.scenes.PlaceholderScene("Weather").draw(c, ctxAt(14, 5, 0))
        StatusStrip.draw(c, strip(pinned = true, frozen = true, index = 1, bars = 4, ageSecs = 8000))
        assertTrue(GoldenRender.write(fb, "m3_placeholder_pinned", scale = 3).length() > 0)
    }
}
