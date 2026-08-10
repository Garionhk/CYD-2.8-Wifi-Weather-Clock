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

// ---- Bus scene layout -----------------------------------------------------
// Two rows, ~86 px each, over a 16 px header. Two and not four: the whole point
// of this scene is being readable from the sofa, and four rows of 44 px is a
// phone app on a wall. Three configured stops page between two screens.
//
//            y=0..16    dots                        更新 47秒前
//            y=20..106  ROW 0
//            y=108..194 ROW 1
//
// Within a row, measured down from its top:
//
//   +15  荃灣站                                   |            <- stop, 28 px baked
//   +45  |==[68X]======================          |    7       <- track, badge slides
//   +72  往 旺角(柏景灣)          未開出           |  分鐘
//        |<---- track 8..214 ---->|               |<- 228..314 ->|
//
// The right column is 88 px because that is what the widest baked state label
// (即將到站, 78 px) needs. Everything else was fitted around that one string.
static const int BUS_HDR_Y      = 9;                 // header text centre
static const int BUS_ROW_Y[2]   = { 20, 108 };       // row tops
static const int BUS_ROW_H      = 86;

static const int BUS_NAME_DY    = 15;                // stop name centre
static const int BUS_TRACK_DY   = 45;                // rail + badge centre
static const int BUS_DEST_DY    = 72;                // destination / remark centre
static const int BUS_MIN_DY     = 30;                // big minutes centre
static const int BUS_UNIT_DY    = 68;                // 分鐘 centre

static const int BUS_TRACK_X0   = 8;                 // stop marker end of the rail
static const int BUS_TRACK_X1   = 214;               // far end
static const int BUS_NUM_L      = 226;               // right column left edge
static const int BUS_NUM_R      = 314;               // ...and its right edge

// The badge is a position, not just a label: it slides right-to-left along the
// rail as the vehicle approaches. Font 4 rather than the smaller Font 2 -- the
// route number is the most identifying thing on the row, and shrinking it to
// fit a tidier badge would undo the reason this scene has two big rows.
static const int BUS_BADGE_W    = 52;
static const int BUS_BADGE_H    = 28;
// How far out the rail still resolves. Past this the badge parks at the far end,
// dimmed: the difference between 40 and 50 minutes is not worth a pixel.
static const int BUS_TRACK_MAX_MIN = 30;

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

// Operator liveries. These are recognition, not decoration: in Hong Kong the
// colour tells you which company's bus is coming before you have read the
// number, so getting them right is the difference between a glance and a read.
// KMB dropped champagne gold for red/silver in 2017, which is exactly why gold
// still reads as Long Win to anyone who lives there.
static const uint16_t COL_OP_KMB     = 0xE103;    // (226, 65, 24)  red
static const uint16_t COL_OP_CTB     = 0xFE80;    // (255,210,  0)  yellow
static const uint16_t COL_OP_GMB     = 0x052A;    // (  0,166, 82)  green
static const uint16_t COL_OP_LWB     = 0xCD04;    // (206,162, 33)  champagne gold
