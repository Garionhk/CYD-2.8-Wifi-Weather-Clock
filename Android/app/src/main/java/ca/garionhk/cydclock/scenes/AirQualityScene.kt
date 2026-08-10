// ===========================================================================
// AirQualityScene.kt -- Scene 4. Port of scenes.cpp:338-422
// ===========================================================================
// AQI headline colour-coded to the US bands, with the band name in words --
// the number alone means little to most people, and the colour is the part
// that reads across a room.
package ca.garionhk.cydclock.scenes

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.core.dispPress
import ca.garionhk.cydclock.core.dispWind
import ca.garionhk.cydclock.core.lround
import ca.garionhk.cydclock.core.pressUnit
import ca.garionhk.cydclock.core.useImperial
import ca.garionhk.cydclock.core.windUnit
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.render.Datum
import ca.garionhk.cydclock.render.DeviceCanvas
import java.util.Locale

data class AqiBand(val colour: Int, val name: String)

fun aqiBand(aqi: Int): AqiBand = when {
    aqi <= 50 -> AqiBand(Theme.C_GREEN, "Good")
    aqi <= 100 -> AqiBand(Theme.C_YELLOW, "Moderate")
    aqi <= 150 -> AqiBand(Theme.C_ORANGE, "Unhealthy (SG)")
    aqi <= 200 -> AqiBand(Theme.C_RED, "Unhealthy")
    aqi <= 300 -> AqiBand(Theme.C_PURPLE, "Very Unhealthy")
    else -> AqiBand(Theme.C_MAROON, "Hazardous")
}

object AirQualityScene : Scene {
    override val name = "Air Quality"
    override val dwellMs = 12_000L

    private const val HEADER_Y = 16
    private const val NUMBER_Y = 62

    /**
     * Where the band name sits: centred in the gap between the AQI number's ink
     * and the stats row below it.
     *
     * The firmware fixed this at y=112, which suited its own Font 8. Once that
     * font grew, "Good" ended up three units under the number and twenty-one
     * above the stats -- clearly attached to the wrong thing. Measuring the two
     * neighbours keeps it centred whatever either font does next, which matters
     * here because the app has two renderers with different metrics.
     */
    private fun bandNameY(c: DeviceCanvas): Int {
        val numberInkBottom = NUMBER_Y + c.inkCenterOffset(8) + c.inkHeight(8) / 2
        val statsInkTop = STAT_VALUE_Y + c.inkCenterOffset(4) - c.inkHeight(4) / 2
        val wantedInkCentre = (numberInkBottom + statsInkTop) / 2
        // Convert the wanted ink centre back into a datum, which is not the same
        // point on the raster surface.
        return wantedInkCentre - c.inkCenterOffset(4)
    }

    override fun draw(c: DeviceCanvas, ctx: SceneContext) {
        c.fillRect(0, 0, ctx.width, Theme.CONTENT_H, Theme.COL_BG)

        val d = ctx.data
        val s = ctx.settings
        val centre = ctx.width / 2

        if (!d.aqiValid) {
            c.setTextColor(Theme.COL_DIM, Theme.COL_BG)
            c.setTextDatum(Datum.MC)
            c.setTextFont(4)
            c.drawString("fetching air quality...", centre, Theme.CONTENT_H / 2)
            return
        }

        val band = aqiBand(d.aqi)

        c.setTextColor(Theme.COL_DIM, Theme.COL_BG)
        c.setTextDatum(Datum.MC)
        c.setTextFont(2)
        c.drawString("AIR QUALITY INDEX", centre, HEADER_Y)

        c.setTextColor(band.colour, Theme.COL_BG)
        c.setTextFont(8)
        c.drawString(d.aqi.toString(), centre, NUMBER_Y)

        c.setTextFont(4)
        c.drawString(band.name, centre, bandNameY(c))

        // Secondary row. Short labels so four columns fit; wind and pressure use
        // the configured units, and the wind column's label IS its unit.
        weatherStat(c, ctx.sx(42), d.pm25.toString(), "PM2.5")
        weatherStat(c, ctx.sx(116), "${d.humidityPct}%", "HUM")
        weatherStat(c, ctx.sx(190), lround(s.dispWind(d.windKph)).toString(), s.windUnit())
        pressureStat(c, ctx.sx(268), pressureText(s, d.pressureHpa), s.pressUnit(), d.pressureTrend)
    }

    /**
     * DIVERGENCE (fixed): the firmware printed the pressure with %d, so 29.92 inHg
     * displayed as "30" -- losing the only digit that carries meaning, since
     * inches of mercury barely move off 30.
     *
     * Two decimals measure 63 px in Font 4, so centred at cx=268 the value spans
     * 237..299 and the trend glyph lands at 305..313 on a 320 px screen. It fits;
     * FontMetricsTest pins that. Metric stays a whole number, as it was.
     */
    private fun pressureText(s: AppSettings, hpa: Float): String =
        if (s.useImperial()) String.format(Locale.US, "%.2f", s.dispPress(hpa))
        else lround(s.dispPress(hpa)).toString()

    /** Value, trend glyph, then the unit as a caption. */
    private fun pressureStat(c: DeviceCanvas, cx: Int, value: String, unit: String, trend: Float) {
        c.setTextDatum(Datum.MC)
        c.setTextColor(Theme.COL_TEXT, Theme.COL_BG)
        c.setTextFont(4)
        c.drawString(value, cx, 150)
        drawTrend(c, cx + c.textWidth(value) / 2 + 6, 150, trend)
        c.setTextColor(Theme.COL_DIM, Theme.COL_BG)
        c.setTextFont(2)
        c.drawString(unit, cx, 176)
    }

    /**
     * Rising, falling or steady. The threshold is +/-0.3 hPa against a delta
     * measured between successive fetches -- about fifteen minutes apart, not the
     * three hours a barometer's trend usually means.
     */
    private fun drawTrend(c: DeviceCanvas, x: Int, y: Int, trend: Float) {
        when {
            trend > 0.3f -> c.fillTriangle(x, y + 4, x + 8, y + 4, x + 4, y - 4, Theme.C_GREEN)
            trend < -0.3f -> c.fillTriangle(x, y - 4, x + 8, y - 4, x + 4, y + 4, Theme.C_RAIN)
            else -> c.fillRect(x, y - 1, 8, 3, Theme.COL_DIM)
        }
    }
}
