#!/usr/bin/env python3
"""
Turn TFT_eSPI's C font tables into flat binary assets the Android app can load.

Why this exists
---------------
Every x-position in scenes.cpp is derived from textWidth(): drawDegVal chains
`nx = x + textWidth(s)`, weatherStat centres, pressureStat puts the trend glyph
at `cx + textWidth/2 + 6`. A substituted TTF gets the heights right and the
per-character widths wrong, and the wrongness compounds along every chained x.
So the real tables get ported, not approximated.

Input  (downloaded into tools/tft_espi_fonts/, gitignored):
    Font16.c    Font 2, 16 px, uncompressed, 1..2 bytes per row, MSB left
    Font32rle.c Font 4, 26 px, run-length encoded
    Font64rle.c Font 6, 48 px, run-length encoded
    Font72rle.c Font 8, 75 px, run-length encoded

Output (app/src/main/assets/fonts/tft_fN.bin), little-endian:
    magic   4s   b"TFTF"
    version u8   1
    fontNo  u8   2 | 4 | 6 | 8
    height  u8
    format  u8   0 = RAW (row-padded bitmap), 1 = RLE
    then 96 glyph records, for ASCII 32..127:
        advance u8   what textWidth() sums -- the layout width
        bmpW    u8   how many columns the glyph data actually covers
        offset  u32  into the blob
        length  u16
    then the blob.

Advance and bitmap width are not the same number, and the difference is not the
same in both formats:

    Font 2 (RAW)  widtbl carries a +1 spacing column that is NOT in the bitmap.
                  '#' has advance 9 but 8 ink columns, so one byte per row.
                  'W' has advance 10 and 9 columns, so two bytes per row.
    Fonts 4/6/8   the spacing is baked into the encoded bitmap, so the RLE
        (RLE)     stream decodes to advance * height pixels exactly.

Getting this backwards shifts every chained x-position, so both numbers are
written out rather than recomputed on the Kotlin side.

Glyphs are shared: in Font 8 every non-digit points at the same space glyph, and
that is load-bearing -- it is *why* drawDegVal switches to Font 2 for the unit
letter. Shared glyphs are de-duplicated in the blob, exactly as chrtbl_fNN does.

Run:  py tools/extract_tft_fonts.py
"""

import re
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = HERE / "tft_espi_fonts"
OUT = HERE.parent / "app" / "src" / "main" / "assets" / "fonts"

FMT_RAW = 0
FMT_RLE = 1

# (source file, tag used in the C identifiers, TFT_eSPI font number, height, format)
FONTS = [
    ("Font16.c",    "f16", 2, 16, FMT_RAW),
    ("Font32rle.c", "f32", 4, 26, FMT_RLE),
    ("Font64rle.c", "f64", 6, 48, FMT_RLE),
    ("Font72rle.c", "f72", 8, 75, FMT_RLE),
]


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def resolve_ifdefs(text: str) -> str:
    """
    Font16.c guards the width of char 0x60 behind TFT_ESPI_GRAVE_IS_DEGREE, which
    the file #defines at the top. Take the defined branch so the widths match a
    stock TFT_eSPI build -- that is what the panel was rendering.
    """
    defined = set(re.findall(r"^\s*#define\s+(\w+)", text, flags=re.M))

    out, skip_stack, emitting = [], [], True
    for line in text.splitlines(keepends=True):
        s = line.strip()
        if s.startswith("#ifdef "):
            skip_stack.append(emitting)
            emitting = emitting and (s.split()[1] in defined)
        elif s.startswith("#ifndef "):
            skip_stack.append(emitting)
            emitting = emitting and (s.split()[1] not in defined)
        elif s.startswith("#else"):
            if skip_stack:
                emitting = skip_stack[-1] and not emitting
        elif s.startswith("#endif"):
            emitting = skip_stack.pop() if skip_stack else True
        elif s.startswith("#define") or s.startswith("#include"):
            pass
        elif emitting:
            out.append(line)
    return "".join(out)


def parse_bytes(body: str) -> list[int]:
    return [int(v, 0) for v in re.findall(r"0[xX][0-9a-fA-F]+|\b\d+\b", body)]


def extract(path: Path, tag: str):
    raw = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
    text = resolve_ifdefs(raw)

    m = re.search(rf"widtbl_{tag}\s*\[\s*96\s*\]\s*=\s*\{{(.*?)\}}", text, flags=re.S)
    if not m:
        sys.exit(f"{path.name}: no widtbl_{tag}")
    widths = parse_bytes(m.group(1))
    if len(widths) != 96:
        sys.exit(f"{path.name}: widtbl_{tag} had {len(widths)} entries, expected 96")

    glyphs = {}
    for gm in re.finditer(rf"chr_{tag}_([0-9A-Fa-f]+)\s*\[[^\]]*\]\s*=\s*\{{(.*?)\}}", text, flags=re.S):
        glyphs[f"chr_{tag}_{gm.group(1).upper()}"] = bytes(parse_bytes(gm.group(2)))
    if not glyphs:
        sys.exit(f"{path.name}: no chr_{tag}_* arrays")

    m = re.search(rf"chrtbl_{tag}\s*\[\s*96\s*\]\s*=\s*\{{(.*?)\}}", text, flags=re.S)
    if not m:
        sys.exit(f"{path.name}: no chrtbl_{tag}")
    # Only the hex suffix is case-normalised; the prefix stays as written, so
    # these keys match the ones built above.
    names = [f"chr_{tag}_{h.upper()}" for h in re.findall(rf"chr_{tag}_([0-9A-Fa-f]+)", m.group(1))]
    if len(names) != 96:
        sys.exit(f"{path.name}: chrtbl_{tag} had {len(names)} entries, expected 96")

    return widths, names, glyphs


def bitmap_width(advance: int, fmt: int) -> int:
    return advance - 1 if fmt == FMT_RAW else advance


def build(font_no: int, height: int, fmt: int, widths, names, glyphs) -> bytes:
    blob = bytearray()
    placed: dict[str, tuple[int, int]] = {}      # name -> (offset, length)
    records = []

    for i in range(96):
        name = names[i]
        if name not in glyphs:
            sys.exit(f"font {font_no}: chrtbl references unknown glyph {name}")
        if name not in placed:                    # de-duplicate shared glyphs
            data = glyphs[name]
            placed[name] = (len(blob), len(data))
            blob += data
        off, ln = placed[name]
        records.append((widths[i], bitmap_width(widths[i], fmt), off, ln))

    head = struct.pack("<4sBBBB", b"TFTF", 1, font_no, height, fmt)
    table = b"".join(struct.pack("<BBIH", a, bw, o, l) for (a, bw, o, l) in records)
    return head + table + bytes(blob)


def verify(font_no: int, height: int, fmt: int, widths, names, glyphs) -> int:
    """
    Check every glyph carries at least bitmapWidth * height pixels of data.

    It is *at least*, not *exactly*, and the reason matters. Fonts 6 and 8 only
    contain digits and a little punctuation; chrtbl points every other character
    at the shared space glyph, which is wider than those characters' own entries
    in widtbl. TFT_eSPI decodes until width*height pixels have been produced and
    simply never reads the remaining bytes, which is what makes those characters
    "print as a space". The decoder must bound the same way.

    Returns how many glyphs over-run, purely as a reported number.
    """
    overrun = 0
    for i in range(96):
        bw = bitmap_width(widths[i], fmt)
        data = glyphs[names[i]]
        if fmt == FMT_RAW:
            expect = ((bw + 7) // 8) * height
            if len(data) < expect:
                sys.exit(f"font {font_no} char {i+32} (w={widths[i]}): "
                         f"RAW size {len(data)} B, need {expect} B")
            if len(data) != expect:
                overrun += 1
        else:
            total = 0
            for b in data:
                total += (b & 0x7F) + 1 if b & 0x80 else b + 1
            if total < bw * height:
                sys.exit(f"font {font_no} char {i+32} (w={widths[i]}): "
                         f"RLE decodes to {total} px, need {bw * height}")
            if total != bw * height:
                overrun += 1
    return overrun


def main() -> None:
    if not SRC.is_dir():
        sys.exit(f"missing {SRC} -- download the TFT_eSPI Fonts/*.c files there first")
    OUT.mkdir(parents=True, exist_ok=True)

    for filename, tag, font_no, height, fmt in FONTS:
        path = SRC / filename
        if not path.is_file():
            sys.exit(f"missing {path}")
        widths, names, glyphs = extract(path, tag)
        overrun = verify(font_no, height, fmt, widths, names, glyphs)
        blob = build(font_no, height, fmt, widths, names, glyphs)
        (OUT / f"tft_f{font_no}.bin").write_bytes(blob)

        distinct = len(set(names))
        kind = "RAW" if fmt == FMT_RAW else "RLE"
        print(f"font {font_no}: h={height} {kind} {len(blob):6d} B  "
              f"{distinct:2d} distinct glyphs  widths {min(widths)}..{max(widths)}  "
              f"{overrun} share the space glyph")


if __name__ == "__main__":
    main()
