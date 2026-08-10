// ===========================================================================
// AppData.kt -- the single data model. Port of app/app_data.h
// ===========================================================================
// The firmware's contract, carried over verbatim:
//
//   A failed fetch NEVER clears existing values. It only stops refreshing
//   *UpdatedAt, so scenes always render last-good data and the status strip
//   shows staleness.
//
// Immutability enforces that more strongly than the C did. A repository that
// fails simply never publishes a new instance, so there is no code path that
// can write half a struct.
//
// moonrise/moonset are dropped: declared in app_data.h:43, never populated,
// never displayed.
package ca.garionhk.cydclock.core

data class AppData(
    // weather
    val tempC: Float = 0f,
    val feelsLikeC: Float = 0f,
    val tempMaxC: Float = 0f,
    val tempMinC: Float = 0f,
    val dailyValid: Boolean = false,      // daily block seen at least once
    val weatherCode: Int = 0,
    val cloudCoverPct: Int = 0,
    val humidityPct: Int = 0,
    val windKph: Float = 0f,
    val pressureHpa: Float = 0f,
    val pressureTrend: Float = 0f,
    val weatherUpdatedAt: Long = 0,       // epoch secs; 0 = never
    val weatherValid: Boolean = false,

    // air quality
    val aqi: Int = 0,
    val pm25: Int = 0,
    val aqiUpdatedAt: Long = 0,
    val aqiValid: Boolean = false,

    // uv -- rides on the weather call, but tracked separately as the firmware does
    val uvIndex: Float = 0f,
    val uvUpdatedAt: Long = 0,
    val uvValid: Boolean = false,

    // sun/moon -- computed locally, always valid once computed
    val sunriseToday: Long = 0,
    val sunsetToday: Long = 0,
    val sunriseTomorrow: Long = 0,
    val sunsetTomorrow: Long = 0,
    val showingNextDay: Boolean = false,
    val moonPhase: Float = 0f,            // 0.0-1.0
    val moonIlluminationPct: Float = 0f,
    val goldenHour: String = "--",
)
