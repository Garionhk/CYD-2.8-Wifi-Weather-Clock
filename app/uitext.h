// ===========================================================================
// uitext.h -- the text output layer: which font is loaded, and who loaded it
// ===========================================================================
// TFT_eSPI can hold exactly ONE smooth font at a time. gFont and the seven
// g* metric arrays are single members of the display object, so loadFont() on
// an already-loaded font quietly unloads the old one first -- and each load
// mallocs those seven arrays afresh (see loadMetrics in Smooth_font.cpp).
//
// That matters more here than it would elsewhere. This project draws a scene by
// switching size several times per frame, and labels.cpp is written entirely
// around the rule that small repeated malloc/free is what leaves getMaxAllocHeap
// looking fine while the clock's 10 KB contiguous sprite fails to allocate hours
// later. Forty font swaps a frame is that same pattern, only faster.
//
// So all font selection goes through font_use(), which is a no-op when the
// requested font is already the loaded one. Callers may then switch freely and
// in any order: correctness never depends on grouping draws by size, but
// grouping them costs nothing, and a frame that draws all its small text
// together pays for one load rather than six.
//
// The clock's digit sprite is the exception, and has to be: TFT_eSprite derives
// from TFT_eSPI, so it carries its OWN gFont and its own metrics, loaded once
// where the sprite is created (scenes.cpp) and never given back.
#pragma once

#include <Arduino.h>
#include "ui_fonts.h"
#include "labels.h"   // ZhLabel, LabelAlign

// Select a font for subsequent tft.drawString/textWidth/fontHeight calls. The
// first call for a given font loads it; the rest are free. Cheap and
// idempotent -- calling it before every draw is the intended style.
void font_use(UiFont f);

// Which font font_use() last selected, for code that has to put it back.
UiFont font_current();

// ===========================================================================
// The string table
// ===========================================================================
// One id per piece of user-visible text on the panel, carrying both languages:
// an English literal with the font it wants, and the baked Chinese bitmap that
// replaces it. ui_draw() picks a side and the caller never asks which.
//
// The font lives in the table rather than at the call site because the two
// halves have to be chosen together -- the Chinese is baked at a fixed pixel
// size (tools/gen_zh_labels.py names it per string), and the English has to be
// the size that sits in the same slot. Splitting them across two files is how
// they would drift.
//
// Both sides centre vertically on the y you pass, which is already the rule
// labels.h works by and already what every MC/ML/MR datum in the scenes does.
//
// What is NOT here: anything with a runtime value in it. A temperature, an IP
// address, a countdown. Those are laid out with UiRun below, because the parts
// go in a different order in the two languages -- "in 2h 05m" against
// "2小時05分後" -- and a format string cannot express that.
enum UiText : uint8_t {
  // Bus scene. These existed as ZhLabels before there was a language setting;
  // the English side is what the toggle added.
  T_MIN_UNIT, T_ARRIVING, T_NO_SERVICE, T_NOT_SET, T_NO_CLOCK, T_NO_DATA,
  T_CHECK_STOP, T_SCHEDULED, T_DEPARTED, T_LAST_BUS, T_TIMETABLE, T_TO,
  T_STOP_WORD, T_UPDATED, T_SEC_AGO, T_MIN_AGO, T_NEXT_BUS, T_LOADING,
  T_ADD_STOPS,

  // Clock: the date line's fixed parts, and the footer.
  T_YEAR, T_MONTH, T_DAY, T_WEEKDAY,
  T_WD_0, T_WD_1, T_WD_2, T_WD_3, T_WD_4, T_WD_5, T_WD_6,
  T_SETUP_AT, T_WIFI_OFFLINE, T_SYNCING,

  // Weather.
  T_WX_CLEAR, T_WX_PARTLY, T_WX_CLOUD, T_WX_FOG, T_WX_RAIN, T_WX_SNOW,
  T_WX_THUNDER, T_WX_LOADING, T_FEELS, T_CLOUD_PCT, T_HUMIDITY, T_WIND,

  // Air quality.
  T_AQI_TITLE, T_AQI_LOADING, T_AQI_GOOD, T_AQI_MODERATE, T_AQI_SENSITIVE,
  T_AQI_UNHEALTHY, T_AQI_VERY_BAD, T_AQI_HAZARD,

  // Sun & moon.
  T_SUNRISE, T_SUNSET, T_UV, T_TOMORROW, T_GOLDEN_HOUR, T_LIT,
  T_NOW, T_HOURS, T_MINUTES, T_AFTER,
  T_MOON_NEW, T_MOON_WAX_CRE, T_MOON_FIRST_Q, T_MOON_WAX_GIB,
  T_MOON_FULL, T_MOON_WAN_GIB, T_MOON_LAST_Q, T_MOON_WAN_CRE,

  // Boot, setup portal, calibration.
  T_BOOT_STORAGE, T_BOOT_WIFI, T_BOOT_SAVED,
  T_SETUP_TITLE, T_SETUP_JOIN, T_SETUP_AUTO, T_SETUP_OR,
  T_CAL_TL, T_CAL_TR, T_CAL_BR, T_CAL_BL, T_CAL_HOLD, T_CAL_CONFIRM,
  T_CAL_TITLE, T_CAL_PRESS_EACH, T_CAL_SKIPPED, T_CAL_KEEPING,
  T_CAL_DONE, T_CAL_NOT_QUITE, T_CAL_OFF_BY, T_CAL_ROUGH, T_CAL_REDO,

  T_COUNT
};

// True when the panel is set to Chinese. Reads g_settings, which is fixed for
// the life of a boot -- the settings page restarts the device on save.
bool ui_zh();

// Pixel width in the current language. Selects the entry's font as a side
// effect, so measuring then drawing costs one font load, not two.
int ui_width(UiText id);

// Draw it. `y` is the vertical CENTRE; `align` says what `x` means.
void ui_draw(UiText id, int x, int y, LabelAlign align, uint16_t fg, uint16_t bg);

// The English literal, for the few callers that need the characters themselves
// rather than pixels -- serial traces, and the settings page's scene names.
const char* ui_en(UiText id);

// ===========================================================================
// UiRun -- a measured left-to-right run of labels and values
// ===========================================================================
// For text that mixes fixed words with runtime values, where the two languages
// order the parts differently:
//
//   EN  "in 2h 05m"          TEXT("in") TEXT("2h 05m")
//   ZH  "2小時05分後"         TEXT("2") LABEL(小時) TEXT("05") LABEL(分) LABEL(後)
//
// Build the run, then draw it: width() is known before anything is painted, so
// the whole thing can be centred or right-aligned as one object. Segments hold
// borrowed pointers, so the strings they name must outlive the draw -- in
// practice they are stack buffers in the same function.
class UiRun {
public:
  void reset() { n_ = 0; w_ = 0; }

  // A run of Latin characters -- digits, a time, an IP address.
  UiRun& text(const char* s, UiFont f);
  // One baked word.
  UiRun& label(UiText id);
  // A fixed gap, for when the two languages want different spacing.
  UiRun& gap(int px);

  int width() const { return w_; }
  void draw(int x, int y, LabelAlign align, uint16_t fg, uint16_t bg);

private:
  // Ten is the longest run any scene builds (the Chinese date line is eight).
  static const int MAX = 10;
  struct Seg { const char* s; UiFont f; uint8_t id; int16_t w; bool isLabel; };
  Seg seg_[MAX];
  int n_ = 0;
  int w_ = 0;
};
