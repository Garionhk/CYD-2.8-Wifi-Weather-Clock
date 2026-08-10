#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# test_scenes.sh -- host-side check of the scene rotation helpers
#
# Scenes can now be switched off from the settings page, which turns a trivial
# `(i + 1) % COUNT` into something that can strand the device: pick the next
# scene wrongly and the panel sits frozen on whatever was drawn last, and the
# address of the page that would undo it is itself painted by a scene. So the
# skip logic and the all-off guard are worth checking where it is cheap.
#
# The functions are extracted from app/scenes.cpp rather than copied, so this
# cannot drift away from what ships.
#
#   ./tools/test_scenes.sh
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

python3 - "$ROOT/app/scenes.cpp" "$OUT/t.cpp" <<'PY'
import sys
src = open(sys.argv[1]).read()

def grab(marker, end='\n}\n'):
    i = src.index(marker)
    return src[i:src.index(end, i) + len(end)]

body = (grab('static bool anySceneOn()') + grab('static bool sceneOn(int i)')
        + grab('static int nextScene(int from)') + grab('static int firstScene()')
        + grab('int sceneManager_count()') + grab('int sceneManager_index()'))

open(sys.argv[2], 'w').write(r'''
#include <cstdio>
#include <cstring>
static const int SCENE_COUNT = 5;
struct S { bool sceneOn[5]; } g_settings;
static int curIdx = 0;
''' + body + r'''
static int fails = 0;
static void set(const char* mask) {
  for (int i = 0; i < 5; i++) g_settings.sceneOn[i] = (mask[i] != '0');
}
static void chk(const char* what, long got, long want) {
  bool ok = (got == want);
  if (!ok) fails++;
  printf("  %-46s %3ld  %s\n", what, got, ok ? "ok" : "FAIL");
  if (!ok) printf("  %46s %3ld  expected\n", "", want);
}

int main() {
  char buf[96];

  printf("all five on -- unchanged behaviour\n");
  set("11111");
  for (int i = 0; i < 5; i++) {
    snprintf(buf, sizeof buf, "nextScene(%d)", i);
    chk(buf, nextScene(i), (i + 1) % 5);
  }
  chk("count", sceneManager_count(), 5);
  chk("firstScene", firstScene(), 0);

  printf("middle three off -- must skip to the next live one\n");
  set("10001");
  chk("nextScene(0) skips 1,2,3", nextScene(0), 4);
  chk("nextScene(4) wraps to 0",  nextScene(4), 0);
  chk("count", sceneManager_count(), 2);
  curIdx = 4; chk("index of table slot 4", sceneManager_index(), 1);
  curIdx = 0; chk("index of table slot 0", sceneManager_index(), 0);

  printf("first scene off -- rotation must not start on it\n");
  set("01111");
  chk("firstScene", firstScene(), 1);
  chk("nextScene(4) wraps past 0", nextScene(4), 1);

  printf("exactly one on -- must hold still, not flicker\n");
  set("00100");
  chk("nextScene(2) returns itself", nextScene(2), 2);
  chk("firstScene", firstScene(), 2);
  chk("count", sceneManager_count(), 1);
  curIdx = 2; chk("index", sceneManager_index(), 0);

  printf("ALL off -- the guard: scene 0 is forced back on\n");
  set("00000");
  chk("sceneOn(0) forced true",  sceneOn(0), 1);
  chk("sceneOn(3) still false",  sceneOn(3), 0);
  chk("firstScene", firstScene(), 0);
  chk("nextScene(0) returns itself", nextScene(0), 0);
  chk("count is never zero", sceneManager_count(), 1);

  printf("\n%s\n", fails ? "FAILED" : "all passed");
  return fails != 0;
}
''')
PY

c++ -O1 -Wall -o "$OUT/t" "$OUT/t.cpp" || { echo "compile failed"; exit 1; }
"$OUT/t"
