// ===========================================================================
// SettingsPropagationTest.kt -- a settings change applies without a restart
// ===========================================================================
// This is the behaviour the firmware could not offer: it restarted on save,
// because WiFi, timezone, location and units each needed a different refresh
// path. Two of those reasons no longer exist, and rendering is now a pure
// function of (AppData, AppSettings, now), so the rest follows.
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.core.dispPress
import ca.garionhk.cydclock.core.dispTemp
import ca.garionhk.cydclock.core.dispWind
import ca.garionhk.cydclock.core.pressUnit
import ca.garionhk.cydclock.core.tempUnit
import ca.garionhk.cydclock.core.windUnit
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.data.UNITS_IMPERIAL
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.scenes.SetupButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPropagationTest {

    // The controller has no Android dependencies at all, so the whole thing --
    // including tick() and draw() -- runs on the JVM with no emulator.
    private fun controller(): AppController = AppController()

    @Test
    fun `changing units keeps the fetched data intact`() {
        val c = controller()
        c.data = AppData(weatherValid = true, aqiValid = true, dailyValid = true, uvValid = true)
        c.applySettings(AppSettings(units = UNITS_IMPERIAL))

        assertTrue("units are a draw-time concern only", c.data.weatherValid)
        assertTrue(c.data.aqiValid)
        assertEquals(UNITS_IMPERIAL, c.settings.units)
    }

    @Test
    fun `moving the location invalidates the fetched data but keeps the numbers`() {
        val c = controller()
        c.data = AppData(
            tempC = 21.5f, aqi = 42, weatherValid = true,
            aqiValid = true, dailyValid = true, uvValid = true,
        )
        c.applySettings(AppSettings(latitude = 35.6762, longitude = 139.6503))

        assertTrue("another city's weather must not read as current", !c.data.weatherValid)
        assertTrue(!c.data.aqiValid)
        assertTrue(!c.data.dailyValid)
        assertTrue(!c.data.uvValid)
        assertEquals("the values themselves survive for the loading state", 21.5f, c.data.tempC, 1e-6f)
        assertEquals(42, c.data.aqi)
    }

    @Test
    fun `changing only the timezone does not invalidate weather`() {
        val c = controller()
        c.data = AppData(weatherValid = true, aqiValid = true)
        c.applySettings(AppSettings(zoneId = "Asia/Tokyo"))
        assertTrue("the weather did not move, only the clock", c.data.weatherValid)
        assertTrue(c.data.aqiValid)
    }

    // ---- unit conversion --------------------------------------------------

    @Test
    fun `conversions match units_h`() {
        val metric = AppSettings()
        val imperial = AppSettings(units = UNITS_IMPERIAL)

        assertEquals(21.5f, metric.dispTemp(21.5f), 1e-4f)
        assertEquals(70.7f, imperial.dispTemp(21.5f), 1e-3f)
        assertEquals(32.0f, imperial.dispTemp(0f), 1e-4f)

        assertEquals(20.0f, metric.dispWind(20f), 1e-4f)
        assertEquals(12.4274f, imperial.dispWind(20f), 1e-3f)

        assertEquals(1013.0f, metric.dispPress(1013f), 1e-3f)
        assertEquals(29.9139f, imperial.dispPress(1013f), 1e-3f)

        assertEquals("C", metric.tempUnit()); assertEquals("F", imperial.tempUnit())
        assertEquals("km/h", metric.windUnit()); assertEquals("mph", imperial.windUnit())
        assertEquals("hPa", metric.pressUnit()); assertEquals("inHg", imperial.pressUnit())
    }

    // ---- the setup button vs the scene gestures ---------------------------

    @Test
    fun `a press on the gear opens settings and never advances the scene`() {
        val c = controller()
        c.start(0)
        val before = c.sceneManager.index

        c.onPressStart(1_000, SetupButton.CX, SetupButton.CY)
        val ev = c.onPressEnd(1_200)         // a tap-length press, on the button

        assertEquals(ca.garionhk.cydclock.input.TouchEvent.SETTINGS, ev)
        assertEquals("the gear must not also advance the scene", before, c.sceneManager.index)
    }

    @Test
    fun `a long press on the gear still opens settings rather than pinning`() {
        val c = controller()
        c.start(0)
        c.onPressStart(1_000, SetupButton.CX, SetupButton.CY)
        val ev = c.onPressEnd(3_000)         // long-press length

        assertEquals(ca.garionhk.cydclock.input.TouchEvent.SETTINGS, ev)
        assertTrue("the gear is not a pin gesture", !c.sceneManager.pinned)
    }

    @Test
    fun `a press elsewhere still advances the scene`() {
        val c = controller()
        c.start(0)
        c.onPressStart(1_000, 160, 100)
        val ev = c.onPressEnd(1_200)
        assertEquals(ca.garionhk.cydclock.input.TouchEvent.TAP, ev)
        assertEquals(1, c.sceneManager.index)
    }

    // ---- fetch results must not clobber locally computed state ------------

    @Test
    fun `a weather fetch landing after a sun-moon recompute keeps the sun and moon`() {
        // The failure this pins: a fetch reads `data`, suspends on the network for
        // a second or two, then builds a new model from that stale snapshot. The
        // tick loop recomputes sun and moon every minute, so a fetch landing just
        // after one used to blank sunrise, sunset and the phase until the next
        // minute -- every fifteen minutes, on the scene most likely to be showing
        // at dusk.
        val c = controller()
        c.applySettings(AppSettings(latitude = 51.4779, longitude = -0.0015))
        c.start(0)

        // The snapshot a fetch would have captured before suspending.
        val stale = c.data
        assertEquals("precondition: nothing computed yet", 0L, stale.sunriseToday)

        // Meanwhile the tick loop computes sun and moon.
        c.tick(0, 1_775_000_000L)
        val sunrise = c.data.sunriseToday
        assertTrue("precondition: sunrise should be computed", sunrise > 0)

        // Now the fetch completes, carrying its weather onto the stale snapshot.
        c.applyWeather(stale.copy(tempC = 19.4f, weatherValid = true, weatherUpdatedAt = 123L))

        assertEquals("weather must land", 19.4f, c.data.tempC, 1e-4f)
        assertTrue(c.data.weatherValid)
        assertEquals("sunrise must survive the fetch", sunrise, c.data.sunriseToday)
        assertTrue("golden hour must survive too", c.data.goldenHour != "--" || sunrise == 0L)
    }

    @Test
    fun `the two fetchers do not overwrite each other`() {
        val c = controller()
        c.applyWeather(AppData(tempC = 19.4f, weatherValid = true, weatherUpdatedAt = 100L))
        // An AQI fetch that captured the model before the weather landed.
        c.applyAirQuality(AppData(aqi = 42, pm25 = 9, aqiValid = true, aqiUpdatedAt = 200L))

        assertEquals(19.4f, c.data.tempC, 1e-4f)
        assertTrue("weather must survive the air-quality write", c.data.weatherValid)
        assertEquals(42, c.data.aqi)
        assertTrue(c.data.aqiValid)
    }

    // ---- the tick loop ----------------------------------------------------

    @Test
    fun `tick captures a frame and draw paints all three layers`() {
        val fb = GoldenRender.blank()
        val c = controller()
        c.start(0)
        assertTrue("nothing to paint before the first tick", c.frame == null)

        c.tick(nowMs = 0, nowEpoch = 1_775_000_000L)
        assertTrue("a tick must produce a frame", c.frame != null)
        assertEquals(1, c.frameTick)

        c.draw(DeviceCanvas(fb, loadFonts()))
        var ink = 0
        for (y in 0 until fb.height) for (x in 0 until fb.width) {
            if (fb.pixelAt(x, y) != ca.garionhk.cydclock.core.Theme.COL_BG) ink++
        }
        assertTrue("the frame should not be blank", ink > 1000)
        assertTrue(
            "the setup gear should be composited",
            fb.pixelAt(SetupButton.CX, SetupButton.CY - 6) != ca.garionhk.cydclock.core.Theme.COL_BG,
        )
    }

    @Test
    fun `draw before the first tick is a no-op rather than a crash`() {
        val fb = GoldenRender.blank()
        controller().draw(DeviceCanvas(fb, loadFonts()))
        for (y in 0 until fb.height) for (x in 0 until fb.width) {
            assertEquals(ca.garionhk.cydclock.core.Theme.COL_BG, fb.pixelAt(x, y))
        }
    }

    @Test
    fun `tick computes sun and moon on the first pass`() {
        val c = controller()
        c.applySettings(AppSettings(latitude = 51.4779, longitude = -0.0015))
        assertEquals("nothing computed yet", 0L, c.data.sunriseToday)

        c.start(0)
        c.tick(nowMs = 0, nowEpoch = 1_775_000_000L)
        assertTrue("sunrise should be populated after one tick", c.data.sunriseToday > 0)
        assertTrue(c.data.goldenHour.isNotEmpty())
    }

    @Test
    fun `a location change is reflected on the very next tick`() {
        // The firmware needed a restart for this. Sunrise in Tokyo and sunrise in
        // London are hours apart, so the difference is unmistakable.
        val c = controller()
        c.start(0)
        c.applySettings(AppSettings(latitude = 51.4779, longitude = -0.0015))
        c.tick(0, 1_775_000_000L)
        val london = c.data.sunriseToday

        c.applySettings(AppSettings(latitude = 35.6762, longitude = 139.6503))
        c.tick(100, 1_775_000_000L)          // same wall second, so no minute rollover
        val tokyo = c.data.sunriseToday

        assertTrue("sunrise should have moved with the location", london != tokyo)
        assertTrue("Tokyo is nine hours east", london - tokyo > 8 * 3600)
    }

    @Test
    fun `a hold elsewhere past four seconds also opens settings`() {
        // The firmware ran touch calibration here. Remapping rather than deleting
        // keeps the status strip's three-tier hold preview meaningful.
        val c = controller()
        c.start(0)
        c.onPressStart(1_000, 160, 100)
        assertEquals(
            ca.garionhk.cydclock.input.TouchEvent.SETTINGS,
            c.onPressEnd(5_500),
        )
    }
}
