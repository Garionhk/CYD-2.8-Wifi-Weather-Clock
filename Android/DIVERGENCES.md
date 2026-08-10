# Divergences from the CYD firmware

Where the Android port deliberately behaves differently from `app/`, and why.
The aim is that anyone reading both codebases can tell an intentional change
from a porting mistake.

Newest sections last; entries within a section are grouped by cause.

---

## The big one: rendering is vector, not pixel-identical

The port was planned to reproduce the CYD panel pixel for pixel -- rasterise a
320x240 framebuffer with TFT_eSPI's own routines and bitmap fonts, then upscale
by a whole-number factor with nearest-neighbour. It did exactly that, and on a
1920x1200 tablet a 5x magnified 320x240 image reads as a defect rather than as a
style. So the shipped renderer changed.

**What it does now.** The scenes still speak in the same 320x240 design grid and
none of their code changed. `VectorSurface` scales the canvas by a real
(fractional) factor and draws antialiased shapes and scalable text at the
display's own resolution. `RasterSurface` -- the pixel-exact original -- is still
here and is what the JVM tests draw into, so scene geometry is still checked pixel
by pixel even though the app no longer ships that path.

**Consequences, stated plainly:**

* Output is no longer pixel-identical to the panel. That was the trade.
* `textWidth()` comes from the typeface, not TFT_eSPI's width tables, so chained
  positions land a few units differently. They stay *correct* because every one
  of them is computed from `textWidth` rather than hard-coded -- which is exactly
  why the firmware's habit of deriving positions was worth preserving. The one
  place a constant had been tuned to bitmap metrics (the moon caption) is now
  measured; see below.
* Font sizes are calibrated, not guessed. The four TFT faces fill their declared
  cells very differently -- Font 2's digits occupy 62% of their cell, Font 8's
  93% -- so sizing off the cell height made the clock visibly small. `VectorFonts`
  measures the typeface's own digit ink at a reference size and scales to match
  the bitmap font's measured ink height (10/17/36 px). Roboto's digit advances
  then land within a pixel of every TFT advance, which is a good sign the
  calibration is right rather than merely tuned.
* **Font 8 is then set 10% above its measured 70 px, to 77, by request.** It is
  the hero numeral -- the clock's HH:MM and the AQI headline -- and the panel's
  proportions were chosen for a display read from arm's length rather than from
  across a room. Both users of it still clear their surroundings: the clock's
  digit cell is 82 tall, and the AQI number keeps three units of gap above its
  band name (down from six). The clock colon grew with it, r=5 at +/-15 from the
  digit centre becoming r=6 at +/-16, or it reads as a stray pair of dots between
  two large numbers.
* Two composite primitives were added, `drawArc` and `fillMoon`, because the sun
  arc and the moon terminator were built as thirty straight segments and a stack
  of scanlines respectively. Those constructions are invisible at 320x240 and
  obvious when smooth. The raster surface keeps the firmware's versions; the
  vector one uses a real arc and a real ellipse.
* The opaque text background is ignored in vector mode. It existed so a repainted
  value erased the old one over SPI; the whole frame is redrawn anyway, and
  honouring it would paint visible boxes behind every label.
* The integer-only scale factor is gone. It existed to stop nearest-neighbour
  upscaling making some pixel columns wider than others; with antialiased output
  there is no such artefact, and rounding down just wasted screen.

## Fill mode widens the grid; it does not scale past the edges

"Fill the screen" first meant "scale until the larger axis is covered". That
crops. On a 20:9 phone it is a 7.5x scale against a 4.5x height, so 48 design
units go off the top -- taking the setup gear -- and 48 off the bottom, taking
the status strip. The control silently hid two of the app's three permanent
elements.

It now fits the HEIGHT and lets the grid grow sideways instead. `Letterbox.width`
is the grid's width in design units: 320 when letterboxed, and on a 20:9 phone
533. Consequences:

* Scenes spread into the extra width. Centring the 320-wide layout inside a
  533-wide grid left a third of a 20:9 phone black down each side, so
  `SceneContext.sx()` scales each horizontal anchor by `width / 320`: columns
  and stat cells move apart while the text and icons in them keep the size they
  were designed at. At width 320 it is the identity, so the firmware's layout is
  untouched on a 4:3 screen -- a test asserts exactly that.

  The clock is the exception. Its four digits are one number, not four columns;
  spreading them would render "03 : 23" with holes in it. That scene uses
  `SceneContext.groupOffset` to re-centre the block intact instead.
* The status strip spans the full grid. Its left group stays anchored left and
  the scene dots anchor right, so on a wide screen the two move apart instead of
  the whole strip sitting marooned in the middle.
* The setup gear anchors to the grid's right edge, as does its hit rectangle.
  Those were absolute coordinates; a fixed x would have left the gear stranded
  mid-screen while the tappable area sat somewhere else entirely.
* A screen narrower than 4:3 fits the width instead, so the sides cannot crop
  either. The app is landscape-locked, but a foldable's inner display can be
  near-square and a free-form window can be any shape.

`ViewportTest` asserts across six real aspect ratios that nothing leaves the
screen on any edge, in either mode.

**The moon caption is now measured, not positioned.** `moonTextX` shifts it left
only as far as it must to clear the right column, clamped so it never overlaps the
moon disk. A constant tuned to one font's metrics is wrong for the other, and this
scene has two renderers.

**The golden-hour block is centred in the space right of the moon caption.** The
firmware left-aligned the value at x=170, four units past where the longest phase
name ends, so on a wide row the two read as one run of text while the right half
sat empty. Label and value are now both centred between the right column's x and
the screen edge.

The label therefore leaves the left-aligned column that Sunrise, Sunset and UV
share. Those three are label-then-value on one line, so their labels line up;
golden hour is label above value, and a caption that does not sit over the thing
it names reads as belonging to the row above it -- UV.

The left bound is the column x, deliberately, not the caption's measured end:
`moonTextX` already guarantees the caption clears 170 whatever the phase, and
keying off its actual width would make the block drift left and right over a
month as the phase names changed length.

**The AQI band name is centred, not placed.** The firmware fixed "Good" at y=112,
which suited its own Font 8. Once that font grew, the word sat three units under
the number and twenty-one above the stats row -- reading as part of the number
rather than as a caption between two things. `bandNameY` now measures both
neighbours and centres in the gap.

Doing that needed one more thing from the renderer: `inkCenterOffset`. The two
surfaces place ink differently relative to a middle datum -- the vector one
centres the ink by construction, while TFT_eSPI centres the declared CELL and the
glyph sits wherever its fixed baseline puts it inside. Ignoring that put the word
nine units below and fifteen above on the raster surface. Any layout measuring a
gap between rows has to account for it.

## Dropped, because the constraint does not exist on Android

| Firmware | Why it is gone |
|---|---|
| `webconfig.cpp/.h`, SoftAP fallback, DNS captive portal, `AP_FALLBACK_MS` | Android owns connectivity. There is nothing to provision and nowhere to leak credentials. Settings are a native screen reached from a button on every scene. |
| WiFi state machine in `app.ino` | `ConnectivityManager` reports reachability directly. |
| `configTzTime` / NTP wait | The system clock is already synchronised. |
| `calibrate.cpp/.h`, the calibration half of `touch.cpp`, `TouchCal` | Capacitive panels need no four-corner calibration. |
| `TFT_eSprite` | Sprites existed to make partial redraws cheap over SPI. The Android renderer repaints the whole 320x240 frame every tick. |
| NVS (`Preferences`) | Replaced by DataStore. |
| `board.h`, `config/pins.h`, `User_Setup_*.h` | No panel, no pins. |
| `AppData.moonrise` / `moonset` | Declared at `app_data.h:43`, never populated, never displayed. |
| Restart-on-save | `decisions.md` justified it by "WiFi, timezone, location and units each need a different refresh path". Two of those four are gone, and rendering is now a pure function of `(AppData, AppSettings, now)`, so a settings change is visible on the next 100 ms tick. |

## Changed in kind

**Timezone: POSIX TZ string -> IANA zone id.** `settings.h` stored
`"PST8PDT,M3.2.0,M11.1.0"` because the ESP32's newlib has no timezone database
and DST rules had to be spelled out inline. Android has the IANA database, so
settings store a `ZoneId` and `null` means "follow the device". The firmware's
23 presets map straightforwardly (`UTC0` -> `UTC`, `GMT0BST,...` ->
`Europe/London`, `EST5EDT,...` -> `America/New_York`, ...).

**The >4 s hold: recalibrate -> open settings.** `TOUCH_RECAL_MS` has no Android
meaning. Remapping rather than deleting keeps `status_strip.cpp:86-91`'s
three-tier pin-glyph preview a verbatim port, and gives a second route to setup.

## Added, with no firmware counterpart

**A setup gear on every scene**, centred at (306, 12). The firmware had no such
control because settings lived in a web page; the clock printed its own IP so you
could find it. The corner was chosen over the status strip because the strip's
widest free gap is 20 px and sits against the scene dots.

Clearance there is one pixel: Sun & Moon's sunrise value is Font 4, ML datum at
x=232, measured at 63 px, so it ends at x=294 and the glyph starts at 296.
`SetupButtonCollisionTest` rasterises every scene across a data matrix and
asserts the reserved rectangle is empty before the button is composited, so M6
cannot regress it silently.

The hit test runs against the DOWN point before any dispatch, so a press on the
gear can neither advance the scene nor start the hold preview -- the pin glyph
must not begin dimming while someone is reaching for settings. Duration is not
part of that gesture: a press on the gear opens settings on release however long
it was held.

**First run opens settings.** With `provisioned == false` there are no
coordinates worth fetching for, and quietly showing the weather at Greenwich
would look like a working clock that is simply wrong. The firmware handled the
same case with a blocking setup portal that never timed out.

**The screen never sleeps, and the clock shows over the lock screen.** The CYD
panel was lit whenever the board was powered. There was nothing to blank it and
no keyguard to get past, so the firmware has no counterpart to either of these --
they are not divergences but the price of running on a general-purpose OS.

`FLAG_KEEP_SCREEN_ON` is unconditional (`MainActivity.kt`), so the display does
not time out while the clock is foreground and the device therefore never locks
on its own. That leaves exactly one route to the keyguard, a deliberate
power-button press, and `setShowWhenLocked(true)` covers it: the activity is
drawn above the lock screen and stays touchable.

What this deliberately is *not*: it does not dismiss the keyguard
(`requestDismissKeyguard` and `FLAG_DISMISS_KEYGUARD` are both absent), it holds
no wake lock, and it does not set `turnScreenOn` -- a clock that undid every
power-button press would be worse than one that waits to be asked. There is no
boot receiver either, so nothing starts on its own. Home from over the lock
screen returns to the keyguard and the PIN; verified on a PIN-locked emulator,
where `mKeyguardOccluded=true` while the clock is up and Home lands on the PIN
rather than the launcher.

The honest consequence is that Setup is reachable from the clock, so someone with
physical access can read and change the coordinates without unlocking. Hence the
toggle, in Setup → Display, default on.

`setShowWhenLocked` is API 27 and `minSdk` is 26, so API 26 alone takes the
deprecated `FLAG_SHOW_WHEN_LOCKED` instead. The manifest attribute
`android:showWhenLocked` is not used: it is a static `ActivityInfo` flag baked in
at install time, and this is a setting. The flag is also *not* re-asserted in
`onWindowFocusChanged` alongside the immersive bars -- SystemUI genuinely
reclaims the bars, but show-when-locked is recorded on the `ActivityRecord` and
survives pause/stop/resume.

**An explicit dark Compose theme** (`ui/CydTheme.kt`). The manifest theme covers
the window, but Compose's `MaterialTheme` defaults to a LIGHT colour scheme when
nothing supplies one -- so the settings screen rendered black-on-white inside a
black window until this was added. Caught on the emulator, not by any test.

It reuses the panel palette (`COL_ACCENT` cyan for controls, `COL_STRIP_BG` for
surfaces) so the two halves of the app look like one product, and it never reads
`isSystemInDarkTheme()`: the clock is white-on-black at all hours, and a settings
screen that flashed white at night would be the brightest thing in the room.
This matches the firmware, whose served config page was also dark
(`webconfig.cpp` inline `<style>`).

**The settings form is capped at 720 dp wide** and centred. A landscape tablet is
up to 1920 px across; left to fill, the coordinate fields become absurdly long
and the section rules run edge to edge.

## Behaviour worth knowing about

**Timezone and location are independent settings, and can disagree.** Set the
location to Greenwich while the device timezone is America/Los_Angeles and the
Sun & Moon scene reads "Sunrise 21:35, Sunset 12:35" -- correct, but startling.
Those are Greenwich's sun times rendered in Los Angeles local time.

This is inherited, not introduced: the firmware had the same two independent
settings and the same consequence. The setup screen exists to resolve it, and
setting the zone to Europe/London immediately gives 05:35 / 20:35. Worth knowing
because the default state (Greenwich coordinates, device timezone) is exactly the
configuration that shows it.

**A fetch owns only its own fields.** `applyWeather` and `applyAirQuality` copy a
named list of fields onto the *current* model rather than assigning the
repository's result wholesale.

This is not tidiness. A fetch reads `data`, suspends a second or two on the
network, then builds a new model from that stale snapshot. The tick loop
recomputes sun and moon every minute, so a fetch landing just after one used to
blank sunrise, sunset, the moon phase and the golden hour until the next minute
ticked over -- every fifteen minutes, on the scene most likely to be showing at
dusk. Found on the emulator, now pinned by two tests in
`SettingsPropagationTest`.

The firmware had the same field ownership implicitly, each fetcher writing its own
members of one global struct; it just had no suspension point at which to lose a
race.

**A press shorter than 40 ms does nothing.** `MIN_PRESS_MS` is kept from
`touch.cpp` as ghost-touch rejection. Human taps run 50-150 ms so this is not
felt, but it does mean `adb shell input tap` -- which sends down and up with
essentially no gap -- will not advance a scene. Use
`adb shell input swipe X Y X Y 120` to script a tap. The setup gear is exempt:
its gesture ignores duration entirely.

**Status strip WiFi bars.** `WifiManager.getConnectionInfo()` is deprecated at
API 31 and may return a redacted -127. Plan: 0 bars if no validated network;
real RSSI uses the firmware's thresholds unchanged (>=-60 -> 4, >=-68 -> 3,
>=-75 -> 2, else 1); RSSI unavailable or a non-WiFi transport shows 4 bars
meaning "online". On the CYD the bars meant WiFi signal strength specifically.

**Clock network footer** (`NET_Y = 180`). The firmware printed
`"setup: <ip>"` so you could find the config page. There is no config page now:
draw nothing when a validated network exists, and `"offline"` otherwise.

**Location change clears the valid flags.** The firmware restarted on save,
which zeroed `g_data` and left the scenes showing "fetching weather...". Simply
keeping the old values would display another city's weather as current, and the
freshness dot would certify it green. So a lat/lon change clears
`weatherValid` / `aqiValid` / `dailyValid` / `uvValid` while keeping the numbers,
and the loading state shows until the refetch lands. This is the one place the
"a failed fetch never clears existing values" rule is deliberately yielded.

## Bugs fixed

**`showingNextDay` is stuck true at polar latitudes.** `sun_moon.cpp:126` writes
`showingNextDay = (now > sunsetToday)`, but `sunEventUTC` returns `0` during
polar day and polar night, so above the Arctic circle this reduces to `now > 0`
and the Sun & Moon scene claims TOMORROW permanently. Fixed by requiring a real
sunset: `sunsetToday != 0L && now > sunsetToday`. Covered by
`SunMoonParityTest.showingNextDay is not stuck true during polar night`.

**Imperial pressure loses its only meaningful digit.** `scenes.cpp:410` renders
the pressure with `%d`, so 29.92 inHg displays as `30`. Fixed to two decimals.

  The plan had assumed one decimal, on an estimate that `"29.92"` would be about
  70 px wide and push the trend glyph against the screen edge. Measured against
  the real font tables it is 63 px: centred at `cx = 268` it spans 237..299 and
  the trend glyph lands at 305..313 against a 320 px screen. Two decimals fit,
  so the digit that carries the meaning is kept. Pinned by
  `FontMetricsTest.imperial pressure fits at two decimals`.

**Blank or malformed coordinates parse as 0.0.** `webconfig.cpp` used Arduino's
`String::toFloat()`, which yields `0.0` on garbage -- silently relocating the
device to the Gulf of Guinea. The settings screen uses `toFloatOrNull()` and
reports a validation error instead.

**The two longest moon phase names are clipped.** `scenes.cpp:512` draws the
phase name from x=72 in Font 2. Measured against the real font tables,
"Waxing Crescent" is 100 px and "Waning Crescent" is 101 px, so they end at 171
and 172 -- past the right column at x=170. `drawGolden()` then runs and clears a
150 px rect from x=170, so the panel silently clips the last letter of both.

Only those two of the eight names are long enough to reach, which is presumably
how it survived: six phases render correctly and two lose a pixel column or two
for about a week each per lunar month.

Moved to x=68, the largest x at which all eight clear the column. The moon disk
ends at x=62, so a visible gap remains. Pinned twice by `SunMoonSceneTest`: once
on the measured widths, and once on the rendered gutter being empty.

## Unreachable code kept for shape

NTP is gone, so `TimeManager` always has a valid time and the firmware's
`"--:--"` / `"syncing time..."` / `"----"` branches cannot be reached. They are
kept behind a `clockValid` flag so the scene code still reads like `scenes.cpp`,
but nothing sets it false today.

## Notes that are not divergences

**Font 2 does contain a degree symbol.** TFT_eSPI's `Font16.c` defines
`TFT_ESPI_GRAVE_IS_DEGREE`, which turns character 0x60 into `°`. The firmware's
`drawDegVal` (`scenes.cpp:244`) says "the built-in fonts don't include the °
glyph" and hand-draws a ring instead -- true of Fonts 6 and 8, which are
digits-only, but not of Font 2. The port keeps the hand-drawn ring, because that
is what the panel actually shows.

**The RGB565 palette.** Several hand-written conversions of this palette are
wrong in the low bits, because the 6-bit green channel replicates differently
from the 5-bit red and blue. `0xC618` is `#C6C3C6`, not `#C6C6C6`; `0x52AA` is
the grey `#525552`, not a green. `Theme.kt` therefore keeps `theme.h`'s RGB565
literals and expands them once at runtime, so the two files diff cleanly and
there is no second source of truth. Pinned by `FramebufferTest.rgb565 expands by
bit replication, not by shifting`.
