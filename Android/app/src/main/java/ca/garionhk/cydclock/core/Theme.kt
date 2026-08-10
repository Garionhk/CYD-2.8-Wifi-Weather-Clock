// ===========================================================================
// Theme.kt -- layout geometry and colour palette. Port of app/theme.h
// ===========================================================================
// The colours are kept as their original RGB565 literals rather than as hex
// ARGB, so this file diffs line-for-line against theme.h. [rgb565] expands them
// once, by bit replication, which is what reproduces the panel's actual output
// (0x2104 -> #212021, not #202020).
//
// Geometry is the virtual 320x240 landscape canvas. Nothing here knows about
// the real display -- DeviceScreen scales and letterboxes.
package ca.garionhk.cydclock.core

/**
 * RGB565 -> opaque ARGB8888. Bit replication (`r shl 3 or r ushr 2`) rather
 * than a plain shift, so 5-bit 0x1F maps to 0xFF and not 0xF8.
 */
fun rgb565(v: Int): Int {
    val r = (v ushr 11) and 0x1F
    val g = (v ushr 5) and 0x3F
    val b = v and 0x1F
    return (0xFF shl 24) or
        (((r shl 3) or (r ushr 2)) shl 16) or
        (((g shl 2) or (g ushr 4)) shl 8) or
        ((b shl 3) or (b ushr 2))
}

object Theme {
    // ---- Screen geometry --------------------------------------------------
    const val SCREEN_W = 320
    const val SCREEN_H = 240

    const val STATUS_H = 44                          // status strip height
    const val STATUS_Y = SCREEN_H - STATUS_H         // = 196
    const val CONTENT_H = STATUS_Y                   // content area height
    const val STATUS_CY = STATUS_Y + STATUS_H / 2    // strip vertical centre = 218

    // ---- Clock scene layout ----------------------------------------------
    const val DIGIT_W = 60
    const val DIGIT_H = 82
    const val DIGIT_TOP_Y = 28
    val DIGIT_X = intArrayOf(28, 88, 172, 232)
    const val COLON_X = 160
    const val DATE_Y = 150
    const val NET_Y = 180

    // ---- Colours ----------------------------------------------------------
    val COL_BG = rgb565(0x0000)          // black
    val COL_TEXT = rgb565(0xFFFF)        // white
    val COL_TIME = rgb565(0xFFFF)        // clock digits
    val COL_DATE = rgb565(0xC618)        // light grey
    val COL_DIM = rgb565(0x8410)         // mid grey
    val COL_ACCENT = rgb565(0x07FF)      // cyan
    val COL_STRIP_BG = rgb565(0x2104)    // dark grey strip

    // Freshness dot bands
    val COL_FRESH_OK = rgb565(0x07E0)    // green  (< 30 min)
    val COL_FRESH_WARN = rgb565(0xFEA0)  // amber  (< 2 h)
    val COL_FRESH_OLD = rgb565(0xF800)   // red    (older)
    val COL_FRESH_NONE = rgb565(0x52AA)  // grey   (no data yet)

    // Touch-state indicators in the status strip: the state must be visible, or
    // a pinned display just looks like a broken one.
    val COL_PIN = rgb565(0xFD20)         // orange -- scene pinned
    val COL_FREEZE = rgb565(0x07FF)      // cyan   -- rotation paused

    // ---- Scene-local palette (scenes.cpp:203-208) -------------------------
    val C_SUN = rgb565(0xFFE0)           // yellow
    val C_CLOUD = rgb565(0xC618)         // light grey
    val C_RAIN = rgb565(0x5D9F)          // blue
    val C_FOG = rgb565(0x9CD3)           // pale grey
    val C_HI = rgb565(0xFD20)            // amber -- daily high
    val C_LO = rgb565(0x05FF)            // blue  -- daily low

    // AQI / UV band colours
    val C_GREEN = rgb565(0x07E0)
    val C_YELLOW = rgb565(0xFFE0)
    val C_ORANGE = rgb565(0xFD20)
    val C_RED = rgb565(0xF800)
    val C_PURPLE = rgb565(0x8010)
    val C_MAROON = rgb565(0x7800)

    // Moon disk
    val C_MOON_LIT = rgb565(0xE71C)
    val C_MOON_SHADOW = rgb565(0x2965)
}
