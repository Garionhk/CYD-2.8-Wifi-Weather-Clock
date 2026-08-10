// ===========================================================================
// StatusStrip.kt -- the persistent bottom strip. Port of app/status_strip.cpp
// ===========================================================================
// Drawn on every scene, so its geometry constrains everything else -- including
// where the setup button can go.
//
// The firmware's six cache variables are gone with the partial-redraw scheme.
// What is kept exactly is the geometry and the meaning of each widget, because
// the strip is the only place the display's state is legible: a pinned scene
// that showed no pin glyph would just look like a broken clock.
package ca.garionhk.cydclock.scenes

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.input.GestureClassifier
import ca.garionhk.cydclock.render.Datum
import ca.garionhk.cydclock.render.DeviceCanvas

data class StripState(
    val timeText: String,
    val wifiLevel: Int,
    val weatherValid: Boolean,
    val weatherUpdatedAt: Long,
    val nowEpoch: Long,
    val pinned: Boolean,
    val frozen: Boolean,
    /** How long the finger has been down, 0 when nothing is pressed. */
    val heldMs: Long,
    val sceneIndex: Int,
    val sceneCount: Int,
)

object StatusStrip {

    // ---- geometry within the strip ----------------------------------------
    //
    // The left group is anchored to the left edge and the scene dots to the
    // right. On the 320-wide grid that reproduces the firmware exactly; on a
    // wider one the bar spans the screen and the two groups move apart rather
    // than the whole strip being stranded in the middle.
    private const val TIME_X = 10          // left, ML datum
    private const val WIFI_X = 96          // bar group left edge
    private val WIFI_BASE = Theme.STATUS_Y + 34
    private const val FRESH_X = 152
    private const val PIN_X = 188          // pin glyph centre
    private const val FREEZE_X = 216       // pause glyph left edge
    private const val DOTS_RIGHT_INSET = 20  // right-most scene dot, from the right edge

    fun draw(c: DeviceCanvas, s: StripState, viewportWidth: Int = Theme.SCREEN_W) {
        c.fillRect(0, Theme.STATUS_Y, viewportWidth, Theme.STATUS_H, Theme.COL_STRIP_BG)
        c.drawFastHLine(0, Theme.STATUS_Y, viewportWidth, Theme.COL_DIM)   // divider

        drawTime(c, s.timeText)
        drawWifi(c, s.wifiLevel)
        c.fillCircle(FRESH_X, Theme.STATUS_CY, 6, freshnessColor(s))
        drawPinGlyph(c, pinGlyphColour(s))
        drawFreeze(c, s.frozen)
        drawSceneDots(c, s.sceneIndex, s.sceneCount, viewportWidth - DOTS_RIGHT_INSET)
    }

    /** RSSI -> bars, using the firmware's thresholds. */
    fun wifiLevelFromRssi(online: Boolean, rssi: Int?): Int {
        if (!online) return 0
        if (rssi == null || rssi == -127) return 4   // online, but no usable RSSI
        return when {
            rssi >= -60 -> 4
            rssi >= -68 -> 3
            rssi >= -75 -> 2
            else -> 1
        }
    }

    private fun freshnessColor(s: StripState): Int {
        if (!s.weatherValid || s.weatherUpdatedAt == 0L) return Theme.COL_FRESH_NONE
        val age = if (s.nowEpoch > s.weatherUpdatedAt) s.nowEpoch - s.weatherUpdatedAt else 0
        return when {
            age < 1800 -> Theme.COL_FRESH_OK      // < 30 min
            age < 7200 -> Theme.COL_FRESH_WARN    // < 2 h
            else -> Theme.COL_FRESH_OLD
        }
    }

    private fun drawTime(c: DeviceCanvas, hhmm: String) {
        c.fillRect(TIME_X, Theme.STATUS_Y + 6, 74, Theme.STATUS_H - 12, Theme.COL_STRIP_BG)
        c.setTextColor(Theme.COL_TEXT, Theme.COL_STRIP_BG)
        c.setTextDatum(Datum.ML)
        c.setTextFont(4)
        c.drawString(hhmm, TIME_X, Theme.STATUS_CY)
    }

    private fun drawWifi(c: DeviceCanvas, level: Int) {
        for (i in 0 until 4) {
            val h = 8 + i * 6
            val x = WIFI_X + i * 9
            val y = WIFI_BASE - h
            if (i < level) {
                c.fillRect(x, y, 6, h, Theme.COL_ACCENT)
            } else {
                c.fillRect(x, y, 6, h, Theme.COL_STRIP_BG)
                c.drawRect(x, y, 6, h, Theme.COL_DIM)
            }
        }
    }

    /**
     * Thumbtack: round head, tapering shaft. Pass COL_STRIP_BG to hide it.
     *
     * A dimmed glyph means "still holding, release now and it pins"; cyan means
     * the hold has passed 4 s and releasing opens settings instead. Without that
     * feedback a long press is a blind wait with no way to tell it registered --
     * which is why the strip refreshes faster while a finger is down.
     */
    private fun drawPinGlyph(c: DeviceCanvas, colour: Int) {
        c.fillRect(PIN_X - 8, Theme.STATUS_CY - 11, 16, 22, Theme.COL_STRIP_BG)
        if (colour == Theme.COL_STRIP_BG) return
        c.fillCircle(PIN_X, Theme.STATUS_CY - 4, 6, colour)
        c.fillTriangle(
            PIN_X - 3, Theme.STATUS_CY + 1,
            PIN_X + 3, Theme.STATUS_CY + 1,
            PIN_X, Theme.STATUS_CY + 10,
            colour,
        )
    }

    private fun pinGlyphColour(s: StripState): Int = when {
        s.heldMs >= GestureClassifier.SETTINGS_MS -> Theme.COL_FREEZE  // release -> settings
        s.heldMs >= GestureClassifier.LONG_MS -> Theme.COL_DIM         // release -> pin / unpin
        s.pinned -> Theme.COL_PIN
        else -> Theme.COL_STRIP_BG
    }

    /** Pause bars: auto-rotation is temporarily frozen after a tap. */
    private fun drawFreeze(c: DeviceCanvas, on: Boolean) {
        c.fillRect(FREEZE_X, Theme.STATUS_CY - 8, 12, 16, Theme.COL_STRIP_BG)
        if (!on) return
        c.fillRect(FREEZE_X, Theme.STATUS_CY - 7, 4, 14, Theme.COL_FREEZE)
        c.fillRect(FREEZE_X + 7, Theme.STATUS_CY - 7, 4, 14, Theme.COL_FREEZE)
    }

    private fun drawSceneDots(c: DeviceCanvas, index: Int, count: Int, rightMost: Int) {
        for (i in 0 until count) {
            val cx = rightMost - (count - 1 - i) * 16
            if (i == index) {
                c.fillCircle(cx, Theme.STATUS_CY, 4, Theme.COL_ACCENT)
            } else {
                c.fillCircle(cx, Theme.STATUS_CY, 4, Theme.COL_STRIP_BG)
                c.drawCircle(cx, Theme.STATUS_CY, 4, Theme.COL_DIM)
            }
        }
    }
}
