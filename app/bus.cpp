#include "bus.h"
#include "settings.h"
#include "app_data.h"
#include "labels.h"
#include "touch.h"
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <time.h>

// ===========================================================================
// The plain-HTTP split
// ===========================================================================
// Only KMB's API PATH serves plain HTTP. All three host roots redirect to
// https, and both Citybus and GMB redirect the API path too (301 -> CloudFront,
// verified). So:
//
//   KMB / LWB   http://data.etabus.gov.hk/...      ~270 ms, WiFiClient
//   Citybus     https://rt.data.gov.hk/...         TLS handshake, ~1.2-2.5 s
//   GMB         https://data.etagmb.gov.hk/...     TLS handshake, ~1.2-2.5 s
//
// That handshake is the single dominant cost in this feature, and it happens
// inside loop(). Weather already stalls loop() ~2 s every 15 minutes and nobody
// notices (0.2% duty); doing the same for three of four bus slots would be
// visible. The four mitigations, in descending order of value:
//
//   1. touch_isDown() -- a 2 s stall under a finger eats the tap outright.
//   2. One slot per tick, never a burst.
//   3. Idle cadence of 300 s per slot, offset so one fetch runs per ~75 s.
//   4. The connection is CLOSED after every fetch, not held open.
//
// (4) deserves the note. The obvious optimisation is to keep the TLS session
// alive and skip the handshake, and the plan called for WiFiClientSecure::
// setSession(). That API does not exist in the ESP32 Arduino core -- it is an
// ESP8266/BearSSL feature -- and the alternative, holding the connection open
// with setReuse(true), costs ~35 KB of live mbedTLS context per host. Two of
// those against a ~108 KB largest contiguous block is exactly what makes
// clockEnter's 10 KB sprite allocation start failing after a few hours of
// uptime. Paying the handshake and giving the heap straight back is the right
// trade here; the idle cadence is what makes it affordable. The elapsed-millis
// column in the serial line below is there so this stays a measured decision.
// ===========================================================================

// Cadence. Idle: 300 s per slot, spread across BUS_SLOTS, so one fetch runs
// per ~75 s and a worst-case 2.5 s stall is ~3% duty. Active: 30 s per slot at
// 7.5 s offsets -- affordable precisely because the Clock's 1 Hz colon is not
// on screen while this scene is, and a 300 s-old ABSOLUTE eta still counts down
// correctly anyway, so idle staleness only costs a newly-appeared bus.
static const uint32_t IDLE_PERIOD_MS   = 300000;
static const uint32_t ACTIVE_PERIOD_MS = 30000;
// Derived, not written down: the slots have to divide the period evenly or two
// of them drift onto the same tick, and hand-tuned offsets silently stopped
// dividing the moment BUS_SLOTS went from three to four.
static const uint32_t ACTIVE_OFFSET_MS = ACTIVE_PERIOD_MS / BUS_SLOTS;
static const uint32_t ACTIVE_LINGER_MS = 30000;

// Below this, time(nullptr) is the 1970 epoch and no countdown means anything.
static const time_t CLOCK_SYNCED_AFTER = 1700000000;

static uint32_t nextAttemptMs[BUS_SLOTS] = {};
static uint32_t backoffMin[BUS_SLOTS]    = {};
static uint32_t activeUntilMs = 0;
static int      rrSlot = 0;      // round-robin cursor, so no slot can starve

static bool sceneActive() { return (int32_t)(millis() - activeUntilMs) < 0; }

void bus_markActive() { activeUntilMs = millis() + ACTIVE_LINGER_MS; }

int32_t bus_ageSeconds(int slot) {
  if (slot < 0 || slot >= BUS_SLOTS || !g_data.bus[slot].valid) return -1;
  const uint32_t now = (uint32_t)time(nullptr);
  return (now > g_data.bus[slot].updatedAt) ? (int32_t)(now - g_data.bus[slot].updatedAt) : 0;
}

// ---------------------------------------------------------------------------
// Time parsing
// ---------------------------------------------------------------------------
// "2026-08-11T02:34:12+08:00"      KMB, Citybus
// "2026-08-11T02:21:36.138+08:00"  GMB -- milliseconds, and only GMB. A parser
//                                  that assumes one format fails on the other
//                                  with a WRONG TIME rather than an error,
//                                  which is the worst kind of failure here.
//
// strptime/mktime is the wrong tool twice over. mktime() interprets its tm in
// the DEVICE's timezone -- a user-chosen POSIX string with nothing to do with
// Hong Kong, so a device set to UTC0 would be 8 hours out and PST8PDT 16 -- and
// newlib's %z does not reliably accept the colon form "+08:00". So: sscanf the
// fixed layout, convert with days-from-civil, then apply the parsed offset.
//
// time(nullptr) returns UTC regardless of TZ (only localtime/strftime are
// affected by it), so etaEpoch - time(nullptr) is timezone-clean end to end.

// Howard Hinnant's days_from_civil: days since 1970-01-01 for a proleptic
// Gregorian date. Exact for every year in range, no lookup tables, no libc.
static long daysFromCivil(int y, unsigned m, unsigned d) {
  y -= (m <= 2);
  const int era = (y >= 0 ? y : y - 399) / 400;
  const unsigned yoe = (unsigned)(y - era * 400);
  const unsigned doy = (153u * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
  const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return (long)era * 146097L + (long)doe - 719468L;
}

// Returns 0 when the string is absent, empty, or unparseable -- all of which
// mean the same thing to the caller: this row carries no arrival time.
static time_t parseIso8601(const char* s) {
  if (!s || !*s) return 0;
  int Y, M, D, h, mi, sec, n = 0;
  if (sscanf(s, "%4d-%2d-%2dT%2d:%2d:%2d%n", &Y, &M, &D, &h, &mi, &sec, &n) != 6) return 0;
  if (M < 1 || M > 12 || D < 1 || D > 31) return 0;

  const char* p = s + n;
  if (*p == '.') { p++; while (*p >= '0' && *p <= '9') p++; }   // GMB's ms

  long off = 0;
  if (*p == '+' || *p == '-') {
    int oh = 0, om = 0;
    if (sscanf(p + 1, "%2d:%2d", &oh, &om) == 2 ||
        sscanf(p + 1, "%2d%2d",  &oh, &om) == 2) {
      off = (long)oh * 3600 + (long)om * 60;
      if (*p == '-') off = -off;
    }
  }
  // 'Z' or a missing offset both mean UTC, which leaves off at 0.

  return (time_t)(daysFromCivil(Y, (unsigned)M, (unsigned)D) * 86400L
                  + h * 3600L + mi * 60L + sec - off);
}

// ---------------------------------------------------------------------------
// Remarks
// ---------------------------------------------------------------------------
// Live Chinese from the API is not renderable by definition -- there is no CJK
// font on the device. But the operators' remark vocabulary is small and mostly
// enumerable, so the common ones map onto the pre-baked set and everything else
// falls back to the English in Font 2. Mapping from rmk_EN, not rmk_tc, is what
// lets the JSON filter drop the UTF-8 fields entirely (see fetchKmbCtb).
//
// KMB says "Scheduled Bus" (原定班次); GMB says "Scheduled" (未開出). Two labels
// rather than one, because each matches what that operator's own app shows.
static uint8_t remarkLabel(const char* en, bool& scheduled) {
  scheduled = false;
  if (!en || !*en) return 0xFF;
  if (strcmp(en, "Scheduled Bus") == 0) { scheduled = true; return ZH_TIMETABLE; }
  if (strcmp(en, "Scheduled")     == 0) { scheduled = true; return ZH_SCHEDULED; }
  if (strncmp(en, "Scheduled", 9) == 0) { scheduled = true; return ZH_TIMETABLE; }
  if (strstr(en, "Last Bus"))  return ZH_LAST_BUS;
  if (strstr(en, "Departed"))  return ZH_DEPARTED;
  return 0xFF;
}

// ---------------------------------------------------------------------------
// Candidate selection
// ---------------------------------------------------------------------------
// Two variables, no sort: eta_seq does not reliably start at 1 and is not
// reliably ordered, and sorting a handful of rows to take the smallest two
// would be more code and more RAM for the same answer.

struct Cand {
  time_t  t    = 0;
  bool    sched = false;
  uint8_t rmk  = 0xFF;
  char    en[16] = "";
};

struct Picker {
  Cand a, b;
  int  kept = 0;

  void offer(time_t t, const char* rmkEn) {
    kept++;
    Cand c;
    c.t = t;
    c.rmk = remarkLabel(rmkEn, c.sched);
    if (rmkEn) strncpy(c.en, rmkEn, sizeof(c.en) - 1);
    if (a.t == 0 || t < a.t)      { b = a; a = c; }
    else if (b.t == 0 || t < b.t) { b = c; }
  }
};

// A SUCCESSFUL fetch always overwrites, including with zeros -- a departed bus
// must vanish from the display. Only a FAILED fetch preserves last-good, which
// is the rule app_data.h states for every other feed.
static void commit(int slot, const Picker& p) {
  BusEta& e = g_data.bus[slot];
  const Cand* src[2] = { &p.a, &p.b };
  for (int i = 0; i < 2; i++) {
    e.eta[i]       = src[i]->t;
    e.scheduled[i] = src[i]->sched;
    e.remark[i]    = src[i]->rmk;
    strncpy(e.remarkEn[i], src[i]->en, sizeof(e.remarkEn[i]) - 1);
    e.remarkEn[i][sizeof(e.remarkEn[i]) - 1] = '\0';
  }
  const uint32_t now = (uint32_t)time(nullptr);
  e.updatedAt = now;
  e.valid     = true;
  // Distinguishing "this stop id has gone stale" from "no bus is coming" is
  // impossible in one response -- both are HTTP 200 with an empty list -- so
  // the only honest discriminator is time. Six hours of nothing is not a
  // quiet afternoon. See the scene's 檢查車站 state.
  if (p.a.t != 0) e.emptySince = 0;
  else if (e.emptySince == 0) e.emptySince = now;
}

// ---------------------------------------------------------------------------
// Per-operator fetches
// ---------------------------------------------------------------------------

// True when `now` can be trusted to reject arrivals already in the past.
static bool clockUsable() { return time(nullptr) >= CLOCK_SYNCED_AFTER; }

// KMB / LWB / Citybus: same response shape, different transport and host.
//
//   {"data":[{"co","route","dir","service_type","seq","eta_seq","eta",
//             "rmk_en","rmk_tc","dest_tc",...}]}
//
// Both return rows for the OPPOSITE direction at a stop served both ways, so
// client-side dir filtering is mandatory -- and its absence is invisible at a
// one-direction stop, which is why the serial line prints the drop count.
// KMB additionally returns rows for other service types; those are NOT dropped
// (the URL already asked for one, and a same-direction bus on a sibling variant
// is still a bus you can board) but the count is logged.
static bool fetchKmbCtb(int slot, const BusStop& b, uint32_t& elapsedMs) {
  const bool tls = (b.op == OP_CTB);
  String url;
  if (tls) url = "https://rt.data.gov.hk/v2/transport/citybus/eta/CTB/" + b.stopId + "/" + b.route;
  else     url = "http://data.etabus.gov.hk/v1/transport/kmb/eta/" + b.stopId + "/" + b.route
               + "/" + String((int)b.serviceType);

  const uint32_t t0 = millis();

  WiFiClient       plain;
  WiFiClientSecure secure;
  if (tls) { secure.setInsecure(); secure.setHandshakeTimeout(8); }
  WiFiClient& client = tls ? (WiFiClient&)secure : plain;

  HTTPClient http;
  http.setConnectTimeout(3000);
  http.setTimeout(3000);
  http.setReuse(false);          // give the TLS context straight back -- see top
  if (!http.begin(client, url)) { elapsedMs = millis() - t0; return false; }

  const int code = http.GET();
  if (code != 200) {
    Serial.printf("bus[%d] %s: HTTP %d\n", slot, b.route.c_str(), code);
    http.end(); client.stop();
    elapsedMs = millis() - t0;
    return false;
  }
  // Buffered, not streamed: parsing straight from the stream mis-reads chunked
  // responses (weather.cpp:39 -- learned the hard way in Stage 0.5).
  String payload = http.getString();
  http.end();
  client.stop();
  elapsedMs = millis() - t0;

  // Deliberately NOT dest_tc / rmk_tc. They are UTF-8 the device cannot draw,
  // and letting them into the string pool would cost RAM for glyphs that render
  // as nothing at all. Widening this filter "for convenience" later is a
  // regression, not a convenience.
  JsonDocument filter;
  filter["data"][0]["dir"]          = true;
  filter["data"][0]["eta"]          = true;
  filter["data"][0]["rmk_en"]       = true;
  filter["data"][0]["service_type"] = true;

  JsonDocument doc;
  if (deserializeJson(doc, payload, DeserializationOption::Filter(filter))) {
    Serial.printf("bus[%d] %s: JSON parse failed (%u B)\n",
                  slot, b.route.c_str(), (unsigned)payload.length());
    return false;
  }

  JsonArray rows = doc["data"];
  if (rows.isNull()) return false;

  Picker p;
  const time_t now = time(nullptr);
  const bool   useNow = clockUsable();
  int dropped = 0, otherType = 0;

  for (JsonObject r : rows) {
    const char* d = r["dir"] | "";
    if (d[0] != b.dir) { dropped++; continue; }
    // service_type is a Number here and a String in /route-stop/ -- never
    // compare them across endpoints.
    if (b.usesKmbApi() && (int)(r["service_type"] | (int)b.serviceType) != (int)b.serviceType)
      otherType++;

    // Citybus returns "" for a row with no time where KMB returns null, so the
    // parser has to treat absent, null and empty as one case. It does.
    const time_t t = parseIso8601(r["eta"] | "");
    if (t == 0) continue;
    // Without a synced clock there is no "past", so nothing is dropped for
    // being in it. The scene shows 未校時 in that state anyway.
    if (useNow && t <= now) continue;
    p.offer(t, r["rmk_en"] | "");
  }

  commit(slot, p);
  Serial.printf("bus[%d] %s %s %c @%s: 200, %d rows, %d kept (dir %c), dropped %d"
                "%s, +%ldm/+%ldm, %ums, heap %u/%u\n",
                slot, b.op == OP_CTB ? "CTB" : (b.op == OP_LWB ? "LWB" : "KMB"),
                b.route.c_str(), b.dir, b.stopId.c_str(),
                (int)rows.size(), p.kept, b.dir, dropped,
                otherType ? " (+other service types)" : "",
                p.a.t ? (long)((p.a.t - now) / 60) : -1L,
                p.b.t ? (long)((p.b.t - now) / 60) : -1L,
                (unsigned)elapsedMs, ESP.getFreeHeap(), ESP.getMaxAllocHeap());
  return true;
}

// GMB: the odd one, and the friendliest.
//
//   {"data":{"stop_id":20014491,"enabled":true,
//            "eta":[{"eta_seq":1,"diff":15,
//                    "timestamp":"2026-08-11T02:21:36.138+08:00",
//                    "remarks_tc":"未開出","remarks_en":"Scheduled"}]}}
//
// `data` is an OBJECT, not an array. There is no dir filtering at all, because
// route_seq is already in the URL. It carries both `diff` (minutes) and
// `timestamp`; we use the timestamp, so this feed ticks down locally on the
// same clock as the other two rather than being a number that silently ages.
// Note the paths are unversioned -- /eta/..., not /v1/eta/...
static bool fetchGmb(int slot, const BusStop& b, uint32_t& elapsedMs) {
  String url = "https://data.etagmb.gov.hk/eta/route-stop/" + String(b.routeId)
             + "/" + String((int)b.routeSeq) + "/" + String((int)b.stopSeq);

  const uint32_t t0 = millis();

  WiFiClientSecure client;
  client.setInsecure();
  client.setHandshakeTimeout(8);

  HTTPClient https;
  https.setConnectTimeout(3000);
  https.setTimeout(3000);
  https.setReuse(false);
  if (!https.begin(client, url)) { elapsedMs = millis() - t0; return false; }

  const int code = https.GET();
  if (code != 200) {
    Serial.printf("bus[%d] GMB %s: HTTP %d\n", slot, b.route.c_str(), code);
    https.end(); client.stop();
    elapsedMs = millis() - t0;
    return false;
  }
  String payload = https.getString();
  https.end();
  client.stop();
  elapsedMs = millis() - t0;

  JsonDocument filter;
  filter["data"]["enabled"] = true;
  filter["data"]["eta"][0]["timestamp"]   = true;
  filter["data"]["eta"][0]["remarks_en"]  = true;

  JsonDocument doc;
  if (deserializeJson(doc, payload, DeserializationOption::Filter(filter))) {
    Serial.printf("bus[%d] GMB %s: JSON parse failed (%u B)\n",
                  slot, b.route.c_str(), (unsigned)payload.length());
    return false;
  }

  JsonObject data = doc["data"];
  if (data.isNull()) return false;

  const bool enabled = data["enabled"] | true;
  Picker p;
  const time_t now = time(nullptr);
  const bool   useNow = clockUsable();

  if (enabled) {
    JsonArray rows = data["eta"];
    for (JsonObject r : rows) {
      const time_t t = parseIso8601(r["timestamp"] | "");
      if (t == 0) continue;
      if (useNow && t <= now) continue;
      p.offer(t, r["remarks_en"] | "");
    }
  }

  commit(slot, p);
  Serial.printf("bus[%d] GMB %s seq %d/%d: 200, %s, %d kept, +%ldm/+%ldm, "
                "%ums, heap %u/%u\n",
                slot, b.route.c_str(), (int)b.routeSeq, (int)b.stopSeq,
                enabled ? "enabled" : "DISABLED", p.kept,
                p.a.t ? (long)((p.a.t - now) / 60) : -1L,
                p.b.t ? (long)((p.b.t - now) / 60) : -1L,
                (unsigned)elapsedMs, ESP.getFreeHeap(), ESP.getMaxAllocHeap());
  return true;
}

static bool fetchSlot(int slot) {
  const BusStop& b = g_settings.buses[slot];
  uint32_t elapsed = 0;
  return (b.op == OP_GMB) ? fetchGmb(slot, b, elapsed)
                          : fetchKmbCtb(slot, b, elapsed);
}

// ---------------------------------------------------------------------------
// Scheduling
// ---------------------------------------------------------------------------

void bus_begin() {
  // Extends the existing boot stagger (weather 0, aqi 5 s) so nothing lands on
  // top of anything else while the first paint is still happening.
  for (int i = 0; i < BUS_SLOTS; i++) {
    // Tight, not spread across the idle period: every slot should have data
    // within the first minute or so, and each one then reschedules off its own
    // completion time, which preserves the spacing from here on.
    nextAttemptMs[i] = 9000 + (uint32_t)i * 15000;   // 9, 24, 39, 54 s
    backoffMin[i] = 0;
  }
}

void bus_requestRefresh() {
  const uint32_t now = millis();
  for (int i = 0; i < BUS_SLOTS; i++) {
    // The +500 ms matters: no fetch runs inside the scene's onEnter, so the
    // scene paints on the very next frame instead of behind a TLS handshake.
    const uint32_t want = now + 500 + (uint32_t)i * ACTIVE_OFFSET_MS;
    if ((int32_t)(want - nextAttemptMs[i]) < 0) nextAttemptMs[i] = want;
  }
}

void bus_tick() {
  // The highest-value line in this file. A fetch is a 0.3-2.5 s blocking stall;
  // running one while a finger is on the panel swallows the tap outright, and
  // the user's only feedback is that the screen ignored them.
  if (touch_isDown()) return;

  // One slot per call, never a burst -- three back-to-back TLS handshakes would
  // be a visible 6 s freeze. Round-robin so a slot that is always due first
  // cannot starve the others.
  for (int n = 0; n < BUS_SLOTS; n++) {
    const int i = rrSlot;
    rrSlot = (rrSlot + 1) % BUS_SLOTS;

    if (!g_settings.buses[i].valid()) continue;
    if ((int32_t)(millis() - nextAttemptMs[i]) < 0) continue;

    if (WiFi.status() != WL_CONNECTED) {
      nextAttemptMs[i] = millis() + 30000;
      continue;
    }

    const uint32_t period = sceneActive() ? ACTIVE_PERIOD_MS : IDLE_PERIOD_MS;

    if (fetchSlot(i)) {
      backoffMin[i] = 0;
      // Rescheduling from completion time is what keeps the slots spread out:
      // they start staggered at boot (or by bus_requestRefresh) and each one
      // reschedules off its own clock, so the phase difference is preserved
      // without anything having to maintain it.
      nextAttemptMs[i] = millis() + period;
    } else {
      // Same 1->2->4->8->15 min ladder as weather and aqi. Deliberately NOT
      // clamped back to `period` afterwards -- a backoff shorter than the idle
      // cadence is the point of a backoff, and an earlier draft of this
      // overwrote it.
      backoffMin[i] = (backoffMin[i] == 0) ? 1 : backoffMin[i] * 2;
      if (backoffMin[i] > 15) backoffMin[i] = 15;
      nextAttemptMs[i] = millis() + backoffMin[i] * 60UL * 1000;
      Serial.printf("bus[%d]: fetch failed, retry in %u min\n", i, backoffMin[i]);
    }
    return;                      // exactly one fetch per bus_tick(), always
  }
}
