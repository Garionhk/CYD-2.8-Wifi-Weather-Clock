// ===========================================================================
// SunMoon.kt -- sunrise, sunset, golden hour and moon phase. Port of sun_moon.cpp
// ===========================================================================
// No API. This is why the panel stays correct with the network down, and it is
// worth keeping that way -- the whole scene works offline.
//
// The NOAA single-pass solar position is ported term for term. It is easy to
// "tidy" one of these polynomials and be twenty minutes wrong in December, so
// the constants are left exactly as the firmware has them.
//
// Zero android.* imports, so all of this is testable on the JVM.
package ca.garionhk.cydclock.time

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.data.AppSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

object SunMoon {

    private fun deg2rad(d: Double) = d * Math.PI / 180.0
    private fun rad2deg(r: Double) = r * 180.0 / Math.PI

    /** Julian Day at 0h UT for a calendar date (Gregorian). */
    fun julianDay(yIn: Int, mIn: Int, d: Int): Double {
        var y = yIn
        var m = mIn
        if (m <= 2) { y -= 1; m += 12 }
        val a = y / 100
        val b = 2 - a + a / 4
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    /**
     * NOAA single-pass solar position -> UTC epoch seconds of the sun reaching
     * [elevDeg], on the morning or evening side.
     *
     * Returns 0 when the sun never reaches that elevation on that date -- polar
     * day and polar night both land here. Callers must treat 0 as "no such
     * event", not as an epoch.
     */
    fun sunEventUTC(
        y: Int, m: Int, d: Int,
        elevDeg: Double,
        morning: Boolean,
        lat: Double, lon: Double,
    ): Long {
        val jd = julianDay(y, m, d)
        val t = (jd - 2451545.0) / 36525.0

        val l0 = (280.46646 + t * (36000.76983 + 0.0003032 * t)).mod(360.0)
        val mAnom = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mr = deg2rad(mAnom)
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mr) * (0.019993 - 0.000101 * t) +
            sin(3 * mr) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(deg2rad(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(deg2rad(omega))
        val delta = rad2deg(asin(sin(deg2rad(eps)) * sin(deg2rad(lambda))))

        var yv = tan(deg2rad(eps / 2.0)); yv *= yv
        val eqTime = 4.0 * rad2deg(
            yv * sin(2 * deg2rad(l0)) -
                2 * e * sin(mr) +
                4 * e * yv * sin(mr) * cos(2 * deg2rad(l0)) -
                0.5 * yv * yv * sin(4 * deg2rad(l0)) -
                1.25 * e * e * sin(2 * mr)
        )

        val zenith = 90.0 - elevDeg
        val cosH = (cos(deg2rad(zenith)) - sin(deg2rad(lat)) * sin(deg2rad(delta))) /
            (cos(deg2rad(lat)) * cos(deg2rad(delta)))
        if (cosH > 1.0 || cosH < -1.0) return 0    // sun never reaches this elevation

        val h = rad2deg(acos(cosH))
        val minutesUTC = 720.0 - 4.0 * (lon + (if (morning) h else -h)) - eqTime
        val midnightUTCepoch = (jd - 2440587.5) * 86400.0
        return (midnightUTCepoch + minutesUTC * 60.0).roundToLong()
    }

    fun moonPhaseName(p: Float): String = when {
        p < 0.03f || p > 0.97f -> "New Moon"
        p < 0.22f -> "Waxing Crescent"
        p < 0.28f -> "First Quarter"
        p < 0.47f -> "Waxing Gibbous"
        p < 0.53f -> "Full Moon"
        p < 0.72f -> "Waning Gibbous"
        p < 0.78f -> "Last Quarter"
        else -> "Waning Crescent"
    }

    /**
     * Golden hour: sun elevation between -4 and +6 degrees, computed for both the
     * morning and the evening window.
     *
     * The firmware reached tomorrow's date via mktime normalisation at local
     * noon, specifically to dodge DST edges. LocalDate.plusDays has no such
     * hazard, so the noon dance is not needed here -- but the result is the same
     * date, which is the point.
     */
    fun goldenHourStatus(nowEpoch: Long, zone: ZoneId, lat: Double, lon: Double): String {
        val today = Instant.ofEpochSecond(nowEpoch).atZone(zone).toLocalDate()
        val y = today.year; val mo = today.monthValue; val d = today.dayOfMonth

        val mStart = sunEventUTC(y, mo, d, -4.0, true, lat, lon)
        val mEnd = sunEventUTC(y, mo, d, 6.0, true, lat, lon)
        val eStart = sunEventUTC(y, mo, d, 6.0, false, lat, lon)
        val eEnd = sunEventUTC(y, mo, d, -4.0, false, lat, lon)

        if (mStart != 0L && nowEpoch >= mStart && nowEpoch <= mEnd) {
            return String.format(Locale.US, "now (%dm)", (mEnd - nowEpoch) / 60)
        }
        if (eStart != 0L && nowEpoch >= eStart && nowEpoch <= eEnd) {
            return String.format(Locale.US, "now (%dm)", (eEnd - nowEpoch) / 60)
        }

        var next = 0L
        if (mStart != 0L && mStart > nowEpoch) next = mStart
        else if (eStart != 0L && eStart > nowEpoch) next = eStart
        else {
            val t = today.plusDays(1)
            next = sunEventUTC(t.year, t.monthValue, t.dayOfMonth, -4.0, true, lat, lon)
        }
        if (next == 0L) return "--"

        val secs = next - nowEpoch
        return String.format(Locale.US, "in %dh %02dm", secs / 3600, (secs % 3600) / 60)
    }

    /**
     * Recompute the sun/moon block. Cheap and offline, so it is safe to call on
     * every scene entry -- but the tick loop only calls it on start, on a date
     * change, and when the location or timezone changes.
     */
    fun recompute(d: AppData, s: AppSettings, zone: ZoneId, nowEpoch: Long): AppData {
        val lat = s.latitude
        val lon = s.longitude
        val today: LocalDate = Instant.ofEpochSecond(nowEpoch).atZone(zone).toLocalDate()
        val tomorrow = today.plusDays(1)

        val sunriseToday = sunEventUTC(today.year, today.monthValue, today.dayOfMonth, -0.833, true, lat, lon)
        val sunsetToday = sunEventUTC(today.year, today.monthValue, today.dayOfMonth, -0.833, false, lat, lon)
        val sunriseTomorrow = sunEventUTC(tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth, -0.833, true, lat, lon)
        val sunsetTomorrow = sunEventUTC(tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth, -0.833, false, lat, lon)

        // DIVERGENCE (fixed): the firmware wrote `showingNextDay = now > sunsetToday`.
        // sunEventUTC returns 0 during polar day and polar night, so above the
        // Arctic circle that reduces to `now > 0` and the scene claims TOMORROW
        // permanently. Guarding on a real sunset costs nothing and is correct
        // everywhere the original was correct.
        val showingNextDay = sunsetToday != 0L && nowEpoch > sunsetToday

        // Moon phase from the synodic month since a known new moon (JD 2451550.1).
        val jdNow = 2440587.5 + nowEpoch / 86400.0
        var phase = (jdNow - 2451550.1) / 29.530588853
        phase -= floor(phase)

        return d.copy(
            sunriseToday = sunriseToday,
            sunsetToday = sunsetToday,
            sunriseTomorrow = sunriseTomorrow,
            sunsetTomorrow = sunsetTomorrow,
            showingNextDay = showingNextDay,
            moonPhase = phase.toFloat(),
            moonIlluminationPct = ((1.0 - cos(2 * Math.PI * phase)) / 2.0 * 100.0).toFloat(),
            goldenHour = goldenHourStatus(nowEpoch, zone, lat, lon),
        )
    }
}
