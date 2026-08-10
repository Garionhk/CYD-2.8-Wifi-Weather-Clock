// ===========================================================================
// TestPattern.kt -- M1's shipped artefact: proof the rasteriser and the
// letterbox both behave
// ===========================================================================
// Three things this is meant to expose at a glance:
//
//  * the 1 px checkerboard -- if the upscale is filtering or the scale factor is
//    fractional, this turns into grey mush or a moire pattern instead of a crisp
//    grid. It is the fastest way to catch a broken FilterQuality or a
//    non-integer scale.
//  * the line starburst -- every octant, so an inverted steep/swap branch in
//    drawLine shows up as an asymmetric star.
//  * the status strip -- drawn at the real STATUS_Y with its divider, so the
//    geometry constants can be checked against the panel before any scene exists.
//
// Deleted once M3 has real scenes.
package ca.garionhk.cydclock.render

import ca.garionhk.cydclock.core.Theme
import kotlin.math.cos
import kotlin.math.sin

object TestPattern {

    fun draw(c: DeviceCanvas) {
        c.fillScreen(Theme.COL_BG)

        checkerboard(c, 0, 0, 64, 48)
        nestedRects(c, 70, 0, 60, 48)
        circleLadder(c, 136, 24)
        starburst(c, 256, 60, 44)
        triangles(c, 8, 56)
        gradientBars(c, 8, 150)
        statusStrip(c)
    }

    /** 1 px alternating pixels. Must stay a crisp grid at any integer scale. */
    private fun checkerboard(c: DeviceCanvas, x: Int, y: Int, w: Int, h: Int) {
        for (yy in y until y + h) {
            for (xx in x until x + w) {
                c.drawPixel(xx, yy, if ((xx + yy) and 1 == 0) Theme.COL_TEXT else Theme.COL_BG)
            }
        }
    }

    private fun nestedRects(c: DeviceCanvas, x: Int, y: Int, w: Int, h: Int) {
        var i = 0
        while (i * 2 < minOf(w, h)) {
            val col = if (i % 2 == 0) Theme.COL_ACCENT else Theme.COL_DIM
            c.drawRect(x + i, y + i, w - 2 * i, h - 2 * i, col)
            i += 2
        }
    }

    /** drawCircle above, fillCircle below, so rim rasterisation is comparable. */
    private fun circleLadder(c: DeviceCanvas, x: Int, y: Int) {
        var cx = x
        for (r in 1..8) {
            c.drawCircle(cx + r, y, r, Theme.COL_TEXT)
            c.fillCircle(cx + r, y + 24, r, Theme.C_SUN)
            cx += 2 * r + 3
        }
    }

    /** 24 spokes: every octant, both signs, plus the exact horizontals/verticals. */
    private fun starburst(c: DeviceCanvas, cx: Int, cy: Int, r: Int) {
        for (i in 0 until 24) {
            val a = i * 15.0 * Math.PI / 180.0
            val x1 = cx + (cos(a) * r).toInt()
            val y1 = cy + (sin(a) * r).toInt()
            c.drawLine(cx, cy, x1, y1, if (i % 2 == 0) Theme.COL_ACCENT else Theme.COL_DATE)
        }
    }

    private fun triangles(c: DeviceCanvas, x: Int, y: Int) {
        c.fillTriangle(x, y + 30, x + 15, y, x + 30, y + 30, Theme.C_HI)          // flat bottom
        c.fillTriangle(x + 36, y, x + 66, y, x + 51, y + 30, Theme.C_LO)          // flat top
        c.fillTriangle(x + 72, y + 4, x + 100, y + 14, x + 78, y + 30, Theme.C_RAIN)  // scalene
        c.fillTriangle(x + 106, y + 10, x + 136, y + 10, x + 121, y + 10, Theme.COL_DIM) // degenerate
    }

    /** Every palette entry side by side, to eyeball the RGB565 expansion. */
    private fun gradientBars(c: DeviceCanvas, x: Int, y: Int) {
        val palette = intArrayOf(
            Theme.COL_TEXT, Theme.COL_DATE, Theme.COL_DIM, Theme.COL_ACCENT,
            Theme.COL_STRIP_BG, Theme.COL_FRESH_OK, Theme.COL_FRESH_WARN,
            Theme.COL_FRESH_OLD, Theme.COL_FRESH_NONE, Theme.COL_PIN,
            Theme.C_SUN, Theme.C_RAIN, Theme.C_FOG, Theme.C_LO,
            Theme.C_PURPLE, Theme.C_MAROON,
        )
        palette.forEachIndexed { i, col -> c.fillRect(x + i * 19, y, 18, 30, col) }
    }

    private fun statusStrip(c: DeviceCanvas) {
        c.fillRect(0, Theme.STATUS_Y, Theme.SCREEN_W, Theme.STATUS_H, Theme.COL_STRIP_BG)
        c.drawFastHLine(0, Theme.STATUS_Y, Theme.SCREEN_W, Theme.COL_DIM)

        // The four scene dots at their real positions, right-anchored at x=300.
        val count = 4
        for (i in 0 until count) {
            val cx = 300 - (count - 1 - i) * 16
            if (i == 0) c.fillCircle(cx, Theme.STATUS_CY, 4, Theme.COL_ACCENT)
            else c.drawCircle(cx, Theme.STATUS_CY, 4, Theme.COL_DIM)
        }

        // The WiFi bar group, likewise.
        for (i in 0 until 4) {
            val h = 8 + i * 6
            val bx = 96 + i * 9
            val by = Theme.STATUS_Y + 34 - h
            if (i < 3) c.fillRect(bx, by, 6, h, Theme.COL_ACCENT)
            else c.drawRect(bx, by, 6, h, Theme.COL_DIM)
        }

        c.fillCircle(152, Theme.STATUS_CY, 6, Theme.COL_FRESH_OK)
    }
}
