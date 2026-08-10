// ===========================================================================
// Surface.kt -- the drawing operations every scene is written against
// ===========================================================================
// There are two implementations:
//
//   RasterSurface   plots into a 320x240 IntArray using TFT_eSPI's own
//                   rasterisers and bitmap fonts. Pixel-identical to the CYD
//                   panel, and the one the JVM tests assert against.
//
//   VectorSurface   draws onto the real display at its native resolution with
//                   antialiasing and a scalable typeface. This is what ships:
//                   a 320x240 image blown up 5x is legibly a 320x240 image, and
//                   on a 1920x1200 tablet that reads as a defect rather than as
//                   a style.
//
// Both take coordinates in the SAME 320x240 design grid, so every scene, every
// layout constant and every geometry test is shared. Only the rasterisation
// differs.
//
// Two composite primitives -- drawArc and fillMoon -- exist because they are the
// only shapes whose smooth form is not reachable from the flat primitives. The
// raster implementation keeps the firmware's segment-and-scanline construction;
// the vector one uses real arcs.
package ca.garionhk.cydclock.render

interface Surface {
    val width: Int
    val height: Int

    /**
     * Shift the origin. Cumulative, so callers undo with the negative -- see
     * [DeviceCanvas.withTranslation].
     *
     * This exists so a scene can keep drawing in its own 320-wide space while
     * being centred inside a wider viewport. The scenes' coordinates came from
     * the firmware and are worth leaving alone.
     */
    fun translate(dx: Int)

    fun fillScreen(color: Int)
    fun drawPixel(x: Int, y: Int, color: Int)
    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int)
    fun drawRect(x: Int, y: Int, w: Int, h: Int, color: Int)
    fun drawFastHLine(x: Int, y: Int, w: Int, color: Int)
    fun drawFastVLine(x: Int, y: Int, h: Int, color: Int)
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, color: Int)
    fun drawCircle(cx: Int, cy: Int, r: Int, color: Int)
    fun fillCircle(cx: Int, cy: Int, r: Int, color: Int)
    fun fillTriangle(x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int, color: Int)

    /** Open arc, angles in degrees measured anticlockwise from due east. */
    fun drawArc(cx: Int, cy: Int, r: Int, startDeg: Int, endDeg: Int, color: Int)

    /**
     * Moon disk with its terminator. [phase] is 0 new, 0.5 full, and the lit limb
     * is on the right while waxing.
     */
    fun fillMoon(cx: Int, cy: Int, r: Int, phase: Float, lit: Int, shadow: Int, outline: Int)

    // ---- text -------------------------------------------------------------
    fun textWidth(s: String, font: Int): Int

    /** The declared cell height. Layout arithmetic ported from the firmware uses this. */
    fun fontHeight(font: Int): Int

    /**
     * Height of the digit ink, which is what the eye reads as the size of a
     * number and what a middle datum centres on. It is much less than
     * [fontHeight] for the small fonts -- Font 2's digits fill 62% of their cell.
     * Vertical gaps between rows should be measured against this, not the cell.
     */
    fun inkHeight(font: Int): Int

    /**
     * Where the digit ink's centre falls relative to a middle-datum anchor.
     *
     * Zero for the vector renderer, which centres ink on the anchor by
     * construction. Not zero for the raster one, because TFT_eSPI centres the
     * declared CELL and the glyph sits wherever its fixed baseline puts it
     * inside that cell. Any layout that measures a gap between two rows has to
     * account for it, or the two renderers disagree by a few units.
     */
    fun inkCenterOffset(font: Int): Int
    fun drawText(s: String, x: Int, y: Int, datum: Int, font: Int, fg: Int, bg: Int)
}
