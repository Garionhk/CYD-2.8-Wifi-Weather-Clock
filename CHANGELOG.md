# Changelog

## Unreleased

### Added

- **A display colour setting: Normal, or "My colours are inverted".** Boards
  sold as ESP32-2432S028R do not all drive the panel with the same inversion
  polarity — same chip, same silkscreen, same pin map, opposite controller. On
  one of the other ones every colour came out as its exact complement
  (red→cyan, green→magenta, blue→yellow), and the only remedy was to edit
  `CYD_TFT_INVERT` in `config/board.h` and rebuild, which is not something you
  can ask of somebody who flashed a published `.bin`.
  - `CYD_TFT_INVERT` remains the default, so a board that was already correct is
    unaffected and upgrading cannot turn a working panel inside out. A device
    provisioned before this setting existed has no stored value, and the default
    is what it gets.
  - The toggle is in the **setup portal** as well as the settings page. It has
    to be: someone holding the other panel has wrong colours on the first screen
    they ever see, before the device has joined any network.
  - The options name what the user is looking at rather than what the register
    does. "Inversion on/off" is unanswerable for somebody who does not know how
    their board is wired; "my colours are inverted" is not.

### Changed

- **`settings_begin()` now runs before `tft.init()`.** Colour inversion is a
  setting, and it has to be known before the first pixel — including the pixels
  of the touch calibration wizard, which on a new device runs before anything
  else. Nothing in `settings_begin()` touches the display. A side effect is that
  boot-time calibration is now drawn in the saved language rather than always in
  English.
- Restored the executable bit on `tools/build_all.sh`, `tools/flash.sh` and
  `tools/sync_shared.sh`. All three are documented as `./tools/...` and none of
  them would run from a fresh clone.


## v2.2.0 — English or 繁體中文, and text that stopped being jagged — 2026-08-24

Adds a language setting that covers every surface the device has — all five
screens, the status strip, the boot messages, the setup portal, the touch
wizard and the settings page itself — and replaces the built-in bitmap fonts
with antialiased ones everywhere Latin is drawn. The clock keeps its
seven-segment face, now with smooth edges.

Chinese stays 1-bit, deliberately. An antialiased version was built and
withdrawn the same day: it would have made Chinese the only thing on the panel
rendered through a blit path of our own, for glyphs that are bitmap art either
way. `docs/decisions.md` has the reasoning, including the byte-order trap that
made it visibly wrong on the panel.

Flashing `cyd-clock-weather-v2.2.0-4mb.bin` at `0x0` is a **factory reset** —
it erases stored settings and touch calibration along with everything else. To
update an existing device and keep them, upload the application only with
`./tools/flash.sh app`. See `flash.md`.

### Added

- **A language setting: English or 繁體中文.** A radio pair on the settings page,
  stored in NVS beside Units; the device restarts into it like every other
  setting. It covers the whole panel — all five scenes, the status strip, the
  boot messages, the setup portal and the touch calibration wizard — and the
  settings page itself, including the route picker.
  - Bus stop names stay Chinese in both, because that is what is written on the
    stop.
  - An unprovisioned device shows English: the setup portal runs before there is
    a stored preference to read, and the person seeing that screen has not
    chosen one yet.
  - The Chinese vocabulary grew from 18 baked strings to 93 (~14 KB). Anything
    with a number in it — the date, the golden-hour countdown, "72% lit" — is
    laid out part by part, because the two languages order the pieces
    differently: `in 2h 05m` against `2小時05分後`.

- **Every configured stop is baked on save**, not only the ones re-picked in
  that page session. A stop added through "Manual entry" previously never got a
  Chinese bitmap at all, and a slot whose bitmap had been lost could not be
  recovered without drilling the whole route down again — a plain Save uploaded
  nothing. It also refreshes a bitmap whose text was edited by hand.

- **Anti-aliased text everywhere.**
  - **Latin** now uses `.vlw` smooth fonts. `SMOOTH_FONT` had been enabled in
    the `User_Setup` templates since the beginning and nothing had ever used it.
    Four subsets are baked by `tools/gen_vlw.py`, which solves for the pixel
    size that reproduces each built-in font's height rather than guessing, so
    every existing layout constant still lands where it did.
  - **The clock keeps its LCD look.** Font 8 was seven-segment, so the big
    digits are baked from DSEG7 Classic Bold (SIL OFL, vendored in
    `tools/fonts/`) rather than the proportional face used everywhere else.
  - **Chinese is deliberately not antialiased.** It stays 1-bit, drawn by
    `TFT_eSPI::drawBitmap` as it always has been. A 4-bpp alpha version was
    tried and withdrawn: it would have made Chinese the only thing on the panel
    rendered through a blit path of our own, for glyphs that are bitmap art
    either way. See `docs/decisions.md`.

### Changed

- **The clock's digit sprite is allocated once at boot** instead of on every
  scene entry. It was a 10 KB *contiguous* allocation several hundred times a
  day, which is the fragmentation `labels.cpp` has always been written to avoid.
- The degree sign is now a real glyph rather than two hand-drawn circles.
- `goldenHourStatus()` returns numbers and `moonPhaseName()` returns a phase
  enum, so `sun_moon.cpp` no longer contains any user-visible text.

### Fixed

- Removed `placeholder()`, dead since the last scene was implemented.

### Upgrading

Nothing to do. Baked stop-name bitmaps are unchanged from v2.1.0, and no setting
is lost.

If you flashed the short-lived build that stored them as 4-bpp alpha, the first
boot converts them back byte-for-byte and removes `/l4`. Either way the bus
scene never falls back to English on account of the upgrade.

## v2.1.0 — 下一班車, and a settings page for everything — 2026-08-10

Adds a fifth scene showing the next Hong Kong bus or minibus, and moves the
last of the compile-time configuration onto a page the device serves itself.
Nothing user-specific is baked into the binary any more, so this image can be
handed to anyone.

Flashing `cyd-clock-weather-v2.1.0-4mb.bin` at `0x0` is a **factory reset** —
it erases stored settings and touch calibration along with everything else. To
update an existing device and keep them, upload the application only with
`./tools/flash.sh app`. See `flash.md`.

### Added

- **A fifth scene: 下一班車 / Next Bus.** The next Hong Kong bus or minibus at up
  to four stops, in Traditional Chinese, with a coloured route badge that
  slides toward the stop as the vehicle approaches. Two large rows so it reads
  from across a room; four stops fill two pages, alternating every 6 s so an
  unattended rotation shows both within one 12 s dwell.
  Tapping to it holds the rotation for 60 s instead of 45.
  - **KMB/LWB, Citybus and green minibus**, which are three genuinely different
    API models rather than one with a parameter. Direction filtering is done
    client-side for the first two — a stop served both ways returns rows for
    both — and the serial log prints the drop count, because a broken filter is
    invisible at a one-direction stop.
  - **ETAs are stored as absolute epoch times**, and the countdown is recomputed
    every second. So a five-minute-old fetch still shows the right number, and
    unplugging the router leaves the display counting down correctly with only
    the header's freshness changing colour.
  - **Chinese without a CJK font.** TFT_eSPI has none, and an embedded one costs
    1.35 MB at a size too small to read (2.04 MB at a size that isn't, which
    does not fit). Instead the ~18 fixed words are baked at build time by
    `tools/gen_zh_labels.py` (2.5 KB), and your stop names are baked by your
    browser and stored on LittleFS. See `docs/decisions.md`.
  - **Route lookup happens in the browser**, so only resolved stop ids reach the
    device. Each slot is a plain text field with the picker layered on top, not
    the other way round, so the section still works if the lookup is unavailable
    and "Manual entry" is a permanent escape hatch rather than a debug tool.
  - The label preview on the settings page shows the **actual 1-bit result** at
    real size, and the text is editable: operator stop names are written for a
    route database, not a wall.

- **Screens can be switched off, and each one's time on screen adjusted**, from
  a new *Screens* section on the settings page. Untick a screen and the rotation
  skips it and it loses its dot in the status bar; its timing is remembered for
  when you tick it back on. At least one has to stay on — the form refuses
  otherwise, because a panel with no screen left to draw would sit frozen
  showing an address it could no longer print.

### Fixed

- **Saving from the setup AP no longer risks erasing your bus stops.** The bus
  section is hidden in AP mode, since the device *is* the access point and has
  no route to the internet, so every route lookup would fail. The values are
  still carried as hidden fields, and `handleSave` now distinguishes a field
  that was *absent* from one the user *cleared* — previously an absent field
  read as an empty string and cleared the slot.
- **The settings page no longer discards what you typed when a save is
  rejected.** A validation error repopulated every field from the last *saved*
  values instead of the submitted ones. (The password field is deliberately
  excluded — it renders as a placeholder, and echoing it would put the WiFi
  password in the page source.)
- **`flash.md` was wrong about what a reflash keeps.** `app.ino.merged.bin` is a
  full 4 MB image whose padding covers the NVS and filesystem partitions, so
  flashing it at `0x0` is a factory reset — settings, touch calibration and all.
  `./tools/flash.sh app` uploads only the application and preserves them. The
  two are now documented as the different operations they are.
- **Golden hour collided with the moon phase name on the Sun & Moon screen.**
  It sat left-aligned at x=170 while "Waning Crescent" runs to x=173 in Font 2,
  across rows the golden-hour value also occupied — so the longest phase names
  overlapped it outright and the rest sat flush against it. The whole lower-right
  quadrant was empty, so the pair now centres there instead, and the value moves
  up to Font 4 since there is room for it.
- Scene-position dots in the status strip were pitched 16 px apart, which left
  the fifth dot 4 px from the pause glyph and would have overlapped at six. Now
  13 px, with 16 px of clearance.

- **Settings are configured from a web page, not `config.h`.** WiFi, location,
  timezone and units now live in NVS and are set from a page the device serves
  itself. Nothing user-specific is compiled in any more, so **the built binary
  can be shared** — `strings app.bin` used to print the author's WiFi password.
  - The server runs in both modes: on the LAN IP when connected, and as a
    `CYD-Setup-XXXX` SoftAP with a DNS captive portal when it can't connect.
  - **The clock screen prints the settings address** in a dim footer under the
    date. A settings page nobody can find is not a feature.
  - A failed connection stays non-fatal, as it always has been. The setup AP
    comes up *alongside* a working offline clock (`WIFI_AP_STA`) and is dropped
    automatically once the real network returns.
  - This covers WiFi lost **at runtime**, not just at boot: after two minutes
    without a connection the setup AP is raised on its own, so replacing a
    router or changing its password never requires a power cycle to reach the
    settings page. The delay stops an ordinary router reboot from flapping it.
  - **Holding a finger on the panel through boot forces setup** — the recovery
    path for a device joined to a network that no longer exists.
  - The first-run portal deliberately has no timeout, unlike the calibration
    wizard. See `docs/decisions.md` for why that is not a violation of the
    appliance rule.
  - The page never sends a stored password back to the browser; leaving the
    field blank keeps the current one.

### Changed

- **Partition scheme is now `huge_app`**, set as the default `FQBN` in
  `build_all.sh` and `flash.sh`. The web UI pushes the app to ~1.22 MB against
  the default scheme's 1.31 MB slot (93% full); `huge_app` gives it 3 MB (38%)
  by dropping the second OTA slot, which is already a non-goal. `nvs` sits at the
  same offset in both schemes, so stored settings and touch calibration survive
  the switch. **An `FQBN` override must now carry `PartitionScheme=huge_app`.**
- `units.h` reads the units setting at runtime instead of folding a compile-time
  constant. The branch is irrelevant next to the SPI writes each call feeds.
- `tools/sync_shared.sh` no longer syncs `config.h` — only `board.h`.

### Removed

- `config.h`, `config/config.example.h` and the generated per-sketch copies.

### Security

- **`config.h` is no longer tracked by git.** The repo had no `.gitignore` at
  all, despite the README and `config.example.h` both claiming `config.h` was
  ignored — so all four copies (`config/`, `app/`, and the two network Stage 0
  sketches) were committed with real WiFi credentials, and pushed. Added
  `.gitignore` and untracked them; the files stay on disk and
  `tools/sync_shared.sh` still regenerates the sketch-folder copies.
  - **This does not un-publish anything.** Credentials that reached the
    repository must be treated as compromised and the WiFi password rotated;
    purging git history is a separate step (README → *If you already committed
    it*).
  - Redacted the SSID from `docs/stage0_results_2432S024R.md`, the one other
    tracked file that named it.

### Added

- **Today's high and low on the Weather scene**, as an amber ▲ / blue ▼ row
  beneath the feels-like line. The current temperature alone doesn't say whether
  18° is on the way up or on the way down.
  - Comes from the same 15-minute Open-Meteo fetch via `&daily=` — no extra
    request, no new scheduler. JSON filter and document buffers grew to 512 /
    1536 B to fit the extra block.
  - `timezone=auto` was already on the URL, which is what aligns the daily
    bucket to the local calendar day rather than UTC.
  - Tracked by its own `dailyValid` flag, so the row is simply absent — rather
    than showing a stray `0°` — if a response ever arrives without the block.

## v2.0.0 — Touch, on the 2.8" board

Retargets the project from the 2.4" **ESP32-2432S024R** to the 2.8"
**ESP32-2432S028R** (micro-USB + USB-C), and brings **touch input back into
scope**. Touch was cut from v1 for one reason only: that specific 2.4" unit had
a physically dead touch data line. On a working panel the feature returns —
tap to advance, hold to pin, hold longer to recalibrate.

This is a major version because the hardware target changed and the build
requires manual steps to migrate. Both boards remain supported from one source
tree via `CYD_BOARD` in `config/board.h`.

### Added

- **Touch input** (`app/touch.*`) — polling-mode XPT2046 on VSPI, with gestures
  classified on release so a single press can only ever produce one event:
  - **tap** → next scene, and freeze auto-rotation for 45 s
  - **hold 0.8–4 s** → pin the current scene until pressed again
  - **hold > 4 s** → re-run calibration
- **On-device calibration wizard** (`app/calibrate.*`) with the mapping stored
  in **NVS**, not compiled in. A resistive panel's raw range belongs to that
  individual panel and drifts with temperature, so a firmware constant is
  guaranteed wrong on the next unit — and re-flashing is a poor repair path for
  something hanging on a wall. First boot runs the wizard; afterwards the stored
  mapping loads silently. It prints the equivalent constants to serial each time,
  if you ever want a baked-in default.
  - Times out after 60 s of no touch and boots the clock anyway, so an
    unattended reboot can never strand the device on a setup screen.
  - Writes nothing to NVS until a confirmation tap lands within 25 px of a
    centre target — a wrong mapping that survives a reboot is worse than none.
  - Derives the press threshold from the panel's measured noise floor rather
    than assuming one.
- **Touch state is visible in the status strip** — an orange pin while pinned,
  cyan pause bars while frozen. A display that has stopped rotating is otherwise
  indistinguishable from a crashed one. The pin glyph also previews what a
  release would do (dim past 0.8 s, cyan past 4 s), and the strip refreshes five
  times faster while a finger is down.
- **`config/board.h`** — one board profile carrying pins, panel geometry, board
  identity, and the `cydBacklightOn()` / `cydRgbLedOff()` / `cydPrintBanner()`
  helpers, selected by `CYD_BOARD`.
- **`tools/`** — `sync_shared.sh` (push shared headers into every sketch folder),
  `build_all.sh` (compile everything in one command), `flash.sh` (compile +
  upload + monitor, with board auto-detection). They use the `arduino-cli`
  bundled inside Arduino IDE 2 when there isn't one on `PATH`.
- **`stage0/s02e_touch_calibrate`** — the integration test that gates all of
  this: display and touch driven together, a four-corner calibration that
  *measures* the axis mapping instead of guessing it, then a crosshair and
  hit-tested buttons.
- **Compile-time hardware guards** — `s01`, `s02e` and `app` refuse to build
  against the wrong `User_Setup.h`, comparing its pins and SPI port against
  `board.h` and failing with the fix in the message.
- `CHANGELOG.md`, a troubleshooting section in the README, and
  `docs/stage0_results_2432S024R.md` archiving the 2.4" bring-up.

### Fixed

- **The display was silently running on VSPI** — the bus the touch controller
  needs. Nothing broke while touch was dead, and it would have broken the moment
  touch came back. `USE_HSPI_PORT` is now required and enforced at compile time.
- **Sprite leak on scene re-entry.** `clockEnter()` could run without a matching
  `clockExit()` (the calibration wizard restarts the scene machine), leaking
  ~10 KB per pass. It now frees any existing buffer first.
- **`app/config.h` was not git-ignored**, unlike the other copies of the same
  credentials file.
- Three plan snippets that are wrong for this target, carried over from Stage
  0.5: ArduinoJson is **v6** here (`DynamicJsonDocument`, not v7's
  `JsonDocument`), `setBufferSizes()` is ESP8266-only, and parsing straight from
  `https.getStream()` mis-reads chunked responses and yields an all-zeros
  document.

### Changed

- **Rotation is now per-board**: `1` on the 2.8" (the 2.4" reads right-way-up at
  `3`). Both give the same 320×240 landscape view.
- **Backlight is driven from code**, not by TFT_eSPI, since the pin is one of the
  two things that differ between the boards (GPIO 21 vs 27). This also leaves
  room for LDR-driven dimming later.
- **The scene machine takes input.** Dwell timers now yield to a pin flag and a
  freeze deadline; unpinning restarts the dwell rather than resuming a timer that
  may have expired minutes ago.
- **Generated header copies are git-ignored.** `board.h` and `config.h` copies in
  sketch folders are build inputs produced by `sync_shared.sh`; the canonical
  files live in `config/`. A fresh clone needs one `./tools/sync_shared.sh`, which
  `build_all.sh` and `flash.sh` run for you.
- `config/pins.h` is now a thin deprecated alias over `board.h`.
- `config/User_Setup.h.template` split into `User_Setup_2432S028R.h.template`
  (current) and `User_Setup_2432S024R.h.template`, plus a verbatim
  `.backup` of the library file that worked on the 2.4" board.

### Hardware findings

Measured on this unit, and two of them contradict the usual advice for this
board — trust the test, not the internet:

| | 2.4" 2432S024R | 2.8" 2432S028R |
|---|---|---|
| Backlight | GPIO 27 | **GPIO 21** |
| Rotation | 3 | **1** |
| Colour inversion | `TFT_INVERSION_ON` | `TFT_INVERSION_ON` — *the same*, though the 2.8" is usually documented as needing it off |
| Touch | data line dead (hardware fault) | fully working, PENIRQ **and** data |

### Upgrading from v1.x

1. Replace the TFT_eSPI library config — the old one will now fail the build
   rather than produce a subtly broken device:
   ```
   cp config/User_Setup_2432S028R.h.template ~/Documents/Arduino/libraries/TFT_eSPI/User_Setup.h
   ```
2. Run `./tools/sync_shared.sh` (or just build with `./tools/build_all.sh`).
3. Flash `app`. First boot runs the touch wizard; press the four corner targets
   and then the centre one to confirm.

### Known limitations

- **The application has been compile-verified but not yet run on hardware.**
  Every Stage 0 test that gates it has passed on the board, and the app reuses
  the fetch and sun/moon logic that Stage 0.5 exercised, but the scenes, the
  gesture handling and the calibration wizard have not been observed running on
  the device. Treat this as a release candidate until they have.
- Stage 0 is not fully closed: **RSSI at the final mounting location** is not
  recorded, and the **60-fetch leak soak** was interrupted around fetch #2
  (drift was −220 B, i.e. fine, but two fetches cannot distinguish TLS cache
  settling from a slow leak).
- The **72-hour soak** (plan §11 step 6) has not been run.
- Non-goals unchanged from v1: no SD slideshow, audio, OTA, RGB LED use, or
  LDR/brightness control.
