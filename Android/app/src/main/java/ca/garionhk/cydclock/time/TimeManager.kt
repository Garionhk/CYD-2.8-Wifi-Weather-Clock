// ===========================================================================
// TimeManager.kt -- port of app/time_manager.cpp
// ===========================================================================
// The firmware's job here was NTP: configTzTime against pool.ntp.org, then a
// timeManager_now() that returned false until the first sync landed. Android's
// clock is already synchronised, so all that remains is resolving which zone to
// render in.
package ca.garionhk.cydclock.time

import ca.garionhk.cydclock.data.AppSettings
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object TimeManager {

    /** The configured IANA zone, or the device's own when none is set. */
    fun zoneOf(s: AppSettings): ZoneId =
        s.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    fun now(epochSeconds: Long, zone: ZoneId): ZonedDateTime =
        Instant.ofEpochSecond(epochSeconds).atZone(zone)
}
