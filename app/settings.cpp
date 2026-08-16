#include "settings.h"
#include <Preferences.h>

Settings g_settings;

static Preferences prefs;
static const char* NVS_NS = "cydcfg";

// Key names are capped at 15 chars by NVS; these are all well inside it.
// "ok" is the sentinel that gates the whole record, matching touch.cpp.

void settings_begin() {
  // Opened read-write even though nothing is written: opening a namespace that
  // does not exist yet read-only logs an NVS error on every first boot.
  prefs.begin(NVS_NS, false);
  if (prefs.getBool("ok", false)) {
    g_settings.wifiSsid  = prefs.getString("ssid",  g_settings.wifiSsid);
    g_settings.wifiPass  = prefs.getString("pass",  g_settings.wifiPass);
    g_settings.latitude  = prefs.getFloat ("lat",   g_settings.latitude);
    g_settings.longitude = prefs.getFloat ("lon",   g_settings.longitude);
    g_settings.tz        = prefs.getString("tz",    g_settings.tz);
    g_settings.units     = prefs.getUChar ("units", g_settings.units);
    g_settings.provisioned = true;
  }
  prefs.end();

  // The SSID is printed because a router broadcasts it anyway and "connected to
  // the wrong network" is otherwise painful to diagnose. The password never is:
  // serial logs end up pasted into issue reports.
  Serial.printf("settings: %s  ssid \"%s\"  %.4f,%.4f  tz %s  %s\n",
                g_settings.provisioned ? "loaded" : "UNPROVISIONED (defaults)",
                g_settings.wifiSsid.c_str(),
                g_settings.latitude, g_settings.longitude,
                g_settings.tz.c_str(),
                g_settings.units == UNITS_IMPERIAL ? "imperial" : "metric");
}

bool settings_save() {
  prefs.begin(NVS_NS, false);
  prefs.putString("ssid",  g_settings.wifiSsid);
  prefs.putString("pass",  g_settings.wifiPass);
  prefs.putFloat ("lat",   g_settings.latitude);
  prefs.putFloat ("lon",   g_settings.longitude);
  prefs.putString("tz",    g_settings.tz);
  prefs.putUChar ("units", g_settings.units);
  prefs.putBool  ("ok",    true);        // written last: a half-written record
  prefs.end();                           // must not read back as valid
  g_settings.provisioned = true;
  Serial.println("settings: saved to NVS");
  return true;
}

void settings_clear() {
  prefs.begin(NVS_NS, false);
  prefs.clear();
  prefs.end();
  g_settings.provisioned = false;
  Serial.println("settings: cleared");
}
