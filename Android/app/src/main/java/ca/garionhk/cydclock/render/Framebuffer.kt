// ===========================================================================
// Framebuffer.kt -- a 320x240 ARGB surface with TFT_eSPI's rasterisation
// ===========================================================================
// Why not android.graphics.Canvas with antialiasing off? Because Skia's circle
// and line rasterisers are not TFT_eSPI's. fillCircle(cx, cy, 6) differs by a
// pixel or two at the rim, and at a 5x letterbox scale one pixel is a visible
// five-pixel step. Pixel fidelity is the point of this port, so the primitives
// are ported rather than delegated.
//
// Every routine below follows TFT_eSPI's implementation, including its clipping
// order and its integer arithmetic. Deviating "harmlessly" here shifts pixels.
//
// Zero android.* imports: this whole file runs under plain JUnit.
package ca.garionhk.cydclock.render

import ca.garionhk.cydclock.core.Theme

class Framebuffer(
    val width: Int = Theme.SCREEN_W,
    val height: Int = Theme.SCREEN_H,
) {
    /** ARGB8888, row-major. Handed straight to Bitmap.setPixels. */
    val px = IntArray(width * height)

    fun fillScreen(color: Int) {
        px.fill(color)
    }

    fun drawPixel(x: Int, y: Int, color: Int) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        px[y * width + x] = color
    }

    /** Unclipped read, for tests and for the moon terminator's scanline work. */
    fun pixelAt(x: Int, y: Int): Int = px[y * width + x]

    fun drawFastHLine(x: Int, y: Int, w: Int, color: Int) {
        if (y < 0 || x >= width || y >= height) return
        var xx = x
        var ww = w
        if (xx < 0) { ww += xx; xx = 0 }
        if (xx + ww > width) ww = width - xx
        if (ww < 1) return
        val base = y * width + xx
        java.util.Arrays.fill(px, base, base + ww, color)
    }

    fun drawFastVLine(x: Int, y: Int, h: Int, color: Int) {
        if (x < 0 || x >= width || y >= height) return
        var yy = y
        var hh = h
        if (yy < 0) { hh += yy; yy = 0 }
        if (yy + hh > height) hh = height - yy
        if (hh < 1) return
        var i = yy * width + x
        repeat(hh) {
            px[i] = color
            i += width
        }
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (x >= width || y >= height) return
        var xx = x; var yy = y; var ww = w; var hh = h
        if (xx < 0) { ww += xx; xx = 0 }
        if (yy < 0) { hh += yy; yy = 0 }
        if (xx + ww > width) ww = width - xx
        if (yy + hh > height) hh = height - yy
        if (ww < 1 || hh < 1) return
        for (row in yy until yy + hh) {
            val base = row * width + xx
            java.util.Arrays.fill(px, base, base + ww, color)
        }
    }

    fun drawRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        drawFastHLine(x, y, w, color)
        drawFastHLine(x, y + h - 1, w, color)
        drawFastVLine(x, y, h, color)
        drawFastVLine(x + w - 1, y, h, color)
    }

    /**
     * TFT_eSPI's line routine. It is Bresenham, but it accumulates runs and
     * flushes them as H/V lines rather than plotting pixel by pixel -- the
     * pixels touched are identical, and keeping the same structure keeps the
     * port checkable against the C.
     *
     * The two swaps (steep, then x0 > x1) decide which way ties break, so they
     * are not optional: the slanted rain drops and the eight sun rays are
     * exactly this function's output.
     */
    fun drawLine(x0In: Int, y0In: Int, x1In: Int, y1In: Int, color: Int) {
        var x0 = x0In; var y0 = y0In; var x1 = x1In; var y1 = y1In

        val steep = kotlin.math.abs(y1 - y0) > kotlin.math.abs(x1 - x0)
        if (steep) {
            var t = x0; x0 = y0; y0 = t
            t = x1; x1 = y1; y1 = t
        }
        if (x0 > x1) {
            var t = x0; x0 = x1; x1 = t
            t = y0; y0 = y1; y1 = t
        }

        val dx = x1 - x0
        val dy = kotlin.math.abs(y1 - y0)
        var err = dx shr 1
        val ystep = if (y0 < y1) 1 else -1
        var xs = x0
        var dlen = 0

        if (!steep) {
            var x = x0
            while (x <= x1) {
                dlen++
                err -= dy
                if (err < 0) {
                    err += dx
                    if (dlen == 1) drawPixel(xs, y0, color) else drawFastHLine(xs, y0, dlen, color)
                    dlen = 0
                    y0 += ystep
                    xs = x + 1
                }
                x++
            }
            if (dlen > 0) drawFastHLine(xs, y0, dlen, color)
        } else {
            var x = x0
            while (x <= x1) {
                dlen++
                err -= dy
                if (err < 0) {
                    err += dx
                    if (dlen == 1) drawPixel(y0, xs, color) else drawFastVLine(y0, xs, dlen, color)
                    dlen = 0
                    y0 += ystep
                    xs = x + 1
                }
                x++
            }
            if (dlen > 0) drawFastVLine(y0, xs, dlen, color)
        }
    }

    /**
     * TFT_eSPI's midpoint circle. Note it decrements its own `r` as it walks,
     * which is why the local copy exists -- the caller's radius is untouched.
     */
    fun drawCircle(x0: Int, y0: Int, rIn: Int, color: Int) {
        var r = rIn
        var x = 0
        var dx = 1
        var dy = r + r
        var p = -(r shr 1)

        drawPixel(x0 + r, y0, color)
        drawPixel(x0 - r, y0, color)
        drawPixel(x0, y0 - r, color)
        drawPixel(x0, y0 + r, color)

        while (x < r) {
            if (p >= 0) {
                dy -= 2
                p -= dy
                r--
            }
            dx += 2
            p += dx
            x++

            drawPixel(x0 + x, y0 + r, color)
            drawPixel(x0 - x, y0 + r, color)
            drawPixel(x0 - x, y0 - r, color)
            drawPixel(x0 + x, y0 - r, color)
            drawPixel(x0 + r, y0 + x, color)
            drawPixel(x0 - r, y0 + x, color)
            drawPixel(x0 - r, y0 - x, color)
            drawPixel(x0 + r, y0 - x, color)
        }
    }

    /** TFT_eSPI's filled circle: the central scanline first, then paired rows. */
    fun fillCircle(x0: Int, y0: Int, rIn: Int, color: Int) {
        var r = rIn
        var x = 0
        var dx = 1
        var dy = r + r
        var p = -(r shr 1)

        drawFastHLine(x0 - r, y0, dy + 1, color)

        while (x < r) {
            if (p >= 0) {
                dy -= 2
                p -= dy
                r--
            }
            dx += 2
            p += dx
            x++

            drawFastHLine(x0 - r, y0 + x, 2 * r + 1, color)
            drawFastHLine(x0 - r, y0 - x, 2 * r + 1, color)
            drawFastHLine(x0 - x, y0 + r, 2 * x + 1, color)
            drawFastHLine(x0 - x, y0 - r, 2 * x + 1, color)
        }
    }

    /**
     * Adafruit-GFX scanline triangle, as TFT_eSPI inherits it. The three-way
     * y-sort at the top decides which vertex is which, and that ordering shows
     * up in the boundary rows -- so it is reproduced exactly, including the
     * degenerate all-on-one-line case.
     *
     * `sa / dy01` relies on C's truncate-toward-zero integer division. Kotlin's
     * Int `/` does the same, so these translate directly.
     */
    fun fillTriangle(
        x0In: Int, y0In: Int, x1In: Int, y1In: Int, x2In: Int, y2In: Int, color: Int,
    ) {
        var x0 = x0In; var y0 = y0In
        var x1 = x1In; var y1 = y1In
        var x2 = x2In; var y2 = y2In
        var a: Int; var b: Int; var y: Int; val last: Int

        if (y0 > y1) { var t = y0; y0 = y1; y1 = t; t = x0; x0 = x1; x1 = t }
        if (y1 > y2) { var t = y2; y2 = y1; y1 = t; t = x2; x2 = x1; x1 = t }
        if (y0 > y1) { var t = y0; y0 = y1; y1 = t; t = x0; x0 = x1; x1 = t }

        if (y0 == y2) {  // all on the same line
            a = x0; b = x0
            if (x1 < a) a = x1 else if (x1 > b) b = x1
            if (x2 < a) a = x2 else if (x2 > b) b = x2
            drawFastHLine(a, y0, b - a + 1, color)
            return
        }

        val dx01 = x1 - x0
        val dy01 = y1 - y0
        val dx02 = x2 - x0
        val dy02 = y2 - y0
        val dx12 = x2 - x1
        val dy12 = y2 - y1
        var sa = 0
        var sb = 0

        // If the triangle is flat-bottomed, y1's scanline belongs to this loop;
        // otherwise it is skipped here and picked up below. Either way one of
        // the two loops is spared a divide by zero.
        last = if (y1 == y2) y1 else y1 - 1

        y = y0
        while (y <= last) {
            a = x0 + sa / dy01
            b = x0 + sb / dy02
            sa += dx01
            sb += dx02
            if (a > b) { val t = a; a = b; b = t }
            drawFastHLine(a, y, b - a + 1, color)
            y++
        }

        sa = dx12 * (y - y1)
        sb = dx02 * (y - y0)
        while (y <= y2) {
            a = x1 + sa / dy12
            b = x0 + sb / dy02
            sa += dx12
            sb += dx02
            if (a > b) { val t = a; a = b; b = t }
            drawFastHLine(a, y, b - a + 1, color)
            y++
        }
    }
}
