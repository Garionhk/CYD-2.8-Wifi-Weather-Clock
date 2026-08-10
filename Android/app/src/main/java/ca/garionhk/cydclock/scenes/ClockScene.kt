// ===========================================================================
// ClockScene.kt -- Scene 1, the home scene. Port of scenes.cpp:16-136
// ===========================================================================
// Big HH:MM in Font 8, one digit per fixed cell, with a colon that blinks once a
// second to show the clock is live without spending space on a seconds field.
//
// The firmware drew each digit through a reusable 60x82 sprite and compared
// against pDig[4] so a minute tick only re-pushed one or two glyphs. Both the
// sprite and the comparison are gone -- they bought SPI bandwidth, which is not
// a currency here.
package ca.garionhk.cydclock.scenes

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.render.Datum
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.render.withTranslation
import java.time.format.DateTimeFormatter
import java.util.Locale

object ClockScene : Scene {
    override val name = "Clock"
    override val dwellMs = 35_000L

    // strftime("%a  %d %b %Y") runs in the C locale, so the panel shows English
    // month and weekday names. Locale.ENGLISH pins that -- and it has to be
    // pinned, because Fonts 2 and 4 are ASCII-only and a localised name would
    // render as blanks. The double space after the weekday is the firmware's.
    private val DATE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE  dd MMM yyyy", Locale.ENGLISH)

    override fun draw(c: DeviceCanvas, ctx: SceneContext) {
        c.fillRect(0, 0, ctx.width, Theme.CONTENT_H, Theme.COL_BG)
        // HH:MM is one number, not four columns, so the block is re-centred
        // rather than spread. Everything else on this scene is centred anyway.
        c.withTranslation(ctx.groupOffset) { drawContent(c, ctx) }
    }

    private fun drawContent(c: DeviceCanvas, ctx: SceneContext) {
        val t = ctx.now
        val digits = if (ctx.clockValid) {
            intArrayOf(t.hour / 10, t.hour % 10, t.minute / 10, t.minute % 10)
        } else {
            intArrayOf(-1, -1, -1, -1)   // "----" until the clock is valid
        }
        for (i in 0 until 4) drawDigit(c, i, digits[i])

        val colonOn = if (ctx.clockValid) t.second % 2 == 0 else true
        drawColon(c, colonOn)

        // Date line.
        val date = if (ctx.clockValid) t.format(DATE_FMT) else "syncing time..."
        c.fillRect(0, Theme.DATE_Y - 16, Theme.SCREEN_W, 32, Theme.COL_BG)
        c.setTextColor(Theme.COL_DATE, Theme.COL_BG)
        c.setTextDatum(Datum.MC)
        c.setTextFont(4)
        c.drawString(date, Theme.SCREEN_W / 2, Theme.DATE_Y)

        // Footer.
        //
        // DIVERGENCE: the firmware printed "setup: <ip>" here because there was
        // otherwise no way to find the config page without a serial monitor or
        // the router's client list. Settings are a button away now, so the line
        // only has to carry the half that is still news: whether we are offline.
        val footer = if (ctx.online) "" else "offline"
        c.fillRect(0, Theme.NET_Y - 8, Theme.SCREEN_W, 16, Theme.COL_BG)
        if (footer.isNotEmpty()) {
            c.setTextColor(Theme.COL_DIM, Theme.COL_BG)
            c.setTextDatum(Datum.MC)
            c.setTextFont(2)
            c.drawString(footer, Theme.SCREEN_W / 2, Theme.NET_Y)
        }
    }

    /** One digit centred in its fixed cell. A negative value renders as "-". */
    private fun drawDigit(c: DeviceCanvas, idx: Int, value: Int) {
        val x = Theme.DIGIT_X[idx]
        c.fillRect(x, Theme.DIGIT_TOP_Y, Theme.DIGIT_W, Theme.DIGIT_H, Theme.COL_BG)
        c.setTextColor(Theme.COL_TIME, Theme.COL_BG)
        c.setTextDatum(Datum.MC)
        c.setTextFont(8)
        c.drawString(
            if (value < 0) "-" else value.toString(),
            x + Theme.DIGIT_W / 2,
            Theme.DIGIT_TOP_Y + Theme.DIGIT_H / 2,
        )
    }

    /**
     * The colon grows with the digits, or it reads as a stray pair of dots
     * between two large numbers. The firmware's r=5 at +/-15 from the digit
     * centre becomes r=6 at +/-16 -- the same proportions, discretised.
     */
    private fun drawColon(c: DeviceCanvas, on: Boolean) {
        val col = if (on) Theme.COL_TIME else Theme.COL_BG
        val cy = Theme.DIGIT_TOP_Y + Theme.DIGIT_H / 2
        c.fillCircle(Theme.COLON_X, cy - 16, 6, col)
        c.fillCircle(Theme.COLON_X, cy + 16, 6, col)
    }
}

/**
 * Stand-in for scenes not yet ported. Port of scenes.cpp:141-150, which was dead
 * code in the firmware by the time it shipped -- here it is live again for one
 * milestone.
 */
class PlaceholderScene(
    override val name: String,
    override val dwellMs: Long = 12_000L,
) : Scene {
    override fun draw(c: DeviceCanvas, ctx: SceneContext) {
        c.fillRect(0, 0, Theme.SCREEN_W, Theme.CONTENT_H, Theme.COL_BG)
        c.setTextColor(Theme.COL_ACCENT, Theme.COL_BG)
        c.setTextDatum(Datum.MC)
        c.setTextFont(4)
        c.drawString(name, Theme.SCREEN_W / 2, Theme.CONTENT_H / 2 - 12)
        c.setTextColor(Theme.COL_DIM, Theme.COL_BG)
        c.setTextFont(2)
        c.drawString("coming soon", Theme.SCREEN_W / 2, Theme.CONTENT_H / 2 + 22)
    }
}
