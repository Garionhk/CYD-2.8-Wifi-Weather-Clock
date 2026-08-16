// ===========================================================================
// theme.h -- shared display handle, layout geometry, and colour palette
// ===========================================================================
// Landscape 320x240. The rotation itself comes from board.h (rotation 1 on the
// 2.8" board, 3 on the 2.4" -- they are mounted opposite ways up), so nothing
// here assumes which board is being built for.
//
// The screen splits into a content area, redrawn on every scene change, and a
// persistent status strip along the bottom (plan §7).
#pragma once

#include <TFT_eSPI.h>
#include "board.h"

// The one display instance, defined in app.ino. Every module shares it.
extern TFT_eSPI tft;

// ---- Screen geometry ------------------------------------------------------
static const int SCREEN_W   = CYD_SCREEN_W;
static const int SCREEN_H   = CYD_SCREEN_H;

static const int STATUS_H   = 44;                       // status strip height
static const int STATUS_Y   = SCREEN_H - STATUS_H;      // = 196
static const int CONTENT_H  = STATUS_Y;                 // content area height
static const int STATUS_CY  = STATUS_Y + STATUS_H / 2;  // strip vertical centre

// ---- Scene 1 clock layout -------------------------------------------------
// Font 8 (75 px 7-seg) digits, one reusable sprite per digit cell.
static const int DIGIT_W     = 60;
static const int DIGIT_H     = 82;
static const int DIGIT_TOP_Y = 28;
// x of each of the four HH:MM digit cells; colon sits in the gap between them.
static const int DIGIT_X[4]  = { 28, 88, 172, 232 };
static const int COLON_X     = 160;
static const int DATE_Y      = 150;                     // weekday/date line centre
// Settings-page address, Font 2 (16 px) so it spans 172..188 -- clear of the
// date band above (134..166) and the content edge at 196.
static const int NET_Y       = 180;

// ---- Colours (RGB565) -----------------------------------------------------
static const uint16_t COL_BG         = 0x0000;    // black
static const uint16_t COL_TEXT       = 0xFFFF;    // white
static const uint16_t COL_TIME       = 0xFFFF;    // clock digits
static const uint16_t COL_DATE       = 0xC618;    // light grey
static const uint16_t COL_DIM        = 0x8410;    // mid grey
static const uint16_t COL_ACCENT     = 0x07FF;    // cyan
static const uint16_t COL_STRIP_BG   = 0x2104;    // dark grey strip

// Freshness dot bands (plan §7)
static const uint16_t COL_FRESH_OK   = 0x07E0;    // green  (< 30 min)
static const uint16_t COL_FRESH_WARN = 0xFEA0;    // amber  (< 2 h)
static const uint16_t COL_FRESH_OLD  = 0xF800;    // red    (older)
static const uint16_t COL_FRESH_NONE = 0x52AA;    // grey   (no data yet)

// Touch-state indicators in the status strip (plan §6: the state must be
// visible, or a pinned display just looks like a broken one).
static const uint16_t COL_PIN        = 0xFD20;    // orange -- scene pinned
static const uint16_t COL_FREEZE     = 0x07FF;    // cyan   -- rotation paused
