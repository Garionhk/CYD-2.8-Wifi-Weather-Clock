#include "labels.h"
#include "theme.h"
#include "uitext.h"
#include "settings.h"   // BUS_SLOTS
#include <LittleFS.h>
#include <string.h>

// One shared read buffer for user labels, sized by the LABEL_MAX_* caps.
// Deliberately static rather than heap: this scene runs every few minutes for
// the life of the device, and clockEnter needs a 10 KB contiguous allocation
// every time the clock comes round. A per-draw malloc/free of ~900 B is exactly
// the pattern that leaves getMaxAllocHeap fine on paper and the sprite failing
// in practice after a few hours (see the WARN in scenes.cpp).
static const size_t LABEL_BUF = 2 + ((LABEL_MAX_W + 7) / 8) * LABEL_MAX_H;
static uint8_t  userBuf[LABEL_BUF];
static int      userBufSlot  = -1;      // which (slot, which) userBuf holds
static bool     fsOk = false;

// Back in /l, where 1-bpp labels have always lived. /l4 was the 4-bpp
// experiment's directory; migrateFrom4bpp below empties it on the way past.
static const char* LABEL_DIR = "/l";

// Convert any 4-bpp labels back to 1 bpp.
//
// The alpha format shipped briefly and was withdrawn: it rendered in wrong
// colours on the panel (pushImage needs setSwapBytes; see blit above), and
// antialiased Chinese was not worth owning a blit path for. A device that took
// that build has its labels in /l4 and an empty /l, so this walks them back --
// a nibble of 8 or more becomes ink, which is the same midpoint the generator
// thresholds at, and is exact for labels that were themselves converted up from
// 1 bpp because those nibbles are only ever 0x0 or 0xF.
//
// A device that never took that build has no /l4 and skips this entirely.
bool label_saveUser(int slot, UserLabel which, const uint8_t* data, size_t len);

static void migrateFrom4bpp() {
  if (!LittleFS.exists("/l4")) return;
  if (!LittleFS.exists(LABEL_DIR)) LittleFS.mkdir(LABEL_DIR);

  int moved = 0;
  for (int slot = 0; slot < BUS_SLOTS; slot++) {
    for (int w = 0; w < 2; w++) {
      const UserLabel which = (w == 0) ? UL_STOP : UL_DEST;

      char oldPath[24];
      snprintf(oldPath, sizeof(oldPath), "/l4/%d%c.bin",
               slot, which == UL_STOP ? 's' : 'd');
      fs::File f = LittleFS.open(oldPath, "r");
      if (!f) continue;

      uint8_t hdr[2];
      if (f.read(hdr, 2) != 2) { f.close(); continue; }
      const int lw = hdr[0], lh = hdr[1];
      if (lw == 0 || lh == 0 || lw > LABEL_MAX_W || lh > LABEL_MAX_H) {
        f.close();
        continue;
      }

      // Row at a time: the 4-bpp source row is up to 116 bytes of stack, rather
      // than a second full-size buffer competing with the one userBuf holds.
      const int srcRow = (lw + 1) / 2;
      const int dstRow = (lw + 7) / 8;
      userBuf[0] = (uint8_t)lw;
      userBuf[1] = (uint8_t)lh;

      bool ok = true;
      uint8_t row[(LABEL_MAX_W + 1) / 2];
      for (int y = 0; y < lh && ok; y++) {
        if (f.read(row, srcRow) != srcRow) { ok = false; break; }
        uint8_t* dst = userBuf + 2 + (size_t)y * dstRow;
        memset(dst, 0, dstRow);
        for (int x = 0; x < lw; x++) {
          const uint8_t two = row[x >> 1];
          const uint8_t nib = (x & 1) ? (two & 0x0F) : (two >> 4);
          if (nib >= 8) dst[x >> 3] |= 0x80 >> (x & 7);
        }
      }
      f.close();
      if (!ok) {
        Serial.printf("labels: %s is truncated -- not migrating\n", oldPath);
        continue;
      }

      userBufSlot = -1;                       // userBuf no longer holds a read
      if (label_saveUser(slot, which, userBuf, 2 + (size_t)dstRow * lh)) moved++;
    }
  }

  for (int slot = 0; slot < BUS_SLOTS; slot++) {
    for (int w = 0; w < 2; w++) {
      char p[24];
      snprintf(p, sizeof(p), "/l4/%d%c.bin", slot, w == 0 ? 's' : 'd');
      LittleFS.remove(p);
    }
  }
  LittleFS.rmdir("/l4");
  Serial.printf("labels: migrated %d label(s) from 4-bpp /l4 back to %s\n",
                moved, LABEL_DIR);
}

void label_begin() {
  // begin(true) formats when the partition has never held a filesystem, which
  // is every device flashed before this firmware existed. It costs a couple of
  // seconds, once, and the alternative is a permanently broken label store.
  fsOk = LittleFS.begin(true);
  if (!fsOk) {
    Serial.println("labels: LittleFS mount FAILED -- user labels unavailable, "
                   "English names will be used");
    return;
  }
  if (!LittleFS.exists(LABEL_DIR)) LittleFS.mkdir(LABEL_DIR);

  migrateFrom4bpp();

  Serial.printf("labels: LittleFS %u/%u bytes used\n",
                (unsigned)LittleFS.usedBytes(), (unsigned)LittleFS.totalBytes());
}

bool label_fsReady() { return fsOk; }

// ---------------------------------------------------------------------------
// Shared draw
// ---------------------------------------------------------------------------

// `y` is the vertical centre; `align` picks what `x` means horizontally.
//
// One library call, and that is the point. The 4-bpp version of this built
// RGB565 rows by hand and pushed them through pushImage -- which needs
// setSwapBytes(true), because pushImage sends a uint16_t array as raw
// little-endian bytes while the ILI9341 expects each pixel high byte first.
// Without it every label came out in wrong colours. drawBitmap has no such
// trap: it takes the two colours as arguments and does its own byte order, the
// same as every other draw call in this project.
static void blit(const uint8_t* blob, int x, int y, LabelAlign align,
                 uint16_t fg, uint16_t bg) {
  const int w = blob[0], h = blob[1];
  int x0 = x;
  if (align == LBL_CENTRE) x0 = x - w / 2;
  else if (align == LBL_RIGHT) x0 = x - w;
  tft.drawBitmap(x0, y - h / 2, blob + 2, w, h, fg, bg);
}

// ---------------------------------------------------------------------------
// Fixed vocabulary
// ---------------------------------------------------------------------------

int label_width (ZhLabel id) { return (id < ZH_COUNT) ? ZH_LABELS[id][0] : 0; }
int label_height(ZhLabel id) { return (id < ZH_COUNT) ? ZH_LABELS[id][1] : 0; }

void label_draw(ZhLabel id, int x, int y, LabelAlign align, uint16_t fg, uint16_t bg) {
  if (id >= ZH_COUNT) return;
  blit(ZH_LABELS[id], x, y, align, fg, bg);
}

// ---------------------------------------------------------------------------
// User labels
// ---------------------------------------------------------------------------

static void userPath(int slot, UserLabel which, char* out, size_t n) {
  snprintf(out, n, "%s/%d%c.bin", LABEL_DIR, slot, which == UL_STOP ? 's' : 'd');
}

// Pull one label into userBuf, unless it is already there. The one-entry cache
// is what keeps a page flip from re-reading the same four files twice.
static bool loadUser(int slot, UserLabel which) {
  if (!fsOk) return false;
  const int key = slot * 2 + (int)which;
  if (userBufSlot == key) return userBuf[0] != 0;

  userBufSlot = key;
  userBuf[0] = 0;                       // marks "loaded, and it is not there"

  char path[24];
  userPath(slot, which, path, sizeof(path));
  fs::File f = LittleFS.open(path, "r");
  if (!f) return false;

  const size_t n = f.read(userBuf, sizeof(userBuf));
  f.close();

  // A blob shorter than its own header claims is a truncated write -- a power
  // cut mid-upload -- and drawing it would read past the end of the buffer.
  const size_t need = 2 + ((size_t)((userBuf[0] + 7) / 8)) * userBuf[1];
  if (n < 3 || userBuf[0] == 0 || userBuf[1] == 0 ||
      userBuf[0] > LABEL_MAX_W || userBuf[1] > LABEL_MAX_H || n < need) {
    Serial.printf("labels: %s is malformed (%u B, header says %ux%u) -- ignoring\n",
                  path, (unsigned)n, userBuf[0], userBuf[1]);
    userBuf[0] = 0;
    return false;
  }
  return true;
}

bool label_hasUser(int slot, UserLabel which) { return loadUser(slot, which); }

int label_userWidth(int slot, UserLabel which) {
  return loadUser(slot, which) ? userBuf[0] : 0;
}

bool label_drawUser(int slot, UserLabel which, int x, int y,
                    LabelAlign align, uint16_t fg, uint16_t bg) {
  if (!loadUser(slot, which)) return false;
  blit(userBuf, x, y, align, fg, bg);
  return true;
}

bool label_saveUser(int slot, UserLabel which, const uint8_t* data, size_t len) {
  if (!fsOk || len < 3) return false;
  const int w = data[0], h = data[1];
  if (w == 0 || h == 0 || w > LABEL_MAX_W || h > LABEL_MAX_H) {
    Serial.printf("labels: rejecting %dx%d blob (max %dx%d)\n",
                  w, h, LABEL_MAX_W, LABEL_MAX_H);
    return false;
  }
  if (len < 2 + (size_t)((w + 7) / 8) * h) {
    Serial.printf("labels: rejecting short blob (%u B for %dx%d)\n", (unsigned)len, w, h);
    return false;
  }

  char path[24];
  userPath(slot, which, path, sizeof(path));
  fs::File f = LittleFS.open(path, "w");
  if (!f) return false;
  const size_t wrote = f.write(data, len);
  f.close();

  userBufSlot = -1;                     // invalidate the one-entry cache
  Serial.printf("labels: wrote %s  %dx%d  %u B\n", path, w, h, (unsigned)wrote);
  return wrote == len;
}

// ---------------------------------------------------------------------------
// Self test
// ---------------------------------------------------------------------------

void label_selfTest(uint32_t holdMs) {
  tft.fillScreen(COL_BG);
  tft.setTextColor(COL_ACCENT, COL_BG);
  tft.setTextDatum(TL_DATUM);
  font_use(UI_FONT_S);
  tft.drawString("zh label self-test", 4, 2);

  // Laid out at the sizes the scene actually uses, so what you are checking is
  // what will ship -- a proof at 32 px says nothing about 18 px, which is where
  // 1-bit thresholding first hurts.
  //
  // With the vocabulary now past ninety entries this shows only the first
  // screenful; tools/preview_labels.py renders the whole set on the host,
  // from the same generated bytes, and is the better instrument for a sweep.
  // This one earns its keep by proving the DEVICE draws them.
  int x = 4, y = 34;
  for (uint8_t i = 0; i < ZH_COUNT; i++) {
    const ZhLabel id = (ZhLabel)i;
    const int w = label_width(id), h = label_height(id);
    if (x + w > SCREEN_W - 4) { x = 4; y += 30; }
    if (y + h / 2 > SCREEN_H) break;
    label_draw(id, x, y, LBL_LEFT, COL_TEXT, COL_BG);
    x += w + 8;
  }

  Serial.printf("labels: self-test drew %d fixed labels; user slots:", (int)ZH_COUNT);
  for (int s = 0; s < BUS_SLOTS; s++)
    Serial.printf(" %d=%s/%s", s,
                  label_hasUser(s, UL_STOP) ? "stop" : "-",
                  label_hasUser(s, UL_DEST) ? "dest" : "-");
  Serial.println();
  delay(holdMs);
}

void label_clearUser(int slot) {
  if (!fsOk) return;
  char path[24];
  userPath(slot, UL_STOP, path, sizeof(path)); LittleFS.remove(path);
  userPath(slot, UL_DEST, path, sizeof(path)); LittleFS.remove(path);
  userBufSlot = -1;
}
