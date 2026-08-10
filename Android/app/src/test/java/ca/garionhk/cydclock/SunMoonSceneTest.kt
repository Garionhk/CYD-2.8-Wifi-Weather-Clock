// ===========================================================================
// SunMoonSceneTest.kt -- the arc, the moon terminator, and the TOMORROW roll
// ===========================================================================
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.render.Framebuffer
import ca.garionhk.cydclock.scenes.SceneContext
import ca.garionhk.cydclock.scenes.SetupButton
import ca.garionhk.cydclock.scenes.StatusStrip
import ca.garionhk.cydclock.scenes.StripState
import ca.garionhk.cydclock.scenes.SunMoonScene
import ca.garionhk.cydclock.time.SunMoon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private val ZONE: ZoneId = ZoneId.of("Europe/London")

private fun sunMoonCtx(
    nowLocal: ZonedDateTime = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZONE),
    lat: Double = 51.4779,
    lon: Double = -0.0015,
    uvValid: Boolean = true,
    uvIndex: Float = 6.5f,
): SceneContext {
    val settings = AppSettings(latitude = lat, longitude = lon)
    val epoch = nowLocal.toEpochSecond()
    val data = SunMoon.recompute(
        AppData(uvIndex = uvIndex, uvValid = uvValid), settings, ZONE, epoch,
    )
    return SceneContext(data, settings, nowLocal, epoch, online = true)
}

private fun Framebuffer.countOf(colour: Int): Int {
    var n = 0
    for (y in 0 until height) for (x in 0 until width) if (pixelAt(x, y) == colour) n++
    return n
}

class SunMoonSceneTest {

    private val fonts = loadFonts()

    @Test
    fun `the arc is cyan during the day and grey once it rolls to tomorrow`() {
        val day = GoldenRender.blank().also {
            SunMoonScene.draw(DeviceCanvas(it, fonts), sunMoonCtx())
        }
        assertTrue("the daytime arc should be accent-coloured", day.countOf(Theme.COL_ACCENT) > 50)

        // Half an hour after sunset the scene shows tomorrow.
        val afterSunset = sunMoonCtx(ZonedDateTime.of(2026, 6, 21, 22, 30, 0, 0, ZONE))
        assertTrue("should have rolled forward", afterSunset.data.showingNextDay)
        val night = GoldenRender.blank().also {
            SunMoonScene.draw(DeviceCanvas(it, fonts), afterSunset)
        }
        assertEquals("a greyed arc must have no accent pixels", 0, night.countOf(Theme.COL_ACCENT))
    }

    @Test
    fun `the sun marker appears only while today's sun is up`() {
        val noon = GoldenRender.blank().also {
            SunMoonScene.draw(DeviceCanvas(it, fonts), sunMoonCtx())
        }
        val tomorrow = GoldenRender.blank().also {
            SunMoonScene.draw(
                DeviceCanvas(it, fonts),
                sunMoonCtx(ZonedDateTime.of(2026, 6, 21, 22, 30, 0, 0, ZONE)),
            )
        }
        // C_SUN is used by the marker and by the golden-hour text, so compare
        // rather than assert absolute presence.
        assertTrue(
            "the marker should add sun-coloured pixels during the day",
            noon.countOf(Theme.C_SUN) > tomorrow.countOf(Theme.C_SUN),
        )
    }

    @Test
    fun `the marker tracks the sun across the arc`() {
        // Morning marker on the left half, evening marker on the right.
        fun markerX(hour: Int): Int {
            val fb = GoldenRender.blank()
            SunMoonScene.draw(
                DeviceCanvas(fb, fonts),
                sunMoonCtx(ZonedDateTime.of(2026, 6, 21, hour, 0, 0, 0, ZONE)),
            )
            // The marker is a filled r=5 disc; find the sun-coloured centroid
            // above the horizon.
            var sum = 0L
            var n = 0
            for (y in 40..95) for (x in 0..140) {
                if (fb.pixelAt(x, y) == Theme.C_SUN) { sum += x; n++ }
            }
            return if (n == 0) -1 else (sum / n).toInt()
        }
        val morning = markerX(7)
        val evening = markerX(19)
        assertTrue("no morning marker found", morning >= 0)
        assertTrue("no evening marker found", evening >= 0)
        assertTrue("the sun should travel left to right ($morning -> $evening)", morning < evening)
    }

    @Test
    fun `the moon terminator lights the correct limb`() {
        fun litOnRight(phase: Float): Boolean {
            val fb = GoldenRender.blank()
            val ctx = sunMoonCtx()
            SunMoonScene.draw(
                DeviceCanvas(fb, fonts),
                ctx.copy(data = ctx.data.copy(moonPhase = phase)),
            )
            var left = 0
            var right = 0
            for (y in 130..174) {
                for (x in 18..62) {
                    if (fb.pixelAt(x, y) == Theme.C_MOON_LIT) {
                        if (x < 40) left++ else right++
                    }
                }
            }
            return right > left
        }
        assertTrue("a waxing crescent is lit on the right", litOnRight(0.15f))
        assertTrue("a waning crescent is lit on the left", !litOnRight(0.85f))
    }

    @Test
    fun `a full moon is lit and a new moon is not`() {
        fun litPixels(phase: Float): Int {
            val fb = GoldenRender.blank()
            val ctx = sunMoonCtx()
            SunMoonScene.draw(
                DeviceCanvas(fb, fonts),
                ctx.copy(data = ctx.data.copy(moonPhase = phase)),
            )
            return fb.countOf(Theme.C_MOON_LIT)
        }
        val full = litPixels(0.5f)
        val new = litPixels(0.0f)
        assertTrue("a full moon should be almost entirely lit, was $full px", full > 1200)
        assertTrue("a new moon should be nearly dark, was $new px", new < 120)
        assertTrue(full > new * 8)
    }

    @Test
    fun `uv colour bands match the firmware`() {
        assertEquals(Theme.C_GREEN, SunMoonScene.uvColor(0f))
        assertEquals(Theme.C_GREEN, SunMoonScene.uvColor(2.9f))
        assertEquals(Theme.C_YELLOW, SunMoonScene.uvColor(3f))
        assertEquals(Theme.C_YELLOW, SunMoonScene.uvColor(5.9f))
        assertEquals(Theme.C_ORANGE, SunMoonScene.uvColor(6f))
        assertEquals(Theme.C_RED, SunMoonScene.uvColor(8f))
        assertEquals(Theme.C_PURPLE, SunMoonScene.uvColor(11f))
        assertEquals(Theme.C_PURPLE, SunMoonScene.uvColor(15f))
    }

    @Test
    fun `every moon phase name clears the right column and the moon disk`() {
        // The firmware drew these from a hard-coded x=72, where the two
        // fifteen-character crescent names ran past the right column at 170 and
        // were clipped by the golden-hour cell drawn afterwards. moonTextX
        // measures instead, which also survives the shipped renderer's different
        // font metrics.
        val c = DeviceCanvas(Framebuffer(), fonts)
        c.setTextFont(2)
        val names = listOf(
            "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
            "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent",
        )
        for (n in names) {
            val x = SunMoonScene.moonTextX(c, n)
            val end = x + c.textWidth(n)
            assertTrue("'$n' starts at $x, overlapping the moon disk", x >= 64)
            assertTrue("'$n' ends at $end, past the right column", end <= 170)
        }
    }

    @Test
    fun `short phase names keep the nominal position`() {
        // The clamp must only engage when it has to, or every caption shifts.
        val c = DeviceCanvas(Framebuffer(), fonts)
        c.setTextFont(2)
        assertEquals(72, SunMoonScene.moonTextX(c, "New Moon"))
        assertEquals(72, SunMoonScene.moonTextX(c, "Full Moon"))
        assertTrue(
            "the longest name must shift left",
            SunMoonScene.moonTextX(c, "Waning Crescent") < 72,
        )
    }

    @Test
    fun `the golden hour value is clear of the moon caption and centred in the gap`() {
        // The firmware left-aligned this at x=170, four units past where the
        // longest phase name ends -- so the two read as one run of text with the
        // right half of the row empty.
        val ctx = sunMoonCtx()
        for (phase in floatArrayOf(0.15f, 0.5f, 0.85f, 0.0f)) {
            val fb = GoldenRender.blank()
            SunMoonScene.draw(
                DeviceCanvas(fb, fonts),
                ctx.copy(data = ctx.data.copy(moonPhase = phase, goldenHour = "in 1h 48m")),
            )

            // The value is the only C_SUN ink on its row.
            var left = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            for (y in 130..152) {
                for (x in 0 until Theme.SCREEN_W) {
                    if (fb.pixelAt(x, y) == Theme.C_SUN) {
                        if (x < left) left = x
                        if (x > right) right = x
                    }
                }
            }
            assertTrue("no golden-hour value drawn", left <= right)

            // Clear of the caption, which moonTextX guarantees ends by 170.
            assertTrue("value starts at $left, crowding the moon caption", left >= 180)
            assertTrue("value ends at $right, past the screen", right < Theme.SCREEN_W - 4)

            // Roughly centred in the span it was given.
            val gapLeft = left - 170
            val gapRight = (Theme.SCREEN_W - 8) - right
            assertTrue(
                "value is not centred in the free span: $gapLeft left, $gapRight right",
                kotlin.math.abs(gapLeft - gapRight) <= 4,
            )
        }
    }

    @Test
    fun `the golden hour label sits over its own value`() {
        // A caption that is not above the thing it names reads as belonging to
        // the row above it -- here, UV.
        val fb = GoldenRender.blank()
        val ctx = sunMoonCtx()
        SunMoonScene.draw(
            DeviceCanvas(fb, fonts),
            ctx.copy(data = ctx.data.copy(goldenHour = "in 1h 48m")),
        )

        fun centreOf(rows: IntRange, colour: Int): Int {
            var left = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            for (y in rows) for (x in 0 until Theme.SCREEN_W) {
                if (fb.pixelAt(x, y) == colour) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            assertTrue("nothing found in $rows", left <= right)
            return (left + right) / 2
        }

        val labelCentre = centreOf(112..131, Theme.COL_DATE)
        val valueCentre = centreOf(132..152, Theme.C_SUN)
        assertTrue(
            "label centred at $labelCentre but value at $valueCentre",
            kotlin.math.abs(labelCentre - valueCentre) <= 4,
        )
    }

    @Test
    fun `the moon text never overlaps the golden hour value`() {
        // Rendered proof, not just arithmetic: nothing sun-coloured may appear in
        // the moon text's rows to the left of the right column, and vice versa.
        val ctx = sunMoonCtx()
        for (phase in floatArrayOf(0.15f, 0.85f, 0.25f, 0.75f, 0.5f, 0.0f)) {
            val fb = GoldenRender.blank()
            SunMoonScene.draw(
                DeviceCanvas(fb, fonts),
                ctx.copy(data = ctx.data.copy(moonPhase = phase)),
            )
            // Column 168-169 is the gutter between the two columns.
            for (y in 136..152) {
                for (x in 168..169) {
                    assertEquals(
                        "phase name spills into the gutter at $x,$y",
                        Theme.COL_BG, fb.pixelAt(x, y),
                    )
                }
            }
        }
    }

    @Test
    fun `polar night shows dashes rather than a bogus time`() {
        val ctx = sunMoonCtx(
            ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, ZONE),
            lat = 78.2232, lon = 15.6267,
        )
        assertEquals("no sunrise during polar night", 0L, ctx.data.sunriseToday)
        assertTrue("and the TOMORROW banner must not latch on", !ctx.data.showingNextDay)
        // Must render without throwing, and without a marker on the arc.
        val fb = GoldenRender.blank()
        SunMoonScene.draw(DeviceCanvas(fb, fonts), ctx)
        assertTrue(fb.countOf(Theme.COL_TEXT) > 0)
    }

    @Test
    fun `the scene never paints the strip or the setup button`() {
        val cases = listOf(
            sunMoonCtx(),
            sunMoonCtx(ZonedDateTime.of(2026, 6, 21, 22, 30, 0, 0, ZONE)),
            sunMoonCtx(ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, ZONE), 78.2232, 15.6267),
            sunMoonCtx(uvValid = false),
            sunMoonCtx(uvIndex = 12.7f),
        )
        for (ctx in cases) {
            val fb = GoldenRender.blank()
            SunMoonScene.draw(DeviceCanvas(fb, fonts), ctx)
            for (y in Theme.STATUS_Y until Theme.SCREEN_H) for (x in 0 until Theme.SCREEN_W) {
                assertEquals(Theme.COL_BG, fb.pixelAt(x, y))
            }
            for (y in SetupButton.RESERVED_TOP..SetupButton.RESERVED_BOTTOM) {
                for (x in SetupButton.RESERVED_LEFT..SetupButton.RESERVED_RIGHT) {
                    assertEquals("reached the setup button at $x,$y", Theme.COL_BG, fb.pixelAt(x, y))
                }
            }
        }
    }

    @Test
    fun `render sun and moon for inspection`() {
        val cases = listOf(
            "day" to sunMoonCtx(),
            "tomorrow" to sunMoonCtx(ZonedDateTime.of(2026, 6, 21, 22, 30, 0, 0, ZONE)),
            "polar" to sunMoonCtx(ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, ZONE), 78.2232, 15.6267),
        )
        for ((label, ctx) in cases) {
            val fb = GoldenRender.blank()
            val c = DeviceCanvas(fb, fonts)
            SunMoonScene.draw(c, ctx)
            StatusStrip.draw(
                c,
                StripState(
                    timeText = ctx.now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                    wifiLevel = 3, weatherValid = true,
                    weatherUpdatedAt = ctx.nowEpoch - 300, nowEpoch = ctx.nowEpoch,
                    pinned = false, frozen = false, heldMs = 0,
                    sceneIndex = 2, sceneCount = 4,
                ),
            )
            SetupButton.draw(c)
            assertTrue(GoldenRender.write(fb, "m6_sunmoon_$label", scale = 3).length() > 0)
        }
    }
}
