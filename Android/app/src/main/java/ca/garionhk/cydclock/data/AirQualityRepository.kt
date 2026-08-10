// ===========================================================================
// AirQualityRepository.kt -- port of app/airquality.cpp
// ===========================================================================
// A different host from the forecast API, and a 30-minute interval rather than
// 15 -- air quality does not move as fast as weather, and the free tier is
// shared.
package ca.garionhk.cydclock.data

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.core.lround

class AirQualityRepository(
    private val fetchText: suspend (String) -> String? = HttpGet::text,
) {
    /** Returns the updated model, or null if the fetch, parse or validation failed. */
    suspend fun fetch(settings: AppSettings, previous: AppData, nowEpoch: Long): AppData? {
        val body = fetchText(OpenMeteoApi.airQualityUrl(settings.latitude, settings.longitude))
            ?: return null

        val dto = runCatching {
            OpenMeteoApi.json.decodeFromString<AirQualityResponse>(body)
        }.getOrNull() ?: return null

        // A null us_aqi is a hard reject, not a last-good merge. Open-Meteo
        // returns the field as null where it has no model coverage, and showing a
        // stale AQI as freshly fetched would be worse than showing nothing.
        val aqi = dto.current?.usAqi ?: return null

        return previous.copy(
            aqi = aqi,
            pm25 = dto.current.pm25?.let { lround(it) } ?: previous.pm25,
            aqiUpdatedAt = nowEpoch,
            aqiValid = true,
        )
    }
}
