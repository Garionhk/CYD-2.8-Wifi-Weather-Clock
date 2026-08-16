#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# sync_shared.sh -- push the canonical shared headers into every sketch folder.
#
# The Arduino IDE copies a sketch folder to a temp dir before building, so a
# sketch cannot #include "../../config/board.h". Each sketch folder therefore
# carries its own copy, and this script is what keeps those copies honest.
# Edit config/board.h, run this, rebuild.
#
#   ./tools/sync_shared.sh
#
# There is no config.h any more: WiFi, location, timezone and units are set from
# the device's web UI and live in NVS (app/settings.h). Nothing user-specific is
# compiled in, which is what makes the built binary shareable.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BOARD_SRC="config/board.h"

# Folders that need board.h (pins / board identity).
BOARD_TARGETS=(
  stage0/s01_display_test
  stage0/s02_touch_test
  stage0/s02e_touch_calibrate
  stage0/s03_wifi_time_heap_test
  stage0/s04_https_fetch_test
  app
)

echo "syncing from $ROOT"

for dir in "${BOARD_TARGETS[@]}"; do
  if [ -d "$dir" ]; then
    cp "$BOARD_SRC" "$dir/board.h"
    echo "  board.h  -> $dir/"
  fi
done

echo "done."
