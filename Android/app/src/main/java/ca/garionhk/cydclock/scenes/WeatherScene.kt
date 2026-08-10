// ===========================================================================
// WeatherScene.kt -- Scene 2. Port of scenes.cpp:152-336
// ===========================================================================
// Left: vector condition icon and label. Right: big current temperature with
// feels-like beneath and today's range under that. Bottom: cloud, humidity, wind.
//
// Values convert to the configured units at draw time -- AppData stays metric,
// which is what lets a units change apply with no refetch.
package ca.garionhk.cydclock.scenes

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.core.dispTemp
import ca.garionhk.cydclock.core.dispWind
import ca.garionhk.cydclock.core.lround
import ca.garionhk.cydclock.core.tempUnit
import ca.garionhk.cydclock.core.windUnit
import ca.garionhk.cydclock.render.Datum
import ca.garionhk.cydclock.render.DeviceCanvas

object WeatherScene : Scene {
    override val name = "Weather"
    override val dwellMs = 12_000L

    override fun draw(c: DeviceCanvas, ctx: SceneContext) {
        c.fillRect(0, 0, ctx.width, Theme.CONTENT_H, Theme.COL_BG)

        val d = ctx.data
        val s = ctx.settings

        if (!d.weatherValid) {
            c.setTextColor(Theme.COL_DIM, Theme.COL_BG)
            c.setTextDatum(Datum.MC)
            c.setTextFont(4)
            c.drawString("fetching weather...", ctx.width / 2, Theme.CONTENT_H / 2)
            return
        }

        // Two independent columns and a three-cell row, so every anchor spreads.
        val iconCx = ctx.sx(70)
        val rightX = ctx.sx(168)

        // Left: icon + condition label.
        WeatherIcons.draw(c, iconCx, 56, d.weatherCode)
        c.setTextColor(Theme.COL_TEXT, Theme.COL_BG)
        c.setTextDatum(Datum.MC)
        c.setTextFont(2)
        c.drawString(wxLabel(d.weatherCode), iconCx, 110)

        // Right: big temperature, feels-like beneath.
        drawDegVal(c, rightX, 52, 6, lround(s.dispTemp(d.tempC)), s.tempUnit(), Theme.COL_TEXT)
        c.setTextColor(Theme.COL_DATE, Theme.COL_BG)
        c.setTextFont(2)
        c.setTextDatum(Datum.ML)
        c.drawString("Feels", rightX, 96)
        drawDegVal(
            c, rightX + c.textWidth("Feels") + 6, 96, 2,
            lround(s.dispTemp(d.feelsLikeC)), s.tempUnit(), Theme.COL_DATE,
        )

        // Today's range, once the daily forecast has landed. No unit letter --
        // the big temperature above already states C or F, and it keeps the row
        // compact.
        if (d.dailyValid) {
            var x = rightX
            triMark(c, x, 120, up = true, colour = Theme.C_HI)
            x = drawDegVal(c, x + 12, 120, 4, lround(s.dispTemp(d.tempMaxC)), "", Theme.C_HI)
            triMark(c, x + 14, 120, up = false, colour = Theme.C_LO)
            drawDegVal(c, x + 26, 120, 4, lround(s.dispTemp(d.tempMinC)), "", Theme.C_LO)
        }

        // Bottom: cloud, humidity, wind.
        weatherStat(c, ctx.sx(55), "${d.cloudCoverPct}%", "CLOUD")
        weatherStat(c, ctx.sx(160), "${d.humidityPct}%", "HUMIDITY")
        weatherStat(c, ctx.sx(265), "${lround(s.dispWind(d.windKph))} ${s.windUnit()}", "WIND")
    }
}
