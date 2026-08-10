// ===========================================================================
// WeatherIcons.kt -- vector condition icons. Port of scenes.cpp:159-241
// ===========================================================================
// There is not a single bitmap asset in the firmware repo, and there never will
// be here either: every icon is primitives.
//
// The truncation matters. C converts float to int by truncating toward zero, so
// iconCloud(s = 16) draws radii of 11 and 12 -- from 11.2 and 12.8 -- and a body
// 12 px tall. roundToInt() would give 11, 13 and 13, which is a visibly different
// cloud. Everything here goes through trunc() for that reason, and the trig runs
// in Float because the firmware used cosf/sinf and the truncation boundaries have
// to land in the same places.
package ca.garionhk.cydclock.scenes

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.core.trunc
import ca.garionhk.cydclock.render.DeviceCanvas
import kotlin.math.cos
import kotlin.math.sin

enum class WxCat { CLEAR, PARTLY, CLOUD, FOG, RAIN, SNOW, THUNDER }

/** WMO weather codes, as Open-Meteo reports them. */
fun wxCategory(code: Int): WxCat = when {
    code == 0 -> WxCat.CLEAR
    code == 1 || code == 2 -> WxCat.PARTLY
    code == 3 -> WxCat.CLOUD
    code == 45 || code == 48 -> WxCat.FOG
    (code in 51..67) || (code in 80..82) -> WxCat.RAIN
    (code in 71..77) || code == 85 || code == 86 -> WxCat.SNOW
    code >= 95 -> WxCat.THUNDER
    else -> WxCat.CLOUD
}

fun wxLabel(code: Int): String = when (wxCategory(code)) {
    WxCat.CLEAR -> "Clear"
    WxCat.PARTLY -> "Partly Cloudy"
    WxCat.CLOUD -> "Cloudy"
    WxCat.FOG -> "Fog"
    WxCat.RAIN -> "Rain"
    WxCat.SNOW -> "Snow"
    WxCat.THUNDER -> "Thunderstorm"
}

object WeatherIcons {

    private const val PI_F = Math.PI.toFloat()

    /** Disc plus eight rays, at 45 degree steps from radius r+4 out to r+10. */
    fun sun(c: DeviceCanvas, cx: Int, cy: Int, r: Int, colour: Int) {
        for (a in 0 until 8) {
            val ang = a * PI_F / 4.0f
            c.drawLine(
                trunc(cx + cos(ang) * (r + 4)), trunc(cy + sin(ang) * (r + 4)),
                trunc(cx + cos(ang) * (r + 10)), trunc(cy + sin(ang) * (r + 10)),
                colour,
            )
        }
        c.fillCircle(cx, cy, r, colour)
    }

    /** Three overlapping discs on a flat base. */
    fun cloud(c: DeviceCanvas, cx: Int, cy: Int, s: Int, colour: Int) {
        c.fillCircle(cx - s, cy, trunc(s * 0.7f), colour)
        c.fillCircle(cx + s, cy, trunc(s * 0.8f), colour)
        c.fillCircle(cx, trunc(cy - s * 0.5f), s, colour)
        c.fillRect(cx - s, cy, s * 2, trunc(s * 0.8f), colour)
    }

    fun draw(c: DeviceCanvas, cx: Int, cy: Int, code: Int) {
        when (wxCategory(code)) {
            WxCat.CLEAR ->
                sun(c, cx, cy, 18, Theme.C_SUN)

            WxCat.PARTLY -> {
                sun(c, cx - 10, cy - 10, 12, Theme.C_SUN)
                cloud(c, cx + 6, cy + 6, 13, Theme.C_CLOUD)
            }

            WxCat.CLOUD ->
                cloud(c, cx, cy, 16, Theme.C_CLOUD)

            WxCat.FOG -> {
                cloud(c, cx, cy - 6, 14, Theme.C_CLOUD)
                for (i in 0 until 3) c.drawFastHLine(cx - 18, cy + 12 + i * 5, 36, Theme.C_FOG)
            }

            WxCat.RAIN -> {
                cloud(c, cx, cy - 8, 15, Theme.C_CLOUD)
                for (i in -1..1) {
                    c.drawLine(cx + i * 12, cy + 12, cx + i * 12 - 4, cy + 24, Theme.C_RAIN)
                }
            }

            WxCat.SNOW -> {
                cloud(c, cx, cy - 8, 15, Theme.C_CLOUD)
                for (i in -1..1) c.fillCircle(cx + i * 12, cy + 18, 2, Theme.COL_TEXT)
            }

            WxCat.THUNDER -> {
                cloud(c, cx, cy - 8, 15, Theme.C_CLOUD)
                c.fillTriangle(cx - 2, cy + 8, cx + 8, cy + 8, cx - 4, cy + 24, Theme.C_SUN)
                c.fillTriangle(cx + 4, cy + 14, cx - 6, cy + 26, cx + 2, cy + 26, Theme.C_SUN)
            }
        }
    }
}
