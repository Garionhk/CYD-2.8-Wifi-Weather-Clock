#!/usr/bin/env python3
# ---------------------------------------------------------------------------
# gen_zh_labels.py -- bake the bus scene's fixed Chinese vocabulary into
#                     app/zh_labels.h + app/zh_labels.cpp
# ---------------------------------------------------------------------------
# TFT_eSPI ships no CJK font: fonts 2/4/6/8 are ASCII-only, and a .vlw smooth
# font covering a usable Hong Kong character set costs 1.35 MB at 16 px (too
# small to read across a room) and 2.04 MB at 20 px (does not fit the 1.83 MB
# free). u8g2's CJK fonts are GB2312 -- Simplified only, so wrong for Hong Kong.
#
# The set of Chinese strings the firmware itself needs is about twenty, so the
# constraint disappears if we bake only those: ~400 B per label at ANY size.
# Stop names and destinations are the user's, not ours, and are baked by the
# browser at config time instead (webconfig.cpp /label -> LittleFS).
#
# Output format, per label: [0]=width, [1]=height, then packed 1-bpp rows,
# MSB first, each row padded up to a whole byte. That is deliberately the exact
# layout TFT_eSPI::drawBitmap(x,y,bmp,w,h,fg,bg) already accepts, so there is no
# custom blitter, and the fg/bg overload gives the compare-and-redraw partial
# repaint this project uses everywhere.
#
#   python3 tools/gen_zh_labels.py            # regenerate
#   python3 tools/gen_zh_labels.py --preview  # ...and show them as ASCII art
#
# Requires Pillow.  The generated files are checked in, so a normal build never
# runs this -- but keeping it beside them is what makes the bitmaps auditable.
# ---------------------------------------------------------------------------

import argparse
import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("Pillow is required:  python3 -m pip install Pillow")

# ---------------------------------------------------------------------------
# Font
# ---------------------------------------------------------------------------
# Traditional Chinese, and it must be a Hei (sans) face: a Ming/Song face's
# hairline horizontals are the first thing to vanish when a 1-bit threshold is
# applied at 18 px. Heiti TC is on every macOS; the rest are fallbacks so this
# is not a machine-specific script.
FONT_CANDIDATES = [
    ("/System/Library/Fonts/STHeiti Medium.ttc", 0),   # Heiti TC   (macOS)
    ("/System/Library/Fonts/PingFang.ttc", 2),         # PingFang TC (newer macOS)
    ("/Library/Fonts/NotoSansHK-Medium.otf", 0),
    ("/usr/share/fonts/opentype/noto/NotoSansCJKtc-Medium.otf", 0),
    ("C:/Windows/Fonts/msjh.ttc", 0),                  # Microsoft JhengHei
]

# ---------------------------------------------------------------------------
# The vocabulary
# ---------------------------------------------------------------------------
# Sizes are chosen against the 88 px row layout in theme.h: the right-hand
# column is 88 px wide, so a four-character label has to be <= 22 px per glyph
# to fit it, and the three-character states get 24 px because they can.
#
# Adding a string here means regenerating and rebuilding -- that is the whole
# trade. Anything the DEVICE cannot know in advance (stop names, destinations)
# must not go in this list; it is baked by the browser instead.
LABELS = [
    # (C identifier,   text,       px)   purpose
    ("FEN_ZHONG",     "分鐘",      18),   # under the big minutes count
    ("ARRIVING",      "即將到站",   20),   # <= 1 min -- replaces the number
    ("NO_SERVICE",    "無服務",     24),   # fetch fine, no ETA rows
    ("NOT_SET",       "未設定",     24),   # slot unconfigured
    ("NO_CLOCK",      "未校時",     24),   # NTP never synced -- no countdown
    ("NO_DATA",       "無數據",     24),   # never had a successful fetch
    ("CHECK_STOP",    "檢查車站",   20),   # empty for 6 h: stop id likely stale
    ("SCHEDULED",     "未開出",     18),   # rmk: has not left the terminus
    ("DEPARTED",      "已開出",     18),   # rmk: departed
    ("LAST_BUS",      "尾班車",     18),   # rmk: last bus of the day
    ("TIMETABLE",     "原定班次",   18),   # rmk: timetable, not a live estimate
    ("TO",            "往",        18),   # "to <destination>" prefix
    ("STOP_WORD",     "站",        18),   # generic, for fallback rendering
    ("UPDATED",       "更新",       16),   # header: "updated 47s ago"
    ("SEC_AGO",       "秒前",       16),
    ("MIN_AGO",       "分前",       16),
    ("NEXT_BUS",      "下一班車",   20),   # scene title / unconfigured screen
    ("LOADING",       "載入中",     24),   # first fetch has not landed yet
]


def load_font(px):
    for path, index in FONT_CANDIDATES:
        if not os.path.exists(path):
            continue
        try:
            return ImageFont.truetype(path, px, index=index), path, index
        except OSError:
            continue
    sys.exit("No Traditional Chinese font found. Edit FONT_CANDIDATES.")


def render(text, px):
    """Rasterise `text` to an 8-bit alpha bitmap, plus its ink bbox."""
    font, _, _ = load_font(px)
    ascent, descent = font.getmetrics()
    # Draw into a generous box anchored at the ascender line, so every label of
    # a given size shares one baseline before any cropping happens.
    w = px * len(text) * 2 + 8
    h = ascent + descent + 4
    img = Image.new("L", (w, h), 0)
    ImageDraw.Draw(img).text((2, 2), text, font=font, fill=255, anchor="la")
    return img


def pack(img, box):
    """Crop to `box` and pack to 1 bpp, MSB first, rows padded to a byte."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    px = img.load()
    row_bytes = (w + 7) // 8
    out = bytearray([w, h])
    for y in range(y0, y1):
        for bx in range(row_bytes):
            b = 0
            for bit in range(8):
                x = x0 + bx * 8 + bit
                # alpha > 128: the midpoint. Lower and the antialiased fringe
                # fattens every stroke into a blob; higher and the interior
                # strokes of dense glyphs (灣, 觀) drop out first.
                if x < x1 and px[x, y] > 128:
                    b |= 0x80 >> bit
            out.append(b)
    return bytes(out), w, h


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true",
                    help="print each label as ASCII art for eyeball checking")
    args = ap.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    _, font_path, font_index = load_font(18)

    # Rasterise everything first: labels of the same px share one vertical
    # window (the union of their ink rows) so they line up when drawn at the
    # same y. Horizontal cropping is per-label -- each one is positioned by its
    # own width, so there is nothing to keep in step and no reason to pad.
    rendered = {}
    for ident, text, px in LABELS:
        rendered[ident] = (render(text, px), text, px)

    vband = {}
    for ident, text, px in LABELS:
        bb = rendered[ident][0].getbbox()
        if bb is None:
            sys.exit(f"'{text}' rendered blank -- the font lacks those glyphs.")
        lo, hi = vband.get(px, (10**6, -1))
        vband[px] = (min(lo, bb[1]), max(hi, bb[3]))

    blobs = []
    for ident, text, px in LABELS:
        img = rendered[ident][0]
        bb = img.getbbox()
        y0, y1 = vband[px]
        data, w, h = pack(img, (bb[0], y0, bb[2], y1))
        blobs.append((ident, text, px, data, w, h))
        if args.preview:
            print(f"\n{text}  ({ident}, {px}px, {w}x{h}, {len(data)} B)")
            row_bytes = (w + 7) // 8
            for y in range(h):
                line = "".join(
                    "#" if data[2 + y * row_bytes + (x >> 3)] & (0x80 >> (x & 7)) else "."
                    for x in range(w))
                print("  " + line)

    total = sum(len(b[3]) for b in blobs)
    banner = ("// Generated by tools/gen_zh_labels.py -- DO NOT EDIT BY HAND.\n"
              f"// Font: {os.path.basename(font_path)} (face {font_index}).\n"
              f"// {len(blobs)} labels, {total} bytes of bitmap.\n")

    # --- header: the enum, and nothing that costs flash per translation unit.
    h_lines = [banner, "#pragma once\n", "#include <Arduino.h>\n\n",
               "enum ZhLabel : uint8_t {\n"]
    for ident, text, px, _, w, hgt in blobs:
        h_lines.append(f"  ZH_{ident:<12} // {text}  {px}px, {w}x{hgt}\n"
                       .replace(f"ZH_{ident:<12}", f"ZH_{ident},".ljust(16)))
    h_lines.append("  ZH_COUNT\n};\n\n")
    h_lines.append(
        "// Blob layout: [0]=w, [1]=h, then packed 1-bpp rows, MSB first, each\n"
        "// row padded to a whole byte -- exactly what TFT_eSPI::drawBitmap wants.\n"
        "extern const uint8_t* const ZH_LABELS[ZH_COUNT];\n")

    # --- source: the data. Kept out of the header so it is emitted once, not
    #     once per translation unit that wants the enum.
    c_lines = [banner, '#include "zh_labels.h"\n', "#include <pgmspace.h>\n\n"]
    for ident, text, px, data, w, hgt in blobs:
        c_lines.append(f"// {text}  --  {px}px, {w}x{hgt}, {len(data)} B\n")
        c_lines.append(f"static const uint8_t ZH_{ident}_BITS[] PROGMEM = {{\n")
        for i in range(0, len(data), 16):
            c_lines.append("  " + ", ".join(f"0x{b:02X}" for b in data[i:i + 16]) + ",\n")
        c_lines.append("};\n\n")
    c_lines.append("const uint8_t* const ZH_LABELS[ZH_COUNT] = {\n")
    for ident, *_ in blobs:
        c_lines.append(f"  ZH_{ident}_BITS,\n")
    c_lines.append("};\n")

    with open(os.path.join(root, "app", "zh_labels.h"), "w") as f:
        f.write("".join(h_lines))
    with open(os.path.join(root, "app", "zh_labels.cpp"), "w") as f:
        f.write("".join(c_lines))

    print(f"\napp/zh_labels.{{h,cpp}}: {len(blobs)} labels, {total} B of bitmap "
          f"({os.path.basename(font_path)} face {font_index})")


if __name__ == "__main__":
    main()
