// ===========================================================================
// GestureClassifier.kt -- gesture half of app/touch.cpp
// ===========================================================================
// Gestures are classified on RELEASE, so one press produces exactly one event.
// This is deliberately not Android's GestureDetector, which fires onLongPress
// *during* the press and would then also deliver a tap -- two actions from one
// finger, which on a wall clock means the scene both pins and advances.
//
// Dropped as resistive-panel plumbing: TOUCH_DEBOUNCE_MS (250),
// RELEASE_GRACE_MS (60), zThreshold, and all of touch_screenPoint plus the
// calibration store. MIN_PRESS_MS is kept as cheap ghost-touch rejection.
package ca.garionhk.cydclock.input

enum class TouchEvent {
    NONE,
    TAP,          // < 800 ms   -> next scene + freeze auto-rotation
    LONG_PRESS,   // 0.8 - 4 s  -> pin / unpin the current scene
    SETTINGS,     // > 4 s      -> open settings (was: recalibrate)
}

object GestureClassifier {
    const val MIN_PRESS_MS = 40L
    const val LONG_MS = 800L
    const val SETTINGS_MS = 4000L

    fun classify(heldMs: Long): TouchEvent = when {
        heldMs < MIN_PRESS_MS -> TouchEvent.NONE
        heldMs >= SETTINGS_MS -> TouchEvent.SETTINGS
        heldMs >= LONG_MS -> TouchEvent.LONG_PRESS
        else -> TouchEvent.TAP
    }
}
