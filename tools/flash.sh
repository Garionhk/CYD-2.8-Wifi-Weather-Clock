#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# flash.sh -- compile, upload and open the serial monitor for one sketch.
#
#   ./tools/flash.sh stage0/s01_display_test
#   ./tools/flash.sh stage0/s02e_touch_calibrate /dev/tty.usbserial-1234
#
# With no port given it auto-detects a single connected board and refuses to
# guess if there is more than one. Ctrl-C leaves the monitor.
#
# NOTE on the 2.8" board: it has both micro-USB and USB-C, wired to the same
# CH340 serial chip. Use one at a time -- with both plugged in, the port may
# enumerate but uploads fail or reset mid-transfer.
# ---------------------------------------------------------------------------
set -o pipefail   # not -u: bash 3.2 (macOS stock) trips over empty arrays

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# See build_all.sh: the app needs huge_app's 3 MB slot, not default.csv's 1.31 MB.
FQBN="${FQBN:-esp32:esp32:esp32:PartitionScheme=huge_app}"
BAUD="${BAUD:-115200}"

SKETCH="${1:-}"
PORT="${2:-}"

if [ -z "$SKETCH" ]; then
  echo "usage: tools/flash.sh <sketch-folder> [port]" >&2
  echo "e.g.   tools/flash.sh stage0/s01_display_test" >&2
  exit 1
fi
SKETCH="${SKETCH%/}"
if [ ! -d "$SKETCH" ]; then
  echo "no such sketch folder: $SKETCH" >&2
  exit 1
fi

if command -v arduino-cli >/dev/null 2>&1; then
  CLI="$(command -v arduino-cli)"
elif [ -x "/Applications/Arduino IDE.app/Contents/Resources/app/lib/backend/resources/arduino-cli" ]; then
  CLI="/Applications/Arduino IDE.app/Contents/Resources/app/lib/backend/resources/arduino-cli"
else
  echo "arduino-cli not found (not on PATH, not inside Arduino IDE.app)." >&2
  exit 1
fi

if [ -z "$PORT" ]; then
  # CYD boards show up as a CH340: usbserial on macOS, ttyUSB on Linux.
  # (Built without mapfile so this still runs on macOS's stock bash 3.2.)
  PORTS=()
  for p in /dev/tty.usbserial* /dev/tty.wchusbserial* /dev/ttyUSB*; do
    [ -e "$p" ] && PORTS+=("$p")
  done
  if [ "${#PORTS[@]}" -eq 0 ]; then
    echo "No board found. Plug in ONE USB cable (micro-USB or USB-C, not both)." >&2
    echo "If the port never appears, you likely need the CH340 driver." >&2
    exit 1
  elif [ "${#PORTS[@]}" -gt 1 ]; then
    echo "More than one serial port present -- pass the one you want:" >&2
    printf '  %s\n' "${PORTS[@]}" >&2
    exit 1
  fi
  PORT="${PORTS[0]}"
fi

"$ROOT/tools/sync_shared.sh" >/dev/null

echo "sketch : $SKETCH"
echo "port   : $PORT"
echo

"$CLI" compile --fqbn "$FQBN" "$SKETCH" || exit 1
"$CLI" upload  --fqbn "$FQBN" -p "$PORT" "$SKETCH" || {
  echo
  echo "Upload failed. Things that cause this on a CYD:" >&2
  echo "  * both USB connectors plugged in at once" >&2
  echo "  * a charge-only cable (very common -- try another cable)" >&2
  echo "  * the serial monitor still open in the Arduino IDE" >&2
  exit 1
}

echo
echo "--- monitor @ $BAUD (Ctrl-C to exit) ---"
exec "$CLI" monitor -p "$PORT" -c "baudrate=$BAUD"
