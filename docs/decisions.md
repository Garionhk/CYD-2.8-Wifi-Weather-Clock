# Project decisions

Running log of choices that change scope or architecture. Newest first.

## 2026-08-06 — All user settings move to NVS and a web UI; config.h is deleted

**Context.** WiFi credentials, latitude/longitude, timezone and units were
compile-time `#define`s in `config.h`, included by five translation units. Three
things followed from that, and the third is what forced the change:

1. Changing any setting meant a rebuild and a reflash.
2. The binary contained them. `strings app.bin` printed the author's WiFi
   password, so the firmware could never be shared with anyone.
3. It caused a real credential leak — see the Security entry in `CHANGELOG.md`.

**Decision.** `config.h` is gone. Settings live in NVS (namespace `cydcfg`,
`app/settings.*`) and are set from a web page served by the device
(`app/webconfig.*`).

The server runs in **both** modes, not just during setup:

- **STA connected** — settings page on the LAN IP.
- **WiFi unavailable** — SoftAP `CYD-Setup-XXXX` plus a DNS captive portal on
  `192.168.4.1`.

This is the same argument already accepted for touch calibration one entry down:
re-flashing to change a setting is a poor repair path for something hanging on a
wall. It generalises from "this panel's constants are wrong on the next unit" to
"this owner's settings are wrong for the next owner".

**Why the clock screen prints an address.** A settings page nobody can find is
not a feature. Without it you need a serial monitor or the router's client list,
so the clock carries a dim footer naming the network and address.

**Where this deliberately breaks the appliance rule.** The rule below says the
device must never wait on a person who is not there. The first-run setup portal
has **no timeout**, and that is not an oversight. Calibration times out because
it has a useful fallback — the previous mapping. An unprovisioned device has
none: no credentials means no WiFi, no NTP, and therefore no clock. There is
nothing to fall back *to*, so a screen explaining how to fix it is the most
useful state available rather than a trap.

A device that has been configured and merely can't reach the network is a
different case, and keeps the old behaviour: the clock runs offline exactly as
before, with the setup AP raised alongside it (`WIFI_AP_STA`) so the credentials
can be corrected. The AP is dropped automatically when the real network returns.

**Recovery path.** Holding a finger on the panel through boot forces setup. That
covers the one failure the web UI cannot fix itself — the device is joined to a
network that no longer exists, so nothing can reach it.

**Consequences.**
- The build needs the `huge_app` partition scheme: with the web UI the app is
  ~1.22 MB against the default scheme's 1.31 MB slot (93%). `huge_app` gives it
  3 MB, at the cost of the second OTA slot — already a non-goal. `nvs` is at the
  same offset in both schemes, so switching preserves stored settings.
- Saving restarts the device rather than applying live. WiFi, timezone, location
  and units each need a different refresh path; a ~3 s restart cannot leave the
  device half-configured.
- The settings page has no authentication. For a wall clock on a home LAN that
  is an accepted trade, and it never reveals the stored password — only accepts
  a new one.

## 2026-07-22 — Touch calibration lives in NVS, not in the firmware

**Context.** Stage 0.2e produces four raw-to-pixel constants. The obvious next
step is to paste them into a header. On this project that step failed in the
most ordinary way possible: the test passed, the numbers scrolled past, and
nobody kept them.

**Decision.** The application does not hard-code calibration. It stores it in
NVS and measures it on the device:

- First boot with nothing stored runs the on-screen wizard (`calibrate.*`,
  ported from `s02e`), and saves the result.
- A press held longer than 4 s re-runs it at any time.
- Nothing is written until a confirmation tap lands within 25 px of a centre
  target. A wrong mapping in NVS is worse than none, because it survives a
  reboot.

**Why this is better than the constant, not just easier.** A resistive panel's
raw range is a property of that individual panel — a constant that is correct
here is wrong on the next unit, and drifts with temperature. Re-flashing to fix
touch is a bad repair path for something hanging on a wall. The press threshold
is likewise derived from the panel's own measured noise floor at wizard time
rather than assumed.

**The failure mode this had to avoid.** This is an appliance: it reboots after
a power cut with nobody in the room. The wizard therefore times out after 60 s
of no touch and boots the clock anyway on the previous mapping, and gives up
after three failed confirmations rather than looping. It can never trap the
device on a screen waiting for a person who is not there.

**Consequence.** `s02e`'s printed constants are now a convenience, not a
dependency — the wizard prints the equivalent block to serial each time it
succeeds, if they are ever wanted for a baked-in default.

## 2026-07-22 — Move to the 2.8" ESP32-2432S028R; touch is back in scope

**Context.** A second board is now in hand: a 2.8" CYD with both micro-USB and
USB-C, i.e. an **ESP32-2432S028R**. Touch was dropped from v1 only because the
2.4" unit's XPT2046 data line was physically dead (entry below). That reason
does not carry over to a different board, so the decision below is reopened
rather than inherited.

**Decision.** Retarget the project at the 2.8" board and re-run **all** of
Stage 0 on it, with touch as part of the gate. The application is rewritten for
this board only after every Stage 0 row passes — same rule as the first time
round, for the same reason (plan §1, §3): every Stage 0 test produces a value
the real code depends on.

**What actually differs between the two boards.** Measured in Stage 0.1, only
two things:

| | 2.4" 2432S024R | 2.8" 2432S028R |
|---|---|---|
| Backlight | GPIO 27 | GPIO 21 |
| Rotation (right way up) | `3` | `1` |
| Inversion | `TFT_INVERSION_ON` | `TFT_INVERSION_ON` — *same*, though the 2.8" is usually documented as needing it off |

TFT pins, touch pins, SD, LDR, RGB LED and speaker are identical. That is small
enough that forking the code base would be silly, so both boards live behind one
`config/board.h` profile selected by `CYD_BOARD`.

**Structural changes that came with it.**
- `config/board.h` replaces `config/pins.h` as the single source of truth for
  pins, panel geometry and board identity. `pins.h` stays as a thin alias so
  nothing that already included it breaks.
- The display is pinned to **HSPI** (`#define USE_HSPI_PORT` in `User_Setup.h`)
  so the touch controller keeps **VSPI** to itself. Previously TFT_eSPI was
  silently taking VSPI. Nothing broke while touch was dead; it would have
  broken the moment touch came back. `s01` and `s02e` now refuse to compile if
  that define is missing, so the mistake cannot reach the board.
- The backlight is driven from code (`cydBacklightOn()`), not by TFT_eSPI, since
  the pin is the thing that differs between the boards.
- New `stage0/s02e_touch_calibrate` — display and touch running together, a
  guided 4-corner calibration that works out the axis mapping by itself, and a
  crosshair plus hit-tested buttons. This is the test that gates the rewrite:
  raw readings alone don't prove the panel is usable.
- `tools/` — `sync_shared.sh` (push shared headers into every sketch folder),
  `build_all.sh` (compile everything in one command), `flash.sh`
  (compile + upload + monitor).

**Consequences.** Tap-to-advance, long-press-to-pin and the pin glyph — all
struck from v1 in the entry below — come back into the plan for the rewrite,
provided 0.2e passes on this board.

## 2026-07-22 — Drop touch from v1; scenes auto-rotate only

**Context.** Stage 0.2 found the touch controller's SPI data line (DOUT/GPIO39)
non-functional on this board while its PENIRQ (GPIO36) works — a hardware fault,
not fixable in software. Full diagnosis in `docs/stage0_results.md`.

**Decision.** Ship v1 with **no touch input**. The four scenes cycle purely on
their dwell timers, 24/7. This removes from plan §6:

- Tap → advance + 45 s auto-rotation freeze
- Long-press (>800 ms) → pin current scene
- The pin glyph in the status strip

Everything else in the plan is unchanged. Auto-rotation was always the core
behaviour (plan §1, §6); touch was a nice-to-have layered on top, and v1
explicitly lists touch-adjacent extras as non-goals.

**Consequences for the build (plan §11).**
- Scene machine (step 2) becomes simpler: a dwell-timer state machine with no
  input handling. No debounce, no freeze/pin state.
- The `Scene` struct and array are unchanged; only the tick loop that would have
  read touch is omitted.
- Status strip drops the pin glyph; scene-position dots and freshness dot stay.

**Reversibility.** Touch is isolated behind one input module. If a known-good
board is used later, restoring it is: re-run `stage0/s02*` to get calibration,
then re-add the tap/long-press handling to the scene loop. The diagnostic
sketches are kept in `stage0/` for exactly this.
