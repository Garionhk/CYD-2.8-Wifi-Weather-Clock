# Building and distributing firmware

How to produce a `.bin` other people can flash to their own CYD board, and how
they flash it.

Nothing user-specific is compiled in — Wi-Fi credentials, location, timezone and
units live in NVS on the device, entered through the setup portal on first boot.
That is what makes the binary shareable.

Touch calibration is likewise measured on the device, not baked in, so one image
suits every panel.

---

## 1. Prerequisites (you, the builder)

[arduino-cli](https://arduino.github.io/arduino-cli/) plus the ESP32 core and
three libraries:

```bash
arduino-cli core install esp32:esp32
```

```bash
arduino-cli lib install TFT_eSPI XPT2046_Touchscreen ArduinoJson
```

`WiFi`, `WebServer`, `DNSServer`, `WiFiClientSecure`, `HTTPClient`,
`Preferences` and `time.h` all ship with the core.

> **ArduinoJson 7 is fine.** The fetch code uses `DynamicJsonDocument`, which is
> v6 syntax; v7 still accepts it as a deprecated alias, so it compiles (with
> warnings under `--warnings all`) and runs correctly. No version pin needed.

### Install the display config — the step that catches everyone

TFT_eSPI is configured by editing a header **inside the library**, not by this
project's source. Skip it and the build fails, or worse, produces a board whose
touch panel reads a constant zero.

```bash
cp config/User_Setup_2432S028R.h.template ~/Documents/Arduino/libraries/TFT_eSPI/User_Setup.h
```

Repeat this after **any** TFT_eSPI update — updating the library overwrites
`User_Setup.h` and silently reverts you to stock settings.

The app compares that header's pins and SPI port against `config/board.h` and
**refuses to build** against the wrong one, with the fix in the error message.
That guard exists because the failure it prevents is invisible: without
`USE_HSPI_PORT` the display takes the bus the touch controller needs, everything
looks fine, and touch dies.

For the 2.4" ESP32-2432S024R, use `User_Setup_2432S024R.h.template` and set
`CYD_BOARD` in `config/board.h`.

## 2. Build

```bash
arduino-cli compile --fqbn esp32:esp32:esp32:PartitionScheme=huge_app --export-binaries app
```

The artifacts land in:

```
app/build/esp32.esp32.esp32/
```

| File | Size | Purpose |
| --- | --- | --- |
| **`app.ino.merged.bin`** | **4 MB** | **all of the below, pre-combined — this is the one you publish** |
| `app.ino.bin` | 1.2 MB | the application alone |
| `app.ino.bootloader.bin` | 24 KB | second-stage bootloader |
| `app.ino.partitions.bin` | 3 KB | partition table |
| `boot_app0.bin` | 8 KB | OTA selector |

**Publish `app.ino.merged.bin` and nothing else.** An ESP32 needs the bootloader,
partition table and OTA selector alongside the application, at specific offsets —
the merged file already contains all four, so it flashes as a single file to a
single address. Shipping the parts separately means four downloads and four
chances to get an offset wrong, for no benefit.

Rename it to something meaningful before uploading, e.g.:

```bash
cp app/build/esp32.esp32.esp32/app.ino.merged.bin cyd-clock-weather-v2.1.0-4mb.bin
```

The application is about 38% of the 3 MB app partition, so there is plenty of
headroom.

`--export-binaries` is what writes these into the sketch folder; without it
arduino-cli builds into a temporary directory and throws the result away.

### The merged image at `0x0` is a factory reset — settings and all

Worth being precise about, because it is easy to assume otherwise. The merged
file is a **full 4 MB image**: the gaps between the four real segments are
padded with `0xFF`, and that padding covers the NVS partition at `0x9000` and
the LittleFS partition at `0x310000`. Writing it at `0x0` erases both.

```
$ python3 -c "d=open('cyd-clock-weather-v2.1.0-4mb.bin','rb').read(); \
              print(len(d), set(d[0x9000:0x9040]), set(d[0x310000:0x310040]))"
4194304 {255} {255}
```

So flashing the merged image wipes **WiFi credentials, location, timezone,
units, your configured bus stops, the touch calibration, and the baked Chinese
labels**. The device comes up in the setup portal and runs the touch wizard, as
if new. That is the right behaviour for the file you hand to someone else — it
is what makes it a clean install — but it is not an upgrade path.

**To update firmware and keep everything, upload the application only:**

```bash
./tools/flash.sh app
```

`arduino-cli upload` writes the bootloader, partition table, OTA selector and
application at their own offsets and never touches `0x9000` or `0x310000`, so
settings, calibration and labels all survive.

### If the Chinese stop names come back as English

The device has no CJK font. Stop names and destinations are rendered to 1-bit
images by your browser and stored on LittleFS (see `docs/decisions.md`); the
stop *text* lives in NVS in both languages. When the image is missing the bus
scene draws the English name in the built-in font rather than going blank —
which is what you will see if the filesystem was erased or was never written
(for example, a stop added by hand through "Manual entry", or a save made while
the browser had no internet).

The fix is one step: open the settings page and press **Save & restart**. The
browser re-bakes and re-uploads the labels. You do not need to pick the routes
again.

If you have the source checked out and just want to flash your own board,
`./tools/flash.sh app` does compile, upload and serial monitor in one step, and
already defaults to the right FQBN.

### Why `huge_app`

The default partition scheme reserves a second application slot for OTA updates.
This firmware has no OTA, so that slot is dead weight — and without the switch
the build sits at **93%** of the default 1.31 MB app area, which leaves no room
to grow. `huge_app` gives 3 MB and drops the same image to 38%.

The scheme is baked into the partition table, which the merged image carries, so
it travels with the firmware automatically — nobody flashing it has to know or
match the setting.

Both `tools/build_all.sh` and `tools/flash.sh` default to it. An `FQBN` override
**replaces** the default wholesale, so any override has to carry
`PartitionScheme=huge_app` too or you are silently back at 93%.

### Flash size

CYD boards are **4 MB**, which is what the FQBN above targets and what
`merged.bin` assumes. The boot banner prints the actual figure
(`flash   : 4194304 bytes`) if you want to confirm a particular unit.

## 3. Confirm the binary carries no secrets

Do this before publishing. It is the whole point of keeping configuration off the
compile-time path:

```bash
strings app/build/esp32.esp32.esp32/app.ino.merged.bin | grep -iE "your-ssid|your-wifi-password|49\.28|-123\.12"
```

Check the **merged** image, not `app.ino.bin` — merged is what you upload, so
that is what has to be clean.

Substitute your own network name, password and coordinates. Expect **no output**.

Grep for the actual values, not for the word "password" — the settings form
contains `<input type=password …>` markup, so searching for the word produces
harmless hits and hides a real one.

Sanity-check that `strings` is actually reading the file, so an empty result
means "clean" rather than "nothing was scanned". This should print a non-zero
count:

```bash
strings app/build/esp32.esp32.esp32/app.ino.merged.bin | grep -c "CYD-Setup"
```

Publish a checksum alongside the binary, generated from the renamed file you are
actually uploading:

```bash
shasum -a 256 cyd-clock-weather-v2.1.0-4mb.bin > SHA256SUMS
```

---

## 4. Instructions for the person flashing

They need [esptool](https://github.com/espressif/esptool) — no Arduino
toolchain, no source, and **no TFT_eSPI setup**, since all of that was resolved
at build time:

```bash
pip install esptool
```

### Find the port

The 2.8" board uses a **CH340** USB-serial bridge.

| OS | Typically |
| --- | --- |
| macOS | `/dev/cu.usbserial-1440`, `/dev/cu.wchusbserial*` |
| Linux | `/dev/ttyUSB0` |
| Windows | `COM3`, `COM4`, … |

If nothing appears, install the [CH340 driver](https://www.wch-ic.com/downloads/CH341SER_ZIP.html).
A charge-only USB cable produces the same symptom, so try another cable before
chasing drivers.

> **The 2.8" board has both micro-USB and USB-C, wired to the same CH340.**
> Use one at a time. With both plugged in the port may enumerate but uploads
> fail or reset mid-transfer.

On macOS, `arduino-cli board list` also lists Bluetooth ports and can show stale
`usbserial-*` entries with nothing behind them. To see what is genuinely plugged
in:

```bash
ioreg -p IOUSB -l -w 0 | grep "USB Product Name"
```

### Erase, then write

Erasing first avoids inheriting stale settings from whatever was on the board
before:

```bash
esptool --port /dev/cu.usbserial-1440 erase-flash
```

Then write the firmware — one file, one address:

```bash
esptool --port /dev/cu.usbserial-1440 --baud 460800 write-flash 0x0 cyd-clock-weather-v2.1.0-4mb.bin
```

That is the whole job. **An ESP32 is not flashed like an ESP8266** — it needs a
bootloader, a partition table and an OTA selector alongside the application, at
specific offsets. Writing a bare *application* image to `0x0` produces a board
that does not boot. The published file is a merged image that already contains
all four at the right places, which is why a single `0x0` write is correct here.

Older esptool (v4 and earlier) uses underscores: `erase_flash`, `write_flash`.

Verify the download first if a checksum was published alongside it:

```bash
shasum -a 256 -c SHA256SUMS
```

> Building from source instead? `./tools/flash.sh app` compiles, uploads and
> opens the serial monitor in one step, and already knows the right FQBN and
> offsets. The offsets themselves live in `flash_args` in the build folder,
> which is the authoritative source if a future core version changes them.

### If the upload dies at 921600

This board's CH340 often fails the switch to high speed. The failure is
distinctive — the handshake succeeds, then dies the moment the rate changes:

```
Changing baud rate to 921600
Changed.
A fatal error occurred: Unable to verify flash chip connection
(Invalid head of packet (0xE0): Possible serial noise or corruption.)
```

Nothing is written when this happens, so the board still holds its previous
firmware. Retry at a lower rate — `--baud 115200` works consistently, at about a
minute for a full image instead of ten seconds. 460800 is a reasonable middle
ground. This is a property of the individual USB-serial chip and cable, not of
the firmware.

### First boot

1. Power the board. The screen lights and runs the **touch calibration wizard** —
   press each of the four corner targets, then a centre target to confirm. This
   happens once; the mapping is stored on the device.

   The wizard times out after 60 s of no touch and boots anyway, so an
   unattended power cut can never strand the device on a setup screen.

2. The **setup screen** appears next:

   ```
                       Setup

              1. Join this WiFi network
                   CYD-Setup-A1B2

        2. The setup page opens automatically,
                     or browse to
                 http://192.168.4.1
   ```

3. Join that open network from a phone or laptop — the suffix is derived from
   that board's MAC, so two devices on a bench do not collide. The setup page
   opens by itself (it is a captive portal); if not, browse to
   **http://192.168.4.1**.

4. Fill in the form → **Save & restart**:

   | Section | Fields |
   | --- | --- |
   | Wi-Fi | network (there is a **Scan** button for a live list) and password — **2.4 GHz only**, the ESP32 has no 5 GHz radio |
   | Location | latitude and longitude in decimal degrees — longitude is **negative** west of Greenwich, so all of the Americas is negative |
   | Time zone | a dropdown of common zones, plus a free-text POSIX TZ field |
   | Units | metric (°C, km/h, hPa) or imperial (°F, mph, inHg) |

The device restarts, joins the network and goes to the clock. **The address of
its settings page is printed along the bottom of the clock screen**, so it stays
reachable on the LAN afterwards without hunting through the router.

The setup screen deliberately never times out, unlike the calibration wizard —
an unprovisioned device has no credentials, so no Wi-Fi, no NTP and therefore no
clock. There is nothing to fall back to, so a page telling you how to fix that is
the most useful state available.

Watch progress on serial at **115200 baud** if anything goes wrong.

### GUI alternatives

For people who would rather not use a terminal:

- [ESP Web Tools](https://esphome.github.io/esp-web-tools/) — flashes from a
  Chrome or Edge browser over Web Serial, no install at all. Point its manifest
  at `merged.bin` with an offset of `0`.
- [Espressif Flash Download Tool](https://www.espressif.com/en/support/download/other-tools)
  — official Windows GUI; add the merged `.bin` as a single entry at offset
  `0x0`, set SPI speed **80 MHz**, SPI mode **DIO**, flash size **4 MB** (these
  are the values in `flash_args` in the build folder), then Start.

Both need the same erase-then-write behaviour.

---

## 5. Notes

- **Reflashing does not erase settings.** NVS lives outside the application
  partition and survives a firmware write, so an upgrade keeps the configuration
  *and* the touch calibration. That is usually what you want; use `erase-flash`
  for a true factory reset — after which the calibration wizard runs again.
- **To return a configured device to setup mode without USB**, hold a finger on
  the panel while it powers on. The portal opens with the current values
  pre-filled; nothing is erased.

  This is the way back in when the device is joined to a network that no longer
  exists, so nothing can reach its settings page. It is deliberately
  unauthenticated — physical access already implies control, since anyone
  holding the board can reflash it.

  You should rarely need it: if the network simply goes away, the device raises
  the `CYD-Setup-XXXX` network by itself after two minutes without a connection,
  and drops it again once the real network returns.
- **To wipe settings over USB without reflashing**, erase just the NVS partition:

  ```bash
  esptool --port /dev/cu.usbserial-1440 erase-region 0x9000 0x5000
  ```

  That wipes **both** stored namespaces — `cydcfg` (Wi-Fi, location, timezone,
  units) and `cydtouch` (calibration) — so the next boot runs the calibration
  wizard before the setup portal. The offset is the `nvs` line in
  `partitions.csv` in the build folder; check it there rather than assuming, as
  it differs for other layouts.
- **Changing partition scheme requires writing the partition table**, which both
  flashing methods above do. `nvs` sits at `0x9000` size `0x5000` in both the
  default and `huge_app` schemes, so settings and calibration survive that
  particular switch.
- **The settings page has no password.** Anyone on the network can open it and
  change the clock's configuration, and the setup AP is open while it is up. For
  a wall clock on a home LAN that is a reasonable trade; if it is not for you,
  put the device on a guest VLAN. The page never *reveals* the stored Wi-Fi
  password — it only accepts a new one.
- **There is nothing to distribute alongside the binary.** Configuration exists
  only in NVS on each device, and no build step embeds it.
