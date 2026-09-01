#include "settings.h"
#include <Preferences.h>

Settings g_settings;

static Preferences prefs;
static const char* NVS_NS = "cydcfg";

// Key names are capped at 15 chars by NVS; these are all well inside it.
// "ok" is the sentinel that gates the whole record, matching touch.cpp.

// ---------------------------------------------------------------------------
// Bus stops
// ---------------------------------------------------------------------------
// Packed layout, twelve fields, '|' separated:
//
//   op|route|stopId|serviceType|dir|routeId|routeSeq|stopSeq|stopTc|stopEn|destTc|destEn
//
// About 120 B per slot with the Chinese names as UTF-8. The names come from the
// operators' own APIs and have never contained a pipe, but a name that did
// would silently shift every field after it, so the writer strips them.

static const char BUS_SEP = '|';

bool BusStop::valid() const {
  if (route.isEmpty()) return false;
  if (op == OP_GMB)    return routeId != 0 && routeSeq != 0 && stopSeq != 0;
  return !stopId.isEmpty();
}

// Pull field `n` out of a packed string. Returns "" for a field that isn't
// there, so a short record from an older firmware reads as defaults rather
// than as garbage.
static String busField(const String& s, int n) {
  int start = 0;
  for (int i = 0; i < n; i++) {
    start = s.indexOf(BUS_SEP, start);
    if (start < 0) return String();
    start++;
  }
  const int end = s.indexOf(BUS_SEP, start);
  return (end < 0) ? s.substring(start) : s.substring(start, end);
}

static String busClean(const String& s) {
  String o = s;
  o.replace(BUS_SEP, ' ');
  o.trim();
  return o;
}

String busStop_pack(const BusStop& b) {
  if (!b.valid()) return String();
  String o;
  o.reserve(160);
  o += String((int)b.op);        o += BUS_SEP;
  o += busClean(b.route);        o += BUS_SEP;
  o += busClean(b.stopId);       o += BUS_SEP;
  o += String((int)b.serviceType); o += BUS_SEP;
  o += b.dir;                    o += BUS_SEP;
  o += String(b.routeId);        o += BUS_SEP;
  o += String((int)b.routeSeq);  o += BUS_SEP;
  o += String((int)b.stopSeq);   o += BUS_SEP;
  o += busClean(b.stopTc);       o += BUS_SEP;
  o += busClean(b.stopEn);       o += BUS_SEP;
  o += busClean(b.destTc);       o += BUS_SEP;
  o += busClean(b.destEn);
  return o;
}

bool busStop_unpack(const String& packed, BusStop& out) {
  out = BusStop();                       // reset, so a bad parse leaves nothing
  String s = packed;
  s.trim();
  if (s.isEmpty()) return false;

  const int op = busField(s, 0).toInt();
  out.op = (op >= OP_KMB && op <= OP_LWB) ? (BusOperator)op : OP_KMB;
  out.route  = busField(s, 1);
  out.stopId = busField(s, 2);

  const int st = busField(s, 3).toInt();
  out.serviceType = (st >= 1 && st <= 255) ? (uint8_t)st : 1;

  const String d = busField(s, 4);
  // Anything that is not an explicit 'I' is outbound. A blank or corrupted
  // direction filtering everything out would look exactly like "no bus today",
  // which is the one failure this scene must not be able to fake.
  out.dir = (d.length() && (d[0] == 'I' || d[0] == 'i')) ? 'I' : 'O';

  out.routeId  = (uint32_t)busField(s, 5).toInt();
  out.routeSeq = (uint8_t)busField(s, 6).toInt();
  out.stopSeq  = (uint8_t)busField(s, 7).toInt();
  out.stopTc   = busField(s, 8);
  out.stopEn   = busField(s, 9);
  out.destTc   = busField(s, 10);
  out.destEn   = busField(s, 11);

  return out.valid();
}

// ---------------------------------------------------------------------------
// Scene rotation
// ---------------------------------------------------------------------------
// Two short strings rather than ten keys: "10111" and "35,12,12,12,60". Keeping
// the dwell for a scene that is switched off is deliberate -- turning Air
// Quality back on should restore the timing you chose for it, not reset it.

static String scenesPackOn() {
  String o;
  for (int i = 0; i < SCENE_SLOTS; i++) o += g_settings.sceneOn[i] ? '1' : '0';
  return o;
}

static String scenesPackDwell() {
  String o;
  for (int i = 0; i < SCENE_SLOTS; i++) {
    if (i) o += ',';
    o += String((unsigned)g_settings.sceneDwellS[i]);
  }
  return o;
}

// A stored record shorter than SCENE_SLOTS -- which is what a device saved
// before a scene was added will have -- leaves the extra entries at their
// compiled defaults rather than at zero.
static void scenesUnpack(const String& on, const String& dwell) {
  for (int i = 0; i < SCENE_SLOTS && i < (int)on.length(); i++)
    g_settings.sceneOn[i] = (on[i] != '0');

  int start = 0;
  for (int i = 0; i < SCENE_SLOTS && start <= (int)dwell.length(); i++) {
    const int end = dwell.indexOf(',', start);
    const String tok = (end < 0) ? dwell.substring(start) : dwell.substring(start, end);
    const long v = tok.toInt();
    if (v >= SCENE_DWELL_MIN_S && v <= SCENE_DWELL_MAX_S)
      g_settings.sceneDwellS[i] = (uint16_t)v;
    if (end < 0) break;
    start = end + 1;
  }
}

String busStop_describe(const BusStop& b) {
  if (!b.valid()) return String("(unset)");
  static const char* NAMES[] = { "KMB", "CTB", "GMB", "LWB" };
  String o = NAMES[b.op];
  o += ' ';
  o += b.route;
  if (b.op == OP_GMB) {
    o += " seq " + String(b.routeSeq) + "/" + String(b.stopSeq)
       + " route " + String(b.routeId);
  } else {
    o += ' ';
    o += b.dir;
    if (b.usesKmbApi()) { o += " st"; o += String((int)b.serviceType); }
    o += " @" + b.stopId;
  }
  const String& name = b.stopTc.length() ? b.stopTc : b.stopEn;
  if (name.length()) o += "  \"" + name + "\"";
  return o;
}

void settings_begin() {
  // Opened read-write even though nothing is written: opening a namespace that
  // does not exist yet read-only logs an NVS error on every first boot.
  prefs.begin(NVS_NS, false);
  if (prefs.getBool("ok", false)) {
    g_settings.wifiSsid  = prefs.getString("ssid",  g_settings.wifiSsid);
    g_settings.wifiPass  = prefs.getString("pass",  g_settings.wifiPass);
    g_settings.latitude  = prefs.getFloat ("lat",   g_settings.latitude);
    g_settings.longitude = prefs.getFloat ("lon",   g_settings.longitude);
    g_settings.tz        = prefs.getString("tz",    g_settings.tz);
    g_settings.units     = prefs.getUChar ("units", g_settings.units);
    g_settings.lang      = prefs.getUChar ("lang",  g_settings.lang);
    // Absent on a device provisioned before this setting existed, and
    // getBool then hands back CYD_TFT_INVERT -- exactly the behaviour that
    // firmware had. An upgrade cannot turn a working panel inside out.
    g_settings.invert    = prefs.getBool  ("invert", g_settings.invert);

    // No migration needed for devices provisioned before the bus scene existed:
    // the key simply isn't there, getString returns the default "", and
    // busStop_unpack leaves the slot unset -- which is the correct state.
    for (int i = 0; i < BUS_SLOTS; i++) {
      char key[8];
      snprintf(key, sizeof(key), "bus%d", i);
      busStop_unpack(prefs.getString(key, String()), g_settings.buses[i]);
    }
    scenesUnpack(prefs.getString("scOn",    scenesPackOn()),
                 prefs.getString("scDwell", scenesPackDwell()));
    g_settings.provisioned = true;
  }
  prefs.end();

  // The SSID is printed because a router broadcasts it anyway and "connected to
  // the wrong network" is otherwise painful to diagnose. The password never is:
  // serial logs end up pasted into issue reports.
  Serial.printf("settings: %s  ssid \"%s\"  %.4f,%.4f  tz %s  %s  %s  invert %s\n",
                g_settings.provisioned ? "loaded" : "UNPROVISIONED (defaults)",
                g_settings.wifiSsid.c_str(),
                g_settings.latitude, g_settings.longitude,
                g_settings.tz.c_str(),
                g_settings.units == UNITS_IMPERIAL ? "imperial" : "metric",
                g_settings.lang == LANG_ZH ? "zh" : "en",
                g_settings.invert ? "on" : "off");

  for (int i = 0; i < BUS_SLOTS; i++)
    Serial.printf("settings: bus%d %s\n", i, busStop_describe(g_settings.buses[i]).c_str());

  Serial.printf("settings: scenes on %s  dwell %s\n",
                scenesPackOn().c_str(), scenesPackDwell().c_str());
}

bool settings_save() {
  prefs.begin(NVS_NS, false);
  prefs.putString("ssid",  g_settings.wifiSsid);
  prefs.putString("pass",  g_settings.wifiPass);
  prefs.putFloat ("lat",   g_settings.latitude);
  prefs.putFloat ("lon",   g_settings.longitude);
  prefs.putString("tz",    g_settings.tz);
  prefs.putUChar ("units", g_settings.units);
  prefs.putUChar ("lang",  g_settings.lang);
  prefs.putBool  ("invert", g_settings.invert);
  for (int i = 0; i < BUS_SLOTS; i++) {
    char key[8];
    snprintf(key, sizeof(key), "bus%d", i);
    prefs.putString(key, busStop_pack(g_settings.buses[i]));   // "" when unset
  }
  prefs.putString("scOn",    scenesPackOn());
  prefs.putString("scDwell", scenesPackDwell());
  prefs.putBool  ("ok",    true);        // written last: a half-written record
  prefs.end();                           // must not read back as valid
  g_settings.provisioned = true;
  Serial.println("settings: saved to NVS");
  return true;
}

void settings_clear() {
  prefs.begin(NVS_NS, false);
  prefs.clear();
  prefs.end();
  g_settings.provisioned = false;
  Serial.println("settings: cleared");
}
