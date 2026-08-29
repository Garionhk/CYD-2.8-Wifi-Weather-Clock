#include "uitext.h"
#include "theme.h"
#include "settings.h"

static UiFont current = UI_FONT_COUNT;    // nothing loaded yet

void font_use(UiFont f) {
  if (f >= UI_FONT_COUNT || f == current) return;
  // loadFont unloads the incumbent itself, so there is no unloadFont here --
  // calling one would just free the metrics twice as often.
  tft.loadFont(UI_FONTS[f]);
  current = f;
}

UiFont font_current() { return current; }

// ===========================================================================
// The string table
// ===========================================================================

namespace {

struct UiEntry {
  const char* en;
  UiFont      enFont;
  ZhLabel     zh;
};

// Order must match the UiText enum; the static_assert below is what enforces
// it, so a row added in one place and forgotten in the other fails the build
// rather than shifting every string after it by one.
const UiEntry TABLE[] = {
  // --- bus scene ---------------------------------------------------------
  { "min",          UI_FONT_S,  ZH_FEN_ZHONG   },
  { "Arriving",     UI_FONT_S,  ZH_ARRIVING    },
  { "No service",   UI_FONT_M,  ZH_NO_SERVICE  },
  { "Not set",      UI_FONT_M,  ZH_NOT_SET     },
  { "No clock",     UI_FONT_M,  ZH_NO_CLOCK    },
  { "No data",      UI_FONT_M,  ZH_NO_DATA     },
  { "Check stop",   UI_FONT_S,  ZH_CHECK_STOP  },
  { "Scheduled",    UI_FONT_S,  ZH_SCHEDULED   },
  { "Departed",     UI_FONT_S,  ZH_DEPARTED    },
  { "Last bus",     UI_FONT_S,  ZH_LAST_BUS    },
  { "Timetable",    UI_FONT_S,  ZH_TIMETABLE   },
  { "to",           UI_FONT_S,  ZH_TO          },
  { "stop",         UI_FONT_S,  ZH_STOP_WORD   },
  { "updated",      UI_FONT_S,  ZH_UPDATED     },
  { "s ago",        UI_FONT_S,  ZH_SEC_AGO     },
  { "m ago",        UI_FONT_S,  ZH_MIN_AGO     },
  { "Next Bus",     UI_FONT_S,  ZH_NEXT_BUS    },
  { "Loading",      UI_FONT_M,  ZH_LOADING     },
  { "Add bus stops on the settings page",
                    UI_FONT_S,  ZH_ADD_STOPS   },

  // --- clock -------------------------------------------------------------
  // The date parts have no English side that is ever drawn: English builds the
  // whole line with strftime instead. They are still spelled out rather than
  // left blank, because ui_en() is what serial traces print.
  { "y",            UI_FONT_M,  ZH_YEAR        },
  { "m",            UI_FONT_M,  ZH_MONTH       },
  { "d",            UI_FONT_M,  ZH_DAY         },
  { "week",         UI_FONT_M,  ZH_WEEKDAY     },
  { "Sun",          UI_FONT_M,  ZH_WD_0        },
  { "Mon",          UI_FONT_M,  ZH_WD_1        },
  { "Tue",          UI_FONT_M,  ZH_WD_2        },
  { "Wed",          UI_FONT_M,  ZH_WD_3        },
  { "Thu",          UI_FONT_M,  ZH_WD_4        },
  { "Fri",          UI_FONT_M,  ZH_WD_5        },
  { "Sat",          UI_FONT_M,  ZH_WD_6        },
  { "setup:",       UI_FONT_S,  ZH_SETUP_AT    },
  { "wifi offline", UI_FONT_S,  ZH_WIFI_OFFLINE},
  { "syncing time...", UI_FONT_M, ZH_SYNCING   },

  // --- weather -----------------------------------------------------------
  { "Clear",        UI_FONT_S,  ZH_WX_CLEAR    },
  { "Partly Cloudy",UI_FONT_S,  ZH_WX_PARTLY   },
  { "Cloudy",       UI_FONT_S,  ZH_WX_CLOUD    },
  { "Fog",          UI_FONT_S,  ZH_WX_FOG      },
  { "Rain",         UI_FONT_S,  ZH_WX_RAIN     },
  { "Snow",         UI_FONT_S,  ZH_WX_SNOW     },
  { "Thunderstorm", UI_FONT_S,  ZH_WX_THUNDER  },
  { "fetching weather...", UI_FONT_M, ZH_WX_LOADING },
  { "Feels",        UI_FONT_S,  ZH_FEELS       },
  { "CLOUD",        UI_FONT_S,  ZH_CLOUD_PCT   },
  { "HUMIDITY",     UI_FONT_S,  ZH_HUMIDITY    },
  { "WIND",         UI_FONT_S,  ZH_WIND        },

  // --- air quality -------------------------------------------------------
  { "AIR QUALITY INDEX", UI_FONT_S, ZH_AQI_TITLE },
  { "fetching air quality...", UI_FONT_M, ZH_AQI_LOADING },
  { "Good",           UI_FONT_M, ZH_AQI_GOOD      },
  { "Moderate",       UI_FONT_M, ZH_AQI_MODERATE  },
  { "Unhealthy (SG)", UI_FONT_M, ZH_AQI_SENSITIVE },
  { "Unhealthy",      UI_FONT_M, ZH_AQI_UNHEALTHY },
  { "Very Unhealthy", UI_FONT_M, ZH_AQI_VERY_BAD  },
  { "Hazardous",      UI_FONT_M, ZH_AQI_HAZARD    },

  // --- sun & moon --------------------------------------------------------
  { "Sunrise",      UI_FONT_S,  ZH_SUNRISE     },
  { "Sunset",       UI_FONT_S,  ZH_SUNSET      },
  { "UV",           UI_FONT_S,  ZH_UV          },
  { "TOMORROW",     UI_FONT_S,  ZH_TOMORROW    },
  { "Golden hour",  UI_FONT_S,  ZH_GOLDEN_HOUR },
  { "lit",          UI_FONT_S,  ZH_LIT         },
  { "now",          UI_FONT_M,  ZH_NOW         },
  { "h",            UI_FONT_M,  ZH_HOURS       },
  { "m",            UI_FONT_M,  ZH_MINUTES     },
  { "in",           UI_FONT_M,  ZH_AFTER       },
  { "New Moon",        UI_FONT_S, ZH_MOON_NEW      },
  { "Waxing Crescent", UI_FONT_S, ZH_MOON_WAX_CRE  },
  { "First Quarter",   UI_FONT_S, ZH_MOON_FIRST_Q  },
  { "Waxing Gibbous",  UI_FONT_S, ZH_MOON_WAX_GIB  },
  { "Full Moon",       UI_FONT_S, ZH_MOON_FULL     },
  { "Waning Gibbous",  UI_FONT_S, ZH_MOON_WAN_GIB  },
  { "Last Quarter",    UI_FONT_S, ZH_MOON_LAST_Q   },
  { "Waning Crescent", UI_FONT_S, ZH_MOON_WAN_CRE  },

  // --- boot, setup portal, calibration -----------------------------------
  { "Preparing storage...", UI_FONT_M, ZH_BOOT_STORAGE },
  { "Connecting WiFi...",   UI_FONT_M, ZH_BOOT_WIFI    },
  { "Saved -- restarting",  UI_FONT_M, ZH_BOOT_SAVED   },
  { "Setup",                UI_FONT_M, ZH_SETUP_TITLE  },
  { "1. Join this WiFi network",           UI_FONT_S, ZH_SETUP_JOIN },
  { "2. The setup page opens automatically,", UI_FONT_S, ZH_SETUP_AUTO },
  { "or browse to",         UI_FONT_S, ZH_SETUP_OR     },
  { "top left",             UI_FONT_M, ZH_CAL_TL       },
  { "top right",            UI_FONT_M, ZH_CAL_TR       },
  { "bottom right",         UI_FONT_M, ZH_CAL_BR       },
  { "bottom left",          UI_FONT_M, ZH_CAL_BL       },
  { "press firmly and hold",   UI_FONT_S, ZH_CAL_HOLD    },
  { "tap the centre to confirm", UI_FONT_S, ZH_CAL_CONFIRM },
  { "Touch setup",              UI_FONT_M, ZH_CAL_TITLE      },
  { "press each target, firmly",UI_FONT_S, ZH_CAL_PRESS_EACH },
  { "Touch setup skipped",      UI_FONT_M, ZH_CAL_SKIPPED    },
  { "using previous calibration", UI_FONT_S, ZH_CAL_KEEPING  },
  { "Touch calibrated",         UI_FONT_M, ZH_CAL_DONE       },
  { "Not quite",                UI_FONT_M, ZH_CAL_NOT_QUITE  },
  { "px off - try again",       UI_FONT_S, ZH_CAL_OFF_BY     },
  { "Saved (rough)",            UI_FONT_M, ZH_CAL_ROUGH      },
  { "hold 4s any time to redo", UI_FONT_S, ZH_CAL_REDO       },
};

static_assert(sizeof(TABLE) / sizeof(TABLE[0]) == T_COUNT,
              "UiText enum and the string table have drifted apart");

uint8_t datumFor(LabelAlign a) {
  return a == LBL_CENTRE ? MC_DATUM : (a == LBL_RIGHT ? MR_DATUM : ML_DATUM);
}

}  // namespace

bool ui_zh() { return g_settings.lang == LANG_ZH; }

const char* ui_en(UiText id) {
  return (id < T_COUNT) ? TABLE[id].en : "";
}

int ui_width(UiText id) {
  if (id >= T_COUNT) return 0;
  const UiEntry& e = TABLE[id];
  if (ui_zh()) return label_width(e.zh);
  font_use(e.enFont);
  return tft.textWidth(e.en);
}

void ui_draw(UiText id, int x, int y, LabelAlign align, uint16_t fg, uint16_t bg) {
  if (id >= T_COUNT) return;
  const UiEntry& e = TABLE[id];
  if (ui_zh()) {
    label_draw(e.zh, x, y, align, fg, bg);
    return;
  }
  font_use(e.enFont);
  tft.setTextColor(fg, bg);
  tft.setTextDatum(datumFor(align));
  tft.drawString(e.en, x, y);
}

// ---------------------------------------------------------------------------
// UiRun
// ---------------------------------------------------------------------------

UiRun& UiRun::text(const char* s, UiFont f) {
  if (n_ >= MAX || !s || !*s) return *this;
  font_use(f);
  const int w = tft.textWidth(s);
  seg_[n_++] = { s, f, 0, (int16_t)w, false };
  w_ += w;
  return *this;
}

UiRun& UiRun::label(UiText id) {
  if (n_ >= MAX || id >= T_COUNT) return *this;
  const int w = ui_width(id);
  seg_[n_++] = { nullptr, UI_FONT_S, (uint8_t)id, (int16_t)w, true };
  w_ += w;
  return *this;
}

UiRun& UiRun::gap(int px) {
  if (n_ >= MAX || px <= 0) return *this;
  seg_[n_++] = { nullptr, UI_FONT_S, 0, (int16_t)px, false };
  w_ += px;
  return *this;
}

void UiRun::draw(int x, int y, LabelAlign align, uint16_t fg, uint16_t bg) {
  int cx = x;
  if (align == LBL_CENTRE) cx = x - w_ / 2;
  else if (align == LBL_RIGHT) cx = x - w_;

  for (int i = 0; i < n_; i++) {
    const Seg& s = seg_[i];
    if (s.isLabel) {
      ui_draw((UiText)s.id, cx, y, LBL_LEFT, fg, bg);
    } else if (s.s) {
      font_use(s.f);
      tft.setTextColor(fg, bg);
      tft.setTextDatum(ML_DATUM);
      tft.drawString(s.s, cx, y);
    }
    cx += s.w;
  }
}
