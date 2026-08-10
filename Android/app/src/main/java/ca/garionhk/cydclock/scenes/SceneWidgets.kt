// ===========================================================================
// SceneWidgets.kt -- the small drawing helpers shared by the data scenes
// ===========================================================================
// Ports of drawDegVal, triMark, weatherStat and pressureStat from scenes.cpp.
// Both of the hand-drawn glyphs exist for the same reason: the built-in fonts
// have neither a degree symbol nor arrows.
package ca.garionhk.cydclock.scenes

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.render.Datum
import ca.garionhk.cydclock.render.DeviceCanvas

/**
 * Draw "<value>°<unit>" left-anchored at (x, yMid) in [font], returning the x
 * just past the unit so callers can chain.
 *
 * The ring is drawn by hand. Fonts 6 and 8 are digits-only, which is also why
 * the unit letter switches to Font 2 -- in Font 6 a "C" would come out blank.
 */
fun drawDegVal(
    c: DeviceCanvas,
    x: Int,
    yMid: Int,
    font: Int,
    value: Int,
    unit: String,
    colour: Int,
): Int {
    c.setTextFont(font)
    c.setTextDatum(Datum.ML)
    c.setTextColor(colour, Theme.COL_BG)

    val s = value.toString()
    c.drawString(s, x, yMid)

    val nx = x + c.textWidth(s)
    val fh = c.fontHeight()
    val r = if (font >= 6) 4 else 2
    val ringX = nx + r + 3
    val ringY = yMid - fh / 2 + r + 2
    c.drawCircle(ringX, ringY, r, colour)
    if (r > 2) c.drawCircle(ringX, ringY, r - 1, colour)

    val ux = ringX + r + 3
    c.setTextFont(2)
    c.drawString(unit, ux, yMid)
    return ux + c.textWidth(unit)
}

/** 9x9 up/down triangle, vertically centred on [yMid]. */
fun triMark(c: DeviceCanvas, x: Int, yMid: Int, up: Boolean, colour: Int) {
    if (up) c.fillTriangle(x, yMid + 4, x + 8, yMid + 4, x + 4, yMid - 4, colour)
    else c.fillTriangle(x, yMid - 4, x + 8, yMid - 4, x + 4, yMid + 4, colour)
}

/** The bottom stats row, shared by the Weather and Air Quality scenes. */
const val STAT_VALUE_Y = 150
const val STAT_LABEL_Y = 176

/** A big value over a small caption, centred on [cx]. */
fun weatherStat(c: DeviceCanvas, cx: Int, big: String, label: String) {
    c.setTextDatum(Datum.MC)
    c.setTextColor(Theme.COL_TEXT, Theme.COL_BG)
    c.setTextFont(4)
    c.drawString(big, cx, STAT_VALUE_Y)
    c.setTextColor(Theme.COL_DIM, Theme.COL_BG)
    c.setTextFont(2)
    c.drawString(label, cx, STAT_LABEL_Y)
}
