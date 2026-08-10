// ===========================================================================
// SunMoonScene.kt -- Scene 3. Port of scenes.cpp:424-562
// ===========================================================================
// Left: the sunrise-to-sunset arc with the sun's current position, and the moon
// phase disk beneath it. Right: rise and set times, UV, golden-hour countdown.
//
// After today's sunset the scene rolls forward to tomorrow: a TOMORROW banner
// appears and the arc greys out. That is deliberate -- it must never be ambiguous
// which day the times belong to, and a sunrise time with no marker on the arc
// would otherwise read as "this morning".
package ca.garionhk.cydclock.scenes

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.core.lround
import ca.garionhk.cydclock.render.Datum
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.time.SunMoon
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

object SunMoonScene : Scene {
    override val name = "Sun & Moon"
    override val dwellMs = 12_000L

    private const val ACX = 76
    private const val ACY_BASE = 96
    private const val ARC_R = 54

    private const val RX = 170          // right column label x
    private const val GOLDEN_LABEL_Y = 122
    private const val SM_GY = 140       // golden-hour value row

    /**
     * The golden-hour value is centred in the space to the right of the moon
     * caption rather than left-aligned under its label.
     *
     * The firmware put it at x=170, hard against the caption on the same row --
     * "Waning Crescent" ends at 166 and the value began four units later, so the
     * two read as one run of text while the right half of the row sat empty.
     *
     * The left bound is the column x, not the caption's actual end: moonTextX
     * guarantees the caption clears 170 whatever the phase, and keying off its
     * measured width would make this value drift left and right over a month as
     * the phase names changed length.
     */
    private const val GOLDEN_RIGHT_MARGIN = 8

    private fun goldenValueCx(ctx: SceneContext): Int =
        (ctx.sx(RX) + ctx.width - GOLDEN_RIGHT_MARGIN) / 2

    private const val MOON_CX = 40
    private const val MOON_CY = 152
    private const val MOON_R = 22

    /** Nominal start of the moon caption, when the name is short enough to fit. */
    private const val MOON_TEXT_X = 72

    /**
     * Where the moon caption starts, shifted left only as far as it needs to be.
     *
     * DIVERGENCE (fixed): the firmware hard-coded x=72 (scenes.cpp:512). The two
     * fifteen-character crescent names overran the right column from there, and
     * since drawGolden() afterwards cleared a 150 px rect from x=170, the panel
     * silently clipped their last letter -- only those two of the eight names
     * were long enough to hit it.
     *
     * Measuring instead of hard-coding matters more now than it did: the shipped
     * renderer sets this text in a scalable face whose widths differ from the
     * bitmap font's, so any constant tuned to one is wrong for the other.
     */
    fun moonTextX(c: DeviceCanvas, name: String, width: Int = Theme.SCREEN_W): Int {
        fun sx(x: Int) = x * width / Theme.SCREEN_W
        // Never overlap the moon disk on the left or the right column on the right.
        val minX = sx(MOON_CX) + MOON_R + 2
        val maxRight = sx(RX) - 4
        val fits = maxRight - c.textWidth(name, 2)
        return minOf(sx(MOON_TEXT_X), fits).coerceAtLeast(minX)
    }

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun uvColor(uv: Float): Int = when {
        uv < 3 -> Theme.C_GREEN
        uv < 6 -> Theme.C_YELLOW
        uv < 8 -> Theme.C_ORANGE
        uv < 11 -> Theme.C_RED
        else -> Theme.C_PURPLE
    }

    override fun draw(c: DeviceCanvas, ctx: SceneContext) {
        c.fillRect(0, 0, ctx.width, Theme.CONTENT_H, Theme.COL_BG)

        val d = ctx.data
        val rise = if (d.showingNextDay) d.sunriseTomorrow else d.sunriseToday
        val set = if (d.showingNextDay) d.sunsetTomorrow else d.sunsetToday

        // Two columns, so every anchor spreads with the grid.
        val arcCx = ctx.sx(ACX)
        val moonCx = ctx.sx(MOON_CX)
        val labelX = ctx.sx(RX)
        val valueX = ctx.sx(RX + 62)
        val goldenCx = goldenValueCx(ctx)

        // Left: sun arc and marker.
        drawSunArc(c, arcCx, ACY_BASE, ARC_R, if (d.showingNextDay) Theme.COL_DIM else Theme.COL_ACCENT)
        if (!d.showingNextDay && set > rise) {
            drawSunMarker(c, arcCx, ACY_BASE, ARC_R, (ctx.nowEpoch - rise).toFloat() / (set - rise).toFloat())
        }

        // Left-bottom: moon.
        drawMoon(c, moonCx, MOON_CY, MOON_R, d.moonPhase)
        val phaseName = SunMoon.moonPhaseName(d.moonPhase)
        c.setTextFont(2)
        val textX = moonTextX(c, phaseName, ctx.width)
        c.setTextColor(Theme.COL_TEXT, Theme.COL_BG)
        c.setTextDatum(Datum.ML)
        c.drawString(phaseName, textX, 144)
        c.setTextColor(Theme.COL_DIM, Theme.COL_BG)
        c.drawString(String.format(Locale.US, "%.0f%% lit", d.moonIlluminationPct), textX, 164)

        // Right column: TOMORROW banner, rise/set, UV.
        if (d.showingNextDay) {
            c.setTextColor(Theme.C_HI, Theme.COL_BG)
            c.setTextDatum(Datum.MC)
            c.setTextFont(2)
            c.drawString("TOMORROW", ctx.sx(240), 12)
        }

        // 0 means the sun never reached that elevation -- polar day or night.
        val riseText = if (rise != 0L) formatLocal(rise, ctx) else "--:--"
        val setText = if (set != 0L) formatLocal(set, ctx) else "--:--"

        c.setTextDatum(Datum.ML)
        c.setTextColor(Theme.COL_DATE, Theme.COL_BG)
        c.setTextFont(2)
        c.drawString("Sunrise", labelX, 34)
        c.drawString("Sunset", labelX, 64)
        c.drawString("UV", labelX, 94)

        c.setTextColor(Theme.COL_TEXT, Theme.COL_BG)
        c.setTextFont(4)
        c.drawString(riseText, valueX, 34)
        c.drawString(setText, valueX, 64)
        if (d.uvValid) {
            c.setTextColor(uvColor(d.uvIndex), Theme.COL_BG)
            c.drawString(lround(d.uvIndex).toString(), valueX, 94)
        } else {
            c.setTextColor(Theme.COL_DIM, Theme.COL_BG)
            c.drawString("--", valueX, 94)
        }

        // Golden hour: label over value, both centred on the same axis.
        //
        // The label leaves the left-aligned column that Sunrise, Sunset and UV
        // share. Those three are label-then-value on one line, so their labels
        // line up; this one is label above value, and a caption that does not sit
        // over the thing it names looks like it belongs to the row above.
        c.setTextColor(Theme.COL_DATE, Theme.COL_BG)
        c.setTextDatum(Datum.MC)
        c.setTextFont(2)
        c.drawString("Golden hour", goldenCx, GOLDEN_LABEL_Y)

        c.setTextColor(Theme.C_SUN, Theme.COL_BG)
        c.setTextDatum(Datum.MC)
        c.setTextFont(2)
        c.drawString(d.goldenHour, goldenCx, SM_GY)
    }

    private fun formatLocal(epoch: Long, ctx: SceneContext): String =
        Instant.ofEpochSecond(epoch).atZone(ctx.now.zone).format(HHMM)

    /** The daylight semicircle, plus a horizon rule. */
    private fun drawSunArc(c: DeviceCanvas, cx: Int, cyBase: Int, r: Int, colour: Int) {
        c.drawArc(cx, cyBase, r, 0, 180, colour)
        c.drawFastHLine(cx - r - 4, cyBase, 2 * r + 8, Theme.COL_DIM)
    }

    private fun drawSunMarker(c: DeviceCanvas, cx: Int, cyBase: Int, r: Int, fIn: Float) {
        val f = fIn.coerceIn(0f, 1f)
        val th = (180.0 - f * 180.0) * Math.PI / 180.0
        c.fillCircle(cx + lround(r * cos(th)), cyBase - lround(r * sin(th)), 5, Theme.C_SUN)
    }

    /** Moon disk: 0 is new, 0.5 is full, and the lit limb is right while waxing. */
    private fun drawMoon(c: DeviceCanvas, cx: Int, cy: Int, r: Int, phase: Float) {
        c.fillMoon(cx, cy, r, phase, Theme.C_MOON_LIT, Theme.C_MOON_SHADOW, Theme.COL_DIM)
    }
}
