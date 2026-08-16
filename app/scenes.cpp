#include "scenes.h"
#include "theme.h"
#include "time_manager.h"
#include "app_data.h"
#include "units.h"
#include "sun_moon.h"
#include "webconfig.h"
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
// Scene table + manager
// ===========================================================================
static Scene scenes[] = {
  { "Clock",       35000, clockEnter,   clockTick, clockExit },
  { "Weather",     12000, weatherEnter, weatherTick, nullptr },
  { "Sun & Moon",  12000, sunMoonEnter, sunMoonTick, nullptr },
  { "Air Quality", 12000, airQualEnter, airQualTick, nullptr },
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
    case TOUCH_TAP:
      // Advance immediately, then hold still for a while. Without the freeze a
      // tap made two seconds before a dwell expires would show the next scene
      // for two seconds and then move on again, which reads as a glitch.
      freezeUntil = millis() + SCENE_FREEZE_MS;
      goToScene((curIdx + 1) % SCENE_COUNT);
      Serial.printf("scene: tap -> %s (rotation frozen %lus)\n",
                    scenes[curIdx].name, SCENE_FREEZE_MS / 1000);
      break;

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
