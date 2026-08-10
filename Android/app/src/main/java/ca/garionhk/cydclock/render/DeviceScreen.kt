// ===========================================================================
// DeviceScreen.kt -- hosts the 320x240 design grid on the real display
// ===========================================================================
// Nothing is rasterised at 320x240 and magnified any more. The canvas is scaled
// by a real factor and the scene draws into it directly, so glyphs and curves
// are resolved at the display's own resolution.
//
// The scale is fractional and aspect-preserving. The old integer-only rule
// existed to keep nearest-neighbour upscaling from making some pixel columns
// wider than others; with antialiased vector output there is no such artefact,
// and rounding down to a whole factor would just waste screen.
package ca.garionhk.cydclock.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import ca.garionhk.cydclock.core.Theme
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the design grid sits on the real display, and how wide that grid is.
 *
 * [width] is the grid's width in design units and is 320 in letterboxed mode. In
 * fill mode it grows past 320 on a screen wider than 4:3 -- the grid widens
 * rather than the content being scaled up and pushed off the top and bottom.
 * [contentLeft] is where a scene's own 320-wide space begins inside it.
 */
data class Letterbox(
    val scale: Float,
    val originX: Float,
    val originY: Float,
    val width: Int = Theme.SCREEN_W,
) {
    val contentLeft: Int get() = (width - Theme.SCREEN_W) / 2

    /** Real viewport point -> design-grid point, clamped to the grid. */
    fun toDevice(px: Float, py: Float): Pair<Int, Int> {
        val x = ((px - originX) / scale).toInt().coerceIn(0, width - 1)
        val y = ((py - originY) / scale).toInt().coerceIn(0, Theme.SCREEN_H - 1)
        return x to y
    }
}

/**
 * Fill mode fits the HEIGHT and lets the grid grow sideways.
 *
 * The obvious reading of "fill the screen" -- scale until the larger axis is
 * covered -- crops. On a 20:9 phone that is a 7.5x scale against a 4.5x height,
 * so 48 design units disappear off the top, taking the setup gear with them, and
 * another 48 off the bottom, taking the status strip. Nothing that matters is
 * allowed to leave the screen, so instead the scale fits the height exactly and
 * the extra width becomes more grid: the status strip spans it and the gear
 * anchors to its real right edge.
 */
fun computeLetterbox(viewW: Float, viewH: Float, fillScreen: Boolean): Letterbox {
    if (!fillScreen) {
        val scale = min(viewW / Theme.SCREEN_W, viewH / Theme.SCREEN_H)
        return Letterbox(
            scale = scale,
            originX = (viewW - Theme.SCREEN_W * scale) / 2f,
            originY = (viewH - Theme.SCREEN_H * scale) / 2f,
        )
    }

    var scale = viewH / Theme.SCREEN_H
    var width = floor(viewW / scale).toInt()
    if (width < Theme.SCREEN_W) {
        // Narrower than 4:3 -- fit the width instead, or the sides would crop.
        scale = viewW / Theme.SCREEN_W
        width = Theme.SCREEN_W
    }
    return Letterbox(
        scale = scale,
        originX = (viewW - width * scale) / 2f,
        originY = (viewH - Theme.SCREEN_H * scale) / 2f,
        width = width,
    )
}

@Composable
fun DeviceScreen(
    fonts: VectorFonts,
    frameTick: Int,
    render: (DeviceCanvas, Letterbox) -> Unit,
    modifier: Modifier = Modifier,
    fillScreen: Boolean = false,
    onPressStart: (x: Int, y: Int, viewportWidth: Int) -> Unit = { _, _, _ -> },
    onPressEnd: () -> Unit = {},
) {
    // The pointer handler needs the same mapping the draw pass computed.
    val box = remember { mutableStateOf(Letterbox(1f, 0f, 0f)) }

    Canvas(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val (dx, dy) = box.value.toDevice(down.position.x, down.position.y)
                    onPressStart(dx, dy, box.value.width)
                    // Gestures are classified on release, so this loop only waits
                    // for the finger to leave.
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    onPressEnd()
                }
            }
    ) {
        // Read the tick inside the draw lambda so a new frame invalidates.
        @Suppress("UNUSED_EXPRESSION")
        frameTick

        val lb = computeLetterbox(size.width, size.height, fillScreen)
        box.value = lb

        drawIntoCanvas { compose ->
            val native = compose.nativeCanvas
            val saved = native.save()
            native.translate(lb.originX, lb.originY)
            native.scale(lb.scale, lb.scale)
            native.clipRect(0f, 0f, lb.width.toFloat(), Theme.SCREEN_H.toFloat())
            render(DeviceCanvas(VectorSurface(native, fonts, lb.width)), lb)
            native.restoreToCount(saved)
        }
    }
}

/** Kept for tests that still reason in whole-pixel terms. */
fun Letterbox.scaleInt(): Int = scale.roundToInt()
