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
};

extern Settings g_settings;

// Load from NVS. Leaves the defaults above in place if nothing is stored.
void settings_begin();

// Persist g_settings and mark the device provisioned.
bool settings_save();

// Wipe stored settings -- next boot comes up in the setup portal.
void settings_clear();
