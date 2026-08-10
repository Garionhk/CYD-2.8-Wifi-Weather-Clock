// ===========================================================================
// FetchScheduler.kt -- interval and backoff, shared by both fetchers
// ===========================================================================
// weather.cpp and airquality.cpp carried identical copies of this logic. Here it
// is one class with two instances.
//
// Times are WALL CLOCK, and the caller polls in short slices rather than sleeping
// the whole interval. That is not fussiness: under Doze a bare delay(15.minutes)
// is deferred by an arbitrary amount and returns believing it was on schedule, so
// a tablet that dozed for two hours would think its data was fifteen minutes old.
// Bookkeeping the due time against the wall clock makes the lateness visible.
//
// Zero android.* imports.
package ca.garionhk.cydclock.data

class FetchScheduler(
    private val intervalMs: Long,
    private val initialDelayMs: Long = 0,
    private val offlineRetryMs: Long = 30_000,
) {
    private var dueAt: Long = 0
    private var backoffMin: Int = 0

    val backoffMinutes: Int get() = backoffMin

    fun begin(now: Long) {
        dueAt = now + initialDelayMs
        backoffMin = 0
    }

    fun isDue(now: Long): Boolean = now >= dueAt

    fun onSuccess(now: Long) {
        backoffMin = 0
        dueAt = now + intervalMs
    }

    /** 1 -> 2 -> 4 -> 8 -> 15 minutes, then held at 15. */
    fun onFailure(now: Long) {
        backoffMin = if (backoffMin == 0) 1 else backoffMin * 2
        if (backoffMin > MAX_BACKOFF_MIN) backoffMin = MAX_BACKOFF_MIN
        dueAt = now + backoffMin * 60_000L
    }

    /** Offline is not a failure: no attempt was made, so no backoff is earned. */
    fun onOffline(now: Long) {
        dueAt = now + offlineRetryMs
    }

    /** Ask for a fetch as soon as the loop comes round -- used when the location moves. */
    fun requestNow(now: Long) {
        dueAt = now
        backoffMin = 0
    }

    /** How long to sleep before looking again, capped so Doze cannot hide lateness. */
    fun sleepMs(now: Long): Long = (dueAt - now).coerceIn(0, MAX_SLEEP_MS)

    companion object {
        const val MAX_BACKOFF_MIN = 15
        const val MAX_SLEEP_MS = 30_000L

        const val WEATHER_INTERVAL_MS = 15L * 60 * 1000
        const val AQI_INTERVAL_MS = 30L * 60 * 1000

        /** Air quality starts 5 s late so the two fetches do not collide at boot. */
        const val AQI_INITIAL_DELAY_MS = 5_000L
    }
}
