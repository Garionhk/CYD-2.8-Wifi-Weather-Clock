#!/usr/bin/env python3
# ---------------------------------------------------------------------------
# gen_vlw.py -- bake anti-aliased Latin fonts into app/ui_fonts.{h,cpp}
# ---------------------------------------------------------------------------
# TFT_eSPI's built-in fonts 2/4/6/8 are 1-bit bitmaps: every stroke is a hard
# edge, which at 16 px makes small text look like a fax and at 75 px turns the
# clock's diagonals into staircases. The library can already do better -- it
# understands .vlw "smooth" fonts, 8-bit alpha per pixel, and SMOOTH_FONT has
# been set in our User_Setup templates since the beginning. Nothing used it.
#
# This bakes four subsets. Subsets, not whole fonts, because cost is per glyph:
# a full ASCII set at 75 px is ~350 KB, while the eleven characters the clock
# actually draws are ~20 KB. Nothing here carries a glyph it does not name.
#
#   python3 tools/gen_vlw.py            # regenerate
#   python3 tools/gen_vlw.py --metrics  # ...and show the solved sizes
#
# Requires Pillow. The generated files are checked in, so a normal build never
# runs this -- but keeping it beside them is what makes the bitmaps auditable.
#
# ---------------------------------------------------------------------------
# The .vlw container, as TFT_eSPI 2.5.43 actually parses it
# ---------------------------------------------------------------------------
# Everything is big-endian int32. There is no magic number and no length field,
# so a malformed file is not rejected -- it is simply drawn wrong.
#
#   header, 24 B:  glyphCount, version, fontSizePt, <ignored>, ascent, descent
#   metrics, 28 B x glyphCount, in the order the bitmaps follow:
#                  codepoint, height, width, xAdvance, dY, dX, <ignored>
#   bitmaps:       width*height bytes of 8-bit alpha, concatenated, unpadded
#
# dY is the glyph's height above the baseline; dX is its left side bearing.
# The library takes maxAscent straight from the header but derives maxDescent
# from the glyphs, then reports fontHeight() as maxAscent + maxDescent. Every
# MC/ML/MR datum in this project centres against that number, which is why the
# pixel size below is solved for rather than guessed.
#
# U+0020 is deliberately absent from every subset: drawGlyph never looks it up,
# it uses gFont.spaceWidth, which loadMetrics computes as (ascent+descent)*2/7.
# ---------------------------------------------------------------------------

import argparse
import os
import struct
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("Pillow is required:  python3 -m pip install Pillow")

# ---------------------------------------------------------------------------
# Font
# ---------------------------------------------------------------------------
# Helvetica Neue, to sit with the Chinese: the labels are baked from STHeiti,
# and Heiti was drawn as the Han companion to exactly this kind of grotesque.
# Pairing it with something geometric (Avenir, Futura) puts two different
# centuries on the same row.
#
# The clock keeps its LCD character. Font 8 was TFT_eSPI's 7-segment face, and
# a proportional grotesque at 75 px is a different object on a wall -- so the XL
# subset is baked from DSEG7 Classic instead, which is a real seven-segment
# outline and antialiases like any other. It is SIL OFL and vendored in-tree
# beside its licence, because a build that silently depends on a font the user
# happens to have installed is not reproducible.
#
# Two faces, then, and each subset names the one it wants.
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FACES = {
    # Body text and everything proportional.
    "sans": [
        ("/System/Library/Fonts/HelveticaNeue.ttc", 0),
        ("/System/Library/Fonts/Helvetica.ttc", 0),
        ("/System/Library/Fonts/Supplemental/Arial.ttf", 0),
        ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 0),
        ("C:/Windows/Fonts/arial.ttf", 0),
    ],
    # The clock and the AQI headline. No fallback on purpose: if the vendored
    # file is missing the build should stop, not quietly render the clock in
    # Helvetica and leave you wondering when it changed.
    "seg7": [
        (os.path.join(ROOT, "tools", "fonts", "DSEG7Classic-Bold.ttf"), 0),
    ],
}

ASCII = "".join(chr(c) for c in range(0x21, 0x7F))
DEGREE = "\u00b0"

# ---------------------------------------------------------------------------
# The subsets
# ---------------------------------------------------------------------------
# Each names the built-in font it replaces and the height it must hit; the pixel
# size is solved for, not guessed, because it moves with the face.
#
# Two ways to hit it, because two kinds of text are being replaced:
#
#   "box"  mixed-case text. Match fontHeight() to the built-in's, so every
#          existing datum lands on the same pixel it does today. The face's own
#          ascent and descent go into the header, so descenders keep their room.
#
#   "ink"  digits only -- the clock, the temperature, the bus countdown. Here
#          the box is the wrong thing to match: Font 8's digits fill all 75 px,
#          whereas Helvetica's inside a 75 px box are ~53 px of ink, and the
#          clock would visibly shrink. So match the DIGIT height instead and
#          write ascent = that height, descent = 0. fontHeight() then equals the
#          ink height, and drawGlyph centres the digits exactly -- no fudge
#          factor here and none in the caller.
#
# The degree sign and the arrows are here so drawDegVal() and triMark() can stop
# hand-drawing a ring out of drawCircle and a wedge out of fillTriangle; both
# say in their comments that they exist only because the built-ins lack glyphs.
# max_w caps the widest glyph, because height is not the only thing a layout
# reserves. The clock draws each digit into a DIGIT_W = 60 px sprite cell, and
# DSEG7 is a wide face -- at the size that hits 75 px of height it is 61 px
# across, which would clip by a pixel on every digit. Where a cap binds before
# the height target does, the solver honours the cap and reports the shortfall.
SUBSETS = [
    # (id,  face,   mode,  height, max_w, replaces, characters)
    # No arrows: Helvetica Neue has neither U+2191 nor U+2193, and triMark draws
    # a cleaner 9 px wedge with fillTriangle than any text face manages at that
    # size anyway. The tofu guard in measure() is what caught the attempt.
    ("S",  "sans", "box", 16, None, "Font 2", ASCII + DEGREE),
    ("M",  "sans", "box", 26, None, "Font 4", ASCII + DEGREE),
    # The weather temperature and the bus countdown stay proportional: both sit
    # right beside lettering (°C, 分鐘) that a segment face cannot draw at all.
    ("L",  "sans", "ink", 48, 44, "Font 6", "0123456789-." + DEGREE),
    # 58, not 60: the sprite is exactly DIGIT_W wide and drawString centres on
    # the ink, so a glyph that measures the full cell has nowhere to round to.
    ("XL", "seg7", "ink", 75, 58, "Font 8", "0123456789-"),
]


def load_font(face, px):
    for path, index in FACES[face]:
        if not os.path.exists(path):
            continue
        try:
            return ImageFont.truetype(path, px, index=index), path, index
        except OSError:
            continue
    sys.exit(f"No usable file for face {face!r}. Looked at: "
             + ", ".join(p for p, _ in FACES[face]))


def glyph(font, ch):
    """One glyph as (alpha bytes, w, h, xAdvance, dX, dY)."""
    # anchor="ls" puts the origin on the baseline at the pen position, so the
    # bbox arrives in exactly the coordinates .vlw wants: x0 is the left side
    # bearing, -y0 is the height above the baseline.
    x0, y0, x1, y1 = font.getbbox(ch, anchor="ls")
    w, h = x1 - x0, y1 - y0
    adv = int(round(font.getlength(ch)))
    if w <= 0 or h <= 0:
        return b"", 0, 0, adv, 0, 0

    # Draw against a generous pad so a negative bearing or a tall accent cannot
    # fall off the canvas before it is cropped back to the bbox.
    pad = max(8, h)
    img = Image.new("L", (w + 2 * pad, h + 2 * pad), 0)
    ImageDraw.Draw(img).text((pad - x0, pad - y0), ch, font=font, fill=255, anchor="ls")
    return img.crop((pad, pad, pad + w, pad + h)).tobytes(), w, h, adv, x0, -y0


# A codepoint no real face assigns. Whatever the face draws for this IS its
# .notdef, and any glyph that rasterises identically is .notdef too.
TOFU_PROBE = "\ue123"


def tofu_signature(font):
    """What this face draws for a character it does not have."""
    mask = font.getmask(TOFU_PROBE, mode="L")
    return (mask.size, bytes(mask))


def measure(face, px, chars, mode):
    """Rasterise one subset at `px`; return (glyphs, ascent, descent, height)."""
    font, _, _ = load_font(face, px)
    face_ascent, face_descent = font.getmetrics()
    tofu = tofu_signature(font)

    glyphs = []
    for ch in chars:
        # A missing glyph is not blank -- it is the .notdef box, which has ink,
        # a width and an advance, and sails through every other check here. It
        # reaches the panel as a rectangle. Helvetica Neue has no U+2191, and
        # that is exactly how the arrows first shipped into a build.
        mask = font.getmask(ch, mode="L")
        if (mask.size, bytes(mask)) == tofu:
            sys.exit(f"U+{ord(ch):04X} ({ch!r}) is not in this face -- it "
                     f"rasterised as .notdef. Pick another character or another "
                     f"face; do not ship a box.")

        data, w, h, adv, dx, dy = glyph(font, ch)
        if w == 0:
            sys.exit(f"U+{ord(ch):04X} rendered blank -- the face lacks it.")
        if w > 255 or h > 255:
            sys.exit(f"U+{ord(ch):04X} is {w}x{h} -- .vlw stores those in a byte.")
        if not -128 <= dx <= 127:
            sys.exit(f"U+{ord(ch):04X} has dX {dx}, outside int8.")
        glyphs.append((ord(ch), data, w, h, adv, dx, dy))

    if mode == "ink":
        # The tallest glyph defines the box, and there is nothing below the
        # baseline in a digit set, so the box IS the ink.
        ascent = max(g[6] for g in glyphs)
        descent = 0
    else:
        ascent, descent = face_ascent, face_descent

    # Mirror loadMetrics: maxDescent starts at the header value and only grows.
    max_descent = max([descent] + [h - dy for _, _, _, h, _, _, dy in glyphs])
    return glyphs, ascent, descent, ascent + max_descent


def widest_glyph(glyphs):
    """Ink extent of the widest glyph, bearing included."""
    return max(g[2] + max(0, g[5]) for g in glyphs)


def solve(face, chars, mode, target, max_w):
    """Largest px that fits inside both the height target and the width cap.

    Neither constraint is quite monotonic in px -- hinting moves an edge by a
    pixel here and there -- so this walks the whole range and keeps the best
    rather than stopping at the first violation.
    """
    best = None
    for px in range(6, 240):
        glyphs, ascent, descent, height = measure(face, px, chars, mode)
        if height > target:
            continue
        if max_w is not None and widest_glyph(glyphs) > max_w:
            continue
        best = (px, glyphs, ascent, descent, height)
    if best is None:
        sys.exit(f"no pixel size fits height <= {target}"
                 + (f" and width <= {max_w}" if max_w else "")
                 + f" for face {face!r}")
    return best


def pack(glyphs, ascent, descent, px):
    glyphs = sorted(glyphs, key=lambda g: g[0])   # tidiness; the lookup is linear
    out = bytearray()
    out += struct.pack(">6i", len(glyphs), 11, px, 0, ascent, descent)
    for code, _, w, h, adv, dx, dy in glyphs:
        out += struct.pack(">7i", code, h, w, adv, dy, dx, 0)
    for g in glyphs:
        out += g[1]
    return bytes(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--metrics", action="store_true",
                    help="print the solved pixel size and height of each subset")
    args = ap.parse_args()

    built, faces_used = [], []
    for ident, face, mode, target, max_w, replaces, chars in SUBSETS:
        px, glyphs, ascent, descent, height = solve(face, chars, mode, target, max_w)
        blob = pack(glyphs, ascent, descent, px)
        _, path, _ = load_font(face, px)
        name = os.path.basename(path)
        if name not in faces_used:
            faces_used.append(name)
        widest = widest_glyph(glyphs)
        built.append((ident, px, replaces, blob, len(glyphs), height, target,
                      mode, name, widest))
        if args.metrics:
            flag = ""
            if height != target:
                why = "width-capped" if max_w is not None else "face quantises"
                flag = f"  (target {target}; {why})"
            print(f"  UI_FONT_{ident:<2}  {px:>3} px  {len(glyphs):>3} glyphs  "
                  f"{len(blob):>6} B  {mode}  fontHeight() = {height}  "
                  f"widest {widest} px  {name}   replaces {replaces}{flag}")

    total = sum(len(b[3]) for b in built)
    banner = ("// Generated by tools/gen_vlw.py -- DO NOT EDIT BY HAND.\n"
              f"// Faces: {', '.join(faces_used)}.\n"
              f"// {len(built)} subsets, {total} bytes of .vlw.\n")

    h_lines = [banner, "#pragma once\n", "#include <Arduino.h>\n\n",
               "// Anti-aliased Latin, for TFT_eSPI::loadFont(const uint8_t[]).\n"
               "// Load these through font_use() in uitext.h, never directly:\n"
               "// only one smooth font can be loaded at a time, and every swap\n"
               "// reallocates its metrics, so the swap wants deduplicating.\n"
               "enum UiFont : uint8_t {\n"]
    for ident, px, replaces, _, n, height, _t, mode, name, widest in built:
        h_lines.append(f"  UI_FONT_{ident},".ljust(18) +
                       f"// {px} px {mode}, {n} glyphs, fontHeight() {height}, "
                       f"widest {widest} px -- was {replaces}\n")
    h_lines.append("  UI_FONT_COUNT\n};\n\n")
    h_lines.append("extern const uint8_t* const UI_FONTS[UI_FONT_COUNT];\n")

    c_lines = [banner, '#include "ui_fonts.h"\n', "#include <pgmspace.h>\n\n"]
    for ident, px, replaces, blob, n, height, _t, mode, name, widest in built:
        c_lines.append(f"// {ident}  --  {name}, {px} px {mode}, {n} glyphs, "
                       f"fontHeight() {height}, widest {widest} px, {len(blob)} B\n")
        c_lines.append(f"static const uint8_t UI_FONT_{ident}_VLW[] PROGMEM = {{\n")
        for i in range(0, len(blob), 16):
            c_lines.append("  " + ", ".join(f"0x{b:02X}" for b in blob[i:i + 16]) + ",\n")
        c_lines.append("};\n\n")
    c_lines.append("const uint8_t* const UI_FONTS[UI_FONT_COUNT] = {\n")
    for ident, *_ in built:
        c_lines.append(f"  UI_FONT_{ident}_VLW,\n")
    c_lines.append("};\n")

    with open(os.path.join(ROOT, "app", "ui_fonts.h"), "w") as f:
        f.write("".join(h_lines))
    with open(os.path.join(ROOT, "app", "ui_fonts.cpp"), "w") as f:
        f.write("".join(c_lines))

    print(f"\napp/ui_fonts.{{h,cpp}}: {len(built)} subsets, {total} B "
          f"({', '.join(faces_used)})")


if __name__ == "__main__":
    main()
