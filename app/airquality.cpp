#include "airquality.h"
#include "settings.h"
#include "app_data.h"
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <time.h>

static const uint32_t INTERVAL_MS = 30UL * 60 * 1000;   // 30 min (plan §5.2)

static uint32_t nextAttemptMs = 0;
static uint32_t backoffMin    = 0;

static bool doFetch() {
  WiFiClientSecure client;
  client.setInsecure();

  HTTPClient https;
  // Note the air-quality host, distinct from the forecast host.
  String url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" +
               String(g_settings.latitude, 4) + "&longitude=" + String(g_settings.longitude, 4) +
               "&current=us_aqi,pm2_5&timezone=auto";

  if (!https.begin(client, url)) return false;

  int code = https.GET();
  if (code != 200) {
    Serial.printf("aqi: HTTP %d\n", code);
    https.end(); client.stop();
    return false;
  }

  String payload = https.getString();
  https.end(); client.stop();

  DynamicJsonDocument filter(192);
  filter["current"]["us_aqi"] = true;
  filter["current"]["pm2_5"]  = true;

  DynamicJsonDocument doc(512);
  if (deserializeJson(doc, payload, DeserializationOption::Filter(filter))) return false;

  JsonObject cur = doc["current"];
  if (cur.isNull() || cur["us_aqi"].isNull()) return false;

  g_data.aqi  = cur["us_aqi"] | g_data.aqi;
  g_data.pm25 = (int)lroundf(cur["pm2_5"] | (float)g_data.pm25);

  g_data.aqiUpdatedAt = (uint32_t)time(nullptr);
  g_data.aqiValid = true;

  Serial.printf("aqi: %d  pm2.5 %d  heap %u\n", g_data.aqi, g_data.pm25, ESP.getFreeHeap());
  return true;
}

void airquality_begin() {
  // Offset a few seconds past boot so the weather fetch goes first (plan §5.2
  // staggers the tasks; here it just avoids a double pause at startup).
  nextAttemptMs = 5000;
  backoffMin = 0;
}

void airquality_tick() {
  if ((int32_t)(millis() - nextAttemptMs) < 0) return;

  if (WiFi.status() != WL_CONNECTED) {
    nextAttemptMs = millis() + 30000;
    return;
  }

  if (doFetch()) {
    backoffMin = 0;
    nextAttemptMs = millis() + INTERVAL_MS;
  } else {
    backoffMin = (backoffMin == 0) ? 1 : backoffMin * 2;
    if (backoffMin > 15) backoffMin = 15;
    nextAttemptMs = millis() + backoffMin * 60UL * 1000;
    Serial.printf("aqi: fetch failed, retry in %u min\n", backoffMin);
  }
}
