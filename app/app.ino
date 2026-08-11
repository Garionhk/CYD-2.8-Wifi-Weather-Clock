// ===========================================================================
// CYD Clock & Weather Station -- application (ESP32-2432S028R, 2.8")
// ===========================================================================
// Five scenes on a continuous rotation, with touch: tap to advance, long press
// to pin, and a 4 s hold to re-run touch calibration (plan §6). Which scenes are
// in the rotation, and how long each holds, come from the settings page.
//
// Board facts come from board.h, display settings from TFT_eSPI's User_Setup.h
// (ILI9341_2, TFT_INVERSION_ON, USE_HSPI_PORT, 55 MHz -- docs/stage0_results.md).
// Display on HSPI, touch on VSPI: separate buses, verified together in 0.2e.
//
// Touch calibration is measured on the device and kept in NVS, so it is not a
// firmware constant. See touch.h for why.

#include <WiFi.h>
#include <TFT_eSPI.h>

#include "board.h"
#include "settings.h"
#include "webconfig.h"
#include "theme.h"
#include "app_data.h"
#include "time_manager.h"
#include "touch.h"
#include "calibrate.h"
#include "status_strip.h"
#include "scenes.h"
#include "weather.h"
#include "airquality.h"
#include "sun_moon.h"
#include "labels.h"
#include "bus.h"

// Uncomment to hold a page of every baked Chinese label on the panel at boot.
// The bus scene's Chinese is 1-bit bitmaps, and whether the threshold kept the
// interior strokes of a dense glyph is not something a test can assert.
//#define BUS_LABEL_SELFTEST

// The shared globals declared extern in the headers.
TFT_eSPI tft = TFT_eSPI();
AppData  g_data;

// Compile-time guard: the display must be on HSPI or it takes the bus the
// touch panel needs. Silent when wrong at runtime, so it is caught here.
#ifndef USE_HSPI_PORT
  #error "User_Setup.h is missing '#define USE_HSPI_PORT' -- the display would take VSPI, which touch needs. Copy config/User_Setup_2432S028R.h.template into the TFT_eSPI library."
#endif

// Centred one-line message on the content area (boot progress).
static void bootMessage(const char* msg) {
  tft.fillRect(0, 0, SCREEN_W, CONTENT_H, COL_BG);
  tft.setTextColor(COL_TEXT, COL_BG);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(4);
  tft.drawString(msg, SCREEN_W / 2, CONTENT_H / 2);
}

// How long the station has to stay down before the setup AP is raised alongside
// it. Long enough to ride out a router reboot without the AP appearing and
// vanishing; short enough that a real problem is fixable without a power cycle.
static const uint32_t AP_FALLBACK_MS = 2UL * 60 * 1000;

static bool connectWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(g_settings.wifiSsid.c_str(), g_settings.wifiPass.c_str());
  Serial.printf("WiFi: connecting to \"%s\"", g_settings.wifiSsid.c_str());
  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 15000) {
    delay(300);
    Serial.print(".");
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf(" ok  IP %s  RSSI %d\n", WiFi.localIP().toString().c_str(), WiFi.RSSI());
    return true;
  }
  Serial.println(" TIMEOUT");
  return false;
}

// Full-screen instructions for the setup portal. Everything needed to finish
// setup has to be on the panel itself -- a device that can't reach the network
// can't tell you anything any other way.
static void setupScreen(const char* headline) {
  tft.fillScreen(COL_BG);
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(COL_ACCENT, COL_BG);
  tft.setTextFont(4);
  tft.drawString(headline, SCREEN_W / 2, 30);

  tft.setTextFont(2);
  tft.setTextColor(COL_DIM, COL_BG);
  tft.drawString("1. Join this WiFi network", SCREEN_W / 2, 70);
  tft.setTextColor(COL_TEXT, COL_BG);
  tft.setTextFont(4);
  tft.drawString(webconfig_apSsid(), SCREEN_W / 2, 95);

  tft.setTextFont(2);
  tft.setTextColor(COL_DIM, COL_BG);
  tft.drawString("2. The setup page opens automatically,", SCREEN_W / 2, 128);
  tft.drawString("or browse to", SCREEN_W / 2, 144);
  tft.setTextColor(COL_TEXT, COL_BG);
  tft.setTextFont(4);
  tft.drawString("http://" + webconfig_ip(), SCREEN_W / 2, 170);
}

// A finger held through boot forces the setup portal. Sampled with
// touch_readRaw rather than touch_isDown(): the gesture state machine only
// updates on touch_poll(), which has not started running yet. Ten samples so a
// single noise spike can't strand a working device in setup.
static bool touchHeldAtBoot() {
  int rx, ry, z, hits = 0;
  for (int i = 0; i < 10; i++) {
    if (touch_readRaw(rx, ry, z)) hits++;
    delay(20);
  }
  return hits >= 8;
}

// Blocking setup portal, used when nothing has ever been configured.
//
// Unlike calibrate_run(), this deliberately has NO timeout. The appliance rule
// in docs/decisions.md exists because calibration has a useful fallback (the
// default mapping). An unprovisioned device has none: no credentials means no
// WiFi, no NTP and therefore no clock, so there is nothing to fall back TO.
// A screen that says how to fix it is the most useful state available.
static void runSetupPortal() {
  webconfig_beginAP();
  setupScreen("Setup");
  Serial.println("setup: waiting for configuration (no timeout by design)");

  while (!webconfig_saved()) {
    webconfig_tick();
    delay(5);
  }

  bootMessage("Saved -- restarting");
  delay(1500);
  ESP.restart();
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n=== CYD clock & weather station ===");
  cydPrintBanner();

  cydRgbLedOff();          // active LOW: powers up white and washes out the panel
  tft.init();
  tft.setRotation(CYD_ROTATION);
  tft.fillScreen(COL_BG);
  cydBacklightOn();        // after init, so there's no flash of power-up garbage

  // Touch before anything slow: if the panel has never been calibrated we want
  // the wizard in front of the user now, not after a 15 s network wait.
  touch_begin();
  if (!touch_hasStoredCalibration()) calibrate_run(true);

  settings_begin();

  // Before the setup portal, not after: the portal's /label endpoint writes
  // here, so a first-time user configuring a bus stop needs the filesystem
  // already mounted. The first mount on a device that has never had one formats
  // the partition and takes a couple of seconds, hence the message.
  bootMessage("Preparing storage...");
  label_begin();
#ifdef BUS_LABEL_SELFTEST
  label_selfTest(8000);
#endif

  // Forced setup is the recovery path for the one case the settings page can't
  // fix itself: the device is configured for a network that no longer exists,
  // so nothing can reach it.
  if (!g_settings.provisioned || touchHeldAtBoot()) {
    runSetupPortal();          // does not return -- restarts after a save
  }

  bootMessage("Connecting WiFi...");
  if (connectWiFi()) {
    webconfig_beginSTA();
  } else {
    // Failure stays non-fatal, as it always has been: the clock, sun/moon math
    // and touch all work offline. AP_STA means the portal is reachable to fix
    // the credentials while loop()'s reconnect keeps retrying the real network.
    Serial.println("WiFi: no connection -- starting setup AP (clock still runs)");
    webconfig_beginAP();
  }

  bootMessage("Syncing time...");
  timeManager_begin(g_settings.tz.c_str());
  // Give NTP a few seconds so the clock shows real time on first paint; not
  // fatal if it misses -- the clock renders "----" and fills in once synced.
  struct tm t;
  uint32_t start = millis();
  while (!timeManager_now(t) && millis() - start < 8000) delay(200);

  sunmoon_recompute();     // prints sunrise/sunset/moon to Serial for verification
  tft.fillScreen(COL_BG);
  statusStrip_init();
  sceneManager_begin();
  weather_begin();
  airquality_begin();
  bus_begin();
  Serial.println("running.  tap = next scene | hold = pin | hold 4 s = recalibrate");
}

void loop() {
  // Input first, so a tap is acted on in the same frame it is released.
  TouchEvent ev = touch_poll();
  if (ev == TOUCH_RECALIBRATE) {
    calibrate_run(true);
    // The wizard owns the whole screen, so rebuild everything behind it.
    tft.fillScreen(COL_BG);
    statusStrip_init();
    sceneManager_begin();
  } else if (ev != TOUCH_NONE) {
    sceneManager_handleTouch(ev);
    statusStrip_tick(false);      // reflect pin/freeze immediately, not in 500 ms
  }

  // Settings page: cheap when idle, and the delay(5) below leaves ample slack.
  webconfig_tick();
  if (webconfig_saved()) {
    // Applying WiFi, timezone, location and units live would each need a
    // different refresh path; a restart costs ~3 s and cannot leave the device
    // half-configured.
    bootMessage("Saved -- restarting");
    delay(1500);
    ESP.restart();
  }

  sceneManager_tick();
  weather_tick();       // fetches when due (first fetch shortly after boot)
  airquality_tick();    // AQI fetch, staggered ~5 s after weather
  bus_tick();           // at most one bus slot per loop -- see bus.cpp

  // Refresh the status strip twice a second (cheap; only changed bits repaint),
  // but five times faster while a finger is down -- that is when the strip is
  // previewing what releasing would do, and a 500 ms lag makes the hint useless.
  static uint32_t lastStrip = 0;
  if (millis() - lastStrip >= (touch_isDown() ? 100u : 500u)) {
    lastStrip = millis();
    statusStrip_tick(false);
  }

  // Lightweight WiFi keep-alive so the strip's bars/time stay live.
  static uint32_t lastWifiTry = 0;
  if (WiFi.status() != WL_CONNECTED && millis() - lastWifiTry >= 10000) {
    lastWifiTry = millis();
    WiFi.reconnect();
  }

  // WiFi lost after a good boot -- a replaced router, or a changed password.
  // Without this the settings page becomes unreachable until someone power
  // cycles the device, which is a poor ask for something mounted on a wall.
  // The grace period keeps a router reboot or a brief dropout from flapping the
  // AP up and down.
  static uint32_t staDownSince = 0;
  if (WiFi.status() != WL_CONNECTED) {
    if (staDownSince == 0) staDownSince = millis();
    if (!webconfig_isAP() && millis() - staDownSince >= AP_FALLBACK_MS) {
      Serial.println("WiFi: down for 2 min -- raising setup AP");
      webconfig_beginAP();
    }
  } else {
    staDownSince = 0;
  }

  // The fallback AP has done its job once the real network comes back. Dropping
  // it frees the radio from AP_STA and returns the clock footer to the LAN IP.
  if (webconfig_isAP() && WiFi.status() == WL_CONNECTED) {
    webconfig_stopAP();
    webconfig_beginSTA();
  }

  // 5 ms keeps touch sampling well inside the debounce window while leaving
  // the CPU mostly idle.
  delay(5);
}
