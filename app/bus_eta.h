// ===========================================================================
// bus_eta.h -- bus ETA fetch scheduler
// ===========================================================================
// Fetches the bus ETA API into g_data every 60 s, with the same backoff and
// last-good semantics as weather/airquality.
#pragma once

void busEta_begin();
void busEta_tick();
