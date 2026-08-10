#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# test_bus_parse.sh -- host-side check of bus.cpp's ETA timestamp parser
#
# Everything else about the bus scene needs the panel, the network, or a bus.
# This does not: parseIso8601() and daysFromCivil() are pure functions, and
# they are the one place where a silent off-by-one would show up as a wrong
# arrival time rather than as an error -- the worst failure this feature has.
#
# The functions are extracted from app/bus.cpp rather than copied, so this
# cannot drift away from what actually ships.
#
#   ./tools/test_bus_parse.sh
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

python3 - "$ROOT/app/bus.cpp" "$OUT/t.cpp" <<'PY'
import sys
src = open(sys.argv[1]).read()

def grab(marker):
    i = src.index(marker)
    return src[i:src.index('\n}\n', i) + 3]

open(sys.argv[2], 'w').write(
    '#include <cstdio>\n#include <cstring>\n#include <ctime>\n#include <cstdint>\n'
    + grab('static long daysFromCivil')
    + grab('static time_t parseIso8601')
    + r'''
static int fails = 0;

// parseIso8601 returns 0 for "no arrival time", which absent, null and Citybus's
// empty string all mean. Epoch 0 collides with that by construction; a bus
// arriving on 1 Jan 1970 is not a case worth separating.
static void chk(const char* s, const char* expectUtc) {
  time_t t = parseIso8601(s);
  char buf[40] = "(none)";
  if (t) { struct tm g; gmtime_r(&t, &g); strftime(buf, sizeof buf, "%Y-%m-%dT%H:%M:%SZ", &g); }
  bool ok = strcmp(buf, expectUtc) == 0;
  if (!ok) fails++;
  printf("  %-32s -> %-22s %s\n", *s ? s : "\"\"", buf, ok ? "ok" : "FAIL");
  if (!ok) printf("  %35s expected %s\n", "", expectUtc);
}

int main() {
  printf("KMB / Citybus -- absolute, no milliseconds\n");
  chk("2026-08-11T02:34:12+08:00", "2026-08-10T18:34:12Z");
  chk("2026-12-31T23:59:59+08:00", "2026-12-31T15:59:59Z");

  printf("GMB -- milliseconds, and ONLY GMB\n");
  chk("2026-08-11T02:21:36.138+08:00", "2026-08-10T18:21:36Z");

  printf("\"no time\" spellings: KMB sends null, Citybus sends an empty string\n");
  chk("", "(none)");
  chk("null", "(none)");
  chk("2026-08-11", "(none)");

  printf("offset forms\n");
  chk("2026-08-11T02:34:12+0800", "2026-08-10T18:34:12Z");   // compact
  chk("2026-08-11T02:34:12Z",     "2026-08-11T02:34:12Z");   // zulu
  chk("2026-08-11T02:34:12",      "2026-08-11T02:34:12Z");   // bare = UTC
  chk("2026-08-10T20:00:00-05:00","2026-08-11T01:00:00Z");   // negative

  printf("calendar edges\n");
  chk("2028-02-29T12:00:00+08:00", "2028-02-29T04:00:00Z");  // leap day
  chk("2027-01-01T00:00:00+08:00", "2026-12-31T16:00:00Z");  // year rollover
  chk("2100-03-01T00:00:00Z",      "2100-03-01T00:00:00Z");  // 2100 is NOT leap
  chk("2000-02-29T00:00:00Z",      "2000-02-29T00:00:00Z");  // 2000 IS leap

  printf("\n%s\n", fails ? "FAILED" : "all passed");
  return fails != 0;
}
''')
PY

c++ -O1 -Wall -o "$OUT/t" "$OUT/t.cpp" || { echo "compile failed"; exit 1; }
"$OUT/t"
