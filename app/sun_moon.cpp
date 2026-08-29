#include "sun_moon.h"
#include "settings.h"
#include "app_data.h"
#include <Arduino.h>
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static double deg2rad(double d) { return d * M_PI / 180.0; }
static double rad2deg(double r) { return r * 180.0 / M_PI; }

// Julian Day at 0h UT for a calendar date (Gregorian).
static double julianDay(int y, int m, int d) {
  if (m <= 2) { y -= 1; m += 12; }
  int A = y / 100;
  int B = 2 - A + A / 4;
  return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + B - 1524.5;
}

// NOAA single-pass solar position -> UTC epoch of the sun at `elevDeg`.
time_t sunEventUTC(int y, int m, int d, double elevDeg, bool morning) {
  double JD = julianDay(y, m, d);
  double T  = (JD - 2451545.0) / 36525.0;

  double L0 = fmod(280.46646 + T * (36000.76983 + 0.0003032 * T), 360.0);
  double M  = 357.52911 + T * (35999.05029 - 0.0001537 * T);
  double e  = 0.016708634 - T * (0.000042037 + 0.0000001267 * T);
  double Mr = deg2rad(M);
  double C  = sin(Mr)     * (1.914602 - T * (0.004817 + 0.000014 * T))
            + sin(2 * Mr) * (0.019993 - 0.000101 * T)
            + sin(3 * Mr) *  0.000289;
  double trueLong = L0 + C;
  double omega  = 125.04 - 1934.136 * T;
  double lambda = trueLong - 0.00569 - 0.00478 * sin(deg2rad(omega));
  double eps0   = 23.0 + (26.0 + (21.448 - T * (46.815 + T * (0.00059 - T * 0.001813))) / 60.0) / 60.0;
  double eps    = eps0 + 0.00256 * cos(deg2rad(omega));
  double delta  = rad2deg(asin(sin(deg2rad(eps)) * sin(deg2rad(lambda))));

  double yv = tan(deg2rad(eps / 2.0)); yv *= yv;
  double eqTime = 4.0 * rad2deg(
        yv * sin(2 * deg2rad(L0))
      - 2 * e * sin(Mr)
      + 4 * e * yv * sin(Mr) * cos(2 * deg2rad(L0))
      - 0.5 * yv * yv * sin(4 * deg2rad(L0))
      - 1.25 * e * e * sin(2 * Mr));

  const double lat = g_settings.latitude;
  const double lon = g_settings.longitude;

  double zenith = 90.0 - elevDeg;
  double cosH = (cos(deg2rad(zenith)) - sin(deg2rad(lat)) * sin(deg2rad(delta)))
              / (cos(deg2rad(lat)) * cos(deg2rad(delta)));
  if (cosH > 1.0 || cosH < -1.0) return 0;    // sun never reaches this elevation

  double H = rad2deg(acos(cosH));
  double minutesUTC = 720.0 - 4.0 * (lon + (morning ? H : -H)) - eqTime;
  double midnightUTCepoch = (JD - 2440587.5) * 86400.0;
  return (time_t)llround(midnightUTCepoch + minutesUTC * 60.0);
}

MoonPhaseName moonPhaseName(float p) {
  if (p < 0.03f || p > 0.97f) return MOON_NEW;
  if (p < 0.22f)              return MOON_WAX_CRE;
  if (p < 0.28f)              return MOON_FIRST_Q;
  if (p < 0.47f)              return MOON_WAX_GIB;
  if (p < 0.53f)              return MOON_FULL;
  if (p < 0.72f)              return MOON_WAN_GIB;
  if (p < 0.78f)              return MOON_LAST_Q;
  return MOON_WAN_CRE;
}

const char* moonPhaseNameEn(MoonPhaseName p) {
  static const char* const NAMES[MOON_PHASE_COUNT] = {
    "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
    "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent",
  };
  return (p < MOON_PHASE_COUNT) ? NAMES[p] : "";
}

// Given a local date's tm, return the next day's date via mktime normalisation.
static void nextLocalDate(const struct tm& today, int& y, int& mo, int& d) {
  struct tm t = today;
  t.tm_mday += 1;
  t.tm_hour = 12; t.tm_min = 0; t.tm_sec = 0;   // noon avoids DST edge issues
  time_t tt = mktime(&t);
  struct tm out;
  localtime_r(&tt, &out);
  y = out.tm_year + 1900; mo = out.tm_mon + 1; d = out.tm_mday;
}

GoldenHour goldenHourStatus() {
  struct tm t;
  if (!getLocalTime(&t, 10)) return { GH_NONE, 0, 0 };
  int y = t.tm_year + 1900, mo = t.tm_mon + 1, d = t.tm_mday;
  time_t now = time(nullptr);

  // Golden hour: sun elevation between -4 deg and +6 deg (plan §8).
  time_t mStart = sunEventUTC(y, mo, d, -4.0, true);   // morning window start
  time_t mEnd   = sunEventUTC(y, mo, d,  6.0, true);   // morning window end
  time_t eStart = sunEventUTC(y, mo, d,  6.0, false);  // evening window start
  time_t eEnd   = sunEventUTC(y, mo, d, -4.0, false);  // evening window end

  if (mStart && now >= mStart && now <= mEnd) return { GH_NOW, 0, (int)((mEnd - now) / 60) };
  if (eStart && now >= eStart && now <= eEnd) return { GH_NOW, 0, (int)((eEnd - now) / 60) };

  time_t next = 0;
  if (mStart && mStart > now)      next = mStart;
  else if (eStart && eStart > now) next = eStart;
  else {
    int ny, nmo, nd; nextLocalDate(t, ny, nmo, nd);
    next = sunEventUTC(ny, nmo, nd, -4.0, true);
  }
  if (!next) return { GH_NONE, 0, 0 };

  const long secs = next - now;
  return { GH_IN, (int)(secs / 3600), (int)((secs % 3600) / 60) };
}

void sunmoon_recompute() {
  struct tm t;
  if (!getLocalTime(&t, 10)) return;   // need a valid date first
  int y = t.tm_year + 1900, mo = t.tm_mon + 1, d = t.tm_mday;

  g_data.sunriseToday = sunEventUTC(y, mo, d, -0.833, true);
  g_data.sunsetToday  = sunEventUTC(y, mo, d, -0.833, false);

  int ny, nmo, nd; nextLocalDate(t, ny, nmo, nd);
  g_data.sunriseTomorrow = sunEventUTC(ny, nmo, nd, -0.833, true);
  g_data.sunsetTomorrow  = sunEventUTC(ny, nmo, nd, -0.833, false);

  time_t now = time(nullptr);
  g_data.showingNextDay = (now > g_data.sunsetToday);   // roll forward at sunset

  // Moon phase from synodic month since a known new moon (JD 2451550.1).
  double JDnow = 2440587.5 + (double)now / 86400.0;
  double phase = (JDnow - 2451550.1) / 29.530588853;
  phase -= floor(phase);
  g_data.moonPhase = (float)phase;
  g_data.moonIlluminationPct = (float)((1.0 - cos(2 * M_PI * phase)) / 2.0 * 100.0);

  // Print once per boot for offline almanac verification (plan §8).
  static bool printed = false;
  if (!printed) {
    printed = true;
    char a[32], b[32], c[32], e[32];
    strftime(a, sizeof(a), "%Y-%m-%d %H:%M", localtime(&g_data.sunriseToday));
    strftime(b, sizeof(b), "%H:%M",          localtime(&g_data.sunsetToday));
    strftime(c, sizeof(c), "%m-%d %H:%M",    localtime(&g_data.sunriseTomorrow));
    strftime(e, sizeof(e), "%H:%M",          localtime(&g_data.sunsetTomorrow));
    Serial.println("=== sun/moon (verify vs almanac for your coords) ===");
    Serial.printf("today   sunrise %s  sunset %s\n", a, b);
    Serial.printf("tomorrow sunrise %s  sunset %s\n", c, e);
    Serial.printf("moon phase %.3f (%s)  illumination %.0f%%\n",
                  g_data.moonPhase, moonPhaseNameEn(moonPhaseName(g_data.moonPhase)),
                  g_data.moonIlluminationPct);
  }
}
