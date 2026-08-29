#include "status_strip.h"
#include "theme.h"
#include "uitext.h"
#include "time_manager.h"
#include "scenes.h"
#include "touch.h"
#include "app_data.h"
#include <WiFi.h>
#include <time.h>

// Cached state so we only repaint a widget when its value actually changes
// (keeps the strip flicker-free).
static char     cacheTime[6]  = "";
static int      cacheWifi     = -1;
static uint16_t cacheFresh    = 0xABCD;   // impossible sentinel
static int      cacheScene    = -1;
static uint16_t cachePin      = 0xABCD;   // impossible sentinel
static int      cacheFreeze   = -1;

// ---- geometry within the strip --------------------------------------------
static const int TIME_X    = 10;          // left, ML datum
static const int WIFI_X    = 96;          // bar group left edge
static const int WIFI_BASE = STATUS_Y + 34;
static const int FRESH_X   = 152;
static const int PIN_X     = 188;         // pin glyph centre
static const int FREEZE_X  = 216;         // pause glyph left edge
static const int DOTS_R    = 300;         // right-most scene dot centre
// Dot pitch. Was 16, which fitted four; the fifth scene's leftmost dot would
// have had its left edge at x=232 against the freeze glyph ending at x=228 --
// 4 px of clearance, and a sixth would have overlapped outright. At 13 the five
// dots span 248..300 with 16 px clear, and since they are only r=4 the 5 px gap
// between them looks no different from before.
static const int DOTS_PITCH = 13;

static int wifiLevel() {
  if (WiFi.status() != WL_CONNECTED) return 0;
  int r = WiFi.RSSI();
  if (r >= -60) return 4;
  if (r >= -68) return 3;
  if (r >= -75) return 2;
  return 1;
}

static uint16_t freshnessColor() {
  if (!g_data.weatherValid || g_data.weatherUpdatedAt == 0) return COL_FRESH_NONE;
  uint32_t now = (uint32_t)time(nullptr);
  uint32_t age = (now > g_data.weatherUpdatedAt) ? now - g_data.weatherUpdatedAt : 0;
  if (age < 1800)  return COL_FRESH_OK;    // < 30 min
  if (age < 7200)  return COL_FRESH_WARN;  // < 2 h
  return COL_FRESH_OLD;
}

static void drawTime(const char* hhmm) {
  tft.fillRect(TIME_X, STATUS_Y + 6, 74, STATUS_H - 12, COL_STRIP_BG);
  tft.setTextColor(COL_TEXT, COL_STRIP_BG);
  tft.setTextDatum(ML_DATUM);
  font_use(UI_FONT_M);
  tft.drawString(hhmm, TIME_X, STATUS_CY);
}

static void drawWifi(int level) {
  for (int i = 0; i < 4; i++) {
    int h  = 8 + i * 6;
    int x  = WIFI_X + i * 9;
    int y  = WIFI_BASE - h;
    if (i < level) tft.fillRect(x, y, 6, h, COL_ACCENT);
    else {
      tft.fillRect(x, y, 6, h, COL_STRIP_BG);   // clear old fill
      tft.drawRect(x, y, 6, h, COL_DIM);
    }
  }
}

static void drawFresh(uint16_t c) {
  tft.fillCircle(FRESH_X, STATUS_CY, 6, c);
}

// Thumbtack: round head, tapering shaft. The cell is always repainted first so
// nothing ghosts. Pass COL_STRIP_BG to hide it. A dimmed glyph means "still holding, release
// now and it pins"; cyan means the hold has passed 4 s and a release would
// open calibration instead. Without that feedback a long press is a blind
// wait with no way to tell it has registered.
static void drawPinGlyph(uint16_t c) {
  tft.fillRect(PIN_X - 8, STATUS_CY - 11, 16, 22, COL_STRIP_BG);
  if (c == COL_STRIP_BG) return;
  tft.fillCircle(PIN_X, STATUS_CY - 4, 6, c);
  tft.fillTriangle(PIN_X - 3, STATUS_CY + 1,
                   PIN_X + 3, STATUS_CY + 1,
                   PIN_X,     STATUS_CY + 10, c);
}

// What the pin cell should currently show.
static uint16_t pinGlyphColour() {
  const uint32_t held = touch_heldMs();
  if (held >= TOUCH_RECAL_MS) return COL_FREEZE;   // release -> recalibrate
  if (held >= TOUCH_LONG_MS)  return COL_DIM;      // release -> pin / unpin
  return sceneManager_isPinned() ? COL_PIN : COL_STRIP_BG;
}

// Pause bars: auto-rotation is temporarily frozen after a tap.
static void drawFreeze(bool on) {
  tft.fillRect(FREEZE_X, STATUS_CY - 8, 12, 16, COL_STRIP_BG);
  if (!on) return;
  tft.fillRect(FREEZE_X,     STATUS_CY - 7, 4, 14, COL_FREEZE);
  tft.fillRect(FREEZE_X + 7, STATUS_CY - 7, 4, 14, COL_FREEZE);
}

static void drawSceneDots(int idx) {
  int count = sceneManager_count();
  for (int i = 0; i < count; i++) {
    int cx = DOTS_R - (count - 1 - i) * DOTS_PITCH;
    if (i == idx) tft.fillCircle(cx, STATUS_CY, 4, COL_ACCENT);
    else {
      tft.fillCircle(cx, STATUS_CY, 4, COL_STRIP_BG);  // clear old fill
      tft.drawCircle(cx, STATUS_CY, 4, COL_DIM);
    }
  }
}

void statusStrip_init() {
  tft.fillRect(0, STATUS_Y, SCREEN_W, STATUS_H, COL_STRIP_BG);
  tft.drawFastHLine(0, STATUS_Y, SCREEN_W, COL_DIM);   // divider
  cacheTime[0] = '\0';
  cacheWifi = -1;
  cacheFresh = 0xABCD;
  cacheScene = -1;
  cachePin = 0xABCD;
  cacheFreeze = -1;
  statusStrip_tick(true);
}

void statusStrip_tick(bool force) {
  struct tm t;
  bool valid = timeManager_now(t);
  char hhmm[6];
  if (valid) strftime(hhmm, sizeof(hhmm), "%H:%M", &t);
  else       strncpy(hhmm, "--:--", sizeof(hhmm));
  if (force || strcmp(hhmm, cacheTime) != 0) { drawTime(hhmm); strncpy(cacheTime, hhmm, sizeof(cacheTime)); }

  int level = wifiLevel();
  if (force || level != cacheWifi) { drawWifi(level); cacheWifi = level; }

  uint16_t fc = freshnessColor();
  if (force || fc != cacheFresh) { drawFresh(fc); cacheFresh = fc; }

  uint16_t pin = pinGlyphColour();
  if (force || pin != cachePin) { drawPinGlyph(pin); cachePin = pin; }

  int frz = sceneManager_isFrozen() ? 1 : 0;
  if (force || frz != cacheFreeze) { drawFreeze(frz); cacheFreeze = frz; }

  int idx = sceneManager_index();
  if (force || idx != cacheScene) { drawSceneDots(idx); cacheScene = idx; }
}
