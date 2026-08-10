// ===========================================================================
// DeviceCanvas.kt -- a TFT_eSPI-shaped drawing surface
// ===========================================================================
// This deliberately keeps TFT_eSPI's *stateful* API -- setTextFont, setTextDatum,
// setTextColor, then drawString -- rather than a tidier Kotlin one. The reason is
// that scenes.cpp then transliterates line for line, so porting mistakes show up
// as typos instead of as translation errors. weatherStat (scenes.cpp:271-279)
// reads the same in both languages.
//
// It holds only the text state. Everything else is forwarded to a [Surface],
// which is either the pixel-exact raster reference or the vector renderer that
// ships. Scenes cannot tell which they are drawing into, and that is the point:
// one set of layout code, checked pixel by pixel in tests and drawn smoothly on
// the device.
package ca.garionhk.cydclock.render

/** Text datums, matching TFT_eSPI's numbering. Only ML and MC are used by the app. */
object Datum {
    const val TL = 0; const val TC = 1; const val TR = 2
    const val ML = 3; const val MC = 4; const val MR = 5
    const val BL = 6; const val BC = 7; const val BR = 8
}

class DeviceCanvas(val surface: Surface) {

    // ---- text state -------------------------------------------------------
    var textFont: Int = 2; private set
    var textDatum: Int = Datum.TL; private set
    var textColor: Int = 0; private set
    var textBgColor: Int = 0; private set

    fun setTextFont(n: Int) { textFont = n }
    fun setTextDatum(d: Int) { textDatum = d }

    /**
     * Two-argument form only. TFT_eSPI's one-argument setTextColor draws
     * transparent text; every call site in this app passes a background, because
     * on the panel that opaque cell fill is how widgets erase their old value.
     */
    fun setTextColor(fg: Int, bg: Int) { textColor = fg; textBgColor = bg }

    // ---- primitives -------------------------------------------------------
    fun fillScreen(color: Int) = surface.fillScreen(color)
    fun drawPixel(x: Int, y: Int, color: Int) = surface.drawPixel(x, y, color)
    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) = surface.fillRect(x, y, w, h, color)
    fun drawRect(x: Int, y: Int, w: Int, h: Int, color: Int) = surface.drawRect(x, y, w, h, color)
    fun drawFastHLine(x: Int, y: Int, w: Int, color: Int) = surface.drawFastHLine(x, y, w, color)
    fun drawFastVLine(x: Int, y: Int, h: Int, color: Int) = surface.drawFastVLine(x, y, h, color)
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) = surface.drawLine(x0, y0, x1, y1, color)
    fun drawCircle(cx: Int, cy: Int, r: Int, color: Int) = surface.drawCircle(cx, cy, r, color)
    fun fillCircle(cx: Int, cy: Int, r: Int, color: Int) = surface.fillCircle(cx, cy, r, color)
    fun fillTriangle(x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) =
        surface.fillTriangle(x0, y0, x1, y1, x2, y2, color)

    fun drawArc(cx: Int, cy: Int, r: Int, startDeg: Int, endDeg: Int, color: Int) =
        surface.drawArc(cx, cy, r, startDeg, endDeg, color)

    fun fillMoon(cx: Int, cy: Int, r: Int, phase: Float, lit: Int, shadow: Int, outline: Int) =
        surface.fillMoon(cx, cy, r, phase, lit, shadow, outline)

    // ---- text -------------------------------------------------------------
    fun textWidth(s: String, fontNo: Int = textFont): Int = surface.textWidth(s, fontNo)

    fun fontHeight(fontNo: Int = textFont): Int = surface.fontHeight(fontNo)

    /** Digit ink height -- what a middle datum centres on. See [Surface.inkHeight]. */
    fun inkHeight(fontNo: Int = textFont): Int = surface.inkHeight(fontNo)

    /** See [Surface.inkCenterOffset]. Needed to measure gaps between rows. */
    fun inkCenterOffset(fontNo: Int = textFont): Int = surface.inkCenterOffset(fontNo)

    /** Returns the advance, matching TFT_eSPI. */
    fun drawString(s: String, x: Int, y: Int): Int {
        surface.drawText(s, x, y, textDatum, textFont, textColor, textBgColor)
        return surface.textWidth(s, textFont)
    }
}

/**
 * Run [block] with the origin shifted right by [dx], then put it back.
 *
 * Used to centre a scene's own 320-wide space inside a wider viewport without
 * any scene having to know its coordinates moved.
 */
inline fun DeviceCanvas.withTranslation(dx: Int, block: () -> Unit) {
    if (dx == 0) { block(); return }
    surface.translate(dx)
    try {
        block()
    } finally {
        surface.translate(-dx)
    }
}

/**
 * Convenience for tests and the golden renderer: a canvas over the pixel-exact
 * raster reference.
 */
fun DeviceCanvas(fb: Framebuffer, fonts: FontSet? = null): DeviceCanvas =
    DeviceCanvas(RasterSurface(fb, fonts))
