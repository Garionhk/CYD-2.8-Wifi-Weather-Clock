// ===========================================================================
// ViewportTest.kt -- fill mode must not push anything off the screen
// ===========================================================================
// The failure this guards: "fill the screen" originally scaled until the larger
// axis was covered, which on a 20:9 phone meant a 7.5x scale against a 4.5x
// height. Forty-eight design units went off the top, taking the setup gear, and
// another forty-eight off the bottom, taking the status strip.
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.render.computeLetterbox
import ca.garionhk.cydclock.scenes.SetupButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real landscape viewports, longest edge first. */
private val SCREENS = listOf(
    "4:3 tablet" to (1920f to 1440f),
    "16:10 tablet" to (1920f to 1200f),
    "16:9" to (1920f to 1080f),
    "19.5:9 phone" to (2340f to 1080f),
    "20:9 phone" to (2400f to 1080f),
    "21:9 phone" to (2520f to 1080f),
)

class ViewportTest {

    @Test
    fun `letterboxed mode keeps the 320 grid and centres it`() {
        for ((name, size) in SCREENS) {
            val (w, h) = size
            val box = computeLetterbox(w, h, fillScreen = false)
            assertEquals("$name: grid should stay 320 wide", Theme.SCREEN_W, box.width)
            assertTrue("$name: content must not overflow", box.originX >= -0.01f)
            assertTrue("$name: content must not overflow", box.originY >= -0.01f)
            assertTrue("$name: right edge off screen", box.originX + 320 * box.scale <= w + 0.01f)
            assertTrue("$name: bottom edge off screen", box.originY + 240 * box.scale <= h + 0.01f)
        }
    }

    @Test
    fun `fill mode never pushes content off the screen`() {
        for ((name, size) in SCREENS) {
            val (w, h) = size
            val box = computeLetterbox(w, h, fillScreen = true)
            assertTrue("$name: top is cropped (originY=${box.originY})", box.originY >= -0.01f)
            assertTrue("$name: left is cropped (originX=${box.originX})", box.originX >= -0.01f)
            assertTrue(
                "$name: bottom is cropped",
                box.originY + Theme.SCREEN_H * box.scale <= h + 0.01f,
            )
            assertTrue(
                "$name: right is cropped",
                box.originX + box.width * box.scale <= w + 0.01f,
            )
        }
    }

    @Test
    fun `fill mode uses the full height and widens the grid instead`() {
        val box = computeLetterbox(2400f, 1080f, fillScreen = true)
        assertEquals("should fit the height exactly", 1080f / 240f, box.scale, 0.001f)
        assertTrue("grid should widen past 320, was ${box.width}", box.width > 320)
        // 2400 / 4.5 = 533
        assertEquals(533, box.width)
        assertEquals("nothing left over vertically", 0f, box.originY, 0.01f)
    }

    @Test
    fun `a 4 by 3 screen looks the same in both modes`() {
        val fit = computeLetterbox(1920f, 1440f, fillScreen = false)
        val fill = computeLetterbox(1920f, 1440f, fillScreen = true)
        assertEquals(fit.scale, fill.scale, 0.001f)
        assertEquals(fit.width, fill.width)
    }

    @Test
    fun `a screen narrower than 4 by 3 fits the width rather than cropping the sides`() {
        // The app is landscape-locked, but a foldable's inner display can be
        // close to square and a free-form window can be anything.
        val box = computeLetterbox(1000f, 1000f, fillScreen = true)
        assertEquals(Theme.SCREEN_W, box.width)
        assertEquals(1000f / 320f, box.scale, 0.001f)
        assertTrue("must not crop the sides", box.originX >= -0.01f)
    }

    // ---- the chrome follows the grid --------------------------------------

    @Test
    fun `the setup gear anchors to the grid's right edge`() {
        for (width in intArrayOf(320, 400, 533, 640)) {
            val cx = SetupButton.cx(width)
            assertEquals("gear should sit 14 units in from the right", width - 14, cx)
            assertTrue("gear must be on screen", cx < width)
            assertTrue(
                "hit rect must reach the gear",
                SetupButton.hits(cx, SetupButton.CY, width),
            )
            assertTrue(
                "hit rect must not swallow the middle of a wide screen",
                !SetupButton.hits(width / 2, SetupButton.CY, width),
            )
        }
    }

    @Test
    fun `the gear's hit rect follows it and does not stay at 320`() {
        // The bug this pins: a fixed hit rect at x 292..319 would be unreachable
        // dead space in the middle of a 533-wide grid, while the visible gear at
        // 519 would not respond at all.
        val wide = 533
        assertTrue("gear at its real position must be tappable", SetupButton.hits(519, 12, wide))
        assertTrue("the old 320 position must no longer hit", !SetupButton.hits(300, 12, wide))
    }

    @Test
    fun `touch mapping covers the whole widened grid`() {
        val box = computeLetterbox(2400f, 1080f, fillScreen = true)
        val (rightX, _) = box.toDevice(2399f, 10f)
        assertEquals("the far right of the screen should map to the grid's edge",
            box.width - 1, rightX)
        val (leftX, _) = box.toDevice(0f, 10f)
        assertEquals(0, leftX)
    }
}
