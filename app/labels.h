// ===========================================================================
// labels.h -- 1-bit Chinese text, from two sources (bus scene, plan §1)
// ===========================================================================
// TFT_eSPI has no CJK font and there is no affordable way to embed one: see
// tools/gen_zh_labels.py for the sizing that killed that option, and
// docs/decisions.md for the short version. Chinese therefore arrives as
// pre-rasterised 1-bpp bitmaps from two places:
//
//   * the FIXED vocabulary -- 分鐘, 即將到站, 無服務 and friends -- baked at
//     build time into zh_labels.cpp. The firmware knows these strings, so it
//     can carry them.
//
//   * the USER's labels -- stop names and destinations, which the firmware
//     cannot know in advance -- baked by the BROWSER at config time and
//     written to LittleFS by webconfig's /label endpoint.
//
// Both use one blob layout: [0]=w, [1]=h, then packed 1-bpp rows, MSB first,
// each row padded to a whole byte. That is exactly what TFT_eSPI::drawBitmap
// accepts, so there is no custom blitter, and its fg/bg overload paints the
// background as it goes -- which is what makes the compare-and-redraw partial
// repaint used everywhere else in this project work for Chinese too.
//
// A 4-bpp alpha version of this shipped briefly and was withdrawn. It rendered
// in the wrong colours on the panel -- pushImage sends a uint16_t array as raw
// little-endian bytes and the ILI9341 wants each pixel high byte first, so it
// needed setSwapBytes(true) that it did not have. The deeper reason not to
// bring it back is that Chinese would have been the only thing on the display
// using a hand-written blit path: the Latin is antialiased by TFT_eSPI itself
// (see ui_fonts.h), which costs us no draw code at all.
//
// Why LittleFS and not NVS for the user labels: huge_app leaves an 896 KB
// filesystem partition completely unused, while NVS is 20 KB and already holds
// settings and touch calibration. ~1.3 KB per slot is uncomfortable in one and
// nothing in the other.
//
// The consequence, and it matters: the bitmap can be missing while the text is
// not. NVS holds both name_tc and name_en; LittleFS holds only the picture. A
// stop added through the settings page's "Manual entry", or saved while the
// browser had no internet, or whose bake failed, has text and no bitmap. So
// label_drawUser() returns false rather than drawing nothing, and the scene
// renders name_en in the built-in ASCII font. Useful, not blank.
//
// (Flashing app.ino.merged.bin at 0x0 erases this partition -- but it erases
// NVS too, since the 4 MB image pads both with 0xFF, so that case is a factory
// reset rather than a fallback. tools/flash.sh app preserves both. See
// flash.md, which spells out which command to use for which purpose.)
#pragma once

#include <Arduino.h>
#include "zh_labels.h"

// Horizontal anchoring. `y` is always the vertical CENTRE of the label -- every
// use in the bus scene is centred on a row line, and offering nine datums to
// serve one would just be more to get wrong.
enum LabelAlign : uint8_t { LBL_LEFT, LBL_CENTRE, LBL_RIGHT };

// Mounts LittleFS, formatting it if it has never been used (which is the case
// on any device flashed before this firmware existed). Safe to call once at
// boot; everything else here degrades quietly if it fails.
void label_begin();
bool label_fsReady();

// ---- fixed vocabulary -----------------------------------------------------
int  label_width (ZhLabel id);
int  label_height(ZhLabel id);
void label_draw  (ZhLabel id, int x, int y, LabelAlign align, uint16_t fg, uint16_t bg);

// ---- user labels ----------------------------------------------------------
// Two per bus slot: the stop name (baked ~28 px) and the destination (~18 px).
enum UserLabel : uint8_t { UL_STOP = 0, UL_DEST = 1 };

// True when a bitmap exists for this slot -- i.e. the browser has baked it and
// no reflash has wiped it since.
bool label_hasUser(int slot, UserLabel which);

// Width in pixels, or 0 when there is no bitmap.
int  label_userWidth(int slot, UserLabel which);

// Draw it. Returns false when there is no bitmap, leaving the pixels untouched
// so the caller can fall back to the English name in Font 2.
bool label_drawUser(int slot, UserLabel which, int x, int y,
                    LabelAlign align, uint16_t fg, uint16_t bg);

// Store a freshly baked bitmap (called by webconfig's /label handler).
// `data` is a whole blob, header included. Rejects anything malformed or wider
// than the row can draw, so a bad upload cannot wedge the scene.
bool label_saveUser(int slot, UserLabel which, const uint8_t* data, size_t len);
void label_clearUser(int slot);

// Paint every baked label on the panel and hold for `holdMs`. Chinese
// rendering is the one part of this feature that cannot be verified by
// assertion -- a threshold set too high drops interior strokes out of dense
// glyphs (灣, 觀) and the only instrument for that is a pair of eyes. Enabled by
// #define BUS_LABEL_SELFTEST in app.ino.
void label_selfTest(uint32_t holdMs);

// The widest bitmap the device will accept or draw. The stop-name field is
// 216 px, so anything past this could not be rendered anyway, and the cap is
// what lets the read path use one static buffer instead of the heap -- which
// matters here, because clockEnter wants a 10 KB *contiguous* sprite and this
// scene must not be the thing that fragments it away.
static const int LABEL_MAX_W = 232;
static const int LABEL_MAX_H = 34;
