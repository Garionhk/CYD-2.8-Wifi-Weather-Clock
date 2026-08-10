// ===========================================================================
// WeatherRepository.kt -- port of app/weather.cpp
// ===========================================================================
// The invariant from app_data.h, preserved structurally:
//
//   A failed fetch NEVER clears existing values. It only stops refreshing
//   *UpdatedAt, so scenes always render last-good data and the strip shows
//   staleness.
//
// Here that is stronger than in the C. A failure returns null and the caller
// simply never publishes, so there is no code path that can write half a model --
// whereas a partial ArduinoJson parse could in principle leave the struct mixed.
package ca.garionhk.cydclock.data

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.core.lround

class WeatherRepository(
    private val fetchText: suspend (String) -> String? = HttpGet::text,
) {
    /**
     * Returns the updated model, or null if the fetch or parse failed.
     *
     * UV rides on this call. The firmware tracked uvValid/uvUpdatedAt separately
     * even though there is no separate UV request, and that is kept -- the Sun &
     * Moon scene reads those flags.
     */
    suspend fun fetch(settings: AppSettings, previous: AppData, nowEpoch: Long): AppData? {
        val body = fetchText(OpenMeteoApi.forecastUrl(settings.latitude, settings.longitude))
            ?: return null

        val dto = runCatching {
            OpenMeteoApi.json.decodeFromString<ForecastResponse>(body)
        }.getOrNull() ?: return null

        val cur = dto.current ?: return null

        // Pressure trend is the delta between successive successful fetches, so
        // roughly fifteen minutes -- not the three-hour trend a barometer means.
        // The first fetch after a cold start has nothing to compare against.
        val pressure = cur.surfacePressure ?: previous.pressureHpa
        val trend =
            if (previous.pressureHpa > 0) pressure - previous.pressureHpa
            else previous.pressureTrend

        var next = previous.copy(
            tempC = cur.temperature ?: previous.tempC,
            feelsLikeC = cur.apparentTemperature ?: previous.feelsLikeC,
            weatherCode = cur.weatherCode ?: previous.weatherCode,
            cloudCoverPct = cur.cloudCover ?: previous.cloudCoverPct,
            humidityPct = cur.relativeHumidity ?: previous.humidityPct,
            windKph = cur.windSpeed ?: previous.windKph,
            pressureHpa = pressure,
            pressureTrend = trend,
            uvIndex = cur.uvIndex ?: previous.uvIndex,
            uvUpdatedAt = nowEpoch,
            uvValid = true,
            weatherUpdatedAt = nowEpoch,
            weatherValid = true,
        )

        // Today's high and low.
        val hi = dto.daily?.temperatureMax?.firstOrNull()
        val lo = dto.daily?.temperatureMin?.firstOrNull()
        if (hi != null && lo != null) {
            next = next.copy(tempMaxC = hi, tempMinC = lo, dailyValid = true)
        }

        return next
    }
}

/** Rounded, unit-converted values are a draw-time concern; this is just a helper. */
internal fun roundToDisplay(v: Float): Int = lround(v)
