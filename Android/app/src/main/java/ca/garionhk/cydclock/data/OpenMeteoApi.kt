// ===========================================================================
// OpenMeteoApi.kt -- URLs and response shapes. Port of weather.cpp / airquality.cpp
// ===========================================================================
// Keyless, so there is no credential plumbing anywhere in this app.
//
// Every field is nullable with a null default. That is what reproduces
// ArduinoJson's `cur["x"] | g_data.tempC` idiom: a field the server omits keeps
// its last-good value rather than becoming zero. `ignoreUnknownKeys` replaces
// the filter documents the firmware needed to keep its RAM down.
package ca.garionhk.cydclock.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

object OpenMeteoApi {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = false     // a null must stay null, not become 0
    }

    /**
     * Four decimals, in the C locale.
     *
     * The locale is not cosmetic here. A comma-decimal default would send
     * "latitude=51,4779" and every fetch would 400 -- on a device whose owner
     * would have no way to see why.
     */
    private fun coord(v: Double): String = String.format(Locale.US, "%.4f", v)

    /**
     * timezone=auto is load-bearing: it is what aligns the `daily` block to the
     * local day rather than to UTC.
     */
    fun forecastUrl(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${coord(lat)}&longitude=${coord(lon)}" +
            "&current=temperature_2m,apparent_temperature,weather_code,cloud_cover," +
            "relative_humidity_2m,wind_speed_10m,surface_pressure,uv_index" +
            "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1" +
            "&timezone=auto"

    fun airQualityUrl(lat: Double, lon: Double): String =
        "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=${coord(lat)}&longitude=${coord(lon)}" +
            "&current=us_aqi,pm2_5&timezone=auto"
}

@Serializable
data class ForecastResponse(
    val current: ForecastCurrent? = null,
    val daily: ForecastDaily? = null,
)

@Serializable
data class ForecastCurrent(
    @SerialName("temperature_2m") val temperature: Float? = null,
    @SerialName("apparent_temperature") val apparentTemperature: Float? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("cloud_cover") val cloudCover: Int? = null,
    @SerialName("relative_humidity_2m") val relativeHumidity: Int? = null,
    @SerialName("wind_speed_10m") val windSpeed: Float? = null,
    @SerialName("surface_pressure") val surfacePressure: Float? = null,
    @SerialName("uv_index") val uvIndex: Float? = null,
)

@Serializable
data class ForecastDaily(
    // Single-element arrays, because forecast_days=1.
    @SerialName("temperature_2m_max") val temperatureMax: List<Float?>? = null,
    @SerialName("temperature_2m_min") val temperatureMin: List<Float?>? = null,
)

@Serializable
data class AirQualityResponse(val current: AirQualityCurrent? = null)

@Serializable
data class AirQualityCurrent(
    @SerialName("us_aqi") val usAqi: Int? = null,
    @SerialName("pm2_5") val pm25: Float? = null,
)
