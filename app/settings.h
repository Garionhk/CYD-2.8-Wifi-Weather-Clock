// ===========================================================================
// settings.h -- runtime user configuration, stored in NVS (plan §10)
// ===========================================================================
// WiFi credentials, location, timezone and units used to be compile-time
// #defines in config.h. They now live in NVS and are set from the web UI
// (webconfig.h), for the same reason touch calibration does -- see touch.h:
// re-flashing to change a setting is a poor repair path for something hanging
// on a wall.
//
// The second reason is specific to credentials: a binary built from a header
// full of secrets carries those secrets. `strings app.bin` used to print the
// author's WiFi password. Nothing user-specific is compiled in any more, so the
// same image can be handed to anyone.
#pragma once

#include <Arduino.h>

static const uint8_t UNITS_METRIC   = 0;   // C, km/h, hPa
static const uint8_t UNITS_IMPERIAL = 1;   // F, mph, inHg

// ---------------------------------------------------------------------------
// Hong Kong bus / minibus stops (bus scene)
// ---------------------------------------------------------------------------
// Three operators, three genuinely different API models -- not one with a
// parameter -- which is why the identity fields do not overlap:
//
//   KMB / LWB  stopId + route + serviceType + dir
//   CTB        stopId + route + dir
//   GMB        routeId + routeSeq + stopSeq   (direction is IN the URL, so
//              there is nothing to filter client-side)
//
// LWB shares KMB's endpoint entirely and differs only in badge colour -- gold
// rather than red. It is a separate value rather than a flag because that is
// all it needs to be, and because the browser already knows which it picked.
//
// The IDs are resolved in the browser and only the results are stored: the
// device never matches route numbers to stops. That is deliberate (see
// docs/decisions.md) -- HK route variants are renumbered on a daily 05:00
// regeneration, and chasing that on an ESP32 is a maintenance burden the
// browser absorbs for free.
enum BusOperator : uint8_t {
  OP_KMB = 0,
  OP_CTB = 1,
  OP_GMB = 2,
  OP_LWB = 3,     // KMB's endpoint, Long Win's colours
};

struct BusStop {
  BusOperator op = OP_KMB;
  String   route;               // "68X" / "1" / "12A" -- ASCII, drawn in Font 2

  // KMB / LWB / CTB
  String   stopId;              // 16-hex (KMB/LWB) or 6-digit (CTB)
  uint8_t  serviceType = 1;     // KMB/LWB only
  char     dir = 'O';           // 'O'utbound / 'I'nbound; KMB/LWB/CTB only

  // GMB
  uint32_t routeId  = 0;
  uint8_t  routeSeq = 0;
  uint8_t  stopSeq  = 0;

  // Labels. The _tc pair is what the user reads; the _en pair is the fallback
  // when the baked bitmap is missing, which happens after every reflash because
  // that wipes LittleFS but not NVS. Keeping both is what makes that survivable.
  String   stopTc, stopEn, destTc, destEn;

  bool valid() const;
  bool usesKmbApi() const { return op == OP_KMB || op == OP_LWB; }
};

// One pipe-separated string per slot, because NVS keys are capped at 15 chars
// and twelve fields x three slots would need thirty-six of them. Round-trips
// through the settings page's text field unchanged, which is what makes manual
// entry a real fallback rather than a theoretical one.
String busStop_pack(const BusStop& b);
bool   busStop_unpack(const String& packed, BusStop& out);

// Human-readable, for serial and for the settings page's summary line.
String busStop_describe(const BusStop& b);

static const int BUS_SLOTS = 3;

struct Settings {
  String  wifiSsid;
  String  wifiPass;
  // Greenwich Observatory -- a placeholder that is obviously not the user's,
  // so an unconfigured device shows visibly wrong data rather than plausible
  // data for someone else's city.
  float   latitude    =  51.4779f;
  float   longitude   =  -0.0015f;
  String  tz          = "UTC0";        // POSIX TZ string, not an IANA name
  uint8_t units       = UNITS_METRIC;
  bool    provisioned = false;         // false until the user has saved once
  BusStop buses[BUS_SLOTS];            // all unset by default -- see valid()
};

extern Settings g_settings;

// Load from NVS. Leaves the defaults above in place if nothing is stored.
void settings_begin();

// Persist g_settings and mark the device provisioned.
bool settings_save();

// Wipe stored settings -- next boot comes up in the setup portal.
void settings_clear();
