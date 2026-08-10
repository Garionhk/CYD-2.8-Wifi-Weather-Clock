// ===========================================================================
// ClockApp.kt -- Compose root; routes between the device canvas and settings
// ===========================================================================
// Two destinations, so the router is one boolean. A navigation library here
// would be more moving parts than the thing it navigates.
package ca.garionhk.cydclock

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.data.AirQualityRepository
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.data.ConnectivityMonitor
import ca.garionhk.cydclock.data.FetchScheduler
import ca.garionhk.cydclock.data.SettingsRepository
import ca.garionhk.cydclock.data.WeatherRepository
import ca.garionhk.cydclock.input.TouchEvent
import ca.garionhk.cydclock.render.DeviceScreen
import ca.garionhk.cydclock.render.VectorFonts
import ca.garionhk.cydclock.ui.CydTheme
import ca.garionhk.cydclock.ui.SettingsScreen
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ClockApp(activity: MainActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // The bars follow the destination: hidden over the clock, shown over
    // settings so the IME and back gesture work.
    activity.systemBarsWanted = showSettings

    // Scalable faces, resolved at the display's own resolution. The TFT_eSPI
    // bitmap tables are still in the repo and still drive the pixel-exact tests,
    // but a magnified bitmap font is precisely what looked wrong on a tablet.
    val fonts = remember { VectorFonts() }
    val controller = remember { AppController() }
    val monitor = remember { ConnectivityMonitor(context) }
    val settingsRepo = remember { SettingsRepository(context) }

    // Applied once per change, not once per recomposition. Nothing takes this
    // flag back -- unlike the bars above, which SystemUI really does reclaim --
    // so re-asserting it at 10 Hz would be an IPC call per frame for nothing.
    val overLock = controller.settings.showOverLockScreen
    LaunchedEffect(overLock) { activity.showOverLockScreen = overLock }

    DisposableEffect(monitor) {
        monitor.register()
        onDispose { monitor.unregister() }
    }

    LaunchedEffect(Unit) {
        controller.network = monitor
        controller.applySettings(settingsRepo.flow.first())
        controller.start(SystemClock.uptimeMillis())

        // First run has no coordinates worth fetching for, so open setup rather
        // than quietly showing the weather at Greenwich.
        if (!controller.settings.provisioned) showSettings = true

        while (isActive) {
            controller.tick(SystemClock.uptimeMillis(), System.currentTimeMillis() / 1000)
            delay(TICK_MS)
        }
    }

    // The two fetchers run in their own coroutines. The firmware interleaved them
    // into the display loop because it had one thread; there is no reason to here,
    // and keeping them separate means a slow network cannot stall the colon blink.
    val weatherRepo = remember { WeatherRepository() }
    val airQualityRepo = remember { AirQualityRepository() }

    LaunchedEffect(Unit) {
        runFetchLoop(
            controller = controller,
            monitor = monitor,
            scheduler = FetchScheduler(FetchScheduler.WEATHER_INTERVAL_MS),
            fetch = { settings, data, epoch -> weatherRepo.fetch(settings, data, epoch) },
            apply = controller::applyWeather,
        )
    }

    LaunchedEffect(Unit) {
        runFetchLoop(
            controller = controller,
            monitor = monitor,
            scheduler = FetchScheduler(
                FetchScheduler.AQI_INTERVAL_MS,
                initialDelayMs = FetchScheduler.AQI_INITIAL_DELAY_MS,
            ),
            fetch = { settings, data, epoch -> airQualityRepo.fetch(settings, data, epoch) },
            apply = controller::applyAirQuality,
        )
    }

    BackHandler(enabled = showSettings) { showSettings = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (showSettings) {
            CydTheme {
                SettingsScreen(
                    settings = controller.settings,
                    onApply = { next ->
                        controller.applySettings(next)
                        scope.launch { settingsRepo.save(next) }
                    },
                    onClose = { showSettings = false },
                )
            }
        } else {
            DeviceScreen(
                fonts = fonts,
                frameTick = controller.frameTick,
                render = { canvas, box -> controller.draw(canvas, box.width) },
                fillScreen = controller.settings.fillScreen,
                onPressStart = { x, y, w ->
                    controller.onPressStart(SystemClock.uptimeMillis(), x, y, w)
                },
                onPressEnd = {
                    if (controller.onPressEnd(SystemClock.uptimeMillis()) == TouchEvent.SETTINGS) {
                        showSettings = true
                    }
                },
            )
        }
    }
}

/**
 * One fetcher. Both instances are identical apart from their interval and the
 * call they make -- which is exactly the duplication weather.cpp and
 * airquality.cpp carried between them.
 *
 * A failed fetch returns null and nothing is published, which is what preserves
 * the last-good-data invariant: the scene keeps rendering the previous values and
 * the status strip's freshness dot ages to amber and then red.
 */
private suspend fun runFetchLoop(
    controller: AppController,
    monitor: ConnectivityMonitor,
    scheduler: FetchScheduler,
    fetch: suspend (AppSettings, AppData, Long) -> AppData?,
    apply: (AppData) -> Unit,
) {
    scheduler.begin(System.currentTimeMillis())
    var seenLocation = controller.locationVersion

    while (currentCoroutineContext().isActive) {
        if (controller.locationVersion != seenLocation) {
            seenLocation = controller.locationVersion
            scheduler.requestNow(System.currentTimeMillis())
        }

        val now = System.currentTimeMillis()
        if (scheduler.isDue(now)) {
            if (!monitor.online) {
                // Not a failure: no attempt was made, so no backoff is earned.
                scheduler.onOffline(now)
            } else {
                val updated = fetch(controller.settings, controller.data, now / 1000)
                if (updated != null) {
                    // Only this fetcher's own fields, onto the current model --
                    // see AppController.applyWeather for why wholesale assignment
                    // would discard the sun/moon block.
                    apply(updated)
                    scheduler.onSuccess(System.currentTimeMillis())
                } else {
                    scheduler.onFailure(System.currentTimeMillis())
                }
            }
        }
        // Short slices, so Doze deferring a sleep cannot hide how late we are.
        delay(scheduler.sleepMs(System.currentTimeMillis()).coerceAtLeast(500L))
    }
}

/** 10 Hz -- see AppController for why nothing needs to be faster. */
private const val TICK_MS = 100L
