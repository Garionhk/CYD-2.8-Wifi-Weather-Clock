# Project decisions

Running log of choices that change scope or architecture. Newest first.

## 2026-08-24 — The panel speaks two languages, and antialiases both

**Context.** Two requests that turned out to be one problem: a Chinese/English
toggle across the whole system, and antialiased text. They meet in the same
place, because the reason the panel could not do either was the same — every
glyph on it was one bit deep, and only the bus scene had any Chinese at all.

**Three pieces.**

**1. Chinese stays 1 bpp.** It was changed to 4-bpp alpha and changed back the
same day, which is worth recording because the reasoning outlives the reversal.

The case for alpha was real: a hard `alpha > 128` threshold at 18 px either
fattens strokes into a blob or drops the interior out of dense glyphs (灣, 觀),
and no single value avoids both. Sixteen levels ends that trade for 4x the flash
— ~53 KB against 1.5 MB free, which is nothing.

It reached the panel in the wrong colours, and the cause is a trap worth naming:
**`pushImage` sends a `uint16_t` array as raw little-endian bytes**, while the
ILI9341 wants each RGB565 pixel high byte first. It needs `setSwapBytes(true)`,
which the blitter did not have. Every other draw call in this project takes its
colours as arguments and sorts out byte order internally, so this was the only
place the trap existed — and, accordingly, the only thing on screen that broke.

That is a one-line fix, so it is not why the format was withdrawn. It was
withdrawn for the shape it left behind: Chinese would have been the **only**
thing on the display going through a hand-written blit path. The Latin is
antialiased by TFT_eSPI itself and costs us no draw code at all, and the panel's
Chinese is bitmap art either way. Owning a blitter, a second on-disk format, a
migration in each direction and a byte-order footgun — to smooth the edges of
glyphs that are already pictures — is a bad trade at any flash budget.

So `blit()` is a single `drawBitmap` call again. A device that took the alpha
build converts `/l4` back to `/l` on boot, losslessly: those nibbles are only
ever `0x0` or `0xF`, and the round trip is byte-identical at every width.

**What did survive from that day** is the fix underneath it. The browser now
bakes **every** slot that has Chinese text on save, not just the ones re-picked
in that page session. The old behaviour meant a stop entered through "Manual
entry" never got a bitmap at all, and a lost bitmap could only be restored by
drilling the entire route down again — a plain Save uploaded nothing, so the
obvious remedy was a button that did nothing. Uploads run two at a time through
the `mapLimit` helper the stop lookups already use.

**2. Latin got .vlw smooth fonts.** `SMOOTH_FONT` had been set in our
`User_Setup` templates since the beginning and nothing ever used it.
`tools/gen_vlw.py` bakes four subsets from Helvetica Neue — subsets, not whole
fonts, because a full ASCII set at 75 px is ~350 KB while the eleven characters
the clock draws are ~20 KB. The pixel size is *solved for*, not guessed: every
`MC`/`ML`/`MR` datum in this project centres against `fontHeight()`, so the
generator walks the range and takes the largest size that hits the built-in
font's height exactly.

Digit-only subsets are solved differently. Matching the *box* would have shrunk
the clock — Font 8's digits fill all 75 px, Helvetica's inside a 75 px box are
~53 px of ink — so those match digit height instead and write `ascent = that
height, descent = 0`, which makes `fontHeight()` equal the ink and lets
`drawGlyph` centre them with no fudge factor anywhere.

**The clock kept its LCD look.** Font 8 was seven-segment and a grotesque at
75 px is a different object on a wall, so the XL subset is baked from **DSEG7
Classic Bold**, vendored in `tools/fonts/` with its OFL licence. A build that
depends on a font the developer happens to have installed is not reproducible,
which is also why the `seg7` face has no fallback: if the file is missing the
build stops rather than quietly rendering the clock in Helvetica.

**The digit sprite is now permanent.** It used to be created in `clockEnter` and
freed in `clockExit` — a 10 KB *contiguous* allocation perhaps four hundred
times a day. `labels.cpp` is written entirely around not doing that to this
sprite; the sprite was doing it to itself. It now lives for the life of the
device, which is what makes it safe for `font_use()` to swap smooth-font metrics
on the same heap: only one font can be loaded at a time and each load mallocs
seven arrays, so the swap is real churn, and nothing else wants a block that
size once it is claimed at boot.

**3. One dispatch point.** `app/uitext.{h,cpp}` holds a `UiText` id per string,
carrying both an English literal with its font and the baked Chinese that
replaces it. The font is in the table rather than at the call site because the
two halves must be chosen together — the Chinese is baked at a fixed pixel size
and the English has to sit in the same slot. A `static_assert` ties the table to
the enum.

Text with a runtime value in it does not go through the table: `UiRun` measures
a left-to-right run of labels and Latin segments before drawing it, because the
two languages order the parts differently — `in 2h 05m` against `2小時05分後` —
and no format string expresses that. `sun_moon.cpp` was changed to return
numbers and a phase enum rather than formatted text, which keeps the almanac
maths free of any dependency on the display.

**What the toggle does not touch.** Bus stop names stay Chinese either way:
they are what is written on the stop.

**The settings page was nearly free.** The browser has real fonts and
`PAGE_HEAD` has declared `<meta charset=utf-8>` since the beginning, so
translating it is picking a different literal — an `L(en, zh)` helper returning
a flash string. `sendPage` writes `window.__zh` into the head before `/bus.js`
is fetched, so the route picker comes up in the same language as the form.

**Cost.** App 1.32 MB → 1.47 MB of 3 MB (42% → 46%).

**What this does not solve.** Live Chinese from the bus APIs is still
unrenderable, and `remarkLabel` still matches on the operators' English field to
pick a baked word. The vocabulary is fixed at build time; a string nobody baked
cannot appear. That was the deal in the entry below and it has not changed —
only the depth of the pixels has.

## 2026-08-10 — The rotation is the owner's, not the firmware's

**Context.** The scene table carried both the list of scenes and each one's
dwell as compile-time constants. Adding a fifth scene made that untenable: the
bus scene is useless outside Hong Kong, and "re-flash to stop it appearing" is
the exact repair path this project has twice rejected — once for touch
calibration, once for WiFi credentials.

**Decision.** `sceneOn[]` and `sceneDwellS[]` in NVS, edited from a **Screens**
section on the settings page. The table's `dwellMs` degrades to a default for a
device that has never saved. Two short strings (`"11011"`, `"35,12,12,12,12"`)
rather than ten keys, and a stored record shorter than `SCENE_SLOTS` leaves the
newer entries at their compiled defaults — so adding a scene later needs no
migration.

**The failure this had to design out.** Every scene switched off leaves the
panel frozen on its last frame, and the address of the settings page that could
undo it is *itself printed by a scene*. The device would be unrecoverable
without a serial cable or the boot-time touch override. So it is refused at the
form, and unreachable at runtime even if a stored record says otherwise:
`sceneOn()` forces scene 0 back on when nothing else is enabled. Belt and
braces, because only one of them is in the same binary as the failure.

**Consequence.** `sceneManager_index()` and `_count()` now describe the
*rotation*, not the table, so the status strip draws one dot per screen you will
actually see. A `static_assert` ties `SCENE_SLOTS` to the table length, making
"added a scene and forgot the settings array" a build error rather than a
silently ignored setting.

## 2026-08-10 — Chinese is baked to 1-bit bitmaps, not an embedded font

> Still current. A 4-bpp alpha variant was tried on 2026-08-24 and withdrawn
> the same day — see that entry for why. Nothing below changed.

**Context.** The bus scene shows Hong Kong stop names, destinations and status
words in Traditional Chinese. TFT_eSPI has no CJK font at all — fonts 2/4/6/8
are ASCII-only — so this needed a glyph pipeline that did not exist in this
project.

**What was measured.** A `.vlw` smooth font costs 28 B of metadata plus
`width × height` bytes per glyph (8-bit alpha). Against a ~4,700-glyph Hong Kong
common-character set, and 1.83 MB of free flash:

| size | cost | verdict |
|---|---|---|
| 12 px | ~819 KB | fits, unreadable across a room |
| 16 px | ~1.35 MB | fits, marginal |
| 20 px | ~2.04 MB | **does not fit** |

The readable sizes are exactly the ones that do not fit. Checked also: u8g2's
CJK fonts are GB2312 — **Simplified only**, so the usual recommendation is
wrong for Hong Kong.

**Decision.** Do not embed a font. Bake only the characters actually used, as
1-bpp bitmaps, from two sources:

- **Fixed vocabulary** (`分鐘`, `即將到站`, `無服務`, …) — 18 strings baked at
  build time by `tools/gen_zh_labels.py` into `app/zh_labels.{h,cpp}`. 2.5 KB.
  Checked in, with the generator beside them so they stay auditable.
- **The user's stop names and destinations** — baked by the **browser** at
  config time and POSTed to `/label`, because the device cannot know them in
  advance.

At ~400 B per label the size constraint disappears entirely, **at any size**.
The blob layout is `[w][h][packed rows, MSB first, row-padded]`, chosen because
that is exactly what `TFT_eSPI::drawBitmap(x,y,bmp,w,h,fg,bg)` already accepts —
so there is no custom blitter, and the fg/bg form keeps the compare-and-redraw
partial repaint used everywhere else in this project.

**Storage: LittleFS, not NVS.** `huge_app` leaves an 896 KB filesystem
partition completely unused; NVS is 20 KB and already holds settings and touch
calibration. ~1.3 KB per slot is uncomfortable in one and nothing in the other.

**The failure mode this had to survive.** NVS keeps the stop *text* — both
`name_tc` and `name_en` — while LittleFS keeps only the *bitmap*. Whenever the
bitmap is absent the scene renders `name_en` in the built-in ASCII font: still
useful, never blank, and the settings page restores the Chinese with one Save.

Note this is **not**, as originally assumed, because a reflash wipes LittleFS
and spares NVS. Checked against the actual artifact: `app.ino.merged.bin` is a
full 4 MB image whose `0xFF` padding covers *both* partitions, so flashing it at
`0x0` is a factory reset and there is nothing to fall back to — the device comes
up in the setup portal. `arduino-cli upload` (`tools/flash.sh app`) writes only
the app segments and preserves both. `flash.md` documents the difference,
because "update firmware" and "hand this file to someone else" want opposite
commands.

The fallback still earns its place, for the cases that produce text without a
bitmap: a stop entered by hand through "Manual entry", a save made while the
browser had no internet, a bake that failed, or a filesystem erased on its own.

**Consequence.** Live Chinese from the APIs (`rmk_tc`) is not renderable by
definition. The operators' remark vocabulary is small, so known remarks map onto
the baked set from their **English** field and anything unrecognised falls back
to `rmk_en` in Font 2 — which is also why the JSON filters deliberately drop
`dest_tc`/`rmk_tc` rather than carrying UTF-8 the device cannot draw.

## 2026-08-10 — Only KMB serves plain HTTP; the other two cost a TLS handshake

**Context.** An earlier claim that "KMB serves plain HTTP, so no TLS anywhere"
was half right, and the wrong half drove the whole fetch design. Probed live:

```
http://data.etabus.gov.hk/v1/transport/kmb/eta/...   200, ~270 ms   <- works
http://rt.data.gov.hk/v2/transport/citybus/eta/...   301 -> https
http://data.etagmb.gov.hk/eta/route-stop/...         301 -> https
```

Host *roots* redirect on all three; only KMB's **API path** serves plain HTTP.
So two of three operators cost a ~1.2–2.5 s handshake, inside `loop()`.

**Decision.** Pay the handshake and give the heap straight back, rather than
holding connections open. Four mitigations, in descending order of value:

1. `if (touch_isDown()) return;` at the top of `bus_tick()`. One line, and the
   most valuable of the four — a 2 s stall under a finger eats the tap outright.
2. One slot per tick, never a burst.
3. Idle cadence 300 s per slot at 100 s offsets → one fetch per ~100 s, ~2%
   duty. Active (scene showing, or left within 30 s) 30 s at 10 s offsets.
4. `setReuse(false)`, so no mbedTLS context outlives a fetch.

**What changed from the plan.** It called for `WiFiClientSecure::setSession()`
to skip the handshake. **That API does not exist in the ESP32 Arduino core** —
it is an ESP8266/BearSSL feature — so session resumption is not available at
all. The alternative, `setReuse(true)`, holds ~35 KB of live TLS context per
host; two of those against a ~108 KB largest contiguous block is precisely what
makes `clockEnter`'s 10 KB sprite allocation start failing after hours of
uptime. The plan's own stated fallback — lengthen the idle interval — is what
this ships with. Every fetch logs elapsed millis, free heap and
`getMaxAllocHeap` so this stays a measured decision rather than an assumed one.

**Why staleness is cheap here.** ETAs are stored as **absolute epoch seconds**
and the countdown is recomputed every second from `time(nullptr)`. A 300 s-old
fetch still shows the right number; idle staleness costs only a newly-appeared
bus. Unplugging the router mid-scene leaves the display counting down correctly,
with only the header's age changing colour.

## 2026-08-10 — Route and stop discovery happens in the browser, not on the device

**Context.** Turning "68X" into something the device can poll means resolving a
route to a variant to a stop id, against three different APIs with three
different models — and KMB has no "list variants" endpoint at all
(`/route/68X` → 422), so the eight `{outbound,inbound} × service_type 1..4`
combinations have to be probed and filtered.

**Decision.** All of it runs in `/bus.js` in the browser. Only resolved ids ever
reach the ESP32; `settings.h` stores the result and nothing else.

**Why, beyond "the ESP32 is small".** The operators regenerate route variant
numbering nightly at 05:00. A device that matched routes to stops itself would
need that matching logic maintained on the wall, and its failure mode — a slot
that worked yesterday and now returns empty forever — is indistinguishable from
"no bus is coming". Keeping the matching in the browser means a re-pick is the
repair, and the device never has to be clever. **Do not add self-healing route
matching to the firmware**; that is the thing this decision moved out.

Three consequences worth stating:

- **Validation is structural, not semantic.** An invalid stop id returns HTTP
  200 with `"data":[]`, identical to "no bus is coming", so `handleSave` can
  only check shape. The picker is the real validation — the `/stop/{id}` call
  that fetched the name *is* the proof the id resolves. Semantic validation
  would mean an internet call inside a request handler, which the appliance rule
  below forbids. Over time the scene discriminates instead: a slot returning
  nothing for **six consecutive hours** shows `檢查車站`.
- **The page must work with the picker dead.** AP mode has no internet by
  construction, so the fieldset is a plain text input holding a packed string
  with the picker layered on top — not the reverse. Saving with the picker dead
  preserves every configured stop, and "Manual entry" is a permanent escape
  hatch rather than a debug affordance.
- **Canvas rasterisation is not deterministic across browsers.** Different
  devices have different Hong Kong fonts and hinting, so the same name baked on
  a phone and a desktop will not be pixel-identical. Harmless — but it is why
  the page previews the **packed 1-bit result** rather than the antialiased
  canvas, and why the label text is editable: the operators' names are written
  for a route database, not a wall, and `洪水橋(洪福邨)總站 (YL900)` shrinks to
  ~12 px to fit a 232 px row, which defeats the point of a two-row layout.

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
