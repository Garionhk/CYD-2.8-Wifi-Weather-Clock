// ===========================================================================
// MainActivity.kt -- the kiosk shell (replaces app.ino's setup())
// ===========================================================================
// The firmware's setup() had a lot to do: bring up the panel, calibrate a
// resistive touch screen, join WiFi, fall back to SoftAP, sync NTP. None of
// that exists here -- Android owns connectivity and the system clock, and a
// capacitive panel needs no calibration.
//
// What is left is making an ordinary Android app behave like an appliance:
// landscape, always on, no system bars, always dark.
package ca.garionhk.cydclock

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ca.garionhk.cydclock.data.AppSettings

class MainActivity : ComponentActivity() {

    /**
     * While the settings screen is open the bars must come back -- you need the
     * IME to type coordinates and the back gesture to leave. [ClockApp] flips
     * this, and [applyImmersive] reads it.
     */
    var systemBarsWanted: Boolean = false
        set(value) {
            field = value
            applyImmersive()
        }

    /**
     * Draw the clock ABOVE the keyguard, so a wall clock stays a clock after
     * someone taps the power button. Driven by a setting; [ClockApp] flips it.
     *
     * It shows over the lock screen; it does not dismiss it. The rest of the
     * device still needs the PIN, and Home from here returns to the keyguard.
     *
     * The initializer assigns the backing field directly -- Kotlin property
     * initializers do not run the setter -- so this cannot touch `window` before
     * onCreate. The stored value arrives a moment later from DataStore.
     */
    var showOverLockScreen: Boolean = AppSettings().showOverLockScreen
        set(value) {
            field = value
            applyShowWhenLocked()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A wall clock that sleeps is not a clock. Unconditional: this is what
        // stops the device ever locking on its own, leaving the power button as
        // the only route to the keyguard -- which showOverLockScreen then covers.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersive()
        applyShowWhenLocked()

        setContent { ClockApp(activity = this) }
    }

    // The bars creep back after an IME dismissal and after the settings screen
    // closes, and neither of those routes through onCreate. Re-asserting on every
    // focus gain is the only reliable way to stay immersive.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    /**
     * Deliberately NOT re-asserted in onWindowFocusChanged. Unlike the immersive
     * bars, which SystemUI genuinely takes back, this is recorded on the
     * ActivityRecord and survives pause/stop/resume.
     *
     * Also deliberately not the manifest's android:showWhenLocked: that is a
     * static ActivityInfo flag baked in at install time, so it cannot serve a
     * runtime toggle.
     */
    private fun applyShowWhenLocked() {
        // setShowWhenLocked is API 27 and minSdk is 26, so exactly one API level
        // takes the deprecated flag. It does the same job: show above the
        // keyguard, dismiss nothing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(showOverLockScreen)
        } else {
            @Suppress("DEPRECATION")
            if (showOverLockScreen) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }
    }

    private fun applyImmersive() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (systemBarsWanted) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
