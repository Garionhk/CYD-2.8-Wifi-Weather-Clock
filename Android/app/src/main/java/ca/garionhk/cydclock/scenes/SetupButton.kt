// ===========================================================================
// SetupButton.kt -- the gear that opens settings, drawn on every scene
// ===========================================================================
// The firmware had no equivalent: settings lived in a web page the device
// served, and the clock printed its own IP so you could find it. Here they are
// one tap away, which means finding 20-odd free pixels on a 320x240 canvas that
// four different scenes are already using.
//
// Why the top-right corner
// ------------------------
// The status strip's gaps are narrower. Its widgets occupy x 10..84, 96..123,
// 146..158, 180..196, 216..227, and the four scene dots run 248..304, leaving a
// widest gap of 20 px -- and that gap sits right against the scene dots, the one
// strip widget a user might plausibly try to tap.
//
// Above y=28 on the right, the scenes are quieter. The binding constraint is
// Sun & Moon's sunrise value: Font 4, ML datum at x=232, y=34, measured at 63 px,
// so it occupies x 232..294 and rows 21..46. The glyph therefore starts at 296.
// That is one pixel of clearance, so it is asserted rather than assumed -- see
// SetupButtonCollisionTest.
package ca.garionhk.cydclock.scenes

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.render.DeviceCanvas
import kotlin.math.cos
import kotlin.math.sin

object SetupButton {

    const val CY = 12

    /**
     * Insets from the viewport's right edge, not absolute coordinates.
     *
     * The gear anchors to the real corner of whatever grid it is drawn into. In
     * fill mode that grid is wider than 320, and a fixed x would leave the gear
     * stranded in the middle of the screen.
     */
    private const val CX_INSET = 14
    private const val RESERVED_LEFT_INSET = 24
    private const val RESERVED_RIGHT_INSET = 2
    private const val HIT_LEFT_INSET = 28

    private const val BODY_R = 7
    private const val TOOTH_R = 9      // ring the teeth sit on
    private const val HOLE_R = 3

    const val RESERVED_TOP = 0
    const val RESERVED_BOTTOM = 24

    fun cx(viewportWidth: Int = Theme.SCREEN_W): Int = viewportWidth - CX_INSET

    /** The area the glyph paints, which no scene may draw into. */
    fun reservedLeft(viewportWidth: Int = Theme.SCREEN_W): Int = viewportWidth - RESERVED_LEFT_INSET
    fun reservedRight(viewportWidth: Int = Theme.SCREEN_W): Int = viewportWidth - RESERVED_RIGHT_INSET

    // Kept for the collision test, which reasons about the 320-wide grid.
    const val CX = Theme.SCREEN_W - CX_INSET
    const val RESERVED_LEFT = Theme.SCREEN_W - RESERVED_LEFT_INSET
    const val RESERVED_RIGHT = Theme.SCREEN_W - RESERVED_RIGHT_INSET

    /**
     * Where a press counts as hitting the button. Deliberately larger than the
     * glyph -- at a 5x scale this is roughly 140x160 real pixels -- because
     * nothing else in the corner is tappable and a missed press would silently
     * advance the scene instead.
     */
    private const val HIT_TOP = 0
    private const val HIT_BOTTOM = 32

    fun hits(x: Int, y: Int, viewportWidth: Int = Theme.SCREEN_W): Boolean =
        x >= viewportWidth - HIT_LEFT_INSET && x < viewportWidth &&
            y in HIT_TOP..HIT_BOTTOM

    fun draw(c: DeviceCanvas, viewportWidth: Int = Theme.SCREEN_W, pressed: Boolean = false) {
        val colour = if (pressed) Theme.COL_ACCENT else Theme.COL_DIM
        val cx = cx(viewportWidth)

        // Eight teeth at 45 degree steps. Truncating float->int, as the firmware's
        // icon routines do, so the teeth land on the same pixels every time.
        for (i in 0 until 8) {
            val a = i * 45.0f * Math.PI.toFloat() / 180.0f
            val tx = cx + (cos(a) * TOOTH_R).toInt()
            val ty = CY + (sin(a) * TOOTH_R).toInt()
            c.fillRect(tx - 1, ty - 1, 3, 3, colour)
        }

        c.fillCircle(cx, CY, BODY_R, colour)
        c.fillCircle(cx, CY, HOLE_R, Theme.COL_BG)
    }
}
