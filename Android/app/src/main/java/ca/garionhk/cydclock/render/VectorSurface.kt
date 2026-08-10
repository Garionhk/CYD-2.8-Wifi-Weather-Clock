// ===========================================================================
// VectorSurface.kt -- draws the 320x240 design grid at the display's own resolution
// ===========================================================================
// The scenes are unchanged: they still speak in 320x240 coordinates. The canvas
// is scaled by a real (fractional) factor and everything is antialiased, so a
// circle is a circle rather than a staircase and text is set in a scalable face
// instead of being a magnified bitmap.
//
// Consequences of that, stated plainly:
//
//  * Output is no longer pixel-identical to the CYD panel. That was the explicit
//    trade -- a 5x magnified 320x240 image reads as a defect on a 1920x1200
//    display, however faithful it is.
//  * textWidth() now comes from the typeface, not from TFT_eSPI's width tables,
//    so chained positions (drawDegVal, weatherStat, pressureStat) land a few
//    units differently. They stay *correct*, because every one of them is
//    computed from textWidth rather than hard-coded -- which is exactly why the
//    firmware's habit of deriving positions was worth preserving.
//  * The opaque text background is ignored. It existed so a repainted value
//    erased the old one over SPI; here the whole frame is redrawn anyway, and
//    honouring it would paint visible boxes behind every label.
package ca.garionhk.cydclock.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import ca.garionhk.cydclock.core.Theme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign

/**
 * Paints for the four TFT_eSPI font slots.
 *
 * fontHeight() keeps returning the firmware's nominal cell heights
 * (16/26/48/75) because the layout arithmetic is written against them --
 * drawDegVal positions its degree ring at `yMid - fh/2 + r + 2`. The text SIZE
 * is tuned separately so the drawn glyphs match the originals' visual weight.
 */
class VectorFonts(typeface: Typeface = Typeface.SANS_SERIF) {

    /** TFT_eSPI's declared cell heights. The layout arithmetic is written against these. */
    private val nominal = mapOf(2 to 16, 4 to 26, 6 to 48, 8 to 75)

    /**
     * Measured ink height of the digit '0' in each bitmap font, from the actual
     * glyph tables. These -- not the cell heights -- are what the eye reads as
     * "how big is the text", and the four fonts fill their cells very
     * differently: Font 2's digits occupy 62% of their cell, Font 8's 93%.
     * Sizing off the cell height would have made the clock noticeably small.
     *
     * Font 8 is then set 10% above its measured 70, by request. It is the hero
     * numeral -- the clock's HH:MM and the AQI headline -- and the panel's own
     * proportions were chosen for a display read from arm's length rather than
     * from across a room. 77 still clears its surroundings: the digit cell is
     * 82 tall, and the AQI number keeps a gap above its band name.
     */
    private val inkHeights = mapOf(2 to 10, 4 to 17, 6 to 36, 8 to 77)

    private class Sized(val paint: Paint, val inkHalf: Float)

    private val fonts: Map<Int, Sized> = nominal.keys.associateWith { n ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            isSubpixelText = true
            isLinearText = true
        }
        // Self-calibrating: measure this typeface's digit ink at a reference size
        // and scale so it matches the bitmap font's. Hard-coding a cap-height
        // ratio would silently drift if the typeface ever changed.
        val target = inkHeights.getValue(n).toFloat()
        val bounds = Rect()
        p.textSize = REFERENCE_SIZE
        p.getTextBounds("0", 0, 1, bounds)
        val inkAtReference = bounds.height().toFloat().coerceAtLeast(1f)
        p.textSize = REFERENCE_SIZE * target / inkAtReference
        Sized(p, target / 2f)
    }

    fun paint(font: Int): Paint = (fonts[font] ?: fonts.getValue(2)).paint

    fun height(font: Int): Int = nominal[font] ?: 16

    /**
     * Half the digit ink height. A middle datum puts the baseline this far below
     * the anchor, which centres capitals and digits on it -- which is what the
     * bitmap fonts did, their fixed baseline sitting so the ink came out centred
     * in the cell.
     */
    fun inkHalf(font: Int): Float = (fonts[font] ?: fonts.getValue(2)).inkHalf

    private companion object {
        const val REFERENCE_SIZE = 100f
    }
}

class VectorSurface(
    private val canvas: Canvas,
    private val fonts: VectorFonts,
    override val width: Int = Theme.SCREEN_W,
    override val height: Int = Theme.SCREEN_H,
) : Surface {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rectF = RectF()

    override fun translate(dx: Int) = canvas.translate(dx.toFloat(), 0f)

    private fun fillWith(color: Int): Paint = fill.also { it.color = color }
    private fun strokeWith(color: Int, w: Float = 1f): Paint =
        stroke.also { it.color = color; it.strokeWidth = w }

    override fun fillScreen(color: Int) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillWith(color))
    }

    /**
     * A single design-grid unit. Only the checkerboard test pattern draws these
     * one at a time; scenes use it for nothing, so a filled unit square is right.
     */
    override fun drawPixel(x: Int, y: Int, color: Int) {
        canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, fillWith(color))
    }

    override fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), fillWith(color))
    }

    override fun drawRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        // Inset by half a unit so the stroke sits inside the nominal rectangle.
        canvas.drawRect(
            x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, strokeWith(color),
        )
    }

    override fun drawFastHLine(x: Int, y: Int, w: Int, color: Int) {
        if (w <= 0) return
        // Drawn as a filled rect rather than a stroked line: consecutive scan
        // lines then tile exactly, with no antialiased seams between them.
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), y + 1f, fillWith(color))
    }

    override fun drawFastVLine(x: Int, y: Int, h: Int, color: Int) {
        if (h <= 0) return
        canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, (y + h).toFloat(), fillWith(color))
    }

    override fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        canvas.drawLine(x0 + 0.5f, y0 + 0.5f, x1 + 0.5f, y1 + 0.5f, strokeWith(color, 1.2f))
    }

    override fun drawCircle(cx: Int, cy: Int, r: Int, color: Int) {
        canvas.drawCircle(cx + 0.5f, cy + 0.5f, r.toFloat(), strokeWith(color))
    }

    override fun fillCircle(cx: Int, cy: Int, r: Int, color: Int) {
        canvas.drawCircle(cx + 0.5f, cy + 0.5f, r + 0.5f, fillWith(color))
    }

    override fun fillTriangle(x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        path.reset()
        path.moveTo(x0 + 0.5f, y0 + 0.5f)
        path.lineTo(x1 + 0.5f, y1 + 0.5f)
        path.lineTo(x2 + 0.5f, y2 + 0.5f)
        path.close()
        canvas.drawPath(path, fillWith(color))
    }

    /** A true arc. Android measures clockwise from due east, hence the negation. */
    override fun drawArc(cx: Int, cy: Int, r: Int, startDeg: Int, endDeg: Int, color: Int) {
        rectF.set(cx - r + 0.5f, cy - r + 0.5f, cx + r + 0.5f, cy + r + 0.5f)
        canvas.drawArc(
            rectF,
            -startDeg.toFloat(),
            -(endDeg - startDeg).toFloat(),
            false,
            strokeWith(color, 1.4f),
        )
    }

    /**
     * The terminator is an ellipse, not a scanline stack.
     *
     * The lit region is bounded by one semicircle of the limb and one half of an
     * ellipse whose horizontal semi-axis is |cos(2*pi*phase)| * r. When that
     * ellipse bulges away from the lit side the moon is gibbous; when it bulges
     * across, a crescent. At phase 0.25 and 0.75 the semi-axis is zero and the
     * terminator is straight -- the quarters.
     */
    override fun fillMoon(
        cx: Int, cy: Int, r: Int, phase: Float, lit: Int, shadow: Int, outline: Int,
    ) {
        val fx = cx + 0.5f
        val fy = cy + 0.5f
        val fr = r.toFloat()

        canvas.drawCircle(fx, fy, fr, fillWith(shadow))

        val k = cos(2.0 * Math.PI * phase).toFloat()      // -1 full .. +1 new
        val waxing = phase <= 0.5f
        val rx = abs(k) * fr
        // Mirrored for the waning half, so the lit limb swaps sides.
        val bulge = if (waxing) sign(k) else -sign(k)

        path.reset()
        val limb = RectF(fx - fr, fy - fr, fx + fr, fy + fr)
        // From the top, round the lit side of the limb, to the bottom.
        path.arcTo(limb, 270f, if (waxing) 180f else -180f)

        if (rx < 0.01f) {
            path.lineTo(fx, fy - fr)                       // exact quarter
        } else {
            val term = RectF(fx - rx, fy - fr, fx + rx, fy + fr)
            // Back from the bottom to the top, bulging left or right.
            path.arcTo(term, 90f, if (bulge > 0) -180f else 180f)
        }
        path.close()
        canvas.drawPath(path, fillWith(lit))

        canvas.drawCircle(fx, fy, fr, strokeWith(outline))
    }

    // ---- text -------------------------------------------------------------

    override fun textWidth(s: String, font: Int): Int =
        Math.round(fonts.paint(font).measureText(s))

    override fun fontHeight(font: Int): Int = fonts.height(font)

    override fun inkHeight(font: Int): Int = Math.round(fonts.inkHalf(font) * 2f)

    /** Zero by construction: drawText centres the ink on a middle datum. */
    override fun inkCenterOffset(font: Int): Int = 0

    override fun drawText(s: String, x: Int, y: Int, datum: Int, font: Int, fg: Int, bg: Int) {
        if (s.isEmpty()) return
        val p = fonts.paint(font)
        p.color = fg

        val w = p.measureText(s)

        val x0 = when (datum % 3) {
            0 -> x.toFloat()               // left
            1 -> x - w / 2f                // centre
            else -> x - w                  // right
        }
        // Paint draws from the baseline, so the vertical datum has to be
        // converted. The middle case centres the digit/capital ink on the anchor
        // rather than the em box: Roboto's descent would otherwise push
        // everything visibly high, and it is the ink the eye lines up.
        val y0 = when (datum / 3) {
            0 -> y - p.fontMetrics.ascent                 // top of cell
            1 -> y + fonts.inkHalf(font)                  // middle
            else -> y + fonts.height(font) - p.fontMetrics.descent
        }
        canvas.drawText(s, x0, y0, p)
    }
}
