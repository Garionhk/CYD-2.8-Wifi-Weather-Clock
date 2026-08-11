# CYD Clock & Weather Station — Implementation Plan

> **Historical.** This is the original v1 plan, written against the 2.4"
> ESP32-2432S024R before Stage 0 had run. It is kept because the rest of the
> codebase still cites it by section (plan §5.1, §7, §9 …) and because the
> reasoning behind the architecture has not changed.
>
> What it does **not** describe is everything decided since: the move to the 2.8"
> board, touch coming back into scope, settings moving from `config.h` to a web
> page, and the fifth scene. For what actually shipped, read
> [CHANGELOG.md](CHANGELOG.md); for *why* it diverged, read
> [docs/decisions.md](docs/decisions.md), which is the living record.

Target board: ESP32 2.4" 240×320 TFT module with **resistive touch** (ESP32-2432S024R family,
to be confirmed — see Stage 0).
Framework: Arduino-ESP32.

---

## 1. Scope

A wall/desk display that rotates continuously between four scenes, 24 hours a day.
No night mode, no auto-dimming — brightness is fixed at boot default.

**Non-goals for v1:** SD card slideshow, audio/alarms, OTA updates, RGB LED, LDR/brightness
control. Keep these out until the four scenes are stable.

**Work order:** complete **Stage 0** (§3) in full before writing any application code.
Every test in Stage 0 produces a value that the real code depends on.

---

## 2. Hardware reference

Board is a 2.4" 240×320 module, single USB-C, resistive touch. Note that the widely
documented "Cheap Yellow Display" is the **2.8"** `ESP32-2432S028R` — the 2.4" boards are
the `ESP32-2432S024` family (`N` no touch / `R` resistive / `C` capacitive). Yours is
resistive, so `024R`.

Confirm the exact part number from the silkscreen on the back of the PCB when convenient,
but Stage 0 settles everything that actually matters empirically.

Expected pinout (verify in Stage 0, do not trust blindly):

| Function | Pins |
|---|---|
| TFT (ILI9341, HSPI) | MOSI 13, MISO 12, SCLK 14, CS 15, DC 2, RST -1 |
| Backlight | GPIO 27 on 024 boards, GPIO 21 on 028 — **irrelevant for v1**, left at boot default |
| Touch (XPT2046, own SPI bus) | MOSI 32, MISO 39, CLK 25, CS 33, IRQ 36 |
| SD card (VSPI) | MOSI 23, MISO 19, SCK 18, CS 5 |
| LDR | GPIO 34 (ADC1) — unused in v1 |
| RGB LED | R 4, G 16, B 17 (active LOW) — unused in v1 |
| Speaker / DAC | GPIO 26 — unused in v1 |

Key structural fact: **display and touch are on separate SPI buses.** Touch requires its own
`SPIClass` instance passed to `XPT2046_Touchscreen`. This trips up most first attempts.

ADC2 pins are unusable once WiFi starts. Nothing in v1 depends on them.

---

## 3. Stage 0 — Hardware verification

Run these five tests **in order**. Each has an explicit pass criterion and a value to record.
Do not begin application code until all five pass. If a test fails, fix it before moving on —
a failure at 0.1 makes every later result meaningless.

Fill in the results table at the end (§3.6). Those recorded values become constants in the
real code.

### 0.1 Display — driver, inversion, rotation

Configure `User_Setup.h` in the TFT_eSPI library folder with the pins from §2 and
`ILI9341_2_DRIVER`. Set `TFT_WIDTH 240`, `TFT_HEIGHT 320`, `SPI_FREQUENCY 55000000`.

```cpp
#include <TFT_eSPI.h>
TFT_eSPI tft = TFT_eSPI();

void setup() {
  tft.init();
  tft.setRotation(1);                 // 1 = landscape, 320x240
  tft.fillScreen(TFT_BLACK);
  tft.fillRect(10,  10, 80, 60, TFT_RED);
  tft.fillRect(100, 10, 80, 60, TFT_GREEN);
  tft.fillRect(190, 10, 80, 60, TFT_BLUE);
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  tft.setTextSize(2);
  tft.drawString("TOP LEFT", 10, 100);
  tft.drawString("W=" + String(tft.width()) + " H=" + String(tft.height()), 10, 130);
}
void loop() {}
```

Diagnosing what you see:

| Symptom | Cause | Fix |
|---|---|---|
| Blank / white / garbage | Wrong driver | Try `ST7789_DRIVER` |
| Red shows cyan, blue shows orange | Colours inverted | Toggle `TFT_INVERSION_ON` / `TFT_INVERSION_OFF` |
| Red and blue swapped only | RGB/BGR order | Toggle `TFT_RGB_ORDER TFT_BGR` / `TFT_RGB` |
| Text mirrored or off-screen | Rotation | Try `setRotation()` 0–3 |
| Faint, flickering, or intermittent | SPI too fast | Drop `SPI_FREQUENCY` to 40000000 or 27000000 |

**Pass:** three correctly coloured bars left-to-right red/green/blue, "TOP LEFT" legible in the
upper-left of a landscape screen, and the printed dimensions read `W=320 H=240`.

**Record:** driver define, inversion setting, RGB order, rotation number, SPI frequency.

> **Immediately back up your working `User_Setup.h`** to your project folder. A TFT_eSPI
> library update will overwrite it and you will lose an evening rediscovering these settings.

### 0.2 Touch — raw readings and calibration

```cpp
#include <SPI.h>
#include <XPT2046_Touchscreen.h>

SPIClass touchSPI(HSPI);            // separate bus from the display
XPT2046_Touchscreen ts(33, 36);     // CS, IRQ

void setup() {
  Serial.begin(115200);
  touchSPI.begin(25, 39, 32, 33);   // SCLK, MISO, MOSI, CS
  ts.begin(touchSPI);
  ts.setRotation(1);
}
void loop() {
  if (ts.touched()) {
    TS_Point p = ts.getPoint();
    Serial.printf("raw x=%d y=%d z=%d\n", p.x, p.y, p.z);
    delay(100);
  }
}
```

Press each of the four screen corners firmly with a fingernail or stylus and note the raw
values. Resistive panels need pressure — a light fingertip may not register.

**Pass:** every press prints a reading; values change smoothly and repeatably as you move
across the screen; corners give consistently distinct numbers.

**Record:** raw min/max X and raw min/max Y. These become your `map()` constants for
converting raw readings to 0–319 / 0–239 screen pixels.

Also record typical `z` (pressure) for a deliberate press versus noise, and set your press
threshold above the noise floor.

### 0.3 WiFi and time

```cpp
#include <WiFi.h>
#include "time.h"

void setup() {
  Serial.begin(115200);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.printf("\nIP %s  RSSI %d\n", WiFi.localIP().toString().c_str(), WiFi.RSSI());

  configTzTime("PST8PDT,M3.2.0,M11.1.0", "pool.ntp.org", "time.nist.gov");
  struct tm t;
  while (!getLocalTime(&t)) { delay(500); Serial.print("t"); }
  Serial.println(&t, "\n%A %d %B %Y  %H:%M:%S");
}
void loop() {}
```

**Pass:** connects, and prints the correct **local** wall-clock time including the right
DST offset — not UTC.

**Record:** RSSI at the display's intended location (weak signal here predicts reconnect
problems later), and the confirmed TZ string.

### 0.4 Heap baseline

Add to the end of test 0.3's `setup()`:

```cpp
Serial.printf("free heap %u  min free %u  largest block %u\n",
  ESP.getFreeHeap(), ESP.getMinFreeHeap(),
  heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
```

**Pass:** free heap comfortably above 150 KB with WiFi connected.

**Record:** free heap and largest contiguous block. The largest block is the real limit on
sprite allocation — a fragmented heap can refuse a 15 KB sprite even with 180 KB free.

### 0.5 One HTTPS fetch end-to-end

Fetch current weather from Open-Meteo — no API key needed — with buffer sizing and an
ArduinoJson filter, exactly as the real code will.

```cpp
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>

void fetchWeather() {
  WiFiClientSecure client;
  client.setInsecure();
  client.setBufferSizes(2048, 512);          // biggest single heap saving

  HTTPClient https;
  String url = "https://api.open-meteo.com/v1/forecast?latitude=" + String(LATITUDE, 4) +
               "&longitude=" + String(LONGITUDE, 4) +
               "&current=temperature_2m,apparent_temperature,weather_code,cloud_cover,"
               "relative_humidity_2m,wind_speed_10m,surface_pressure&timezone=auto";

  Serial.printf("before fetch: heap %u\n", ESP.getFreeHeap());

  if (https.begin(client, url) && https.GET() == 200) {
    JsonDocument filter;
    filter["current"]["temperature_2m"]       = true;
    filter["current"]["apparent_temperature"] = true;
    filter["current"]["weather_code"]         = true;
    filter["current"]["cloud_cover"]          = true;

    JsonDocument doc;
    deserializeJson(doc, https.getStream(), DeserializationOption::Filter(filter));
    Serial.printf("temp %.1f  code %d  cloud %d%%\n",
      doc["current"]["temperature_2m"].as<float>(),
      doc["current"]["weather_code"].as<int>(),
      doc["current"]["cloud_cover"].as<int>());
  } else {
    Serial.println("fetch failed");
  }
  https.end();
  client.stop();
  Serial.printf("after fetch: heap %u\n", ESP.getFreeHeap());
}
```

Call it once in `setup()`, then again from `loop()` every 30 s and let it run 30 minutes.

**Pass:** plausible values printed, and heap returns to roughly the same number after every
fetch. A figure that creeps downward across repeated calls is a leak — find it now, not after
you have four scenes on top of it.

**Record:** heap before and after a fetch, and the drift across 60 consecutive fetches.

### 0.6 Results table

Fill this in and keep it with the project:

| Item | Value |
|---|---|
| Driver define | |
| Inversion | |
| RGB order | |
| Rotation | |
| SPI frequency | |
| Touch raw X min / max | |
| Touch raw Y min / max | |
| Touch pressure threshold | |
| TZ string | |
| RSSI at final location | |
| Free heap / largest block | |
| Heap drift over 60 fetches | |

**Gate: all six complete and passing before any application code is written.**

---

## 4. Libraries

| Library | Purpose | Notes |
|---|---|---|
| `TFT_eSPI` | Display + sprites | Chosen over LVGL: less flash, full control of partial redraws |
| `XPT2046_Touchscreen` | Resistive touch | Second SPI bus |
| `WiFi` / `WiFiClientSecure` | Network | One TLS connection at a time |
| `ArduinoJson` v7 | Parsing | Always use filters |
| `time.h` (ESP32 SNTP) | Clock | `configTzTime()` with a POSIX TZ string |

Sun and moon calculations are **local math** — no library, no API, ~200 lines. See §8.

Partition scheme: the default 4 MB with SPIFFS is sufficient on TFT_eSPI.

---

## 5. Architecture

Three layers, strictly separated. **The render layer never makes a network call.**

### 5.1 Data model

One global struct, written only by the fetch layer, read only by scenes:

```cpp
struct AppData {
  // weather
  float  tempC, feelsLikeC;
  int    weatherCode;          // WMO code -> icon + text
  int    cloudCoverPct, humidityPct;
  float  windKph, pressureHpa, pressureTrend;
  uint32_t weatherUpdatedAt;
  bool   weatherValid;

  // air quality
  int    aqi, pm25;
  uint32_t aqiUpdatedAt;
  bool   aqiValid;

  // uv
  float  uvIndex;
  uint32_t uvUpdatedAt;
  bool   uvValid;

  // sun/moon — computed locally, always valid
  time_t sunriseToday, sunsetToday;
  time_t sunriseTomorrow, sunsetTomorrow;
  bool   showingNextDay;
  float  moonPhase;            // 0.0-1.0
  float  moonIlluminationPct;
  time_t moonrise, moonset;
};
```

Rule: a failed fetch **never clears** existing values. It only stops refreshing
`*UpdatedAt`. Scenes render last-good data; the status strip shows a staleness dot.

### 5.2 Fetch scheduler

Non-blocking, cooperative, **strictly sequential**. Never two TLS sockets at once.

```
loop():
  if (fetchInFlight) { serviceFetch(); return; }
  start the single most-overdue due task
```

| Task | Interval | Offset |
|---|---|---|
| Weather | 15 min | 0 |
| Air quality | 30 min | 5 min |
| UV index | 30 min | 10 min |
| Sun/moon recompute | boot, then each local midnight | — |
| SNTP resync | 6 h | — |

Per-request discipline (all validated in test 0.5): `setBufferSizes(2048, 512)`, JSON filters,
always `https.end()` and `client.stop()`.

Retry backoff on failure: 1 → 2 → 4 min, capped at 15. Do not hammer.

### 5.3 Scene state machine

```cpp
struct Scene {
  const char* name;
  uint32_t dwellMs;
  void (*onEnter)();   // full redraw of content area
  void (*onTick)();    // partial redraw only
  void (*onExit)();
};
```

Adding a fifth scene later is one array entry.

---

## 6. Rotation and touch behaviour

Rotation runs continuously, all day and night. No time-of-day branching anywhere.

| Scene | Dwell |
|---|---|
| 1 — Clock (home) | 35 s |
| 2 — Weather | 12 s |
| 3 — Sun & moon | 12 s |
| 4 — Air quality | 12 s |

| Gesture | Action |
|---|---|
| Tap | Advance to next scene **and** freeze auto-rotation for 45 s |
| Long press (> 800 ms) | Pin current scene until pressed again |

Show a pin glyph in the status strip while pinned, so the state is visible.
Debounce at ~250 ms — resistive panels are noisy; use the pressure threshold from test 0.2.

Transitions are instant swaps. No slide animation — SPI redraw at this size looks janky.

---

## 7. Layout

Landscape, 320×240.

```
┌────────────────────────────────────────┐
│                                        │
│           CONTENT AREA                 │  320 × 196
│                                        │
├────────────────────────────────────────┤
│ 21:47  ▂▄▆  ●            ○ ● ○ ○       │  320 × 44  status strip
└────────────────────────────────────────┘
```

**Status strip — persistent on all four scenes.** Only the content area redraws on scene
change, which keeps the swap fast.

Contains: time (HH:MM), WiFi bars, freshness dot (green < 30 min, amber < 2 h, red beyond),
pin glyph when pinned, scene position dots.

### Scene 1 — Clock
Full content area. HH:MM as large as fits, roughly 110 px digit height. Date and weekday as a
subordinate line. Seconds optional; if shown, redraw only the seconds region.

### Scene 2 — Weather
Left: condition icon (~80 px) from the WMO weather code.
Right: large current temp, feels-like beneath.
Bottom row: cloud cover %, humidity, wind.

### Scene 3 — Sun & Moon
Left half: arc from sunrise to sunset with a marker at the sun's current position.
Right half: rise time, set time, golden-hour countdown, UV index, moon phase icon and
illumination %.

**Next-day labelling (required).** When `showingNextDay` is true the panel must say so —
a "TOMORROW" label above the times, and the arc greyed out or replaced by a plain two-line
readout. It must never be ambiguous whether the times shown are today's or tomorrow's.

At night UV reads 0. Display the 0 rather than hiding the field — it keeps the panel looking
live rather than broken.

### Scene 4 — Air Quality
AQI as the headline number, colour-coded to the standard US AQI bands
(green / yellow / orange / red / purple / maroon) with the band name in words.
Secondary: PM2.5, humidity, wind, pressure with a trend arrow.

---

## 8. Sun and moon logic

Computed locally from latitude, longitude and date — no API call, so it stays correct with
WiFi down. NOAA solar position algorithm; moon phase from the synodic month since a known
new moon.

**Roll-forward trigger is sunset, not midnight.**

Compute rise/set for both today and tomorrow at every update, then:

```cpp
if (now > sunsetToday) {
    display sunriseTomorrow / sunsetTomorrow;
    showingNextDay = true;
} else {
    display sunriseToday / sunsetToday;
    showingNextDay = false;
}
```

Between midnight and sunrise, "tomorrow" is the current calendar date — computing both pairs
and picking by comparison avoids the off-by-one that date arithmetic invites.

Golden hour: sun elevation between −4° and +6°. Blue hour: −6° to −4°. Show a countdown to
the next window, or time remaining if currently inside one.

**Verify this offline against a published almanac for your coordinates before wiring it to
the display** — including one date in each of summer and winter, and a check right after
sunset to confirm the TOMORROW label appears.

---

## 9. Rendering and memory

Constraint: ~520 KB SRAM, roughly 200–250 KB free with WiFi up. No PSRAM.

- A full-screen 16-bit sprite is 150 KB — **do not allocate one.**
- Use **per-digit sprites**: ~70×110 px at 16 bpp ≈ 15 KB. Allocate one, draw a digit, reuse.
  Flicker-free without the memory cost.
- Redraw only digits that changed — on a normal minute tick that is one or two glyphs.
- Never allocate a sprite while a TLS connection is open.
- Log `getFreeHeap()` and `getMinFreeHeap()` on every scene change during development.
  A slow leak crashes at hour 30, not hour 1.

---

## 10. Configuration

Single `config.h`, excluded from version control:

```cpp
WIFI_SSID, WIFI_PASS
LATITUDE, LONGITUDE
TZ_STRING            // e.g. "PST8PDT,M3.2.0,M11.1.0"
UNITS                // metric / imperial
```

Data source: Open-Meteo — free, no API key, and it serves forecast, air quality and UV from
one provider. Use the `current=` parameter rather than pulling the full hourly block.

---

## 11. Build order

Stage 0 (§3) must be complete and passing first. Then:

1. **Scene 1** — big clock with partial digit redraw, status strip in place
2. **Scene machine** — four placeholder scenes, dwell timing, tap-to-advance with 45 s freeze,
   long-press pin
3. **Sun/moon math** — verified offline per §8, including roll-forward and the TOMORROW label
4. **Fetch layer** — promote test 0.5 into the scheduler, wire to scene 2
5. **Remaining data** — AQI and UV on the scheduler, scenes 3 and 4 populated
6. **Soak test** — 72 h continuous; watch min free heap and WiFi reconnect behaviour

---

## 12. Open items

- Exact part number from the PCB silkscreen (confirmation only — Stage 0 settles the rest)
- Metric vs imperial
- Icon set: hand-drawn byte arrays vs converted PNGs in flash
- Whether the golden-hour countdown also belongs on scene 1
- Watchdog / auto-reboot policy if WiFi is unrecoverable for > 1 h
