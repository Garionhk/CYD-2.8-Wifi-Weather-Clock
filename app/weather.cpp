#include "weather.h"
#include "settings.h"
#include "app_data.h"
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <time.h>

static const uint32_t INTERVAL_MS = 15UL * 60 * 1000;   // 15 min (plan §5.2)

static uint32_t nextAttemptMs = 0;    // millis() when the next fetch is allowed
static uint32_t backoffMin    = 0;    // current failure backoff (minutes)

// Perform one blocking fetch. Returns true and updates g_data on success.
// (A single 15-min fetch is short enough to run inline; the plan's fully
//  cooperative serviceFetch() state machine isn't needed for one task.)
static bool doFetch() {
  WiFiClientSecure client;
  client.setInsecure();

  HTTPClient https;
  String url = "https://api.open-meteo.com/v1/forecast?latitude=" + String(g_settings.latitude, 4) +
               "&longitude=" + String(g_settings.longitude, 4) +
               "&current=temperature_2m,apparent_temperature,weather_code,cloud_cover,"
               "relative_humidity_2m,wind_speed_10m,surface_pressure,uv_index"
               "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1"
               "&timezone=auto";   // timezone=auto is what aligns `daily` to the local day

  if (!https.begin(client, url)) return false;

  int code = https.GET();
  if (code != 200) {
    Serial.printf("weather: HTTP %d\n", code);
    https.end(); client.stop();
    return false;
  }

  // Buffer the (small) payload then parse -- parsing straight from the stream
  // mis-reads chunked responses (learned in Stage 0.5).
  String payload = https.getString();
  https.end(); client.stop();

  DynamicJsonDocument filter(512);
  filter["current"]["temperature_2m"]       = true;
  filter["current"]["apparent_temperature"] = true;
  filter["current"]["weather_code"]         = true;
  filter["current"]["cloud_cover"]          = true;
  filter["current"]["relative_humidity_2m"] = true;
  filter["current"]["wind_speed_10m"]       = true;
  filter["current"]["surface_pressure"]     = true;
  filter["current"]["uv_index"]             = true;
  filter["daily"]["temperature_2m_max"]     = true;
  filter["daily"]["temperature_2m_min"]     = true;

  DynamicJsonDocument doc(1536);
  if (deserializeJson(doc, payload, DeserializationOption::Filter(filter))) return false;

  JsonObject cur = doc["current"];
  if (cur.isNull()) return false;

  // "| existing" keeps last-good if a field is missing.
  g_data.tempC        = cur["temperature_2m"]       | g_data.tempC;
  g_data.feelsLikeC   = cur["apparent_temperature"] | g_data.feelsLikeC;
  g_data.weatherCode  = cur["weather_code"]         | g_data.weatherCode;
  g_data.cloudCoverPct= cur["cloud_cover"]          | g_data.cloudCoverPct;
  g_data.humidityPct  = cur["relative_humidity_2m"] | g_data.humidityPct;
  g_data.windKph      = cur["wind_speed_10m"]       | g_data.windKph;

  float p = cur["surface_pressure"] | g_data.pressureHpa;
  if (g_data.pressureHpa > 0) g_data.pressureTrend = p - g_data.pressureHpa;
  g_data.pressureHpa  = p;

  g_data.uvIndex = cur["uv_index"] | g_data.uvIndex;
  g_data.uvUpdatedAt = (uint32_t)time(nullptr);
  g_data.uvValid = true;

  // Today's high/low. Single-element arrays -- forecast_days=1.
  JsonArray hi = doc["daily"]["temperature_2m_max"];
  JsonArray lo = doc["daily"]["temperature_2m_min"];
  if (!hi.isNull() && !lo.isNull() && hi.size() && lo.size()) {
    g_data.tempMaxC = hi[0] | g_data.tempMaxC;
    g_data.tempMinC = lo[0] | g_data.tempMinC;
    g_data.dailyValid = true;
  }

  g_data.weatherUpdatedAt = (uint32_t)time(nullptr);
  g_data.weatherValid = true;

  Serial.printf("weather: %.1fC feels %.1f hi/lo %.1f/%.1f code %d cloud %d%% hum %d%% wind %.1f p %.1f  heap %u\n",
                g_data.tempC, g_data.feelsLikeC, g_data.tempMaxC, g_data.tempMinC,
                g_data.weatherCode, g_data.cloudCoverPct,
                g_data.humidityPct, g_data.windKph, g_data.pressureHpa, ESP.getFreeHeap());
  return true;
}

void weather_begin() {
  nextAttemptMs = 0;   // due immediately
  backoffMin = 0;
}

void weather_tick() {
  if ((int32_t)(millis() - nextAttemptMs) < 0) return;   // not due yet

  if (WiFi.status() != WL_CONNECTED) {
    nextAttemptMs = millis() + 30000;   // retry in 30 s when offline
    return;
  }

  if (doFetch()) {
    backoffMin = 0;
    nextAttemptMs = millis() + INTERVAL_MS;
  } else {
    backoffMin = (backoffMin == 0) ? 1 : backoffMin * 2;
    if (backoffMin > 15) backoffMin = 15;               // cap (plan §5.2)
    nextAttemptMs = millis() + backoffMin * 60UL * 1000;
    Serial.printf("weather: fetch failed, retry in %u min\n", backoffMin);
  }
}
