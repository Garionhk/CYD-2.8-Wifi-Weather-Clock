// ===========================================================================
// SettingsFormTest.kt -- validation rules, ported from webconfig.cpp handleSave
// ===========================================================================
package ca.garionhk.cydclock

import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.data.UNITS_IMPERIAL
import ca.garionhk.cydclock.data.UNITS_METRIC
import ca.garionhk.cydclock.ui.SettingsForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFormTest {

    private fun form(lat: String, lon: String) = SettingsForm(lat = lat, lon = lon)

    @Test
    fun `defaults round-trip through the form`() {
        val s = AppSettings()
        val f = SettingsForm.from(s)
        assertEquals("51.4779", f.lat)
        assertEquals("-0.0015", f.lon)
        assertTrue("no zone id means follow the device", f.useDeviceZone)
        val back = f.toSettings(s)
        assertNotNull(back)
        assertEquals(s.latitude, back!!.latitude, 1e-6)
        assertEquals(s.longitude, back.longitude, 1e-6)
    }

    @Test
    fun `fill screen is off by default and survives the form round trip`() {
        // Off by default: the letterboxed 4:3 is the shape the scenes were laid
        // out for, and filling is the opt-in.
        assertTrue("fill screen should default to off", !AppSettings().fillScreen)
        assertTrue(!SettingsForm.from(AppSettings()).fillScreen)

        val on = SettingsForm.from(AppSettings()).copy(fillScreen = true)
        assertTrue(on.toSettings(AppSettings())!!.fillScreen)
        assertTrue(!on.copy(fillScreen = false).toSettings(AppSettings())!!.fillScreen)
    }

    @Test
    fun `show over lock screen is on by default and survives the form round trip`() {
        // On by default: a wall clock that disappears behind the keyguard the
        // moment someone taps power is not doing its job.
        assertTrue("show over lock screen should default to on", AppSettings().showOverLockScreen)
        assertTrue(SettingsForm.from(AppSettings()).showOverLockScreen)

        // Both directions. The false case is the one that earns its keep: a field
        // left out of toSettings() silently reverts to the existing value on
        // every Apply, which is exactly how a five-file change gets half-made
        // and still looks like it works.
        val base = SettingsForm.from(AppSettings())
        val off = base.copy(showOverLockScreen = false).toSettings(AppSettings())!!
        assertTrue("turning it off must persist", !off.showOverLockScreen)

        val backOn = SettingsForm.from(off).copy(showOverLockScreen = true).toSettings(off)!!
        assertTrue("turning it back on must persist", backOn.showOverLockScreen)
    }

    @Test
    fun `valid coordinates pass`() {
        assertTrue(form("51.4779", "-0.0015").valid)
        assertTrue(form("-33.8688", "151.2093").valid)
        assertTrue(form("90", "180").valid)
        assertTrue(form("-90", "-180").valid)
        assertTrue(form("0", "0").valid)
        assertTrue("surrounding whitespace should be tolerated", form(" 51.5 ", " -0.1 ").valid)
    }

    @Test
    fun `empty coordinates are reported once, on latitude`() {
        val f = form("", "")
        assertEquals(SettingsForm.REQUIRED, f.latError)
        assertNull("the same message twice would just be noise", f.lonError)
        assertTrue(!f.valid)

        assertEquals(SettingsForm.REQUIRED, form("51.5", "").latError)
        assertEquals(SettingsForm.REQUIRED, form("", "-0.1").latError)
    }

    @Test
    fun `out of range coordinates are rejected with the firmware's wording`() {
        assertEquals(SettingsForm.LAT_RANGE, form("90.1", "0").latError)
        assertEquals(SettingsForm.LAT_RANGE, form("-90.1", "0").latError)
        assertEquals(SettingsForm.LON_RANGE, form("0", "180.1").lonError)
        assertEquals(SettingsForm.LON_RANGE, form("0", "-180.1").lonError)
    }

    @Test
    fun `unparseable coordinates do not silently become zero`() {
        // The firmware used Arduino's String::toFloat(), which returns 0.0 for
        // anything it cannot read -- so a typo relocated the clock to the Gulf of
        // Guinea and nothing said so.
        for (junk in listOf("abc", "5 1", "--3", ".", "N51", "51,5")) {
            val f = form(junk, "0")
            assertTrue("'$junk' should not validate", !f.valid)
            assertNull("'$junk' must not be accepted as a location", f.toSettings(AppSettings()))
        }
    }

    @Test
    fun `an invalid form yields no settings`() {
        assertNull(form("999", "0").toSettings(AppSettings()))
        assertNull(form("", "").toSettings(AppSettings()))
    }

    @Test
    fun `applying marks the device provisioned`() {
        val s = AppSettings(provisioned = false)
        val out = form("51.5", "-0.1").toSettings(s)!!
        assertTrue(out.provisioned)
    }

    @Test
    fun `device zone maps to a null zone id`() {
        val useDevice = SettingsForm(lat = "0", lon = "0", useDeviceZone = true, zoneId = "Asia/Tokyo")
        assertNull(
            "the switch wins over a stale picked zone",
            useDevice.toSettings(AppSettings())!!.zoneId,
        )

        val explicit = SettingsForm(lat = "0", lon = "0", useDeviceZone = false, zoneId = "Asia/Tokyo")
        assertEquals("Asia/Tokyo", explicit.toSettings(AppSettings())!!.zoneId)
    }

    @Test
    fun `units accept only the two known values`() {
        val metric = SettingsForm(lat = "0", lon = "0", units = UNITS_METRIC)
        val imperial = SettingsForm(lat = "0", lon = "0", units = UNITS_IMPERIAL)
        val junk = SettingsForm(lat = "0", lon = "0", units = 99)
        assertEquals(UNITS_METRIC, metric.toSettings(AppSettings())!!.units)
        assertEquals(UNITS_IMPERIAL, imperial.toSettings(AppSettings())!!.units)
        assertEquals(
            "anything unrecognised falls back to metric, as the firmware did",
            UNITS_METRIC, junk.toSettings(AppSettings())!!.units,
        )
    }

    @Test
    fun `coordinates are formatted to four decimals in the C locale`() {
        // Four decimals is roughly 11 m, and what the firmware sent to Open-Meteo.
        // The locale matters more than the precision: a comma-decimal default
        // would put "51,4779" in the field and later in the URL.
        assertEquals("51.4779", SettingsForm.coord(51.47790))
        assertEquals("-0.0015", SettingsForm.coord(-0.00150))
        assertEquals("0.0000", SettingsForm.coord(0.0))
        assertTrue(!SettingsForm.coord(51.4779).contains(','))
    }
}
