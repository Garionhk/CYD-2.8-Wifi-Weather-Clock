// ===========================================================================
// CMath.kt -- arithmetic that has to behave like C, not like Kotlin
// ===========================================================================
// Three traps live here, all of which shift pixels rather than crash:
//
//  1. Rounding. kotlin.math.round is rint (ties-to-even) and Math.round is
//     floor(x+0.5). C's lround rounds ties AWAY from zero. The firmware uses
//     lroundf for the sun arc, the sun marker, the moon terminator and every
//     temperature, so [lround] is what those must call.
//
//  2. Truncation. C truncates on an implicit float->int conversion; it does not
//     round. iconCloud passes s*0.7f and s*0.8f into int parameters, so for
//     s=16 the panel draws radii 11 and 12 -- roundToInt() would give 11 and 13
//     and a visibly different cloud. Use [trunc], never roundToInt, at those
//     sites.
//
//  3. Float vs double. The firmware uses cosf/sinf on float. Icon and arc trig
//     therefore works in Float here so the truncation boundaries land in the
//     same places.
package ca.garionhk.cydclock.core

import kotlin.math.ceil
import kotlin.math.floor

/** C's lround: ties away from zero. */
fun lround(v: Double): Int = if (v >= 0) floor(v + 0.5).toInt() else ceil(v - 0.5).toInt()

/** C's lroundf. */
fun lround(v: Float): Int = lround(v.toDouble())

/** C's implicit float->int conversion: truncate toward zero. */
fun trunc(v: Float): Int = v.toInt()

/** C's implicit double->int conversion. */
fun trunc(v: Double): Int = v.toInt()
