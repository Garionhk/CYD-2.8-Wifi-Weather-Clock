# Changelog

## Unreleased

### Added

- **A fifth scene: 下一班車 / Next Bus.** The next Hong Kong bus or minibus at up
  to three stops, in Traditional Chinese, with a coloured route badge that
  slides toward the stop as the vehicle approaches. Two large rows so it reads
  from across a room; three configured stops page between two screens every 8 s.
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
    device. The settings page still works with the lookup dead — each slot is a
    plain text field with the picker layered on top, which is what makes saving
    from the offline setup AP preserve your stops rather than erase them.
  - The label preview on the settings page shows the **actual 1-bit result** at
    real size, and the text is editable: operator stop names are written for a
    route database, not a wall.

### Fixed

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
