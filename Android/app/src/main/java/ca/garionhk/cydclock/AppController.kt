// ===========================================================================
// AppController.kt -- the tick loop. Port of app.ino's loop()
// ===========================================================================
// The firmware's loop() ran every 5 ms and interleaved touch polling, the scene
// machine, two fetchers, a WiFi keep-alive and an AP-fallback timer, because it
// had one cooperative thread and no choice. Here the fetchers live in their own
// coroutines and this is only what has to happen in lockstep with the display.
//
// 10 Hz. Only two things in the whole app move faster than 1 Hz: the colon blink
// and the pin-glyph hold preview, which the firmware refreshed at 100 ms while a
// finger was down (app.ino:220). 10 Hz covers both with nothing to spare and
// nothing wasted -- 76,800 pixels of Bresenham ten times a second is not work.
package ca.garionhk.cydclock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.core.NetworkStatus
import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.input.GestureClassifier
import ca.garionhk.cydclock.input.TouchEvent
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.scenes.AirQualityScene
import ca.garionhk.cydclock.scenes.ClockScene
import ca.garionhk.cydclock.scenes.Scene
import ca.garionhk.cydclock.scenes.SceneContext
import ca.garionhk.cydclock.scenes.SceneManager
import ca.garionhk.cydclock.scenes.SetupButton
import ca.garionhk.cydclock.scenes.StatusStrip
import ca.garionhk.cydclock.scenes.StripState
import ca.garionhk.cydclock.scenes.SunMoonScene
import ca.garionhk.cydclock.scenes.WeatherScene
import ca.garionhk.cydclock.time.SunMoon
import ca.garionhk.cydclock.time.TimeManager
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Nothing here depends on Compose or android.graphics, so the tick loop, the
 * gesture routing and the settings propagation are all testable on the JVM.
 *
 * [tick] advances the model and captures a [Frame]; [draw] paints one into any
 * [DeviceCanvas]. Splitting them is what lets the same controller feed the
 * vector renderer on the device and the pixel-exact raster one in tests.
 */
class AppController {

    /** Everything one frame needs, captured at tick time. */
    data class Frame(
        val scene: Scene,
        val ctx: SceneContext,
        val strip: StripState,
        val buttonPressed: Boolean,
    )

    val sceneManager = SceneManager(
        listOf(
            ClockScene,
            WeatherScene,
            SunMoonScene,
            AirQualityScene,
        )
    )

    /**
     * Bumped whenever the coordinates change, so the fetch loop can notice and
     * refetch at once instead of waiting out its interval. The firmware simply
     * restarted.
     */
    var locationVersion by mutableStateOf(0)
        private set

    var data by mutableStateOf(AppData())
    var settings by mutableStateOf(AppSettings())
    var network: NetworkStatus by mutableStateOf(NetworkStatus.OFFLINE)

    /** Set on press, cleared on release. Drives the strip's live hold preview. */
    private var heldSince: Long? = null

    /** Suppressed when the press landed on the setup button. */
    private var pressArmed = true

    /** Lights the gear while it is held. */
    private var pressOnButton by mutableStateOf(false)

    private var lastSunMoonMinute: Long = Long.MIN_VALUE

    /** The frame to paint. Bumped every tick so the host redraws. */
    var frame by mutableStateOf<Frame?>(null)
        private set

    var frameTick by mutableIntStateOf(0)
        private set

    fun start(nowMs: Long) {
        sceneManager.begin(nowMs)
    }

    /**
     * Apply new settings live. The firmware restarted here, because WiFi,
     * timezone, location and units each needed a different refresh path and a
     * 3 s reboot could not leave the device half-configured. Two of those four
     * reasons are gone, and a pure-function renderer dissolves the rest:
     *
     *   units    nothing to do -- conversion happens at draw time
     *   zone     force a sun/moon recompute; the local date drives it
     *   lat/lon  force a recompute, and invalidate the fetched data
     */
    fun applySettings(next: AppSettings) {
        val moved = next.latitude != settings.latitude || next.longitude != settings.longitude
        val rezoned = next.zoneId != settings.zoneId

        if (moved || rezoned) lastSunMoonMinute = Long.MIN_VALUE

        if (moved) {
            // DIVERGENCE: the firmware restarted, zeroing g_data, so the scenes
            // showed "fetching weather...". Keeping the old values would instead
            // display another city's weather as current -- and the freshness dot
            // would certify it green. The numbers stay, the valid flags do not.
            data = data.copy(
                weatherValid = false,
                dailyValid = false,
                uvValid = false,
                aqiValid = false,
            )
            locationVersion++
        }

        settings = next
    }

    // ---- fetch results ----------------------------------------------------
    //
    // A fetch reads `data`, suspends for a second or two on the network, then
    // produces a new model from that stale snapshot. Assigning it wholesale would
    // discard anything computed in the meantime -- and something always is: the
    // tick loop recomputes sun and moon every minute, so a fetch landing just
    // after one would blank sunrise, sunset and the moon phase until the next
    // minute ticked over. Every fifteen minutes, on the scene most likely to be
    // showing at dusk.
    //
    // So each fetcher applies only the fields it owns, onto whatever `data` is
    // NOW. That also states the ownership the firmware had implicitly, where each
    // fetcher wrote its own members of one global struct.

    fun applyWeather(from: AppData) {
        data = data.copy(
            tempC = from.tempC,
            feelsLikeC = from.feelsLikeC,
            tempMaxC = from.tempMaxC,
            tempMinC = from.tempMinC,
            dailyValid = from.dailyValid,
            weatherCode = from.weatherCode,
            cloudCoverPct = from.cloudCoverPct,
            humidityPct = from.humidityPct,
            windKph = from.windKph,
            pressureHpa = from.pressureHpa,
            pressureTrend = from.pressureTrend,
            weatherUpdatedAt = from.weatherUpdatedAt,
            weatherValid = from.weatherValid,
            uvIndex = from.uvIndex,
            uvUpdatedAt = from.uvUpdatedAt,
            uvValid = from.uvValid,
        )
    }

    fun applyAirQuality(from: AppData) {
        data = data.copy(
            aqi = from.aqi,
            pm25 = from.pm25,
            aqiUpdatedAt = from.aqiUpdatedAt,
            aqiValid = from.aqiValid,
        )
    }

    // ---- input ------------------------------------------------------------

    /**
     * The hit test runs against the DOWN point, before anything is dispatched, so
     * a press on the gear can never also advance the scene. The hold preview is
     * suppressed too -- the pin glyph must not start dimming while someone is
     * reaching for settings.
     */
    fun onPressStart(nowMs: Long, x: Int, y: Int, viewportWidth: Int = Theme.SCREEN_W) {
        val onButton = SetupButton.hits(x, y, viewportWidth)
        pressOnButton = onButton
        pressArmed = !onButton
        heldSince = if (onButton) null else nowMs
    }

    /** Returns the classified gesture; [TouchEvent.SETTINGS] is the caller's to act on. */
    fun onPressEnd(nowMs: Long): TouchEvent {
        val start = heldSince
        val wasOnButton = pressOnButton
        heldSince = null
        pressOnButton = false

        // A press that started on the gear opens settings on release, whatever
        // its duration -- duration is not part of that gesture.
        if (wasOnButton) return TouchEvent.SETTINGS
        if (!pressArmed || start == null) return TouchEvent.NONE

        return when (val ev = GestureClassifier.classify(nowMs - start)) {
            TouchEvent.TAP -> { sceneManager.onTap(nowMs); ev }
            TouchEvent.LONG_PRESS -> { sceneManager.onLongPress(nowMs); ev }
            else -> ev
        }
    }

    // ---- tick -------------------------------------------------------------

    fun tick(nowMs: Long, nowEpoch: Long) {
        sceneManager.tick(nowMs)

        val zone = TimeManager.zoneOf(settings)
        val now = TimeManager.now(nowEpoch, zone)

        // Recompute on the minute rather than on the date: the golden-hour
        // countdown is displayed to the minute, and eight solar evaluations once
        // a minute costs nothing. A date change is covered by the same test.
        val minute = nowEpoch / 60
        if (minute != lastSunMoonMinute) {
            lastSunMoonMinute = minute
            data = SunMoon.recompute(data, settings, zone, nowEpoch)
        }

        frame = Frame(
            scene = sceneManager.current,
            ctx = SceneContext(
                data = data,
                settings = settings,
                now = now,
                nowEpoch = nowEpoch,
                online = network.online,
            ),
            strip = StripState(
                timeText = now.format(TIME_FMT),
                wifiLevel = network.wifiBars,
                weatherValid = data.weatherValid,
                weatherUpdatedAt = data.weatherUpdatedAt,
                nowEpoch = nowEpoch,
                pinned = sceneManager.pinned,
                frozen = sceneManager.isFrozen(nowMs),
                heldMs = heldSince?.let { nowMs - it } ?: 0L,
                sceneIndex = sceneManager.index,
                sceneCount = sceneManager.count,
            ),
            buttonPressed = pressOnButton,
        )
        frameTick++
    }

    /**
     * Paint the last ticked frame. Safe to call from a draw pass.
     *
     * [viewportWidth] is the grid's width in design units -- 320 when
     * letterboxed, wider in fill mode. The scene keeps drawing in its own
     * 320-wide space, centred; only the chrome knows the grid got wider.
     */
    fun draw(c: DeviceCanvas, viewportWidth: Int = Theme.SCREEN_W) {
        val f = frame ?: return
        // The width is a property of the display, known here rather than at tick
        // time, so it is stamped onto the captured context on the way past.
        f.scene.draw(c, f.ctx.copy(width = viewportWidth))
        StatusStrip.draw(c, f.strip, viewportWidth)
        SetupButton.draw(c, viewportWidth, pressed = f.buttonPressed)
    }

    companion object {
        // 24 h, as the firmware hardcoded. There is no 12/24 setting.
        private val TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    }
}
