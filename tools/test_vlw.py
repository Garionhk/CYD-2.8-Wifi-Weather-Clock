#!/usr/bin/env python3
# ---------------------------------------------------------------------------
# test_vlw.py -- read app/ui_fonts.cpp back the way TFT_eSPI reads it
# ---------------------------------------------------------------------------
# A .vlw has no magic number and no length field, so TFT_eSPI cannot tell a
# malformed font from a good one -- loadFont() accepts whatever it is given and
# the first sign of trouble is garbage on the panel. That makes the generator's
# output exactly the kind of thing worth checking on the host, where the failure
# is a stack trace instead of a drive across town with a USB cable.
#
# This parses the generated arrays using loadMetrics()'s semantics, asserts the
# structural invariants that parser depends on, and then renders a sample string
# per subset with drawGlyph()'s placement arithmetic -- so a wrong dY or a
# mis-stated advance shows up as visibly broken text rather than as a number
# that happens to be in range.
#
#   python3 tools/test_vlw.py             # structural checks
#   python3 tools/test_vlw.py --render D  # ...and write samples to D/ as PNGs
# ---------------------------------------------------------------------------

import argparse
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "ui_fonts.cpp")

SAMPLES = {
    "S":  "Partly Cloudy 21°C",
    "M":  "Sun 24 Aug 2026",
    "L":  "-7° 0123456789",
    "XL": "0123456789-",
}


def arrays(path):
    """Pull every `static const uint8_t NAME[] PROGMEM = {...}` out of the .cpp."""
    src = open(path).read()
    for m in re.finditer(
            r"static const uint8_t UI_FONT_(\w+?)_VLW\[\] PROGMEM = \{(.*?)\};",
            src, re.S):
        ident, body = m.group(1), m.group(2)
        data = bytes(int(b, 16) for b in re.findall(r"0x([0-9A-Fa-f]{2})", body))
        yield ident, data


def parse(data):
    """Exactly what loadFont + loadMetrics do, and nothing they do not."""
    if len(data) < 24:
        raise ValueError("shorter than the 24-byte header")
    count, _ver, size_pt, _pad, ascent, descent = struct.unpack(">6i", data[:24])
    if count <= 0:
        raise ValueError(f"glyph count {count}")

    header_ptr = 24
    bitmap_ptr = header_ptr + count * 28
    if len(data) < bitmap_ptr:
        raise ValueError(f"metrics table runs {bitmap_ptr - len(data)} bytes past EOF")

    glyphs, ptr = [], bitmap_ptr
    max_descent = descent
    for i in range(count):
        off = header_ptr + i * 28
        code, h, w, adv, dy, dx, _ = struct.unpack(">7i", data[off:off + 28])
        # loadMetrics narrows these on the way in; a value that does not survive
        # the narrowing is a bug the device would render rather than report.
        if not 0 <= h <= 255: raise ValueError(f"U+{code:04X} height {h} truncates to uint8")
        if not 0 <= w <= 255: raise ValueError(f"U+{code:04X} width {w} truncates to uint8")
        if not 0 <= adv <= 255: raise ValueError(f"U+{code:04X} xAdvance {adv} truncates to uint8")
        if not -128 <= dx <= 127: raise ValueError(f"U+{code:04X} dX {dx} truncates to int8")
        if not -32768 <= dy <= 32767: raise ValueError(f"U+{code:04X} dY {dy} truncates to int16")
        if code == 0x20:
            raise ValueError("U+0020 is present; drawGlyph never looks it up "
                             "and the bytes are dead weight")
        # The guard loadMetrics puts on maxDescent, reproduced so the height
        # this script reports is the height the device will report.
        if (0x20 < code < 0xA0 and code != 0x7F) or code > 0xFF:
            max_descent = max(max_descent, h - dy)
        glyphs.append(dict(code=code, w=w, h=h, adv=adv, dx=dx, dy=dy, at=ptr))
        ptr += w * h

    if ptr != len(data):
        raise ValueError(f"bitmaps end at {ptr} but the array is {len(data)} bytes "
                         f"({'short' if ptr > len(data) else 'trailing junk'})")

    codes = [g["code"] for g in glyphs]
    if len(set(codes)) != len(codes):
        raise ValueError("duplicate codepoint -- getUnicodeIndex returns the first")

    return dict(count=count, size_pt=size_pt, ascent=ascent, descent=descent,
                max_ascent=ascent, max_descent=max_descent,
                height=ascent + max_descent,
                space=(ascent + descent) * 2 // 7, glyphs=glyphs)


def render(font, data, text, path):
    """Lay `text` out the way drawGlyph does, for eyeballing."""
    from PIL import Image
    by_code = {g["code"]: g for g in font["glyphs"]}
    width = sum(font["space"] if ch == " " else
                by_code[ord(ch)]["adv"] for ch in text if ch == " " or ord(ch) in by_code)
    img = Image.new("L", (width + 8, font["height"] + 8), 0)

    x = 4
    for ch in text:
        if ch == " ":
            x += font["space"]
            continue
        g = by_code.get(ord(ch))
        if g is None:
            raise ValueError(f"sample uses U+{ord(ch):04X}, which is not in the subset")
        # cy = cursor_y + maxAscent - dY, cx = cursor_x + dX
        gx, gy = x + g["dx"], 4 + font["max_ascent"] - g["dy"]
        if g["w"] and g["h"]:
            glyph = Image.frombytes("L", (g["w"], g["h"]),
                                    data[g["at"]:g["at"] + g["w"] * g["h"]])
            img.paste(glyph, (gx, gy))
        x += g["adv"]
    img.save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--render", metavar="DIR",
                    help="also write a rendered sample per subset into DIR")
    args = ap.parse_args()

    if args.render:
        os.makedirs(args.render, exist_ok=True)

    failures = 0
    for ident, data in arrays(SRC):
        try:
            font = parse(data)
        except ValueError as e:
            print(f"  UI_FONT_{ident:<3} FAIL  {e}")
            failures += 1
            continue

        ink = sum(g["w"] * g["h"] for g in font["glyphs"])
        print(f"  UI_FONT_{ident:<3} ok    {font['count']:>3} glyphs  "
              f"{len(data):>6} B ({ink} ink)  fontHeight() = {font['height']}  "
              f"ascent {font['max_ascent']}  descent {font['max_descent']}  "
              f"space {font['space']}")

        if args.render:
            out = os.path.join(args.render, f"ui_font_{ident.lower()}.png")
            try:
                render(font, data, SAMPLES.get(ident, "0123456789"), out)
                print(f"                  -> {out}")
            except ValueError as e:
                print(f"                  render FAIL  {e}")
                failures += 1

    if failures:
        sys.exit(f"\n{failures} problem(s)")
    print("\nall passed")


if __name__ == "__main__":
    main()
