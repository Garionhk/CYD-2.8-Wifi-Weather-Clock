# CYD Clock & Weather Station

Firmware for a **"cheap yellow display"** ESP32 board: a wall/desk clock that
rotates continuously between clock, weather, sun/moon, air-quality and
next-bus scenes, with a touch panel for advancing, pinning and setup.

**Flash it and configure it from your phone** — there is nothing to edit before
building. WiFi, location, timezone, units, which screens appear and for how long
are all set from a web page the device serves itself and stores in flash, so the
same binary works for anyone.

**No API key.** Weather and air quality come from Open-Meteo. Sun, moon phase and
golden hour are computed on the device, so they stay right with the network down.

**English or 繁體中文**, chosen on the settings page and applied to every screen,
the setup portal and the settings page itself. The panel has no CJK font and no
room for one, so the Chinese is baked to 1-bit bitmaps at build time. The Latin
*is* antialiased, from `.vlw` subsets, and the clock's digits keep their
seven-segment face. See [docs/decisions.md](docs/decisions.md).

**下一班車 / Next Bus** shows the next Hong Kong bus or minibus at up to four
stops, in Traditional Chinese, from the government's open data (KMB/Long Win,
Citybus and green minibus). Switch it off if you are not in Hong Kong — every
screen is optional.

**Target board: ESP32-2432S028R** — 2.8" 240×320 ILI9341, resistive touch
(XPT2046), micro-USB *and* USB-C. The earlier 2.4" ESP32-2432S024R is still
supported by the same code via `CYD_BOARD` in `config/board.h`.

[CHANGELOG.md](CHANGELOG.md) is what shipped, [docs/decisions.md](docs/decisions.md)
is why it was built that way, and [plan.md](plan.md) is the original v1 design
the rest still cites by section. [docs/](docs) has what the hardware actually
turned out to do.

---

## Quick start

**1. Install the display config** (once, and again after any TFT_eSPI update):

```bash
cp config/User_Setup_2432S028R.h.template ~/Documents/Arduino/libraries/TFT_eSPI/User_Setup.h
```

**2. Build and flash — there is nothing to edit first:**

```bash
./tools/build_all.sh
```

```bash
./tools/flash.sh app
```

**3. Configure the device from your phone.** First boot runs the touch
calibration wizard, then shows a setup screen:

```
                    Setup

           1. Join this WiFi network
                CYD-Setup-A1B2

     2. The setup page opens automatically,
                  or browse to
              http://192.168.4.1
```

Join that network and a settings page opens by itself (it's a captive portal).
Fill in your WiFi, location, timezone and units, press save, and the clock
restarts and connects. Screens and bus stops are set from the same page later,
once the clock is on your own network. See [Configuration](#configuration) for what each field
means and how to find your coordinates.

Afterwards the settings page stays available on your normal network — the clock
prints its address along the bottom of the clock screen.

> If the upload dies right after `Changing baud rate to 921600`, drop the upload
> speed — see [Upload speed](#upload-speed) below.

> If you build from the Arduino IDE rather than the scripts, run
> `./tools/sync_shared.sh` first — see [Shared headers](#shared-headers-and-the-arduino-include-rule).

### Upload speed

The Arduino-ESP32 default upload speed is **921600**, and this board's CH340
does not reliably survive the switch to it. The failure is distinctive — the
handshake succeeds, then dies the moment esptool changes rate:

```
Changing baud rate to 921600
Changed.
A fatal error occurred: Unable to verify flash chip connection
(Invalid head of packet (0xE0): Possible serial noise or corruption.)
```

Nothing is written when this happens, so the board still holds its previous
firmware — it is safe to just retry slower. Both scripts take an `FQBN`
override, so no edit is needed:

```bash
FQBN=esp32:esp32:esp32:PartitionScheme=huge_app,UploadSpeed=115200 ./tools/flash.sh app
```

Keep `PartitionScheme=huge_app` in there — an `FQBN` override **replaces** the
default wholesale, and without it the app is squeezed back into the 1.31 MB
slot, which it no longer fits in at all.

115200 works consistently; the cost is about a minute for a full app image
instead of ten seconds. 460800 is worth a try as a middle ground. If you flash
from the Arduino IDE instead, the same setting is **Tools → Upload Speed**.

This is a property of the individual USB-serial chip, not of the firmware — a
different cable or the other USB connector may well behave differently, so treat
the slow speed as the fallback rather than the default.

---

## Configuration

**Nothing is configured at compile time.** There is no `config.h` — WiFi,
location, timezone, units, bus stops, and which screens appear and for how long
are set from a web page and stored in the ESP32's NVS flash, alongside the touch
calibration and for the same reason: re-flashing to change a setting is a poor
repair path for something hanging on a wall.

The practical consequence is that **the built binary contains nothing personal**,
so the same image can be handed to anyone. (It also removes a whole class of
accident: a credentials header cannot be committed if it does not exist.)

### Reaching the settings page

| Situation | Where |
|---|---|
| Normal operation | `http://<device-ip>` on your network — the address is printed along the bottom of the **clock screen** |
| WiFi unreachable, or never configured | Join the **`CYD-Setup-XXXX`** network the device creates, then `http://192.168.4.1` |

The setup network is a captive portal, so phones and laptops pop the page open
by themselves. `XXXX` is derived from the board's MAC, so two devices on a bench
don't collide.

### If the device can't reach your network

It does **not** stop being a clock. WiFi failure has always been non-fatal here
— the clock, sun/moon math and touch all work offline — and that hasn't changed.
What it adds is the `CYD-Setup-XXXX` network in parallel, so you can fix the
credentials. The radio runs AP+STA, so it keeps retrying your real network in
the background and drops the setup network by itself once that succeeds.

This applies **whether the network was missing at boot or disappeared later** —
if you replace the router or change its password, the setup network comes back
by itself after two minutes of no connection. You should never have to power
cycle the clock to reach its settings. The two-minute wait is there so an
ordinary router reboot doesn't make the setup network flicker in and out.

### Forcing setup mode

**Hold a finger on the panel while it boots.** This is the recovery path for the
one case the settings page can't fix on its own: the device is configured for a
network that no longer exists, so nothing can reach it to tell it otherwise.

### WiFi

2.4 GHz only — the ESP32 has no 5 GHz radio, so a 5 GHz-only network will never
appear in the scan. If your router publishes both bands under one name this
usually still works, but a separate 2.4 GHz SSID is the reliable option.

The **Scan** button lists what the device can actually see, which is the quickest
way to tell a 5 GHz-only network from a typo. Leaving the password field blank
keeps the currently stored one — the page never sends your saved password back
to the browser.

### Location — latitude and longitude

These drive both the weather fetch and the local sunrise/sunset/moon math, so
getting them wrong gives you someone else's weather and the wrong golden hour.
Until you set them the device uses the Greenwich Observatory — a placeholder
chosen to be visibly not yours, rather than plausible-but-wrong.

**Format: decimal degrees.** Not degrees-minutes-seconds. Signs are what people
get wrong most often:

| | Positive | Negative |
|---|---|---|
| Latitude | north of the equator | south of the equator |
| Longitude | east of Greenwich | **west** of Greenwich |

So anywhere in the Americas needs a **negative longitude**. Worked examples:
New York `40.7128` / `-74.0060`; Tokyo `35.6762` / `139.6503`; Sydney
`-33.8688` / `151.2093`; São Paulo `-23.5505` / `-46.6333`.

**To find yours**, easiest first:

- **Google Maps** — right-click your location; the first item in the menu is the
  coordinate pair, already in decimal degrees and correctly signed. Click to
  copy.
- **OpenStreetMap** — right-click → *Show address*; the coordinates appear in
  the left panel.
- **[latlong.net](https://www.latlong.net/)** — search a place name, read the
  pair off directly.

If you have DMS from another source (`51°28'40"N`), convert with
degrees + minutes/60 + seconds/3600, then negate for S or W.

**On precision:** four decimals is about 11 m — far finer than any forecast
resolves, and precise enough to point at your house. Two decimals (~1 km) gives
identical weather. Use two, and pick a nearby landmark rather than your address
if you plan to share screenshots or logs: these coordinates are the one setting
that says where you live.

To confirm they took, watch the boot log: the sun/moon block prints sunrise and
sunset for your coordinates, which is easy to sanity-check against an almanac.

### Time zone

The settings page has a dropdown of common zones, plus a free-text field for
anything not listed. The stored value is a **POSIX TZ string**, not an IANA name
like `Europe/London` — the ESP32's libc has no timezone database, so the DST
rules have to be spelled out. The format is `STDoffsetDST,start,end`, where the
offset is *hours west* of UTC (so US Pacific is `8`, and zones east of UTC take
a negative offset).

| Zone | String |
|---|---|
| UK | `GMT0BST,M3.5.0/1,M10.5.0` |
| US Pacific | `PST8PDT,M3.2.0,M11.1.0` |
| US Eastern | `EST5EDT,M3.2.0,M11.1.0` |
| Central Europe | `CET-1CEST,M3.5.0,M10.5.0/3` |
| Japan (no DST) | `JST-9` |
| Sydney | `AEST-10AEDT,M10.1.0,M4.1.0/3` |
| UTC | `UTC0` |

Search "POSIX TZ string" plus your zone if it isn't listed.

Note the weather request uses `timezone=auto` and derives the day boundary from
your coordinates independently, so a wrong timezone shows up as a wrong *clock*,
not wrong weather.

### Units

Metric (°C, km/h, hPa) or imperial (°F, mph, inHg), as a pair — there is no
per-field choice. Display only: `AppData` always stores metric and `units.h`
converts at draw time, so switching never touches stored or fetched data.

### What is stored, and where

Two NVS namespaces, plus a filesystem partition:

| Where | Holds | Set by |
|---|---|---|
| NVS `cydcfg` | WiFi credentials, latitude/longitude, timezone, units, the four bus stops, which screens are on and their dwell times | the settings page |
| NVS `cydtouch` | touch calibration | the on-device wizard |
| LittleFS `/l/` | the baked Chinese stop-name images | your browser, via the settings page |

The split matters. NVS keeps the stop **text** — Chinese *and* English — while
LittleFS keeps only the **picture** of it, because the device has no CJK font
and cannot draw the text itself (see [Next Bus](#next-bus)). If the
picture is missing the scene falls back to the English name rather than going
blank, so nothing is ever lost, only translated.

`huge_app` leaves an 896 KB filesystem partition otherwise unused, which is why
the images go there rather than into the 20 KB of NVS.

Saving restarts the device. Applying WiFi, timezone, location and units live
would each need a different refresh path; a restart takes about three seconds and
cannot leave the device half-configured.

**The settings page has no password.** Anyone on your network can open it and
change the clock's configuration, and the setup AP is an open network while it's
up. For a wall clock on a home LAN that is a reasonable trade; if it isn't for
you, put the device on a guest VLAN. Note the page never *reveals* the stored
WiFi password — it only accepts a new one.

---

## The application

Five scenes on a continuous rotation, with a persistent status strip:

| Scene | Dwell | Shows |
|---|---|---|
| Clock | 35 s | HH:MM in per-digit sprites, blinking colon, date line, settings address |
| Weather | 12 s | vector condition icon, temperature, feels-like, today's high/low, cloud/humidity/wind |
| Sun & Moon | 12 s | sunrise→sunset arc, moon phase + illumination, UV, golden hour |
| Air Quality | 12 s | US AQI colour-coded to the standard bands, PM2.5, pressure trend |
| 下一班車 Next Bus | 12 s | next Hong Kong bus or minibus at up to four stops, in Traditional Chinese |

Any screen can be switched off from the settings page, and each one's time on
screen adjusted, so the rotation is only the screens you actually want.

The first four answer *what is it like outside*. The fifth answers *should I
leave now*, which is why tapping to it holds the rotation for 60 s rather than
the usual 45 — you tap to watch a countdown, not to read a number once. It is
optional: configure no stops and it says so instead.

Weather and air quality come from Open-Meteo (no API key). Sun and moon are
**local math** — no network call, so they stay correct with WiFi down. Data is
stored metric in `AppData` and converted at draw time, so the units switch on
the settings page never touches what was fetched.

The clock screen also carries a dim footer with the settings page's address —
see [Configuration](#configuration).

### Choosing which screens appear

The **Screens** section of the settings page lists all five with a tickbox and a
time each (4–600 s). Untick one and the rotation skips it and it loses its dot in
the status strip; its time is remembered for when you tick it back on.

At least one screen has to stay on, and the form refuses to save otherwise. That
is not pedantry: with nothing left to draw the panel would sit frozen on the last
frame it painted, and the address of the page that could undo it is itself
printed by a screen.

<a id="next-bus"></a>

### 下一班車 / Next Bus

The next bus or minibus at up to four Hong Kong stops. Two large rows so it reads
from across a room, paging between them every 6 s when more than two are
configured.

| | |
|---|---|
| **Operators** | 九巴 KMB, 龍運 Long Win, 城巴 Citybus, 綠色小巴 green minibus |
| **The badge** | route number in the operator's own livery, sliding along a rail toward the stop as the bus approaches. Hollow means the time came from a timetable, not a live prediction |
| **The number** | minutes, floored — understating is the safe direction for something you are about to run for |

Add stops from the settings page: pick an operator, type a route number, then
choose a direction and a stop. Nothing is typed by hand — the picker resolves
route numbers to stop IDs in **your browser**, and only the resolved IDs are
stored on the device.

Two consequences of that worth knowing:

- **Route lookup needs internet access**, which the device's own setup AP does
  not have. The bus section is therefore hidden while the device is in AP mode;
  configured stops are kept untouched. Connect the clock to your WiFi and open
  its settings page from there to change them.
- **Stop names are drawn as images, baked by your browser.** TFT_eSPI has no CJK
  font, and embedding one costs 1.35 MB at a size too small to read across a room
  (2.04 MB at a size that isn't, which does not fit at all). So the ~18 fixed
  words the firmware needs are baked at build time, and *your* stop names are
  rasterised by your browser and uploaded. See [docs/decisions.md](docs/decisions.md).

The settings page previews each name **exactly as the panel will draw it** —
1-bit, at the real size — and lets you edit the text, because operator stop names
are written for a route database rather than a wall. `洪水橋(洪福邨)總站 (YL900)`
is one stop; `洪水橋` is what you actually read.

ETAs are stored as **absolute times**, not countdowns, and recomputed every
second. So a five-minute-old fetch still shows the right number, and pulling the
network out mid-scene leaves the display counting down correctly with only the
freshness indicator changing colour.

Today's high and low ride along on the same weather request via `&daily=`, so
they cost no extra fetch. `timezone=auto` on that URL is what makes the daily
bucket the local calendar day rather than a UTC one. They render as an amber ▲ /
blue ▼ row beneath the feels-like line, and are tracked by their own
`dailyValid` flag — if a response ever arrives without the `daily` block the row
is simply absent rather than showing a stray `0°`.

### Touch

| Gesture | Action |
|---|---|
| Tap | Advance to the next scene, and freeze auto-rotation for 45 s (60 s onto Next Bus — you tap there to watch a countdown, not to read a number once) |
| Hold 0.8–4 s | Pin the current scene until pressed again |
| Hold > 4 s | Re-run touch calibration |
| **Hold through power-on** | Force setup mode — see [Forcing setup mode](#forcing-setup-mode) |

Gestures are classified **on release**, so one press can only ever produce one
event — no "the long press fired, and then the tap fired too". The cost is no
feedback during a hold, so the status strip previews what releasing would do:
the pin glyph goes dim past 0.8 s and cyan past 4 s, and the strip refreshes
five times faster while a finger is down.

Both stopped states are shown, because a display that has stopped rotating is
otherwise indistinguishable from a crashed one: an **orange pin** while pinned,
**cyan pause bars** while frozen after a tap.

### Touch calibration is not a firmware constant

It is measured on the device and stored in NVS. First boot runs a four-corner
wizard; afterwards the stored mapping loads silently.

A resistive panel's raw range is a property of *that individual panel* and
drifts with temperature, so a constant that is right on this unit is wrong on
the next one — and re-flashing is a poor repair path for something hanging on a
wall. The press threshold is likewise derived from the panel's own measured
noise floor at wizard time rather than assumed.

Two properties matter because this is an appliance that reboots unattended:

- The wizard **times out after 60 s** of no touch and boots the clock anyway on
  the previous mapping. A power cut with nobody home can never strand the
  device on a setup screen.
- Nothing is written to NVS until a **confirmation tap lands within 25 px** of a
  centre target. A wrong mapping that survives a reboot is worse than none.

It prints the equivalent constants to serial on every success, if you ever want
them as a baked-in default.

---

## Stage 0 — hardware verification

Before any application code was written, a series of throwaway sketches
established every hardware fact the firmware depends on — the rule (plan §1)
being that each one produces a *value* the real code uses, rather than a vague
"it seems to work".

> The Stage 0 sketches are **not in this repository**; they were scaffolding.
> What they produced is, and it is the interesting part: the findings below, the
> pin map in `config/board.h`, and the full write-ups in
> [docs/stage0_results.md](docs/stage0_results.md) (2.8") and
> `docs/stage0_results_2432S024R.md` (2.4", including the touch autopsy).

| Test | Established | Status |
|---|---|---|
| 0.1 | driver, backlight pin, inversion, RGB order, rotation, SPI speed | ✅ |
| 0.2 | the XPT2046 answers at all — run display-free, so a panel fault couldn't mask a touch fault | ✅ |
| 0.2e | display + touch together: calibration, crosshair accuracy, hit-testing | ✅ |
| 0.3/0.4 | WiFi, local time with DST, heap baseline | ✅ (RSSI at final mounting spot outstanding) |
| 0.5 | one HTTPS fetch end-to-end + leak soak | fetch ✅, long soak incomplete |

### What the 2.8" board actually needed

Measured, not assumed — two of these contradict the usual advice:

| | 2.4" 2432S024R | 2.8" 2432S028R |
|---|---|---|
| Backlight | GPIO 27 | **GPIO 21** |
| Rotation (right way up) | 3 | **1** |
| Colour inversion | `TFT_INVERSION_ON` | `TFT_INVERSION_ON` — *same*, though the 2.8" is usually documented as needing it off |
| Touch | controller data line dead (hardware fault) | fully working |

Everything else — TFT pins, touch pins, SD, LDR, RGB LED, speaker — is identical
on both boards, which is why one `board.h` profile covers them.

---

## Two SPI buses — the one structural fact

- **Display → HSPI.** Requires `#define USE_HSPI_PORT` in TFT_eSPI's `User_Setup.h`.
- **Touch → VSPI.** Its own `SPIClass`, created in code.

Without `USE_HSPI_PORT`, TFT_eSPI takes VSPI as well. The display still works,
so the mistake surfaces much later as a touch panel that reads a constant zero.
The app checks this at **compile time** and refuses to build against the wrong
`User_Setup.h` — including a leftover 2.4" one — with the fix in the error
message.

The backlight is deliberately *not* configured in `User_Setup.h`, since the pin
is one of the things that differs between the boards. It is driven from code by
`cydBacklightOn()` in `board.h`, which also leaves room for LDR-driven dimming
later.

The touch driver is constructed with the **CS pin only**, never the IRQ pin.
Given an IRQ pin, `XPT2046_Touchscreen` reads once and then blocks every later
read until a falling edge fires — and if that interrupt never arrives, the panel
looks dead forever. Polling always works and costs nothing at our loop rate.
PENIRQ is still read as a diagnostic; it just isn't trusted to gate reads.

---

## Layout

```
.gitignore                belt-and-braces: no config.h can reappear
config/
  board.h                 THE pin map + board profile (2.8" and 2.4")
  pins.h                  deprecated alias for board.h
  User_Setup_2432S028R.h.template   TFT_eSPI config for the 2.8"   <- current
  User_Setup_2432S024R.h.template   TFT_eSPI config for the 2.4"
  User_Setup_2432S024R.h.backup     the exact library file that worked on the 2.4"
tools/
  sync_shared.sh          push board.h into every sketch folder
  build_all.sh            compile every sketch in one command
  flash.sh                compile + upload + serial monitor for one sketch
  gen_zh_labels.py        regenerate app/zh_labels.* from a system CJK font
  gen_vlw.py              regenerate app/ui_fonts.* -- antialiased Latin subsets
  fonts/                  DSEG7 Classic (SIL OFL), for the clock's digits
  preview_labels.py       contact sheet of the baked Chinese, read back from
                          the generated bytes -- the host-side eyeball check
  test_bus_parse.sh       host-side check of the ETA timestamp parser
  test_scenes.sh          host-side check of the scene rotation helpers
  test_vlw.py             parse app/ui_fonts.* the way TFT_eSPI does, and
                          render a sample -- a .vlw has no magic number, so a
                          malformed one is only visible as garbage on the panel
app/
  app.ino                 setup/loop: display, touch, WiFi, time, scene machine
  settings.*              runtime user config (WiFi/location/TZ/units) in NVS
  webconfig.*             settings web UI + captive portal, STA and AP modes
  touch.*                 XPT2046 input: polling, gestures, NVS calibration
  calibrate.*             on-screen touch calibration wizard
  scenes.*                scene machine + all five scenes, tap/pin handling
  status_strip.*          persistent bottom strip (time/WiFi/freshness/pin/dots)
  theme.h                 layout geometry + colour palette + shared `tft`
  app_data.h              global data model (plan §5.1)
  time_manager.*          SNTP + local-time helpers
  weather.*               Open-Meteo current + daily hi/lo + UV (15 min, backoff)
  airquality.*            Open-Meteo air-quality scheduler (30 min, backoff)
  bus.*                   HK bus/minibus ETAs: KMB/LWB, Citybus, GMB
  labels.*                1-bit Chinese text: baked vocabulary + LittleFS store
  zh_labels.*             generated -- the baked fixed vocabulary (do not edit)
  uitext.*                the string table, the font bank, and UiRun
  ui_fonts.*              generated -- antialiased Latin subsets (do not edit)
  bus_js.h                the route picker + label baker, served at /bus.js
  sun_moon.*              local NOAA sun/moon math (no API)
  units.h                 metric↔imperial display conversion
docs/
  stage0_results.md            the live gate for the 2.8"
  stage0_results_2432S024R.md  archived 2.4" results (incl. the touch autopsy)
  decisions.md                 scope/architecture decisions, newest first
```

---

## Shared headers and the Arduino include rule

The Arduino build copies a sketch folder to a temp dir before compiling, so a
sketch **cannot** `#include "../../config/board.h"`. Every sketch folder
therefore carries its own copy of `board.h`.

Those copies are **generated** — the canonical file is `config/board.h`. They
are tracked rather than ignored, since they hold no secrets and it means a fresh
clone builds without running the sync first. Edit `config/board.h`, then:

```bash
./tools/sync_shared.sh
```

`build_all.sh` and `flash.sh` run it for you, so in practice you edit `config/`
and rebuild.

There is no longer a `config.h` to sync: user settings live in NVS and are set
from the device's web UI, so nothing user-specific is compiled in at all.

---

## Toolchain

- **Arduino-ESP32** core 3.3.10 — board `esp32:esp32:esp32` ("ESP32 Dev Module")
- `TFT_eSPI` 2.5.43, `XPT2046_Touchscreen` 1.4, `ArduinoJson` 7.4.3
  (`WiFi` / `WiFiClientSecure` / `HTTPClient` / `Preferences` / `LittleFS` /
  `mbedtls` / `time.h` all ship with the core)
- **ArduinoJson 6 or 7 both work.** `weather.cpp` and `airquality.cpp` still use
  v6's `DynamicJsonDocument`, which v7 accepts as a deprecated alias; `bus.cpp`
  uses v7's `JsonDocument`. The mixture is deliberate rather than untidy — on v7
  the capacity argument to `DynamicJsonDocument` is ignored, so writing one in
  new code would state a memory bound that is not real.
- The `tools/*.sh` scripts use the `arduino-cli` bundled inside Arduino IDE 2
  when there isn't one on `PATH` — nothing extra to install.
- **Partition scheme: `huge_app`**, set as the default FQBN in both scripts.

### Why huge_app

With the web UI, the bus scene and the baked fonts the app is ~1.43 MB, which
does not fit the default scheme's 1.31 MB app slot at all. `huge_app` gives it
3 MB (45% full) by dropping the second OTA slot, which costs nothing here: OTA
is a documented non-goal (plan §1).

If you build from the Arduino IDE, set **Tools → Partition Scheme → Huge APP**
or the build will be tight and eventually won't fit.

Switching schemes is safe for your settings: `nvs` sits at `0x9000` size
`0x5000` in both, so stored WiFi credentials and touch calibration survive.

`huge_app` also carries an 896 KB filesystem partition that nothing used until
the bus scene's Chinese stop-name images needed somewhere to live.

ArduinoJson is **v6** here, so `DynamicJsonDocument` is correct and the plan's
v7 `JsonDocument` syntax is not. Two other plan snippets are also wrong for this
target and were fixed during Stage 0.5: `setBufferSizes()` is ESP8266-only, and
parsing straight from `https.getStream()` mis-reads chunked responses and yields
an all-zeros document — buffer with `getString()` first.

---

## Troubleshooting

**Screen stays dark.** The backlight is on GPIO 21 and must be driven HIGH; a
sketch that never calls `cydBacklightOn()` looks like a dead board.

**Colours are the exact complement of what you expect** (red→cyan,
green→magenta, blue→yellow) — that's inversion, toggle `TFT_INVERSION_ON`. If
only red and blue swap while green stays green, that's RGB order instead.

**Touch reads a constant 0.** Check `USE_HSPI_PORT` is defined — without it the
display takes the bus touch needs. If it is defined and touch is still dead, the
controller's data line may be faulty: that's what happened to the 2.4" unit
(`docs/stage0_results_2432S024R.md` has the autopsy), and it isn't fixable in
software.

**Touch is offset.** Hold the panel for 4 seconds to re-run calibration.

**Upload fails or resets mid-transfer.** The 2.8" board's micro-USB and USB-C
connectors are wired to the same CH340 — **use one at a time**. Otherwise it's
usually a charge-only cable, or the Arduino IDE still holding the serial port.

**Upload dies immediately after `Changing baud rate to 921600`**, with
`Invalid head of packet (0xE0)`. That's the CH340 failing the switch to the
default upload speed, not a bad image — retry at 115200. See
[Upload speed](#upload-speed) for the command, and note it has to carry the
partition scheme too.

**The build won't fit.** You overrode `FQBN`
without keeping `PartitionScheme=huge_app`. Any override has to include it — see
[Why huge_app](#why-huge_app).

**Bus stop names show in English instead of Chinese.** The image was never
baked, or was erased. Open the settings page and press **Save & restart** — your
browser re-bakes and re-uploads them; you don't have to pick the routes again.
This is the designed fallback, not a fault: the text lives in NVS in both
languages, so the row is never blank.

**The bus screen says 無服務 (no service) when a bus is definitely due.** Most
likely the direction is wrong — a stop served both ways looks identical in the
picker. Re-pick the other direction. If a slot shows nothing for six hours
straight it changes to 檢查車站 ("check the stop") on its own, because a stale
stop ID and a quiet night are indistinguishable in any single response.

**The bus section is missing from the settings page.** You are on the device's
own `CYD-Setup-XXXX` network, which has no route to the internet, so route
lookup cannot work. Configured stops are kept — connect the clock to your WiFi
and open its settings page from there.

**A screen never appears in the rotation.** Check its tickbox under **Screens**
on the settings page.

**The bus screen says 未校時 ("clock not set").** NTP hasn't synced, so there is
no "now" to count down from. It resolves itself once the network is up.

**No serial port appears at all.** Charge-only cable, or a missing CH340 driver.
