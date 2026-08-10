// ===========================================================================
// Units.kt -- display conversions. Port of app/units.h
// ===========================================================================
// The invariant from units.h, kept: AppData always stores canonical metric
// values as fetched, and conversion happens at draw time only. That is what
// makes a units change apply with no refetch and no restart.
package ca.garionhk.cydclock.core

import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.data.UNITS_IMPERIAL

fun AppSettings.useImperial(): Boolean = units == UNITS_IMPERIAL

fun AppSettings.dispTemp(c: Float): Float = if (useImperial()) c * 9.0f / 5.0f + 32.0f else c
fun AppSettings.dispWind(kph: Float): Float = if (useImperial()) kph * 0.621371f else kph
fun AppSettings.dispPress(hpa: Float): Float = if (useImperial()) hpa * 0.0295300f else hpa

fun AppSettings.tempUnit(): String = if (useImperial()) "F" else "C"
fun AppSettings.windUnit(): String = if (useImperial()) "mph" else "km/h"
fun AppSettings.pressUnit(): String = if (useImperial()) "inHg" else "hPa"
