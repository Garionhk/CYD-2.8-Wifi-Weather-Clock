#include "labels.h"
#include "theme.h"
#include "settings.h"   // BUS_SLOTS
#include <LittleFS.h>

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
  if (!LittleFS.exists("/l")) LittleFS.mkdir("/l");
  Serial.printf("labels: LittleFS %u/%u bytes used\n",
                (unsigned)LittleFS.usedBytes(), (unsigned)LittleFS.totalBytes());
}

bool label_fsReady() { return fsOk; }

// ---------------------------------------------------------------------------
// Shared draw
// ---------------------------------------------------------------------------

// `y` is the vertical centre; `align` picks what `x` means horizontally.
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
  snprintf(out, n, "/l/%d%c.bin", slot, which == UL_STOP ? 's' : 'd');
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
  tft.setTextFont(2);
  tft.drawString("zh label self-test", 4, 2);

  // Laid out at the sizes the scene actually uses, so what you are checking is
  // what will ship -- a proof at 32 px says nothing about 18 px, which is where
  // 1-bit thresholding first hurts.
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
