// ===========================================================================
// FramebufferTest.kt -- pins the rasteriser's actual pixels
// ===========================================================================
// These assert exact pixel sets, not "looks about right". The whole reason the
// primitives were ported instead of delegated to Skia is that a one-pixel
// difference at the rim of a circle becomes a five-pixel step at 5x letterbox,
// so a test that tolerated drift would defeat the purpose.
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.core.lround
import ca.garionhk.cydclock.core.rgb565
import ca.garionhk.cydclock.core.trunc
import ca.garionhk.cydclock.render.Framebuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ON = 0xFFFFFFFF.toInt()

/** Every lit pixel, as "x,y" strings, so failures read legibly. */
private fun Framebuffer.litPixels(): Set<String> = buildSet {
    for (y in 0 until height) for (x in 0 until width) {
        if (pixelAt(x, y) != 0) add("$x,$y")
    }
}

class FramebufferTest {

    // ---- colour -----------------------------------------------------------

    @Test
    fun `rgb565 expands by bit replication, not by shifting`() {
        // Shifting alone would give 0xF8FCF8 for white and 0x202020 for the strip.
        //
        // These expectations are computed from the formula, not eyeballed. Several
        // published conversions of this palette are wrong in the low bits -- green
        // is 6-bit and replicates differently from red and blue, which is why
        // 0xC618 is #C6C3C6 and not the #C6C6C6 you would guess. Keeping theme.h's
        // RGB565 literals as the source of truth is what makes that unambiguous.
        assertEquals(0xFFFFFFFF.toInt(), rgb565(0xFFFF))
        assertEquals(0xFF000000.toInt(), rgb565(0x0000))
        assertEquals(0xFF212021.toInt(), rgb565(0x2104))   // COL_STRIP_BG
        assertEquals(0xFF848284.toInt(), rgb565(0x8410))   // COL_DIM
        assertEquals(0xFF00FFFF.toInt(), rgb565(0x07FF))   // COL_ACCENT
        assertEquals(0xFFFFA600.toInt(), rgb565(0xFD20))   // COL_PIN
        assertEquals(0xFFC6C3C6.toInt(), rgb565(0xC618))   // COL_DATE
        assertEquals(0xFF525552.toInt(), rgb565(0x52AA))   // COL_FRESH_NONE -- grey, not green
        assertEquals(0xFF00FF00.toInt(), rgb565(0x07E0))   // COL_FRESH_OK
        assertEquals(0xFF5AB2FF.toInt(), rgb565(0x5D9F))   // C_RAIN
    }

    @Test
    fun `theme geometry matches theme_h`() {
        assertEquals(196, Theme.STATUS_Y)
        assertEquals(196, Theme.CONTENT_H)
        assertEquals(218, Theme.STATUS_CY)
        assertTrue(Theme.DIGIT_X.contentEquals(intArrayOf(28, 88, 172, 232)))
    }

    // ---- C arithmetic -----------------------------------------------------

    @Test
    fun `lround breaks ties away from zero, unlike kotlin round`() {
        assertEquals(3, lround(2.5))
        assertEquals(-3, lround(-2.5))
        assertEquals(2, lround(1.5))          // kotlin.math.round would give 2 here too
        assertEquals(4, lround(3.5))          // but kotlin.math.round gives 4.0 -> ties-to-even
        assertEquals(0, lround(0.4))
        assertEquals(-1, lround(-0.5))
    }

    @Test
    fun `trunc reproduces the iconCloud radii`() {
        // iconCloud(cx, cy, s=16): C truncates s*0.7f and s*0.8f into int params.
        // roundToInt would give 11 and 13 and draw a visibly different cloud.
        assertEquals(11, trunc(16 * 0.7f))
        assertEquals(12, trunc(16 * 0.8f))
        assertEquals(8, trunc(16 * 0.5f))
    }

    // ---- clipping ---------------------------------------------------------

    @Test
    fun `primitives clip instead of throwing`() {
        val fb = Framebuffer()
        fb.drawPixel(-1, 5, ON)
        fb.drawPixel(320, 5, ON)
        fb.drawPixel(5, -1, ON)
        fb.drawPixel(5, 240, ON)
        assertEquals(emptySet<String>(), fb.litPixels())

        fb.drawFastHLine(-10, 0, 15, ON)      // clipped to x 0..4
        assertEquals(setOf("0,0", "1,0", "2,0", "3,0", "4,0"), fb.litPixels())
    }

    @Test
    fun `fillRect clips against all four edges`() {
        val fb = Framebuffer()
        fb.fillRect(-5, -5, 10, 10, ON)
        // Survives as x 0..4, y 0..4.
        assertEquals(25, fb.litPixels().size)
        assertTrue(fb.pixelAt(0, 0) == ON && fb.pixelAt(4, 4) == ON)
        assertEquals(0, fb.pixelAt(5, 0))
    }

    // ---- lines ------------------------------------------------------------

    @Test
    fun `horizontal and vertical lines are exact`() {
        val fb = Framebuffer()
        fb.drawLine(10, 20, 14, 20, ON)
        assertEquals(setOf("10,20", "11,20", "12,20", "13,20", "14,20"), fb.litPixels())

        val fb2 = Framebuffer()
        fb2.drawLine(7, 3, 7, 7, ON)
        assertEquals(setOf("7,3", "7,4", "7,5", "7,6", "7,7"), fb2.litPixels())
    }

    @Test
    fun `a 45 degree line is the exact diagonal`() {
        val fb = Framebuffer()
        fb.drawLine(0, 0, 5, 5, ON)
        assertEquals(setOf("0,0", "1,1", "2,2", "3,3", "4,4", "5,5"), fb.litPixels())
    }

    @Test
    fun `drawLine is symmetric under endpoint swap`() {
        // TFT_eSPI normalises with the x0 > x1 swap, so both directions must
        // produce identical pixels. If the swap were dropped they would differ.
        val a = Framebuffer().apply { drawLine(3, 7, 29, 18, ON) }
        val b = Framebuffer().apply { drawLine(29, 18, 3, 7, ON) }
        assertEquals(a.litPixels(), b.litPixels())
    }

    @Test
    fun `a steep line takes the transposed branch`() {
        val fb = Framebuffer()
        fb.drawLine(10, 10, 12, 20, ON)
        val lit = fb.litPixels()
        // One pixel per scanline across the full y span, and no gaps.
        assertEquals(11, lit.size)
        for (y in 10..20) {
            assertTrue("missing scanline y=$y", lit.any { it.endsWith(",$y") })
        }
    }

    // ---- circles ----------------------------------------------------------

    @Test
    fun `drawCircle r1 is the four axis pixels`() {
        val fb = Framebuffer()
        fb.drawCircle(50, 50, 1, ON)
        assertEquals(setOf("51,50", "49,50", "50,49", "50,51"), fb.litPixels())
    }

    @Test
    fun `drawCircle is symmetric in both axes`() {
        val fb = Framebuffer()
        fb.drawCircle(100, 100, 12, ON)
        for (p in fb.litPixels()) {
            val (x, y) = p.split(",").map { it.toInt() }
            val mx = 200 - x
            val my = 200 - y
            assertTrue("no x-mirror for $p", fb.pixelAt(mx, y) == ON)
            assertTrue("no y-mirror for $p", fb.pixelAt(x, my) == ON)
        }
    }

    @Test
    fun `fillCircle central scanline spans 2r plus 1`() {
        val fb = Framebuffer()
        fb.fillCircle(100, 100, 6, ON)
        var run = 0
        for (x in 0 until fb.width) if (fb.pixelAt(x, 100) == ON) run++
        assertEquals(13, run)                    // 2*6 + 1
        assertEquals(ON, fb.pixelAt(94, 100))
        assertEquals(ON, fb.pixelAt(106, 100))
        assertEquals(0, fb.pixelAt(93, 100))
    }

    @Test
    fun `fillCircle covers its own outline`() {
        // The status strip's dots and the pin glyph rely on this: a filled circle
        // must leave no unpainted rim pixel, or clearing by overdraw ghosts.
        val outline = Framebuffer().apply { drawCircle(100, 100, 9, ON) }
        val filled = Framebuffer().apply { fillCircle(100, 100, 9, ON) }
        assertTrue(filled.litPixels().containsAll(outline.litPixels()))
    }

    // ---- triangles --------------------------------------------------------

    @Test
    fun `fillTriangle handles the degenerate collinear case`() {
        val fb = Framebuffer()
        fb.fillTriangle(10, 5, 20, 5, 15, 5, ON)
        assertEquals((10..20).map { "$it,5" }.toSet(), fb.litPixels())
    }

    @Test
    fun `fillTriangle is vertex-order independent`() {
        // The three-way y-sort exists so callers can pass vertices in any order.
        // triMark and the pressure-trend glyph depend on it.
        val a = Framebuffer().apply { fillTriangle(10, 30, 25, 4, 40, 30, ON) }
        val b = Framebuffer().apply { fillTriangle(40, 30, 10, 30, 25, 4, ON) }
        val c = Framebuffer().apply { fillTriangle(25, 4, 40, 30, 10, 30, ON) }
        assertEquals(a.litPixels(), b.litPixels())
        assertEquals(a.litPixels(), c.litPixels())
    }

    @Test
    fun `fillTriangle reproduces the status strip pin glyph shaft`() {
        // drawPinGlyph: fillTriangle(cx-3, cy+1, cx+3, cy+1, cx, cy+10).
        // Flat-topped, tapering to a point ten rows down.
        val fb = Framebuffer()
        fb.fillTriangle(185, 219, 191, 219, 188, 228, ON)
        var top = 0
        for (x in 0 until fb.width) if (fb.pixelAt(x, 219) == ON) top++
        assertEquals(7, top)                       // 185..191 inclusive
        assertEquals(ON, fb.pixelAt(188, 228))     // the tip
    }
}
