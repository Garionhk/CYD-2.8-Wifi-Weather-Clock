#include "bus_eta.h"
#include "config.h"
#include "app_data.h"
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <time.h>

static const uint32_t INTERVAL_MS = 60UL * 1000;   // 1 min

#define BUS_DUMMY 1

static const char* BUS_STOP_IDS[] = { "4A84482F3A54E4CC", "ABC15E2110BF7A49" };
static const int   BUS_STOP_N = 2;

static uint32_t nextAttemptMs = 0;
static uint32_t backoffMin    = 0;

static int etaMinutes(const char* iso) {
  if (!iso || strlen(iso) < 16) return -1;
  int hh = (iso[11]-'0')*10 + (iso[12]-'0');
  int mm = (iso[14]-'0')*10 + (iso[15]-'0');

  time_t now = time(nullptr);
  struct tm* t = localtime(&now);

  int nowMin = t->tm_hour * 60 + t->tm_min;
  int etaMin = hh * 60 + mm;

  int diff = etaMin - nowMin;
  if (diff < 0) diff += 24 * 60;
  return diff;
}

static void seedDummy() {
  g_data.busStopCount = 2;

  strncpy(g_data.busStops[0].stopId, BUS_STOP_IDS[0], sizeof(g_data.busStops[0].stopId) - 1);
  strncpy(g_data.busStops[0].name,  "HONG SING GARDEN (TK451)", sizeof(g_data.busStops[0].name) - 1);
  g_data.busStops[0].routeCount = 5;
  strncpy(g_data.busStops[0].routes[0].route, "290",   sizeof(g_data.busStops[0].routes[0].route) - 1);
  strncpy(g_data.busStops[0].routes[0].dest,  "TSUEN WAN WEST STATION", sizeof(g_data.busStops[0].routes[0].dest) - 1);
  snprintf(g_data.busStops[0].routes[0].eta1, sizeof(g_data.busStops[0].routes[0].eta1), "%d min", 3);
  snprintf(g_data.busStops[0].routes[0].eta2, sizeof(g_data.busStops[0].routes[0].eta2), "%d min", 12);
  strncpy(g_data.busStops[0].routes[1].route, "290X",  sizeof(g_data.busStops[0].routes[1].route) - 1);
  strncpy(g_data.busStops[0].routes[1].dest,  "TSUEN WAN WEST STATION", sizeof(g_data.busStops[0].routes[1].dest) - 1);
  snprintf(g_data.busStops[0].routes[1].eta1, sizeof(g_data.busStops[0].routes[1].eta1), "%d min", 7);
  strncpy(g_data.busStops[0].routes[2].route, "93M",   sizeof(g_data.busStops[0].routes[2].route) - 1);
  strncpy(g_data.busStops[0].routes[2].dest,  "PO LAM", sizeof(g_data.busStops[0].routes[2].dest) - 1);
  snprintf(g_data.busStops[0].routes[2].eta1, sizeof(g_data.busStops[0].routes[2].eta1), "%d min", 2);
  strncpy(g_data.busStops[0].routes[3].route, "95",    sizeof(g_data.busStops[0].routes[3].route) - 1);
  strncpy(g_data.busStops[0].routes[3].dest,  "KOWLOON STATION", sizeof(g_data.busStops[0].routes[3].dest) - 1);
  g_data.busStops[0].routes[3].eta1[0] = '\0';
  strncpy(g_data.busStops[0].routes[4].route, "N691",  sizeof(g_data.busStops[0].routes[4].route) - 1);
  strncpy(g_data.busStops[0].routes[4].dest,  "CENTRAL (MACAO FERRY)", sizeof(g_data.busStops[0].routes[4].dest) - 1);
  g_data.busStops[0].routes[4].eta1[0] = '\0';

  strncpy(g_data.busStops[1].stopId, BUS_STOP_IDS[1], sizeof(g_data.busStops[1].stopId) - 1);
  strncpy(g_data.busStops[1].name,  "HONG SING GARDEN (TK200)", sizeof(g_data.busStops[1].name) - 1);
  g_data.busStops[1].routeCount = 4;
  strncpy(g_data.busStops[1].routes[0].route, "290A",  sizeof(g_data.busStops[1].routes[0].route) - 1);
  strncpy(g_data.busStops[1].routes[0].dest,  "TSUEN WAN WEST STATION", sizeof(g_data.busStops[1].routes[0].dest) - 1);
  snprintf(g_data.busStops[1].routes[0].eta1, sizeof(g_data.busStops[1].routes[0].eta1), "%d min", 5);
  strncpy(g_data.busStops[1].routes[1].route, "290X",  sizeof(g_data.busStops[1].routes[1].route) - 1);
  strncpy(g_data.busStops[1].routes[1].dest,  "TSUEN WAN WEST STATION", sizeof(g_data.busStops[1].routes[1].dest) - 1);
  snprintf(g_data.busStops[1].routes[1].eta1, sizeof(g_data.busStops[1].routes[1].eta1), "%d min", 15);
  strncpy(g_data.busStops[1].routes[2].route, "93K",   sizeof(g_data.busStops[1].routes[2].route) - 1);
  strncpy(g_data.busStops[1].routes[2].dest,  "MONG KOK EAST STATION", sizeof(g_data.busStops[1].routes[2].dest) - 1);
  g_data.busStops[1].routes[2].eta1[0] = '\0';
  strncpy(g_data.busStops[1].routes[3].route, "N290",  sizeof(g_data.busStops[1].routes[3].route) - 1);
  strncpy(g_data.busStops[1].routes[3].dest,  "TSUEN WAN WEST STATION", sizeof(g_data.busStops[1].routes[3].dest) - 1);
  g_data.busStops[1].routes[3].eta1[0] = '\0';

  g_data.busUpdatedAt = (uint32_t)time(nullptr);
  g_data.busValid = true;
  Serial.println("bus: dummy data seeded");
}

static bool fetchStop(const char* stopId, AppData::BusStop& stop) {
  String url = String("https://bus-eta-sage.vercel.app/api/stops/") + stopId + "/eta";

  WiFiClientSecure client;
  client.setInsecure();
  client.setTimeout(15000);

  HTTPClient https;
  if (!https.begin(client, url)) {
    Serial.printf("bus: https.begin failed for stop %s\n", stopId);
    return false;
  }
  https.setTimeout(15000);

  int code = https.GET();
  if (code != 200) {
    Serial.printf("bus: stop %s HTTP %d\n", stopId, code);
    https.end(); client.stop();
    return false;
  }

  String payload = https.getString();
  https.end(); client.stop();

  DynamicJsonDocument doc(16384);
  DeserializationError err = deserializeJson(doc, payload);
  if (err) {
    Serial.printf("bus: stop %s JSON parse failed: %s\n", stopId, err.c_str());
    return false;
  }

  JsonArray arr = doc["data"];
  if (arr.isNull()) {
    Serial.printf("bus: stop %s no data array\n", stopId);
    return false;
  }

  stop.routeCount = 0;
  for (JsonObject item : arr) {
    if (stop.routeCount >= AppData::BUS_MAX) break;

    AppData::BusRoute& r = stop.routes[stop.routeCount];
    const char* route = item["route"] | "";
    const char* dest = item["dest_en"] | item["dest_sc"] | "";
    strncpy(r.route, route, sizeof(r.route) - 1);
    strncpy(r.dest, dest, sizeof(r.dest) - 1);

    r.eta1[0] = '\0';
    r.eta2[0] = '\0';
    r.remark1[0] = '\0';
    r.remark2[0] = '\0';

    JsonArray eta = item["eta"];
    if (!eta.isNull() && eta.size() > 0) {
      const char* e = eta[0]["eta"];
      int m1 = etaMinutes(e);
      if (m1 >= 0) snprintf(r.eta1, sizeof(r.eta1), "%d min", m1);
      const char* rm = eta[0]["remark_en"];
      if (rm && rm[0]) strncpy(r.remark1, rm, sizeof(r.remark1) - 1);
    }
    if (!eta.isNull() && eta.size() > 1) {
      const char* e = eta[1]["eta"];
      int m2 = etaMinutes(e);
      if (m2 >= 0) snprintf(r.eta2, sizeof(r.eta2), "%d min", m2);
      const char* rm = eta[1]["remark_en"];
      if (rm && rm[0]) strncpy(r.remark2, rm, sizeof(r.remark2) - 1);
    }

    stop.routeCount++;
  }

  Serial.printf("bus: stop %s (%s) got %d routes\n", stopId, stop.name, stop.routeCount);
  return true;
}

static bool doFetch() {
  Serial.println("bus: fetch starting");

  WiFiClientSecure client;
  client.setInsecure();
  client.setTimeout(15000);

  IPAddress ip;
  Serial.printf("bus: resolving bus-eta-sage.vercel.app ... ");
  if (!WiFi.hostByName("bus-eta-sage.vercel.app", ip) || ip == INADDR_NONE || ip == IPAddress(0, 0, 0, 0)) {
    Serial.println("FAILED, trying explicit DNS (8.8.8.8)");
    IPAddress dns1(8, 8, 8, 8);
    IPAddress dns2(1, 1, 1, 1);
    WiFi.config(WiFi.localIP(), WiFi.gatewayIP(), WiFi.subnetMask(), dns1, dns2);
    delay(200);
    if (!WiFi.hostByName("bus-eta-sage.vercel.app", ip) || ip == INADDR_NONE || ip == IPAddress(0, 0, 0, 0)) {
      Serial.println("FAILED");
      return false;
    }
  }
  Serial.printf("ok -> %s\n", ip.toString().c_str());

  g_data.busStopCount = 0;
  bool anyOk = false;

  for (int i = 0; i < BUS_STOP_N; i++) {
    if (g_data.busStopCount >= AppData::BUS_STOP_MAX) break;
    AppData::BusStop& stop = g_data.busStops[g_data.busStopCount];
    strncpy(stop.stopId, BUS_STOP_IDS[i], sizeof(stop.stopId) - 1);
    stop.name[0] = '\0';
    stop.routeCount = 0;
    if (fetchStop(BUS_STOP_IDS[i], stop)) {
      anyOk = true;
      g_data.busStopCount++;
    }
  }

  if (!anyOk) {
    Serial.println("bus: all stops failed");
    return false;
  }

  g_data.busUpdatedAt = (uint32_t)time(nullptr);
  g_data.busValid = true;

  Serial.printf("bus: %d stops fetched  heap %u\n", g_data.busStopCount, ESP.getFreeHeap());
  return true;
}

void busEta_begin() {
  nextAttemptMs = 0;
  backoffMin = 0;
#if BUS_DUMMY
  seedDummy();
#endif
  Serial.printf("bus: TLS test begin... ");
  {
    WiFiClientSecure tlsTest;
    tlsTest.setInsecure();
    tlsTest.setTimeout(5000);
    HTTPClient h;
    if (h.begin(tlsTest, "https://api.open-meteo.com") && h.GET() > 0) {
      Serial.printf("HTTP %d\n", h.GET());
      h.end();
    } else {
      Serial.println("FAILED");
    }
    h.end();
  }
  Serial.println("bus: TLS test done");
}

void busEta_tick() {
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
    Serial.printf("bus: fetch failed, retry in %u min\n", backoffMin);
#if BUS_DUMMY
    if (!g_data.busValid) seedDummy();
#endif
  }
}
