// ===========================================================================
// sun_moon.h -- local sun/moon math (plan §8). No API: stays correct offline.
// ===========================================================================
// NOAA solar position for sunrise/sunset (and twilight/golden-hour elevations),
// moon phase from the synodic month. Roll-forward is triggered by SUNSET, not
// midnight: after today's sunset we show tomorrow's times (showingNextDay).
#pragma once

#include <time.h>
#include <stddef.h>

// Recompute today's + tomorrow's sun times, the showingNextDay flag, and the
// moon phase/illumination into g_data. Cheap; safe to call on scene enter.
// Prints the results to Serial once per boot for offline almanac verification.
void sunmoon_recompute();

// Which of the eight phases a value in [0,1] falls in (0/1 = new, 0.5 = full).
//
// An enum rather than a string, and a struct below rather than a formatted
// line, because both of these are read out in whichever language the panel is
// set to. Keeping the wording out of here is what lets this file stay pure
// almanac maths with no dependency on the display at all -- scenes.cpp maps
// these onto UiText ids, and nothing else needs to know they have names.
enum MoonPhaseName : uint8_t {
  MOON_NEW, MOON_WAX_CRE, MOON_FIRST_Q, MOON_WAX_GIB,
  MOON_FULL, MOON_WAN_GIB, MOON_LAST_Q, MOON_WAN_CRE,
  MOON_PHASE_COUNT
};
MoonPhaseName moonPhaseName(float phase);

// English name for one, for the boot-time almanac trace on serial. Never drawn.
const char* moonPhaseNameEn(MoonPhaseName p);

// Where the next golden hour is, as numbers. GH_NOW carries how many minutes
// are left in the window; GH_IN carries how long until the next one starts;
// GH_NONE means the sun never reaches the elevation today, or the clock has not
// synced -- both of which the scene draws as "--".
enum GoldenKind : uint8_t { GH_NONE, GH_NOW, GH_IN };
struct GoldenHour {
  GoldenKind kind;
  int hours;      // GH_IN only
  int minutes;    // GH_NOW: left in the window.  GH_IN: minutes past the hour
};
GoldenHour goldenHourStatus();

// UTC epoch of the sun reaching `elevDeg` on the given local date; morning=true
// for the rising crossing, false for setting. 0 if it never reaches it (polar).
// Exposed for the scene's arc/marker use.
time_t sunEventUTC(int year, int mon, int day, double elevDeg, bool morning);
