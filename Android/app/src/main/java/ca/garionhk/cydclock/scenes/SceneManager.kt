// ===========================================================================
// SceneManager.kt -- rotation state machine. Port of scenes.cpp:567-639
// ===========================================================================
// Rotation runs continuously, all day. Touch layers three behaviours on top, all
// of which are visible in the status strip:
//
//   tap        -> advance now, and freeze auto-rotation for 45 s so a glance
//                 doesn't get yanked away mid-read
//   long press -> pin the current scene until pressed again
//   > 4 s hold -> open settings (the firmware ran touch calibration here)
//
// All timings are on the monotonic clock (SystemClock.uptimeMillis), never the
// wall clock -- a timezone change or an NTP correction must not skip a scene.
package ca.garionhk.cydclock.scenes

class SceneManager(val scenes: List<Scene>) {

    var index: Int = 0
        private set

    var pinned: Boolean = false
        private set

    private var enterAt: Long = 0
    private var freezeUntil: Long = 0

    val count: Int get() = scenes.size
    val current: Scene get() = scenes[index]

    fun begin(now: Long) {
        index = 0
        pinned = false
        freezeUntil = 0
        enterAt = now
    }

    /** Instant swap, no animation -- the firmware found an SPI slide janky. */
    private fun goTo(idx: Int, now: Long) {
        index = idx
        enterAt = now
    }

    fun tick(now: Long) {
        if (pinned) return
        if (now < freezeUntil) return
        if (now - enterAt >= current.dwellMs) goTo((index + 1) % count, now)
    }

    /**
     * Advance immediately, then hold still for a while. Without the freeze a tap
     * two seconds before a dwell expires would show the next scene for two
     * seconds and then move on again, which reads as a glitch.
     */
    fun onTap(now: Long) {
        freezeUntil = now + SCENE_FREEZE_MS
        goTo((index + 1) % count, now)
    }

    fun onLongPress(now: Long) {
        pinned = !pinned
        // Unpinning restarts the dwell rather than resuming a timer that may have
        // expired minutes ago -- otherwise the scene would vanish the instant you
        // let go.
        if (!pinned) enterAt = now
        freezeUntil = 0
    }

    fun isFrozen(now: Long): Boolean = !pinned && now < freezeUntil

    companion object {
        /** How long a tap suspends auto-rotation. */
        const val SCENE_FREEZE_MS = 45_000L
    }
}
