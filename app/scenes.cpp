#include "scenes.h"
#include "theme.h"
#include "time_manager.h"
#include "app_data.h"
#include "units.h"
#include "sun_moon.h"
#include "webconfig.h"
#include "settings.h"
#include "labels.h"
#include "bus.h"
#include <WiFi.h>
#include <time.h>
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ===========================================================================
// Scene 1 -- Clock (home)
// ===========================================================================
// Big HH:MM in Font 8, one reusable per-digit sprite (plan §9: never allocate a
// full-screen sprite). Only digits that changed are re-pushed, so a normal
// minute tick redraws one or two glyphs. The colon blinks each second to show
// the clock is live without a full seconds field.

static TFT_eSprite digitSpr = TFT_eSprite(&tft);
static bool  sprReady = false;
static int   pDig[4]  = { -2, -2, -2, -2 };   // previous digit values
static int   pColon   = -1;                   // -1 forces first draw
static char  pDate[32] = "";
static char  pNet[48]  = "";                  // settings-page address footer

static void drawDigit(int idx, int val) {
  // Render one digit centred in its cell. Falls back to direct TFT drawing if
  // the sprite could not be allocated.
  const int x = DIGIT_X[idx];
  String s = (val < 0) ? String("-") : String(val);
  if (sprReady) {
    digitSpr.fillSprite(COL_BG);
    digitSpr.setTextColor(COL_TIME, COL_BG);
    digitSpr.setTextDatum(MC_DATUM);
    digitSpr.setTextFont(8);
    digitSpr.drawString(s, DIGIT_W / 2, DIGIT_H / 2);
    digitSpr.pushSprite(x, DIGIT_TOP_Y);
  } else {
    tft.fillRect(x, DIGIT_TOP_Y, DIGIT_W, DIGIT_H, COL_BG);
    tft.setTextColor(COL_TIME, COL_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextFont(8);
    tft.drawString(s, x + DIGIT_W / 2, DIGIT_TOP_Y + DIGIT_H / 2);
  }
}

static void drawColon(bool on) {
  uint16_t c = on ? COL_TIME : COL_BG;
  tft.fillCircle(COLON_X, DIGIT_TOP_Y + 26, 5, c);
  tft.fillCircle(COLON_X, DIGIT_TOP_Y + 56, 5, c);
}

// Where to reach the settings page. Printed on the clock because there is
// otherwise no way to find it without a serial monitor or the router's client
// list -- and in AP mode, no way to know the network name to join either.
static void netFooter(char* out, size_t n) {
  if (webconfig_isAP())
    snprintf(out, n, "setup: %s / %s", webconfig_apSsid().c_str(), webconfig_ip().c_str());
  else if (WiFi.status() == WL_CONNECTED)
    snprintf(out, n, "setup: %s", webconfig_ip().c_str());
  else
    snprintf(out, n, "wifi offline");
}

static void clockEnter() {
  tft.fillRect(0, 0, SCREEN_W, CONTENT_H, COL_BG);

  // Free any existing buffer first. onEnter can be re-entered without a
  // matching onExit -- the calibration wizard restarts the scene machine --
  // and createSprite over a live sprite would leak its 10 KB every time.
  if (sprReady) { digitSpr.deleteSprite(); sprReady = false; }

  sprReady = (digitSpr.createSprite(DIGIT_W, DIGIT_H) != nullptr);
  if (!sprReady) Serial.println("WARN: digit sprite alloc failed; drawing direct");

  // Force a full redraw on the first tick.
  for (int i = 0; i < 4; i++) pDig[i] = -2;
  pColon = -1;
  pDate[0] = '\0';
  pNet[0]  = '\0';
}

static void clockTick() {
  struct tm t;
  bool valid = timeManager_now(t);

  int d[4];
  if (valid) {
    d[0] = t.tm_hour / 10; d[1] = t.tm_hour % 10;
    d[2] = t.tm_min  / 10; d[3] = t.tm_min  % 10;
  } else {
    d[0] = d[1] = d[2] = d[3] = -1;   // "----" until NTP syncs
  }

  for (int i = 0; i < 4; i++) {
    if (d[i] != pDig[i]) { drawDigit(i, d[i]); pDig[i] = d[i]; }
  }

  int colon = valid ? (t.tm_sec % 2 == 0 ? 1 : 0) : 1;
  if (colon != pColon) { drawColon(colon == 1); pColon = colon; }

  char buf[32];
  if (valid) strftime(buf, sizeof(buf), "%a  %d %b %Y", &t);
  else       strncpy(buf, "syncing time...", sizeof(buf));
  if (strcmp(buf, pDate) != 0) {
    tft.fillRect(0, DATE_Y - 16, SCREEN_W, 32, COL_BG);
    tft.setTextColor(COL_DATE, COL_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextFont(4);
    tft.drawString(buf, SCREEN_W / 2, DATE_Y);
    strncpy(pDate, buf, sizeof(pDate));
  }

  // Footer: where the settings page lives. Same compare-and-redraw idiom as the
  // date, so it only costs SPI when the address actually changes -- which is
  // essentially never once the network is up.
  char net[48];
  netFooter(net, sizeof(net));
  if (strcmp(net, pNet) != 0) {
    tft.fillRect(0, NET_Y - 8, SCREEN_W, 16, COL_BG);
    tft.setTextColor(COL_DIM, COL_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextFont(2);
    tft.drawString(net, SCREEN_W / 2, NET_Y);
    strncpy(pNet, net, sizeof(pNet));
  }
}

static void clockExit() {
  if (sprReady) { digitSpr.deleteSprite(); sprReady = false; }
}

// ===========================================================================
// Scenes 2-4 -- placeholders (populated in later build steps)
// ===========================================================================
static void placeholder(const char* label) {
  tft.fillRect(0, 0, SCREEN_W, CONTENT_H, COL_BG);
  tft.setTextColor(COL_ACCENT, COL_BG);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(4);
  tft.drawString(label, SCREEN_W / 2, CONTENT_H / 2 - 12);
  tft.setTextColor(COL_DIM, COL_BG);
  tft.setTextFont(2);
  tft.drawString("coming soon", SCREEN_W / 2, CONTENT_H / 2 + 22);
}

// ===========================================================================
// Scene 2 -- Weather (plan §7)
// ===========================================================================
// Left: vector condition icon (no image assets) + label. Right: big current
// temperature and feels-like. Bottom: cloud %, humidity, wind. Values convert
// to the configured units at draw time (data stays metric in g_data).

enum WxCat { WX_CLEAR, WX_PARTLY, WX_CLOUD, WX_FOG, WX_RAIN, WX_SNOW, WX_THUNDER };

static WxCat wxCategory(int code) {
  if (code == 0)                                     return WX_CLEAR;
  if (code == 1 || code == 2)                        return WX_PARTLY;
  if (code == 3)                                     return WX_CLOUD;
  if (code == 45 || code == 48)                      return WX_FOG;
  if ((code >= 51 && code <= 67) ||
      (code >= 80 && code <= 82))                    return WX_RAIN;
  if ((code >= 71 && code <= 77) ||
       code == 85 || code == 86)                     return WX_SNOW;
  if (code >= 95)                                    return WX_THUNDER;
  return WX_CLOUD;
}

static const char* wxLabel(int code) {
  switch (wxCategory(code)) {
    case WX_CLEAR:   return "Clear";
    case WX_PARTLY:  return "Partly Cloudy";
    case WX_CLOUD:   return "Cloudy";
    case WX_FOG:     return "Fog";
    case WX_RAIN:    return "Rain";
    case WX_SNOW:    return "Snow";
    case WX_THUNDER: return "Thunderstorm";
  }
  return "";
}

static void iconSun(int cx, int cy, int r, uint16_t col) {
  for (int a = 0; a < 8; a++) {
    float ang = a * (float)PI / 4.0f;
    tft.drawLine(cx + cosf(ang) * (r + 4), cy + sinf(ang) * (r + 4),
                 cx + cosf(ang) * (r + 10), cy + sinf(ang) * (r + 10), col);
  }
  tft.fillCircle(cx, cy, r, col);
}

static void iconCloud(int cx, int cy, int s, uint16_t col) {
  tft.fillCircle(cx - s, cy, s * 0.7f, col);
  tft.fillCircle(cx + s, cy, s * 0.8f, col);
  tft.fillCircle(cx, cy - s * 0.5f, s, col);
  tft.fillRect(cx - s, cy, s * 2, s * 0.8f, col);
}

static const uint16_t C_SUN   = 0xFFE0;   // yellow
static const uint16_t C_CLOUD = 0xC618;   // light grey
static const uint16_t C_RAIN  = 0x5D9F;   // blue
static const uint16_t C_FOG   = 0x9CD3;   // pale grey
static const uint16_t C_HI    = 0xFD20;   // amber -- daily high
static const uint16_t C_LO    = 0x05FF;   // blue  -- daily low

static void drawWeatherIcon(int cx, int cy, int code) {
  switch (wxCategory(code)) {
    case WX_CLEAR:
      iconSun(cx, cy, 18, C_SUN);
      break;
    case WX_PARTLY:
      iconSun(cx - 10, cy - 10, 12, C_SUN);
      iconCloud(cx + 6, cy + 6, 13, C_CLOUD);
      break;
    case WX_CLOUD:
      iconCloud(cx, cy, 16, C_CLOUD);
      break;
    case WX_FOG:
      iconCloud(cx, cy - 6, 14, C_CLOUD);
      for (int i = 0; i < 3; i++) tft.drawFastHLine(cx - 18, cy + 12 + i * 5, 36, C_FOG);
      break;
    case WX_RAIN:
      iconCloud(cx, cy - 8, 15, C_CLOUD);
      for (int i = -1; i <= 1; i++)
        tft.drawLine(cx + i * 12, cy + 12, cx + i * 12 - 4, cy + 24, C_RAIN);
      break;
    case WX_SNOW:
      iconCloud(cx, cy - 8, 15, C_CLOUD);
      for (int i = -1; i <= 1; i++) tft.fillCircle(cx + i * 12, cy + 18, 2, COL_TEXT);
      break;
    case WX_THUNDER:
      iconCloud(cx, cy - 8, 15, C_CLOUD);
      tft.fillTriangle(cx - 2, cy + 8, cx + 8, cy + 8, cx - 4, cy + 24, C_SUN);
      tft.fillTriangle(cx + 4, cy + 14, cx - 6, cy + 26, cx + 2, cy + 26, C_SUN);
      break;
  }
}

// Draw "<value>°<unit>" left-anchored at (x, yMid) in `font`. The degree ring
// is drawn by hand -- the built-in fonts don't include the ° glyph.
static int drawDegVal(int x, int yMid, uint8_t font, int val, const char* unit, uint16_t col) {
  tft.setTextFont(font);
  tft.setTextDatum(ML_DATUM);
  tft.setTextColor(col, COL_BG);
  String s = String(val);
  tft.drawString(s, x, yMid);
  int nx = x + tft.textWidth(s);
  int fh = tft.fontHeight();
  int r  = (font >= 6) ? 4 : 2;
  int ringX = nx + r + 3;
  int ringY = yMid - fh / 2 + r + 2;
  tft.drawCircle(ringX, ringY, r, col);
  if (r > 2) tft.drawCircle(ringX, ringY, r - 1, col);
  int ux = ringX + r + 3;
  tft.setTextFont(2);   // unit needs a lettered font (Font 6/8 are digits-only)
  tft.drawString(unit, ux, yMid);
  return ux + tft.textWidth(unit);
}

// 9x9 up/down triangle, vertically centred on yMid -- the built-in fonts have
// no arrow glyphs, same reason drawDegVal hand-draws the degree ring.
static void triMark(int x, int yMid, bool up, uint16_t col) {
  if (up) tft.fillTriangle(x, yMid + 4, x + 8, yMid + 4, x + 4, yMid - 4, col);
  else    tft.fillTriangle(x, yMid - 4, x + 8, yMid - 4, x + 4, yMid + 4, col);
}

static void weatherStat(int cx, const char* big, const char* label) {
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(COL_TEXT, COL_BG);
  tft.setTextFont(4);
  tft.drawString(big, cx, 150);
  tft.setTextColor(COL_DIM, COL_BG);
  tft.setTextFont(2);
  tft.drawString(label, cx, 176);
}

static uint32_t wxShownAt = 0;   // g_data.weatherUpdatedAt reflected on screen

static void weatherEnter() {
  tft.fillRect(0, 0, SCREEN_W, CONTENT_H, COL_BG);

  if (!g_data.weatherValid) {
    tft.setTextColor(COL_DIM, COL_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextFont(4);
    tft.drawString("fetching weather...", SCREEN_W / 2, CONTENT_H / 2);
    wxShownAt = 0;
    return;
  }

  // Left: icon + condition label
  drawWeatherIcon(70, 56, g_data.weatherCode);
  tft.setTextColor(COL_TEXT, COL_BG);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(2);
  tft.drawString(wxLabel(g_data.weatherCode), 70, 110);

  // Right: big temperature, feels-like beneath
  drawDegVal(168, 52, 6, (int)lroundf(dispTemp(g_data.tempC)), tempUnit(), COL_TEXT);
  tft.setTextColor(COL_DATE, COL_BG);
  tft.setTextFont(2);
  tft.setTextDatum(ML_DATUM);
  tft.drawString("Feels", 168, 96);
  drawDegVal(168 + tft.textWidth("Feels") + 6, 96, 2,
             (int)lroundf(dispTemp(g_data.feelsLikeC)), tempUnit(), COL_DATE);

  // Today's range beneath, once the daily forecast has landed. No unit letter --
  // the big temperature above already states C/F, and it keeps the row compact.
  if (g_data.dailyValid) {
    int x = 168;
    triMark(x, 120, true, C_HI);
    x = drawDegVal(x + 12, 120, 4, (int)lroundf(dispTemp(g_data.tempMaxC)), "", C_HI);
    triMark(x + 14, 120, false, C_LO);
    drawDegVal(x + 26, 120, 4, (int)lroundf(dispTemp(g_data.tempMinC)), "", C_LO);
  }

  // Bottom: cloud, humidity, wind
  char c[8], h[8], w[12];
  snprintf(c, sizeof(c), "%d%%", g_data.cloudCoverPct);
  snprintf(h, sizeof(h), "%d%%", g_data.humidityPct);
  snprintf(w, sizeof(w), "%d %s", (int)lroundf(dispWind(g_data.windKph)), windUnit());
  weatherStat(55,  c, "CLOUD");
  weatherStat(160, h, "HUMIDITY");
  weatherStat(265, w, "WIND");

  wxShownAt = g_data.weatherUpdatedAt;
}

static void weatherTick() {
  // Redraw if a fetch landed new data while this scene is showing.
  if (g_data.weatherUpdatedAt != wxShownAt) weatherEnter();
}

// ===========================================================================
// Scene 4 -- Air Quality (plan §7)
// ===========================================================================
// AQI headline, colour-coded to the US AQI bands, with the band name in words.
// Secondary: PM2.5, humidity, wind, pressure with a trend arrow.

struct AqiBand { uint16_t color; const char* name; };

static AqiBand aqiBand(int aqi) {
  if (aqi <= 50)  return { 0x07E0, "Good" };            // green
  if (aqi <= 100) return { 0xFFE0, "Moderate" };        // yellow
  if (aqi <= 150) return { 0xFD20, "Unhealthy (SG)" };  // orange
  if (aqi <= 200) return { 0xF800, "Unhealthy" };       // red
  if (aqi <= 300) return { 0x8010, "Very Unhealthy" };  // purple
  return            { 0x7800, "Hazardous" };            // maroon
}

// Pressure trend triangle: rising / falling / steady.
static void drawTrend(int x, int y, float trend) {
  if (trend > 0.3f)       tft.fillTriangle(x, y + 4, x + 8, y + 4, x + 4, y - 4, 0x07E0);
  else if (trend < -0.3f) tft.fillTriangle(x, y - 4, x + 8, y - 4, x + 4, y + 4, C_RAIN);
  else                    tft.fillRect(x, y - 1, 8, 3, COL_DIM);
}

// Pressure stat cell (value + trend arrow + unit label).
static void pressureStat(int cx, const char* val, const char* unit, float trend) {
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(COL_TEXT, COL_BG);
  tft.setTextFont(4);
  tft.drawString(val, cx, 150);
  int w = tft.textWidth(val);
  drawTrend(cx + w / 2 + 6, 150, trend);
  tft.setTextColor(COL_DIM, COL_BG);
  tft.setTextFont(2);
  tft.drawString(unit, cx, 176);
}

static uint32_t aqShownAt = 0;

static void airQualEnter() {
  tft.fillRect(0, 0, SCREEN_W, CONTENT_H, COL_BG);

  if (!g_data.aqiValid) {
    tft.setTextColor(COL_DIM, COL_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextFont(4);
    tft.drawString("fetching air quality...", SCREEN_W / 2, CONTENT_H / 2);
    aqShownAt = 0;
    return;
  }

  AqiBand band = aqiBand(g_data.aqi);

  tft.setTextColor(COL_DIM, COL_BG);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(2);
  tft.drawString("AIR QUALITY INDEX", SCREEN_W / 2, 16);

  tft.setTextColor(band.color, COL_BG);
  tft.setTextFont(8);
  tft.drawString(String(g_data.aqi), SCREEN_W / 2, 62);

  tft.setTextColor(band.color, COL_BG);
  tft.setTextFont(4);
  tft.drawString(band.name, SCREEN_W / 2, 112);

  // Secondary row: PM2.5, humidity, wind, pressure(+trend). Short labels so
  // four columns fit; wind/pressure use the configured units.
  char pm[8], hum[8], wind[8], pres[8];
  snprintf(pm,   sizeof(pm),   "%d",   g_data.pm25);
  snprintf(hum,  sizeof(hum),  "%d%%", g_data.humidityPct);
  snprintf(wind, sizeof(wind), "%d",   (int)lroundf(dispWind(g_data.windKph)));
  snprintf(pres, sizeof(pres), "%d",   (int)lroundf(dispPress(g_data.pressureHpa)));

  weatherStat(42,  pm,   "PM2.5");
  weatherStat(116, hum,  "HUM");
  weatherStat(190, wind, windUnit());
  pressureStat(268, pres, pressUnit(), g_data.pressureTrend);

  aqShownAt = g_data.aqiUpdatedAt;
}

static void airQualTick() {
  if (g_data.aqiUpdatedAt != aqShownAt) airQualEnter();
}

// ===========================================================================
// Scene 3 -- Sun & Moon (plan §7, §8)
// ===========================================================================
// Left: sunrise→sunset arc with the sun's current position. Below it, the moon
// phase disk + illumination. Right: rise/set times, UV index, golden-hour
// countdown. When showingNextDay is set (after today's sunset), a TOMORROW
// label appears and the arc is greyed (plan §8: never ambiguous which day).

static uint16_t uvColor(float uv) {
  if (uv < 3)  return 0x07E0;   // green
  if (uv < 6)  return 0xFFE0;   // yellow
  if (uv < 8)  return 0xFD20;   // orange
  if (uv < 11) return 0xF800;   // red
  return 0x8010;                // purple
}

static void drawSunArc(int cx, int cyBase, int R, uint16_t col) {
  int px = -1, py = -1;
  for (int a = 0; a <= 180; a += 6) {
    double th = a * M_PI / 180.0;
    int x = cx + (int)lround(R * cos(th));
    int y = cyBase - (int)lround(R * sin(th));
    if (px >= 0) tft.drawLine(px, py, x, y, col);
    px = x; py = y;
  }
  tft.drawFastHLine(cx - R - 4, cyBase, 2 * R + 8, COL_DIM);   // horizon
}

static void drawSunMarker(int cx, int cyBase, int R, float f) {
  if (f < 0) f = 0; if (f > 1) f = 1;
  double th = (180.0 - f * 180.0) * M_PI / 180.0;
  int x = cx + (int)lround(R * cos(th));
  int y = cyBase - (int)lround(R * sin(th));
  tft.fillCircle(x, y, 5, C_SUN);
}

// Moon disk with a scanline terminator (0=new, 0.5=full; waxing lit on right).
static void drawMoon(int cx, int cy, int R, float phase) {
  const uint16_t lit = 0xE71C, shadow = 0x2965;
  tft.fillCircle(cx, cy, R, shadow);
  for (int dy = -R; dy <= R; dy++) {
    double xe = sqrt((double)R * R - (double)dy * dy);
    double xt = xe * cos(2 * M_PI * phase);
    int y = cy + dy;
    if (phase <= 0.5f) {            // waxing: lit on the right, xt..xe
      int x0 = cx + (int)lround(xt), x1 = cx + (int)lround(xe);
      if (x1 >= x0) tft.drawFastHLine(x0, y, x1 - x0 + 1, lit);
    } else {                        // waning: lit on the left, -xe..xt
      int x0 = cx - (int)lround(xe), x1 = cx + (int)lround(xt);
      if (x1 >= x0) tft.drawFastHLine(x0, y, x1 - x0 + 1, lit);
    }
  }
  tft.drawCircle(cx, cy, R, COL_DIM);
}

static const int SM_GX = 170, SM_GY = 140;   // golden-hour value position
static int smShownMin = -1;

static void drawGolden() {
  char g[24];
  goldenHourStatus(g, sizeof(g));
  tft.fillRect(SM_GX, SM_GY - 10, 150, 20, COL_BG);
  tft.setTextColor(C_SUN, COL_BG);
  tft.setTextDatum(ML_DATUM);
  tft.setTextFont(2);
  tft.drawString(g, SM_GX, SM_GY);
}

static void sunMoonEnter() {
  tft.fillRect(0, 0, SCREEN_W, CONTENT_H, COL_BG);
  sunmoon_recompute();

  time_t rise = g_data.showingNextDay ? g_data.sunriseTomorrow : g_data.sunriseToday;
  time_t set  = g_data.showingNextDay ? g_data.sunsetTomorrow  : g_data.sunsetToday;

  // Left: sun arc + marker
  const int acx = 76, acyBase = 96, R = 54;
  drawSunArc(acx, acyBase, R, g_data.showingNextDay ? COL_DIM : COL_ACCENT);
  if (!g_data.showingNextDay && set > rise) {
    time_t now = time(nullptr);
    drawSunMarker(acx, acyBase, R, (float)(now - rise) / (float)(set - rise));
  }

  // Left-bottom: moon
  drawMoon(40, 152, 22, g_data.moonPhase);
  tft.setTextColor(COL_TEXT, COL_BG);
  tft.setTextDatum(ML_DATUM);
  tft.setTextFont(2);
  tft.drawString(moonPhaseName(g_data.moonPhase), 72, 144);
  char mi[16];
  snprintf(mi, sizeof(mi), "%.0f%% lit", g_data.moonIlluminationPct);
  tft.setTextColor(COL_DIM, COL_BG);
  tft.drawString(mi, 72, 164);

  // Right column: TOMORROW banner, rise/set, UV
  if (g_data.showingNextDay) {
    tft.setTextColor(0xFD20, COL_BG);
    tft.setTextDatum(MC_DATUM);
    tft.setTextFont(2);
    tft.drawString("TOMORROW", 240, 12);
  }

  char rs[8], ss[8];
  if (rise) strftime(rs, sizeof(rs), "%H:%M", localtime(&rise)); else strncpy(rs, "--:--", sizeof(rs));
  if (set)  strftime(ss, sizeof(ss), "%H:%M", localtime(&set));  else strncpy(ss, "--:--", sizeof(ss));

  const int rx = 170;
  tft.setTextDatum(ML_DATUM);
  tft.setTextColor(COL_DATE, COL_BG); tft.setTextFont(2);
  tft.drawString("Sunrise", rx, 34);
  tft.drawString("Sunset",  rx, 64);
  tft.drawString("UV",      rx, 94);
  tft.setTextColor(COL_TEXT, COL_BG); tft.setTextFont(4);
  tft.drawString(rs, rx + 62, 34);
  tft.drawString(ss, rx + 62, 64);
  if (g_data.uvValid) {
    tft.setTextColor(uvColor(g_data.uvIndex), COL_BG);
    tft.drawString(String((int)lroundf(g_data.uvIndex)), rx + 62, 94);
  } else {
    tft.setTextColor(COL_DIM, COL_BG);
    tft.drawString("--", rx + 62, 94);
  }

  // Golden-hour label + value
  tft.setTextColor(COL_DATE, COL_BG);
  tft.setTextDatum(ML_DATUM);
  tft.setTextFont(2);
  tft.drawString("Golden hour", rx, 122);
  struct tm t; getLocalTime(&t, 10); smShownMin = t.tm_min;
  drawGolden();
}

static void sunMoonTick() {
  struct tm t;
  if (!getLocalTime(&t, 10)) return;
  if (t.tm_min == smShownMin) return;   // golden countdown updates per minute
  smShownMin = t.tm_min;
  drawGolden();
}

// ===========================================================================
// Scene 5 -- 下一班車 / Next Bus
// ===========================================================================
// The other four scenes answer "what is it like outside". This one answers
// "should I leave now", which is why it earns a 60 s hold rather than the usual
// 45: you tap to it to WATCH a countdown, not to read a number once.
//
// Two structural things are worth knowing before reading the drawing code:
//
//   * All Chinese is 1-bit bitmaps (labels.h). The fixed vocabulary is baked at
//     build time; stop names and destinations are baked by the browser. When a
//     user bitmap is missing -- which is the state after every reflash, because
//     that wipes LittleFS but not NVS -- the row falls back to the English name
//     in the built-in font. Never blank.
//
//   * Minutes are derived from ABSOLUTE epoch ETAs on every tick, never stored
//     as a countdown. So a five-minute-old fetch still shows the right number,
//     and pulling the network out mid-scene leaves the display counting down
//     correctly with only the header's age going red.

// Sentinels returned by busMinutes() in place of a real minute count. All
// negative, so the ">= 2 minutes" test in the drawing code stays a plain
// comparison rather than a special case.
static const int BM_LOADING = -1;   // no fetch has landed yet
static const int BM_NONE    = -2;   // fetched fine, nothing is coming
static const int BM_CHECK   = -3;   // nothing coming for six hours -- suspect
static const int BM_NOCLOCK = -4;   // NTP never synced; no countdown is possible

// How long a run of empty-but-successful fetches has to last before the scene
// stops saying "no service" and starts suggesting the stop id itself is wrong.
// Six hours is chosen to be longer than any real service gap -- even an
// overnight-only route reappears inside it -- so this cannot fire on a quiet
// afternoon. See BusEta::emptySince.
static const uint32_t BUS_CHECK_AFTER_S = 6 * 3600;

// Age bands for the header. Deliberately NOT the status strip's 30 min / 2 h --
// those are right for weather, which changes hourly, and far too loose here.
// The idle cadence is 300 s per slot, so anything inside 600 s means the feed
// is keeping up and nothing is wrong.
static const uint32_t BUS_AGE_WARN_S = 600;
static const uint32_t BUS_AGE_OLD_S  = 1800;

static const uint32_t BUS_PAGE_MS = 8000;

static int      busSlots[BUS_SLOTS];        // configured slot indices, compacted
static int      busCount   = 0;
static int      busPage    = 0, busPages = 1;
static uint32_t busPageAt  = 0;
static int      busRowSlot[2]  = { -1, -1 };  // slot shown in each row, -1 blank
static int      busShownMin[2] = { -99, -99 };
static uint32_t busShownAt[2]  = { 0, 0 };
static uint32_t busShownSec    = 0;
static int      busShownAge    = -999;
static bool     busShownAgeMin = false;       // header unit is 分前, not 秒前

static uint16_t opColour(BusOperator op) {
  switch (op) {
    case OP_CTB: return COL_OP_CTB;
    case OP_GMB: return COL_OP_GMB;
    case OP_LWB: return COL_OP_LWB;
    default:     return COL_OP_KMB;
  }
}

// Yellow and gold need black on them; red and green need white. Getting this
// backwards makes the route number unreadable at exactly the distance the
// scene is designed for.
static uint16_t opTextColour(BusOperator op) {
  return (op == OP_CTB || op == OP_LWB) ? COL_BG : COL_TEXT;
}

// Minutes until the next bus at `slot`, floored, or one of the BM_ sentinels.
// Flooring rather than rounding is deliberate: understating is the safe
// direction for something you are about to run for.
static int busMinutes(int slot, bool& sched, uint8_t& rmk, const char*& rmkEn) {
  sched = false; rmk = 0xFF; rmkEn = "";
  const BusEta& e = g_data.bus[slot];
  const time_t now = time(nullptr);

  // Before NTP lands, time(nullptr) is somewhere in 1970 and every ETA looks
  // 56 years away. Saying so is far better than rendering that.
  if (now < 1700000000L) return BM_NOCLOCK;
  if (!e.valid)          return BM_LOADING;

  // The second ETA is what makes a departure roll over instantly instead of
  // leaving the row blank until the next fetch, which on the idle cadence
  // could be five minutes.
  for (int i = 0; i < 2; i++) {
    if (e.eta[i] > now) {
      sched = e.scheduled[i];
      rmk   = e.remark[i];
      rmkEn = e.remarkEn[i];
      return (int)((e.eta[i] - now) / 60);
    }
  }
  if (e.emptySince && (uint32_t)now - e.emptySince >= BUS_CHECK_AFTER_S) return BM_CHECK;
  return BM_NONE;
}

// Badge position along the rail. sqrt keeps the resolution where it matters:
// the difference between 2 and 4 minutes is worth seeing, between 24 and 26 it
// is not. Returns the badge's LEFT edge.
static int busBadgeX(int mins) {
  const int xStop = BUS_TRACK_X0 + 4;
  const int xFar  = BUS_TRACK_X1 - BUS_BADGE_W;
  if (mins < 0) return xFar;
  const int m = (mins > BUS_TRACK_MAX_MIN) ? BUS_TRACK_MAX_MIN : mins;
  const float f = sqrtf((float)m / (float)BUS_TRACK_MAX_MIN);
  return xStop + (int)(f * (xFar - xStop));
}

// Rail, stop marker and badge. Repaints the whole strip rather than erasing the
// badge's old position: a fillRect is one windowed blast over SPI and tracking
// the previous x would be state to get wrong for no measurable gain.
static void drawBusTrack(int row, int slot, int mins) {
  const int y0 = BUS_ROW_Y[row];
  const int cy = y0 + BUS_TRACK_DY;
  tft.fillRect(BUS_TRACK_X0 - 2, cy - BUS_BADGE_H / 2 - 1,
               BUS_TRACK_X1 - BUS_TRACK_X0 + 6, BUS_BADGE_H + 2, COL_BG);

  // Nothing is coming: the rail would be a track with no vehicle on it, which
  // reads as "loading" rather than "none". Leave it empty.
  if (mins < 0 && mins != BM_LOADING) return;

  tft.drawFastHLine(BUS_TRACK_X0, cy,     BUS_TRACK_X1 - BUS_TRACK_X0, COL_DIM);
  tft.drawFastHLine(BUS_TRACK_X0, cy + 1, BUS_TRACK_X1 - BUS_TRACK_X0, COL_DIM);
  tft.fillRect(BUS_TRACK_X0 - 1, cy - 7, 3, 16, COL_TEXT);   // the stop itself

  if (mins == BM_LOADING) return;

  const BusStop& b = g_settings.buses[slot];
  bool sched = false; uint8_t rmk; const char* rmkEn;
  busMinutes(slot, sched, rmk, rmkEn);

  const int x = busBadgeX(mins);
  const int by = cy - BUS_BADGE_H / 2;
  const bool far = (mins > BUS_TRACK_MAX_MIN);
  const uint16_t col = far ? COL_DIM : opColour(b.op);

  if (sched) {
    // Hollow means the time came from a timetable, not a live prediction. This
    // keeps the colour channel free for operator identity, which is what the
    // colour is FOR -- encoding confidence in it too would cost recognition.
    tft.fillRoundRect(x, by, BUS_BADGE_W, BUS_BADGE_H, 6, COL_BG);
    tft.drawRoundRect(x, by, BUS_BADGE_W, BUS_BADGE_H, 6, col);
    tft.drawRoundRect(x + 1, by + 1, BUS_BADGE_W - 2, BUS_BADGE_H - 2, 5, col);
    tft.setTextColor(col, COL_BG);
  } else {
    tft.fillRoundRect(x, by, BUS_BADGE_W, BUS_BADGE_H, 6, col);
    tft.setTextColor(far ? COL_BG : opTextColour(b.op), col);
  }
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(4);
  tft.drawString(b.route, x + BUS_BADGE_W / 2, by + BUS_BADGE_H / 2);
}

// The right-hand column: the number and 分鐘, or a baked state label instead.
// Font 6 is digits-only, which is precisely why the special states are bitmaps
// rather than strings -- there is no font on this device that could set them.
static void drawBusMinutes(int row, int slot, int mins) {
  const int y0 = BUS_ROW_Y[row];
  tft.fillRect(BUS_NUM_L, y0 + 2, BUS_NUM_R - BUS_NUM_L + 2, BUS_ROW_H - 6, COL_BG);

  // Vertically centred on the row for the states, which have no second line.
  const int myCy = y0 + BUS_TRACK_DY;

  switch (mins) {
    case BM_NOCLOCK: label_draw(ZH_NO_CLOCK,   BUS_NUM_R, myCy, LBL_RIGHT, COL_DIM,          COL_BG); return;
    case BM_NONE:    label_draw(ZH_NO_SERVICE, BUS_NUM_R, myCy, LBL_RIGHT, COL_DIM,          COL_BG); return;
    case BM_CHECK:   label_draw(ZH_CHECK_STOP, BUS_NUM_R, myCy, LBL_RIGHT, COL_FRESH_OLD,    COL_BG); return;
    case BM_LOADING:
      tft.setTextColor(COL_DIM, COL_BG);
      tft.setTextDatum(MR_DATUM);
      tft.setTextFont(6);
      tft.drawString("--", BUS_NUM_R, y0 + BUS_MIN_DY);
      return;
    default: break;
  }

  if (mins <= 1) {
    label_draw(ZH_ARRIVING, BUS_NUM_R, myCy, LBL_RIGHT, COL_ACCENT, COL_BG);
    return;
  }

  const uint16_t c = (mins <= 3) ? COL_FRESH_WARN : COL_TEXT;
  tft.setTextColor(c, COL_BG);
  tft.setTextDatum(MR_DATUM);
  tft.setTextFont(6);
  tft.drawString(String(mins), BUS_NUM_R, y0 + BUS_MIN_DY);
  label_draw(ZH_FEN_ZHONG, BUS_NUM_R, y0 + BUS_UNIT_DY, LBL_RIGHT, COL_DIM, COL_BG);
}

// Remark, right-aligned against the far end of the track. Known remarks get
// their baked Chinese; anything the operators invent later still shows, in
// English, rather than vanishing.
//
// Redrawn on every minute change, not just on a full row repaint. It belongs to
// the ETA being displayed, and when the first bus departs the row rolls over to
// the second -- whose remark may say something completely different. Leaving it
// with the static half would have shown the previous bus's remark against the
// next bus's time.
static void drawBusRemark(int row, int slot) {
  const int y0 = BUS_ROW_Y[row];
  bool sched; uint8_t rmk; const char* rmkEn;
  busMinutes(slot, sched, rmk, rmkEn);

  // Line 3 is 206 px shared between 往, the destination and this. It only
  // balances because the two commonest remarks -- 原定班次 and 未開出, both
  // meaning "timetable, not a live prediction" -- are already said by the
  // hollow badge, so printing them here as well would spend the scarcest space
  // on the row saying the same thing twice. What is left is rare and genuinely
  // new: 尾班車, 已開出, and whatever the operators invent next.
  const int x0 = BUS_TRACK_X1 - 59;
  tft.fillRect(x0, y0 + BUS_DEST_DY - 10, BUS_TRACK_X1 - x0 + 1, 21, COL_BG);
  if (sched) return;

  if (rmk != 0xFF) {
    label_draw((ZhLabel)rmk, BUS_TRACK_X1, y0 + BUS_DEST_DY, LBL_RIGHT, COL_DIM, COL_BG);
  } else if (rmkEn && *rmkEn) {
    tft.setTextColor(COL_DIM, COL_BG);
    tft.setTextDatum(MR_DATUM);
    tft.setTextFont(2);
    tft.drawString(rmkEn, BUS_TRACK_X1, y0 + BUS_DEST_DY);
  }
}

// Everything in a row that does not change once a minute.
static void drawBusRowStatic(int row, int slot) {
  const int y0 = BUS_ROW_Y[row];
  tft.fillRect(0, y0, BUS_NUM_L - 2, BUS_ROW_H, COL_BG);
  if (slot < 0) { tft.fillRect(BUS_NUM_L - 2, y0, SCREEN_W - BUS_NUM_L + 2, BUS_ROW_H, COL_BG); return; }

  const BusStop& b = g_settings.buses[slot];

  // Stop name. The English fallback is not a degraded mode to be embarrassed
  // about -- it is the whole reason a reflash does not look like data loss.
  if (!label_drawUser(slot, UL_STOP, BUS_TRACK_X0, y0 + BUS_NAME_DY,
                      LBL_LEFT, COL_TEXT, COL_BG)) {
    tft.setTextColor(COL_TEXT, COL_BG);
    tft.setTextDatum(ML_DATUM);
    tft.setTextFont(4);
    tft.drawString(b.stopEn.length() ? b.stopEn : b.stopId, BUS_TRACK_X0, y0 + BUS_NAME_DY);
  }

  // Destination, prefixed 往 ("to").
  int x = BUS_TRACK_X0;
  if (b.destTc.length() || b.destEn.length()) {
    label_draw(ZH_TO, x, y0 + BUS_DEST_DY, LBL_LEFT, COL_DIM, COL_BG);
    x += label_width(ZH_TO) + 5;
    if (!label_drawUser(slot, UL_DEST, x, y0 + BUS_DEST_DY, LBL_LEFT, COL_DATE, COL_BG)) {
      tft.setTextColor(COL_DATE, COL_BG);
      tft.setTextDatum(ML_DATUM);
      tft.setTextFont(2);
      tft.drawString(b.destEn, x, y0 + BUS_DEST_DY);
    }
  }

  drawBusRemark(row, slot);
}

// Header: page dots on the left, "更新 47秒前" on the right.
// Fixed cells, so the once-a-second number repaint cannot shuffle the labels
// beside it -- the only thing that moves is the digits.
static const int BUS_AGE_UNIT_R = BUS_NUM_R;
static const int BUS_AGE_NUM_R  = BUS_NUM_R - 34;
static const int BUS_AGE_NUM_L  = BUS_AGE_NUM_R - 30;

static void drawBusAge(bool force) {
  // Oldest of the rows on screen: the honest answer to "how stale is what I am
  // looking at" is the worst of it, not the best.
  int32_t age = -1;
  for (int r = 0; r < 2; r++) {
    if (busRowSlot[r] < 0) continue;
    const int32_t a = bus_ageSeconds(busRowSlot[r]);
    if (a < 0) continue;
    if (a > age) age = a;
  }

  if (age < 0) {
    if (force) tft.fillRect(BUS_AGE_NUM_L - 42, 0, SCREEN_W - BUS_AGE_NUM_L + 42, 18, COL_BG);
    busShownAge = -999;
    return;
  }

  const bool useMin = (age >= 60);
  const int  val    = useMin ? (int)(age / 60) : (int)age;
  if (!force && val == busShownAge && useMin == busShownAgeMin) return;

  if (force || useMin != busShownAgeMin) {
    tft.fillRect(BUS_AGE_NUM_L - 42, 0, SCREEN_W - BUS_AGE_NUM_L + 42, 18, COL_BG);
    label_draw(ZH_UPDATED, BUS_AGE_NUM_L - 5, BUS_HDR_Y, LBL_RIGHT, COL_DIM, COL_BG);
    label_draw(useMin ? ZH_MIN_AGO : ZH_SEC_AGO, BUS_AGE_UNIT_R, BUS_HDR_Y,
               LBL_RIGHT, COL_DIM, COL_BG);
  }
  busShownAgeMin = useMin;
  busShownAge    = val;

  const uint16_t c = ((uint32_t)age >= BUS_AGE_OLD_S)  ? COL_FRESH_OLD
                   : ((uint32_t)age >= BUS_AGE_WARN_S) ? COL_FRESH_WARN
                                                       : COL_DIM;
  tft.fillRect(BUS_AGE_NUM_L, 1, BUS_AGE_NUM_R - BUS_AGE_NUM_L + 1, 16, COL_BG);
  tft.setTextColor(c, COL_BG);
  tft.setTextDatum(MR_DATUM);
  tft.setTextFont(2);
  tft.drawString(String(val), BUS_AGE_NUM_R, BUS_HDR_Y);
}

static void drawBusDots() {
  if (busPages <= 1) return;
  for (int i = 0; i < busPages; i++) {
    const int cx = 10 + i * 13;
    if (i == busPage) tft.fillCircle(cx, BUS_HDR_Y, 4, COL_ACCENT);
    else { tft.fillCircle(cx, BUS_HDR_Y, 4, COL_BG); tft.drawCircle(cx, BUS_HDR_Y, 4, COL_DIM); }
  }
}

// Paint the current page from scratch.
static void drawBusPage() {
  for (int r = 0; r < 2; r++) {
    const int n = busPage * 2 + r;
    busRowSlot[r] = (n < busCount) ? busSlots[n] : -1;
  }
  tft.fillRect(0, 0, SCREEN_W, CONTENT_H, COL_BG);
  drawBusDots();
  for (int r = 0; r < 2; r++) {
    const int slot = busRowSlot[r];
    drawBusRowStatic(r, slot);
    if (slot < 0) { busShownMin[r] = -99; busShownAt[r] = 0; continue; }
    bool sched; uint8_t rmk; const char* rmkEn;
    const int m = busMinutes(slot, sched, rmk, rmkEn);
    drawBusTrack(r, slot, m);
    drawBusMinutes(r, slot, m);
    busShownMin[r] = m;
    busShownAt[r]  = g_data.bus[slot].updatedAt;
  }
  drawBusAge(true);
  // Deliberately does NOT touch busPageAt. This repaints whatever page is
  // current, and it is called whenever a fetch lands -- on the active cadence
  // that is every ten seconds, which would have kept resetting the eight-second
  // page timer and left a three-stop configuration stuck on page one.
}

// Nothing configured. Never blank, and it says how to fix itself -- the same
// reasoning as the clock's settings-address footer.
static void drawBusUnconfigured() {
  tft.fillRect(0, 0, SCREEN_W, CONTENT_H, COL_BG);
  label_draw(ZH_NEXT_BUS, SCREEN_W / 2, 46, LBL_CENTRE, COL_ACCENT, COL_BG);
  label_draw(ZH_NOT_SET,  SCREEN_W / 2, 92, LBL_CENTRE, COL_TEXT,   COL_BG);
  char net[48];
  netFooter(net, sizeof(net));
  tft.setTextColor(COL_DIM, COL_BG);
  tft.setTextDatum(MC_DATUM);
  tft.setTextFont(2);
  tft.drawString("Add bus stops on the settings page", SCREEN_W / 2, 138);
  tft.drawString(net, SCREEN_W / 2, 162);
}

static void busEnter() {
  busCount = 0;
  for (int i = 0; i < BUS_SLOTS; i++)
    if (g_settings.buses[i].valid()) busSlots[busCount++] = i;

  busPages = (busCount + 1) / 2;
  if (busPages < 1) busPages = 1;
  if (busPage >= busPages) busPage = 0;

  busShownSec    = 0;
  busShownAge    = -999;
  busShownAgeMin = false;

  // Pulls every slot's next fetch forward, but never sooner than 500 ms out --
  // so this function never blocks on a TLS handshake and the scene paints on
  // the very next frame.
  bus_markActive();
  bus_requestRefresh();

  if (busCount == 0) { busRowSlot[0] = busRowSlot[1] = -1; drawBusUnconfigured(); return; }
  busPageAt = millis();
  drawBusPage();
}

// Throttled on epoch SECONDS, not minutes -- sunMoonTick's per-minute pattern
// is the right shape but the wrong period for a countdown. 199 of every 200
// iterations return on the first comparison.
//
// time(nullptr) rather than getLocalTime(): the latter carries a 10 ms timeout
// and builds a broken-down local time this scene never looks at.
static void busTick() {
  bus_markActive();                    // keeps the fast fetch cadence alive
  if (busCount == 0) return;

  // A fetch landing is worth a full repaint: the destination, the remark and
  // the badge's live/scheduled state can all have changed, not just the number.
  for (int r = 0; r < 2; r++) {
    const int slot = busRowSlot[r];
    if (slot >= 0 && g_data.bus[slot].updatedAt != busShownAt[r]) { drawBusPage(); return; }
  }

  if (busPages > 1 && millis() - busPageAt >= BUS_PAGE_MS) {
    busPage = (busPage + 1) % busPages;
    busPageAt = millis();
    drawBusPage();
    return;
  }

  const uint32_t now = (uint32_t)time(nullptr);
  if (now == busShownSec) return;
  busShownSec = now;

  drawBusAge(false);

  // The badge and the number repaint only when the integer minute changes --
  // once a minute per row, not once a second. So the badge slides in
  // one-minute steps, which is honest about the data's precision and avoids
  // redrawing the whole track at 1 Hz for a movement nobody could see.
  for (int r = 0; r < 2; r++) {
    const int slot = busRowSlot[r];
    if (slot < 0) continue;
    bool sched; uint8_t rmk; const char* rmkEn;
    const int m = busMinutes(slot, sched, rmk, rmkEn);
    if (m == busShownMin[r]) continue;
    busShownMin[r] = m;
    drawBusTrack(r, slot, m);
    drawBusMinutes(r, slot, m);
    drawBusRemark(r, slot);
  }
}

// ===========================================================================
// Scene table + manager
// ===========================================================================
// Bus goes LAST, after Air Quality. Inserting it earlier would rewrite the
// tap-count muscle memory of three scenes that have not changed in months.
static Scene scenes[] = {
  { "Clock",       35000, clockEnter,   clockTick, clockExit },
  { "Weather",     12000, weatherEnter, weatherTick, nullptr },
  { "Sun & Moon",  12000, sunMoonEnter, sunMoonTick, nullptr },
  { "Air Quality", 12000, airQualEnter, airQualTick, nullptr },
  { "Next Bus",    12000, busEnter,     busTick,    nullptr, 60000 },
};
static const int SCENE_COUNT = sizeof(scenes) / sizeof(scenes[0]);

static int      curIdx      = 0;
static uint32_t enterMs     = 0;
static bool     pinned      = false;
static uint32_t freezeUntil = 0;   // millis(); rotation suspended until then

// Instant swap, no animation -- an SPI slide at this size looks janky (plan §6).
static void goToScene(int idx) {
  Scene& cur = scenes[curIdx];
  if (cur.onExit) cur.onExit();
  curIdx = idx;
  scenes[curIdx].onEnter();
  enterMs = millis();
}

void sceneManager_begin() {
  curIdx = 0;
  pinned = false;
  freezeUntil = 0;
  scenes[curIdx].onEnter();
  enterMs = millis();
}

void sceneManager_handleTouch(TouchEvent ev) {
  switch (ev) {
    case TOUCH_TAP: {
      // Advance immediately, then hold still for a while. Without the freeze a
      // tap made two seconds before a dwell expires would show the next scene
      // for two seconds and then move on again, which reads as a glitch.
      //
      // The DESTINATION's freeze, not the current scene's -- which is why `next`
      // is computed first. This used to set freezeUntil before goToScene, which
      // was only ever safe because the value was one global constant.
      const int next = (curIdx + 1) % SCENE_COUNT;
      const uint32_t freeze = scenes[next].freezeMs ? scenes[next].freezeMs
                                                    : SCENE_FREEZE_MS;
      freezeUntil = millis() + freeze;
      goToScene(next);
      Serial.printf("scene: tap -> %s (rotation frozen %lus)\n",
                    scenes[curIdx].name, freeze / 1000);
      break;
    }

    case TOUCH_LONG_PRESS:
      pinned = !pinned;
      // Unpinning restarts the dwell rather than resuming a timer that may
      // have expired minutes ago -- otherwise the scene would vanish the
      // instant you let go.
      if (!pinned) enterMs = millis();
      freezeUntil = 0;
      Serial.printf("scene: %s %s\n", pinned ? "pinned" : "unpinned", scenes[curIdx].name);
      break;

    default:
      break;   // TOUCH_NONE / TOUCH_RECALIBRATE are not ours
  }
}

void sceneManager_tick() {
  Scene& s = scenes[curIdx];
  if (s.onTick) s.onTick();

  if (pinned) return;
  if ((int32_t)(millis() - freezeUntil) < 0) return;

  if (millis() - enterMs >= s.dwellMs) goToScene((curIdx + 1) % SCENE_COUNT);
}

int  sceneManager_index()  { return curIdx; }
int  sceneManager_count()  { return SCENE_COUNT; }
bool sceneManager_isPinned() { return pinned; }
bool sceneManager_isFrozen() {
  return !pinned && (int32_t)(millis() - freezeUntil) < 0;
}
