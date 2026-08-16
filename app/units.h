// ===========================================================================
// units.h -- display-unit conversion (plan §10)
// ===========================================================================
// AppData stores canonical METRIC values (as fetched). The display layer calls
// these to present metric or imperial per the user's setting. Keeps the data
// model unit-agnostic.
//
// These used to fold to a constant at compile time from config.h's UNITS. The
// setting is now a runtime value in NVS, so the branch is real -- and entirely
// irrelevant next to the SPI writes each call feeds.
#pragma once

#include "settings.h"

static inline bool useImperial() { return g_settings.units == UNITS_IMPERIAL; }

static inline float dispTemp(float c) {
  return useImperial() ? (c * 9.0f / 5.0f + 32.0f) : c;
}
static inline float dispWind(float kph) {
  return useImperial() ? (kph * 0.621371f) : kph;
}
static inline float dispPress(float hpa) {
  return useImperial() ? (hpa * 0.0295300f) : hpa;
}

static inline const char* tempUnit()  { return useImperial() ? "F"    : "C"; }
static inline const char* windUnit()  { return useImperial() ? "mph"  : "km/h"; }
static inline const char* pressUnit() { return useImperial() ? "inHg" : "hPa"; }
