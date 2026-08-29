#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build_all.sh -- compile every Stage 0 sketch (and the app) in one go.
#
# Catches the whole class of "it didn't build" problems -- a stale User_Setup.h,
# a missing library, a header that never got synced -- before you plug the
# board in. It does NOT flash anything; use tools/flash.sh for that.
#
#   ./tools/build_all.sh            # everything
#   ./tools/build_all.sh stage0     # just the Stage 0 tests
#   ./tools/build_all.sh stage0/s02_touch_test
#
# Uses the arduino-cli bundled inside Arduino IDE 2 if one isn't on PATH.
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# huge_app, not the default partition scheme: with the web UI, the bus scene and
# the baked fonts the app is ~1.43 MB and does not fit default.csv's 1.31 MB app
# slot at all (it was 93% of it before v2.2.0). huge_app gives it 3 MB by
# dropping the second OTA slot, which costs nothing here -- OTA is a documented
# non-goal (plan §1). NVS stays at 0x9000/0x5000 in both, so stored touch
# calibration and settings survive switching between them.
FQBN="${FQBN:-esp32:esp32:esp32:PartitionScheme=huge_app}"

if command -v arduino-cli >/dev/null 2>&1; then
  CLI="$(command -v arduino-cli)"
elif [ -x "/Applications/Arduino IDE.app/Contents/Resources/app/lib/backend/resources/arduino-cli" ]; then
  CLI="/Applications/Arduino IDE.app/Contents/Resources/app/lib/backend/resources/arduino-cli"
else
  echo "arduino-cli not found (not on PATH, not inside Arduino IDE.app)." >&2
  exit 1
fi

FILTER="${1:-}"

# Every folder holding a .ino, in test order.
SKETCHES=(
  stage0/s01_display_test
  stage0/s02_touch_test
  stage0/s02e_touch_calibrate
  stage0/s02b_touch_raw_probe
  stage0/s02c_touch_bitbang_scan
  stage0/s02d_touch_vspi
  stage0/s03_wifi_time_heap_test
  stage0/s04_https_fetch_test
  app
)

"$ROOT/tools/sync_shared.sh" >/dev/null

echo "arduino-cli: $CLI"
echo "fqbn       : $FQBN"
echo

pass=0
fail=0
failed_list=()

for sketch in "${SKETCHES[@]}"; do
  [ -d "$sketch" ] || continue
  if [ -n "$FILTER" ] && [[ "$sketch" != "$FILTER"* ]]; then continue; fi

  printf '%-34s ' "$sketch"
  out="$("$CLI" compile --fqbn "$FQBN" --warnings none "$sketch" 2>&1)"
  if [ $? -eq 0 ]; then
    # Pull the flash/RAM line out of the noise so regressions in size show up.
    usage="$(echo "$out" | grep -m1 'Sketch uses' | sed 's/Sketch uses //; s/ bytes.*(\([0-9]*%\)).*/ B flash (\1)/')"
    echo "OK    $usage"
    pass=$((pass + 1))
  else
    echo "FAIL"
    echo "$out" | grep -E 'error:|#error|#warning' | head -8 | sed 's/^/    /'
    fail=$((fail + 1))
    failed_list+=("$sketch")
  fi
done

echo
echo "$pass built, $fail failed"
if [ "$fail" -gt 0 ]; then
  printf '  failed: %s\n' "${failed_list[@]}"
  exit 1
fi
