// ===========================================================================
// FontMetricsTest.kt -- pins font metrics and renders a specimen sheet
// ===========================================================================
// Every x-position in the app is derived from textWidth(), so these numbers are
// the layout. Pinning them means a later change to the extractor or the decoder
// shows up here rather than as text drifting a few pixels across four scenes.
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.render.Datum
import ca.garionhk.cydclock.render.FontSet
import ca.garionhk.cydclock.render.Framebuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Unit tests run with the module dir as cwd, so assets are readable directly. */
fun loadFonts(): FontSet = FontSet.load { name ->
    File("src/main/assets/fonts/$name").readBytes()
}

class FontMetricsTest {

    private val fonts = loadFonts()

    @Test
    fun `font heights are 16 26 48 75`() {
        assertEquals(16, fonts[2].height)
        assertEquals(26, fonts[4].height)
        assertEquals(48, fonts[6].height)
        assertEquals(75, fonts[8].height)
    }

    @Test
    fun `fonts 6 and 8 have no letters`() {
        // This is not a defect to route around. It is why drawDegVal switches to
        // Font 2 for the unit letter, and why a "helpful" fallback font would
        // silently change textWidth and move everything.
        for (fontNo in intArrayOf(6, 8)) {
            val f = fonts[fontNo]
            for (c in "0123456789") {
                assertTrue("font $fontNo should have digit $c", f.glyph(c).any { it.toInt() != 0 })
            }
            for (c in "XYZbcdfghjk") {
                assertTrue(
                    "font $fontNo should render '$c' blank",
                    f.glyph(c).all { it.toInt() == 0 },
                )
            }
        }
    }

    @Test
    fun `font 8 digits are all the same width`() {
        // The clock's four digit cells are fixed at DIGIT_X, so a proportional
        // digit set would make HH:MM jitter as the time changed.
        val f = fonts[8]
        val widths = "0123456789".map { f.charWidth(it) }.distinct()
        assertEquals("font 8 digits should be monospaced, got $widths", 1, widths.size)
        assertEquals(55, widths[0])
    }

    @Test
    fun `font 4 digits are all the same width`() {
        val f = fonts[4]
        assertEquals(1, "0123456789".map { f.charWidth(it) }.distinct().size)
    }

    @Test
    fun `load-bearing string widths are pinned`() {
        // Each of these decides a position somewhere in the layout.
        val f2 = fonts[2]
        val f4 = fonts[4]

        // Status strip clock: cleared with a 74 px rect at TIME_X, so it must fit.
        val strip = f4.textWidth("00:00")
        assertTrue("strip clock '00:00' is $strip px, must fit the 74 px clear rect", strip <= 74)

        // Sun & Moon: sunrise value at x=232 sets the setup button's clearance.
        val sunrise = f4.textWidth("05:42")
        assertTrue("sunrise value is $sunrise px", sunrise in 40..70)

        // Air Quality: the widest band name must fit the 320 px screen when centred.
        val band = f4.textWidth("Unhealthy (SG)")
        assertTrue("'Unhealthy (SG)' is $band px, must fit 320", band < 320)

        // Weather: 'Feels' sets where the feels-like value starts.
        assertTrue(f2.textWidth("Feels") > 0)

        // Sun & Moon banner.
        assertTrue(f2.textWidth("TOMORROW") > 0)
    }

    @Test
    fun `imperial pressure fits at two decimals`() {
        // pressureStat draws the value MC at cx=268 and puts the trend glyph at
        // cx + textWidth/2 + 6, with the glyph about 8 px wide.
        //
        // Measured, not estimated: "29.92" is 63 px in Font 4, so the glyph runs
        // 305..313 against a 320 px screen. Two decimals fit, which means the
        // imperial pressure fix can keep the digit that carries the meaning --
        // 29.92 inHg rather than the firmware's "30".
        val f4 = fonts[4]
        val twoDp = 268 + f4.textWidth("29.92") / 2 + 6 + 8
        assertTrue("'29.92' should fit, trend glyph ends at $twoDp", twoDp <= Theme.SCREEN_W)

        // The metric case is wider in digits but no wider on screen.
        val metric = 268 + f4.textWidth("1013") / 2 + 6 + 8
        assertTrue("'1013' should fit, trend glyph ends at $metric", metric <= Theme.SCREEN_W)
    }

    @Test
    fun `sun and moon sunrise value nearly reaches the setup button`() {
        // The Sun & Moon right column puts the sunrise value ML at x=232, y=34.
        // Font 4 "05:42" is 63 px, so it occupies x 232..294 and rows 21..46.
        //
        // That is the tightest constraint on where the setup button can live: its
        // glyph must start at x=296 or later. Pinned here so a font change that
        // widened the digits would fail loudly rather than quietly overlap.
        val w = fonts[4].textWidth("05:42")
        val right = 232 + w - 1
        assertEquals(63, w)
        assertTrue("sunrise value ends at $right; the setup glyph starts at 296", right < 296)
    }

    @Test
    fun `textWidth is the sum of character advances`() {
        val f = fonts[2]
        assertEquals(
            f.charWidth('A') + f.charWidth('b') + f.charWidth('1'),
            f.textWidth("Ab1"),
        )
        assertEquals(0, f.textWidth(""))
    }

    @Test
    fun `out of range characters fall back to space`() {
        val f = fonts[2]
        assertEquals(f.charWidth(' '), f.charWidth('é'))
        assertEquals(f.charWidth(' '), f.charWidth(''))
    }

    // ---- datum ------------------------------------------------------------

    @Test
    fun `MC datum centres with truncating integer division`() {
        val fb = Framebuffer()
        val c = DeviceCanvas(fb, fonts)
        c.setTextFont(2)
        c.setTextDatum(Datum.MC)
        c.setTextColor(Theme.COL_TEXT, Theme.COL_BG)

        val text = "Hi"                       // odd total width exercises the truncation
        val w = c.textWidth(text)
        val h = c.fontHeight()
        c.drawString(text, 160, 100)

        // Left edge must be 160 - w/2 with integer division, not a rounded half.
        val expectedLeft = 160 - w / 2
        val expectedTop = 100 - h / 2
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        for (y in 0 until fb.height) for (x in 0 until fb.width) {
            if (fb.pixelAt(x, y) == Theme.COL_TEXT) {
                if (x < minX) minX = x
                if (y < minY) minY = y
            }
        }
        assertTrue("ink starts at $minX, cell left is $expectedLeft", minX >= expectedLeft)
        assertTrue("ink starts at row $minY, cell top is $expectedTop", minY >= expectedTop)
    }

    @Test
    fun `ML datum anchors on the left and centres vertically`() {
        val fb = Framebuffer()
        val c = DeviceCanvas(fb, fonts)
        c.setTextFont(4)
        c.setTextDatum(Datum.ML)
        c.setTextColor(Theme.COL_TEXT, Theme.COL_BG)
        c.drawString("12:34", 10, 218)        // the status strip clock

        val h = c.fontHeight()
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until fb.height) for (x in 0 until fb.width) {
            if (fb.pixelAt(x, y) == Theme.COL_TEXT) {
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        assertTrue("text should sit inside the cell centred on y=218", minY >= 218 - h / 2)
        assertTrue("text should sit inside the cell centred on y=218", maxY <= 218 - h / 2 + h)
    }

    @Test
    fun `opaque background paints the whole glyph cell`() {
        // weatherStat and the strip rely on this to erase the previous value.
        val fb = Framebuffer()
        fb.fillScreen(Theme.C_RAIN)                 // something that is neither fg nor bg
        val c = DeviceCanvas(fb, fonts)
        c.setTextFont(4)
        c.setTextDatum(Datum.TL)
        c.setTextColor(Theme.COL_TEXT, Theme.COL_BG)
        c.drawString("8", 100, 100)

        val f = fonts[4]
        val w = f.bitmapWidth('8')
        for (y in 100 until 100 + f.height) {
            for (x in 100 until 100 + w) {
                val p = fb.pixelAt(x, y)
                assertTrue(
                    "cell pixel $x,$y was not painted",
                    p == Theme.COL_TEXT || p == Theme.COL_BG,
                )
            }
        }
    }

    // ---- specimen ---------------------------------------------------------

    @Test
    fun `render a specimen sheet for inspection`() {
        val fb = Framebuffer()
        val c = DeviceCanvas(fb, fonts)
        c.fillScreen(Theme.COL_BG)
        c.setTextDatum(Datum.TL)

        c.setTextFont(2); c.setTextColor(Theme.COL_TEXT, Theme.COL_BG)
        c.drawString("F2 ABCDEFGHIJKLMNOPQRSTUVWXYZ", 2, 2)
        c.drawString("F2 abcdefghijklmnopqrstuvwxyz", 2, 20)
        c.setTextColor(Theme.COL_DATE, Theme.COL_BG)
        c.drawString("F2 0123456789 !\"#$%&'()*+,-./:;<=>?", 2, 38)

        c.setTextFont(4); c.setTextColor(Theme.COL_ACCENT, Theme.COL_BG)
        c.drawString("F4 Abc 0123 Unhealthy", 2, 58)

        c.setTextFont(6); c.setTextColor(Theme.C_SUN, Theme.COL_BG)
        c.drawString("6 01234 Xy", 2, 90)      // letters must come out blank

        c.setTextFont(8); c.setTextColor(Theme.COL_TIME, Theme.COL_BG)
        c.drawString("12:34", 2, 142)

        val out = GoldenRender.write(fb, "m2_font_specimen", scale = 3)
        assertTrue(out.exists() && out.length() > 0)
    }
}
