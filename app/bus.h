// ===========================================================================
// bus.h -- Hong Kong bus / minibus arrivals (bus scene, plan §3)
// ===========================================================================
// Three operators, three different API models. See settings.h for the identity
// fields and bus.cpp for the per-operator request and parse.
//
// Everything here writes absolute epoch times into g_data.bus[]. Nothing
// computes "minutes from now" -- that is the scene's job, once per second, so
// the countdown stays correct no matter how old the fetch is.
#pragma once

#include <Arduino.h>

void bus_begin();
void bus_tick();          // call every loop; fetches at most one slot per call

// Called by the scene each tick it is on screen. Switches the fetch cadence
// from idle (300 s per slot) to active (30 s per slot) and keeps it there for
// 30 s after the scene leaves, so tapping back and forth doesn't re-stall.
void bus_markActive();

// Pull every slot's next attempt forward, staggered. Called on scene entry.
// Never schedules anything sooner than half a second out, so the scene paints
// instantly on tap rather than behind a 2 s TLS handshake.
void bus_requestRefresh();

// Seconds since this slot last fetched successfully, or -1 if it never has.
int32_t bus_ageSeconds(int slot);
