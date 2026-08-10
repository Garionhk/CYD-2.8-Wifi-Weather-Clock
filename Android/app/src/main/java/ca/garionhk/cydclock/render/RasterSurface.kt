// ===========================================================================
// RasterSurface.kt -- the pixel-exact reference implementation
// ===========================================================================
// Framebuffer's TFT_eSPI rasterisers plus the bitmap fonts, presented as a
// Surface. This is what the JVM golden and geometry tests draw into, so a scene
// change still gets checked pixel by pixel even though the app itself now ships
// the vector renderer.
package ca.garionhk.cydclock.render

import ca.garionhk.cydclock.core.lround
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class RasterSurface(
    val fb: Framebuffer,
    private val fonts: FontSet? = null,
) : Surface {

    override val width: Int get() = fb.width
    override val height: Int get() = fb.height

    /** Applied to every x. Zero in tests, which always draw a 320-wide viewport. */
    private var originX = 0

    override fun translate(dx: Int) { originX += dx }

    override fun fillScreen(color: Int) = fb.fillScreen(color)
    override fun drawPixel(x: Int, y: Int, color: Int) = fb.drawPixel(x + originX, y, color)
    override fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) = fb.fillRect(x + originX, y, w, h, color)
    override fun drawRect(x: Int, y: Int, w: Int, h: Int, color: Int) = fb.drawRect(x + originX, y, w, h, color)
    override fun drawFastHLine(x: Int, y: Int, w: Int, color: Int) = fb.drawFastHLine(x + originX, y, w, color)
    override fun drawFastVLine(x: Int, y: Int, h: Int, color: Int) = fb.drawFastVLine(x + originX, y, h, color)
    override fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) =
        fb.drawLine(x0 + originX, y0, x1 + originX, y1, color)
    override fun drawCircle(cx: Int, cy: Int, r: Int, color: Int) = fb.drawCircle(cx + originX, cy, r, color)
    override fun fillCircle(cx: Int, cy: Int, r: Int, color: Int) = fb.fillCircle(cx + originX, cy, r, color)

    override fun fillTriangle(x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) =
        fb.fillTriangle(x0 + originX, y0, x1 + originX, y1, x2 + originX, y2, color)

    /** The firmware's construction: straight segments every six degrees. */
    override fun drawArc(cx: Int, cy: Int, r: Int, startDeg: Int, endDeg: Int, color: Int) {
        var px = -1
        var py = -1
        var a = startDeg
        while (a <= endDeg) {
            val th = a * Math.PI / 180.0
            val x = cx + originX + lround(r * cos(th))
            val y = cy - lround(r * sin(th))
            if (px >= 0) fb.drawLine(px, py, x, y, color)
            px = x
            py = y
            a += 6
        }
    }

    /** The firmware's construction: a terminator solved per scanline. */
    override fun fillMoon(
        cxIn: Int, cy: Int, r: Int, phase: Float, lit: Int, shadow: Int, outline: Int,
    ) {
        val cx = cxIn + originX
        fb.fillCircle(cx, cy, r, shadow)
        for (dy in -r..r) {
            val xe = sqrt((r.toDouble() * r) - (dy.toDouble() * dy))
            val xt = xe * cos(2 * Math.PI * phase)
            val y = cy + dy
            if (phase <= 0.5f) {
                val x0 = cx + lround(xt)
                val x1 = cx + lround(xe)
                if (x1 >= x0) fb.drawFastHLine(x0, y, x1 - x0 + 1, lit)
            } else {
                val x0 = cx - lround(xe)
                val x1 = cx + lround(xt)
                if (x1 >= x0) fb.drawFastHLine(x0, y, x1 - x0 + 1, lit)
            }
        }
        fb.drawCircle(cx, cy, r, outline)
    }

    // ---- text -------------------------------------------------------------

    private fun font(n: Int): TftFont =
        (fonts ?: error("RasterSurface has no FontSet"))[n]

    override fun textWidth(s: String, font: Int): Int = font(font).textWidth(s)

    override fun fontHeight(font: Int): Int = font(font).height

    override fun inkHeight(font: Int): Int = font(font).inkHeight

    /** Mirrors drawText's `y0 = y - h / 2`, including the truncating division. */
    override fun inkCenterOffset(font: Int): Int {
        val f = font(font)
        return -(f.height / 2) + f.inkTop + f.inkHeight / 2
    }

    override fun drawText(s: String, x: Int, y: Int, datum: Int, font: Int, fg: Int, bg: Int) {
        val f = font(font)
        val w = f.textWidth(s)
        val h = f.height

        // Integer division, truncating, exactly as the C does. roundToInt here
        // shifts centred text a pixel whenever the width is odd.
        val x0 = x + originX - when (datum % 3) { 0 -> 0; 1 -> w / 2; else -> w }
        val y0 = y - when (datum / 3) { 0 -> 0; 1 -> h / 2; else -> h }

        var cx = x0
        for (ch in s) {
            drawGlyph(f, ch, cx, y0, fg, bg)
            cx += f.charWidth(ch)
        }
    }

    /**
     * Paints the glyph's whole cell -- foreground where the mask is set,
     * background everywhere else. That opaque fill is how the firmware's widgets
     * erased the value they drew last time.
     */
    private fun drawGlyph(f: TftFont, ch: Char, x: Int, y: Int, fg: Int, bg: Int) {
        val w = f.bitmapWidth(ch)
        if (w <= 0) return
        val mask = f.glyph(ch)
        for (row in 0 until f.height) {
            val py = y + row
            if (py < 0 || py >= fb.height) continue
            val base = row * w
            for (col in 0 until w) {
                fb.drawPixel(x + col, py, if (mask[base + col].toInt() != 0) fg else bg)
            }
        }
    }
}
