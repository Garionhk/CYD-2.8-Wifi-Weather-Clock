// ===========================================================================
// SunMoonParityTest.kt -- checks the NOAA port against known astronomy
// ===========================================================================
// The firmware prints its sunrise/sunset/moon values to Serial once per boot
// specifically so they can be checked against an almanac (sun_moon.cpp:136-149).
// These are the same checks, automated.
//
// The assertions are anchored on facts that do not depend on a particular
// almanac edition: solstice and equinox day lengths, polar day and polar night,
// and the synodic month the moon phase is defined by. A term dropped from one of
// the NOAA polynomials moves these by far more than the tolerances allow.
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.time.SunMoon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private const val LONDON_LAT = 51.4779
private const val LONDON_LON = -0.0015

/** Day length in minutes, or null if the sun never rose or never set. */
private fun dayLengthMin(y: Int, m: Int, d: Int, lat: Double, lon: Double): Long? {
    val rise = SunMoon.sunEventUTC(y, m, d, -0.833, true, lat, lon)
    val set = SunMoon.sunEventUTC(y, m, d, -0.833, false, lat, lon)
    if (rise == 0L || set == 0L) return null
    return (set - rise) / 60
}

class SunMoonParityTest {

    // ---- julian day -------------------------------------------------------

    @Test
    fun `julian day matches known epochs`() {
        assertEquals(2451545.0, SunMoon.julianDay(2000, 1, 1) + 0.5, 1e-6)  // J2000.0 is noon
        assertEquals(2440587.5, SunMoon.julianDay(1970, 1, 1), 1e-6)        // Unix epoch
        // The m <= 2 branch: January and February belong to the previous year.
        assertEquals(2451575.0, SunMoon.julianDay(2000, 1, 31) + 0.5, 1e-6)
    }

    // ---- day length -------------------------------------------------------

    @Test
    fun `equator equinox day is about twelve hours and seven minutes`() {
        // Exactly 12 h would be the geometric answer. Sunrise is defined at -0.833
        // degrees to allow for refraction and the solar disc, which adds roughly
        // seven minutes at the equator. If this comes out at 12h00 the elevation
        // argument is being ignored.
        val len = dayLengthMin(2026, 3, 20, 0.0, 0.0)!!
        assertTrue("equinox day length was $len min", len in 720..735)
    }

    @Test
    fun `london june solstice is about sixteen hours forty`() {
        val len = dayLengthMin(2026, 6, 21, LONDON_LAT, LONDON_LON)!!
        assertTrue("June solstice day length was $len min", len in 990..1005)  // 16h30-16h45
    }

    @Test
    fun `london december solstice is about seven hours fifty`() {
        val len = dayLengthMin(2026, 12, 21, LONDON_LAT, LONDON_LON)!!
        assertTrue("December solstice day length was $len min", len in 465..480)  // 7h45-8h00
    }

    @Test
    fun `day length grows then shrinks across the year in the north`() {
        val jan = dayLengthMin(2026, 1, 15, LONDON_LAT, LONDON_LON)!!
        val apr = dayLengthMin(2026, 4, 15, LONDON_LAT, LONDON_LON)!!
        val jul = dayLengthMin(2026, 7, 15, LONDON_LAT, LONDON_LON)!!
        val oct = dayLengthMin(2026, 10, 15, LONDON_LAT, LONDON_LON)!!
        assertTrue("$jan < $apr < $jul", jan < apr && apr < jul)
        assertTrue("$oct < $jul", oct < jul)
    }

    @Test
    fun `southern hemisphere seasons are inverted`() {
        val sydneyLat = -33.8688
        val sydneyLon = 151.2093
        val jun = dayLengthMin(2026, 6, 21, sydneyLat, sydneyLon)!!
        val dec = dayLengthMin(2026, 12, 21, sydneyLat, sydneyLon)!!
        assertTrue("Sydney June $jun should be shorter than December $dec", jun < dec)
    }

    // ---- the polar zero return -------------------------------------------

    @Test
    fun `polar night and polar day both return zero`() {
        val svalbardLat = 78.2232
        val svalbardLon = 15.6267
        assertEquals(
            "polar night should have no sunrise",
            0L, SunMoon.sunEventUTC(2026, 12, 21, -0.833, true, svalbardLat, svalbardLon),
        )
        assertEquals(
            "polar day should have no sunset",
            0L, SunMoon.sunEventUTC(2026, 6, 21, -0.833, false, svalbardLat, svalbardLon),
        )
    }

    @Test
    fun `showingNextDay is not stuck true during polar night`() {
        // The firmware's `now > sunsetToday` reduces to `now > 0` when
        // sunEventUTC returns 0, so above the Arctic circle the Sun & Moon scene
        // showed TOMORROW permanently. This is the fix.
        val s = AppSettings(latitude = 78.2232, longitude = 15.6267)
        val noon = ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, ZoneId.of("Arctic/Longyearbyen"))
        val d = SunMoon.recompute(AppData(), s, ZoneId.of("Arctic/Longyearbyen"), noon.toEpochSecond())
        assertEquals(0L, d.sunsetToday)
        assertTrue("TOMORROW banner should not show during polar night", !d.showingNextDay)
    }

    // ---- roll forward at sunset, not midnight -----------------------------

    @Test
    fun `showingNextDay flips at sunset rather than at midnight`() {
        val s = AppSettings(latitude = LONDON_LAT, longitude = LONDON_LON)
        val zone = ZoneId.of("Europe/London")
        val day = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, zone).toEpochSecond()
        val sunset = SunMoon.recompute(AppData(), s, zone, day).sunsetToday

        val before = SunMoon.recompute(AppData(), s, zone, sunset - 600)
        val after = SunMoon.recompute(AppData(), s, zone, sunset + 600)
        assertTrue("before sunset should still show today", !before.showingNextDay)
        assertTrue("after sunset should roll forward", after.showingNextDay)
    }

    // ---- moon -------------------------------------------------------------

    @Test
    fun `moon phase is new at the reference epoch and full half a month later`() {
        // JD 2451550.1 = 2000-01-06T14:24Z, the new moon the formula is anchored to.
        val newMoon = Instant.parse("2000-01-06T14:24:00Z").epochSecond
        val s = AppSettings(latitude = LONDON_LAT, longitude = LONDON_LON)
        val zone = ZoneId.of("UTC")

        val atNew = SunMoon.recompute(AppData(), s, zone, newMoon)
        assertEquals(0.0f, atNew.moonPhase, 0.001f)
        assertEquals(0.0f, atNew.moonIlluminationPct, 0.1f)
        assertEquals("New Moon", SunMoon.moonPhaseName(atNew.moonPhase))

        val halfSynodic = (29.530588853 / 2.0 * 86400.0).toLong()
        val atFull = SunMoon.recompute(AppData(), s, zone, newMoon + halfSynodic)
        assertEquals(0.5f, atFull.moonPhase, 0.001f)
        assertEquals(100.0f, atFull.moonIlluminationPct, 0.1f)
        assertEquals("Full Moon", SunMoon.moonPhaseName(atFull.moonPhase))
    }

    @Test
    fun `moon phase names cover the whole cycle without a gap`() {
        val seen = mutableSetOf<String>()
        var p = 0.0f
        while (p < 1.0f) {
            seen += SunMoon.moonPhaseName(p)
            p += 0.005f
        }
        assertEquals(
            setOf(
                "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
                "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent",
            ),
            seen,
        )
    }

    // ---- golden hour ------------------------------------------------------

    @Test
    fun `golden hour reports now inside the evening window`() {
        val zone = ZoneId.of("Europe/London")
        // The evening window runs from +6 down to -4 degrees.
        val start = SunMoon.sunEventUTC(2026, 6, 21, 6.0, false, LONDON_LAT, LONDON_LON)
        val inside = start + 300
        val s = SunMoon.goldenHourStatus(inside, zone, LONDON_LAT, LONDON_LON)
        assertTrue("expected a 'now (Nm)' string, got '$s'", s.startsWith("now ("))
    }

    @Test
    fun `golden hour counts down to the next window otherwise`() {
        val zone = ZoneId.of("Europe/London")
        // Local midnight: both of today's windows are done, so it must roll to
        // tomorrow morning rather than return "--".
        val midnight = ZonedDateTime.of(2026, 6, 21, 0, 30, 0, 0, zone).toEpochSecond()
        val s = SunMoon.goldenHourStatus(midnight, zone, LONDON_LAT, LONDON_LON)
        assertTrue("expected an 'in Nh MMm' string, got '$s'", Regex("""^in \d+h \d{2}m$""").matches(s))
    }

    @Test
    fun `golden hour degrades to double dash during polar night`() {
        val zone = ZoneId.of("Arctic/Longyearbyen")
        val noon = ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, zone).toEpochSecond()
        assertEquals("--", SunMoon.goldenHourStatus(noon, zone, 78.2232, 15.6267))
    }
}
