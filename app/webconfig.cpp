#include "webconfig.h"
#include "settings.h"
#include "labels.h"
#include "bus_js.h"
#include <mbedtls/base64.h>

#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>

static WebServer server(80);
static DNSServer dns;

static bool   running = false;
static bool   apMode  = false;
static bool   saved   = false;
static String apSsidStr;

// ---------------------------------------------------------------------------
// Time zones
// ---------------------------------------------------------------------------
// POSIX TZ strings, not IANA names -- the ESP32's libc has no tz database, so
// the DST rules have to be spelled out. Anything not listed can be typed into
// the custom field.
struct TzOption { const char* label; const char* tz; };

static const TzOption TZ_OPTIONS[] = {
  { "UTC",                        "UTC0" },
  { "UK (London)",                "GMT0BST,M3.5.0/1,M10.5.0" },
  { "Central Europe (Paris)",     "CET-1CEST,M3.5.0,M10.5.0/3" },
  { "Eastern Europe (Athens)",    "EET-2EEST,M3.5.0/3,M10.5.0/4" },
  { "US Eastern (New York)",      "EST5EDT,M3.2.0,M11.1.0" },
  { "US Central (Chicago)",       "CST6CDT,M3.2.0,M11.1.0" },
  { "US Mountain (Denver)",       "MST7MDT,M3.2.0,M11.1.0" },
  { "US Arizona (no DST)",        "MST7" },
  { "US/Canada Pacific",          "PST8PDT,M3.2.0,M11.1.0" },
  { "Alaska",                     "AKST9AKDT,M3.2.0,M11.1.0" },
  { "Hawaii (no DST)",            "HST10" },
  { "Japan",                      "JST-9" },
  { "Korea",                      "KST-9" },
  { "China / Hong Kong / Taiwan", "CST-8" },
  { "Singapore",                  "SGT-8" },
  { "India",                      "IST-5:30" },
  { "Sydney / Melbourne",         "AEST-10AEDT,M10.1.0,M4.1.0/3" },
  { "Brisbane (no DST)",          "AEST-10" },
  { "Adelaide",                   "ACST-9:30ACDT,M10.1.0,M4.1.0/3" },
  { "Perth",                      "AWST-8" },
  { "New Zealand",                "NZST-12NZDT,M9.5.0,M4.1.0/3" },
  { "Moscow",                     "MSK-3" },
  { "Brazil (Sao Paulo)",         "<-03>3" },
};
static const int TZ_COUNT = sizeof(TZ_OPTIONS) / sizeof(TZ_OPTIONS[0]);

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

// An SSID may legitimately contain & " < >, which would otherwise break out of
// the value attribute and mangle the field.
static String esc(const String& s) {
  String o;
  o.reserve(s.length() + 8);
  for (unsigned i = 0; i < s.length(); i++) {
    char c = s[i];
    switch (c) {
      case '&':  o += F("&amp;");  break;
      case '<':  o += F("&lt;");   break;
      case '>':  o += F("&gt;");   break;
      case '"':  o += F("&quot;"); break;
      case '\'': o += F("&#39;");  break;
      default:   o += c;
    }
  }
  return o;
}

static const char PAGE_HEAD[] PROGMEM =
  "<!doctype html><html><head><meta charset=utf-8>"
  "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
  "<title>CYD Clock Setup</title><style>"
  "body{font-family:system-ui,-apple-system,sans-serif;background:#111;color:#eee;margin:0;padding:16px}"
  ".w{max-width:520px;margin:0 auto}"
  "h1{font-size:20px;margin:0 0 4px}"
  "p.sub{color:#888;margin:0 0 18px;font-size:13px}"
  "fieldset{border:1px solid #333;border-radius:8px;margin:0 0 16px;padding:12px}"
  "legend{color:#0dd;font-size:13px;padding:0 6px}"
  "label{display:block;margin:10px 0 4px;font-size:13px;color:#bbb}"
  "input,select{width:100%;box-sizing:border-box;padding:9px;border-radius:6px;"
  "border:1px solid #444;background:#1c1c1c;color:#eee;font-size:15px}"
  ".row{display:flex;gap:10px}.row>div{flex:1}"
  ".hint{color:#777;font-size:12px;margin-top:5px;line-height:1.4}"
  "button{width:100%;padding:12px;border:0;border-radius:6px;background:#0aa;"
  "color:#fff;font-size:16px;font-weight:600;margin-top:8px}"
  "button.sec{background:#2a2a2a;color:#ccc;font-weight:400;font-size:13px;padding:8px;margin-top:6px}"
  ".radio{display:flex;gap:18px;margin-top:6px;flex-wrap:wrap}"
  ".radio label{display:flex;align-items:center;gap:6px;margin:0;color:#eee}"
  ".radio input{width:auto}"
  ".err{background:#5a1a1a;border:1px solid #a33;padding:10px;border-radius:6px;"
  "margin-bottom:14px;font-size:14px}"
  ".slot{border-top:1px solid #2a2a2a;padding-top:6px;margin-top:10px}"
  ".slot:first-of-type{border-top:0;margin-top:0}"
  ".bsum{color:#0dd;font-size:13px;margin:2px 0 6px;word-break:break-all}"
  "details summary{color:#888;font-size:12px;cursor:pointer;margin-top:6px}"
  ".bpick select{margin-bottom:6px}"
  ".prev{background:#000;border:1px solid #333;border-radius:4px;margin-top:6px;"
  "image-rendering:pixelated;max-width:100%}"
  "</style></head><body><div class=w>"
  "<h1>CYD Clock &amp; Weather</h1>"
  "<p class=sub>Settings are stored on the device. Saving restarts it.</p>";

static const char PAGE_TAIL[] PROGMEM =
  "<button type=submit>Save &amp; restart</button></form>"
  "<script>"
  "function scan(){var m=document.getElementById('sm');m.textContent='Scanning...';"
  "fetch('/scan').then(function(r){return r.json()}).then(function(n){"
  "var d=document.getElementById('nets');d.innerHTML='';"
  "n.forEach(function(s){var o=document.createElement('option');o.value=s;d.appendChild(o)});"
  "m.textContent=n.length?n.length+' found - tap the field above to pick one':'none found';"
  "}).catch(function(){m.textContent='Scan failed'})}"
  "</script><script src=/bus.js defer></script></div></body></html>";

// What a field should show when the page is rendered.
//
// handleSave() calls sendPage(err) synchronously, while the POST body is still
// parsed, so the value the user actually typed is available -- and until now it
// was thrown away, because sendPage repopulated everything from g_settings. A
// rejected save silently reverted the form. That was survivable with four
// fields; after four bus drill-downs it would be infuriating.
//
// NOT used for `pass`, and that is deliberate: the password field renders as a
// placeholder rather than a value (see below), and echoing it back would put
// the WiFi password in the page source.
static String field(const char* name, const String& fallback) {
  return server.hasArg(name) ? server.arg(name) : fallback;
}

static void sendPage(const String& error = String()) {
  String h;
  // ~4.7-5.0 KB before the bus fieldset, which adds ~1.5-2 KB. A power of two
  // so the allocator can reuse the block rather than fragmenting a fresh one --
  // this String is built inside loop(), interleaved with clockEnter's 10 KB
  // contiguous sprite allocation, and that is the pairing that hurts.
  h.reserve(8192);
  h += FPSTR(PAGE_HEAD);

  if (error.length()) { h += F("<div class=err>"); h += esc(error); h += F("</div>"); }

  h += F("<form method=POST action=/save>");

  // --- WiFi
  h += F("<fieldset><legend>WiFi</legend><label>Network (2.4 GHz only)</label>"
         "<input name=ssid list=nets required value=\"");
  h += esc(field("ssid", g_settings.wifiSsid));
  h += F("\"><datalist id=nets></datalist>"
         "<button type=button class=sec onclick=scan()>Scan for networks</button>"
         "<div class=hint id=sm></div>"
         "<label>Password</label><input name=pass type=password placeholder=\"");
  h += g_settings.wifiPass.length() ? F("leave blank to keep saved password")
                                    : F("network password");
  h += F("\"><div class=hint>The ESP32 has no 5 GHz radio, so a 5 GHz-only "
         "network will not appear.</div></fieldset>");

  // --- Location
  h += F("<fieldset><legend>Location</legend><div class=row>"
         "<div><label>Latitude</label>"
         "<input name=lat type=number step=any min=-90 max=90 required value=\"");
  h += esc(field("lat", String(g_settings.latitude, 4)));
  h += F("\"></div><div><label>Longitude</label>"
         "<input name=lon type=number step=any min=-180 max=180 required value=\"");
  h += esc(field("lon", String(g_settings.longitude, 4)));
  h += F("\"></div></div><div class=hint>Decimal degrees. Longitude is negative "
         "west of Greenwich, so everywhere in the Americas is negative. "
         "Right-click your location in Google Maps to copy the pair. "
         "Two decimals (~1&nbsp;km) is plenty.</div></fieldset>");

  // --- Time zone
  h += F("<fieldset><legend>Time zone</legend><label>Zone</label><select name=tzsel>");
  const String tzsel = field("tzsel", g_settings.tz);
  bool matched = false;
  for (int i = 0; i < TZ_COUNT; i++) {
    const bool sel = (tzsel == TZ_OPTIONS[i].tz);
    if (sel) matched = true;
    h += F("<option value=\"");
    h += TZ_OPTIONS[i].tz;
    h += sel ? F("\" selected>") : F("\">");
    h += TZ_OPTIONS[i].label;
    h += F("</option>");
  }
  h += F("</select><label>Or a custom POSIX TZ string</label>"
         "<input name=tzcustom placeholder=\"e.g. PST8PDT,M3.2.0,M11.1.0\" value=\"");
  h += esc(field("tzcustom", matched ? String() : g_settings.tz));
  h += F("\"><div class=hint>Currently <b>");
  h += esc(g_settings.tz);
  h += F("</b>. A custom value overrides the dropdown. These are POSIX strings, "
         "not names like Europe/London.</div></fieldset>");

  // --- Units
  const bool imperial = field("units", String(g_settings.units)).toInt() == UNITS_IMPERIAL;
  h += F("<fieldset><legend>Units</legend><div class=radio>"
         "<label><input type=radio name=units value=0");
  h += imperial ? F(">") : F(" checked>");
  h += F("Metric (&deg;C, km/h, hPa)</label>"
         "<label><input type=radio name=units value=1");
  h += imperial ? F(" checked>") : F(">");
  h += F("Imperial (&deg;F, mph, inHg)</label></div></fieldset>");

  // --- Bus stops
  //
  // Structured so the page is fully usable with no JavaScript at all: each slot
  // is a plain text input holding the packed string, and /bus.js later hangs a
  // route picker above it that writes into the very same field. That is not a
  // fallback bolted on afterwards -- it is the primary contract, which is why
  // the picker can be dead (AP mode has no internet, by construction) and
  // saving still preserves every configured stop.
  h += F("<fieldset><legend>Next bus (Hong Kong)</legend>"
         "<div class=hint id=bne></div>");
  for (int i = 0; i < BUS_SLOTS; i++) {
    char nm[8];
    snprintf(nm, sizeof(nm), "bus%d", i);
    const String cur = field(nm, busStop_pack(g_settings.buses[i]));

    h += F("<div class=slot><label>Stop ");
    h += String(i + 1);
    h += F("</label><div class=bsum id=bsum");
    h += String(i);
    h += F(">");
    h += esc(busStop_describe(g_settings.buses[i]));
    h += F("</div><div id=bpick");
    h += String(i);
    h += F("></div><details><summary>Manual entry</summary><input name=");
    h += nm;
    h += F(" id=");
    h += nm;
    h += F(" value=\"");
    h += esc(cur);
    h += F("\"><div class=hint>op|route|stop|serviceType|dir|routeId|routeSeq|"
           "stopSeq|stopTC|stopEN|destTC|destEN &mdash; op 0=KMB 1=Citybus "
           "2=minibus 3=Long Win. Empty clears the slot.</div></details></div>");
  }
  h += F("<div class=hint>Chinese stop names are drawn on the display as images "
         "baked by this browser, because the device has no Chinese font. If you "
         "re-flash the firmware the names revert to English &mdash; open this "
         "page and save again to restore them.</div></fieldset>");

  h += FPSTR(PAGE_TAIL);
  const size_t len = h.length();
  server.send(200, "text/html", h);
  // Watch these three together: page length rising while getMaxAllocHeap falls
  // is the signature of the fragmentation this reserve() is guarding against.
  Serial.printf("webconfig: page %u B, heap %u, largest block %u\n",
                (unsigned)len, ESP.getFreeHeap(), ESP.getMaxAllocHeap());
}

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

// Set by /label when the browser uploads a freshly baked bitmap for a slot, and
// cleared whenever the form is served fresh. handleSave uses it to decide
// whether a changed stop name has left a stale bitmap behind: the browser
// uploads labels and then submits, so an upload in this page's lifetime means
// the bitmap matches what is being saved. A slot edited by hand in the manual
// field has no upload, so its old bitmap is wrong and gets dropped -- and the
// row falls back to the English name rather than showing the previous stop's.
static bool labelUploaded[BUS_SLOTS] = {};

static void handleRoot() {
  for (int i = 0; i < BUS_SLOTS; i++) labelUploaded[i] = false;
  sendPage();
}

static void handleScan() {
  const int n = WiFi.scanNetworks();
  String j = "[";
  for (int i = 0; i < n; i++) {
    String s = WiFi.SSID(i);
    if (s.isEmpty()) continue;          // hidden networks have nothing to show
    if (j.length() > 1) j += ',';
    j += '"';
    // JSON-escape the two characters that can appear in an SSID and break it.
    for (unsigned k = 0; k < s.length(); k++) {
      char c = s[k];
      if (c == '"' || c == '\\') j += '\\';
      j += c;
    }
    j += '"';
  }
  j += ']';
  WiFi.scanDelete();
  server.send(200, "application/json", j);
}

static void handleSave() {
  String ssid = server.arg("ssid");     ssid.trim();
  String pass = server.arg("pass");
  String tzc  = server.arg("tzcustom"); tzc.trim();
  String latS = server.arg("lat");      latS.trim();
  String lonS = server.arg("lon");      lonS.trim();

  const float lat = latS.toFloat();
  const float lon = lonS.toFloat();

  String err;
  if (ssid.isEmpty())                    err = F("Network name is required.");
  else if (latS.isEmpty() || lonS.isEmpty())
                                         err = F("Latitude and longitude are required.");
  else if (lat < -90.0f  || lat > 90.0f) err = F("Latitude must be between -90 and 90.");
  else if (lon < -180.0f || lon > 180.0f)
                                         err = F("Longitude must be between -180 and 180.");
  // Bus slots. Only shape can be checked here: confirming that a stop id really
  // exists would mean an internet call inside a request handler, which is
  // exactly the blocking the appliance rule in docs/decisions.md forbids. The
  // picker is the real validation -- the /stop/{id} call that fetched the name
  // IS the proof the id resolves -- and the scene's 檢查車站 state catches the
  // rest by watching a slot return nothing for six hours.
  BusStop parsed[BUS_SLOTS];
  for (int i = 0; i < BUS_SLOTS && err.isEmpty(); i++) {
    char nm[8];
    snprintf(nm, sizeof(nm), "bus%d", i);
    String v = server.arg(nm);
    v.trim();
    if (v.isEmpty()) continue;                       // an empty field clears it
    if (!busStop_unpack(v, parsed[i]))
      err = String(F("Stop ")) + String(i + 1) + F(" is not a valid entry.");
  }

  if (err.length()) { sendPage(err); return; }

  for (int i = 0; i < BUS_SLOTS; i++) {
    // The baked bitmap is a picture of exactly these two strings. If either
    // changed and the browser did not upload a replacement, the old picture is
    // now a picture of a different stop -- worse than no picture at all.
    const bool textChanged = (parsed[i].stopTc != g_settings.buses[i].stopTc) ||
                             (parsed[i].destTc != g_settings.buses[i].destTc);
    if (textChanged && !labelUploaded[i]) label_clearUser(i);
    g_settings.buses[i] = parsed[i];
  }

  g_settings.wifiSsid  = ssid;
  if (pass.length()) g_settings.wifiPass = pass;   // blank means "keep current"
  g_settings.latitude  = lat;
  g_settings.longitude = lon;
  g_settings.tz        = tzc.length() ? tzc : server.arg("tzsel");
  g_settings.units     = (server.arg("units").toInt() == UNITS_IMPERIAL)
                           ? UNITS_IMPERIAL : UNITS_METRIC;
  settings_save();
  saved = true;

  server.send(200, "text/html",
    F("<!doctype html><html><head><meta charset=utf-8>"
      "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
      "<title>Saved</title><style>body{font-family:system-ui,sans-serif;"
      "background:#111;color:#eee;padding:40px 16px;text-align:center}"
      "h1{color:#0dd;font-size:20px}p{color:#999;font-size:14px}</style></head>"
      "<body><h1>Saved</h1><p>The clock is restarting with the new settings.</p>"
      "<p>If you changed the network, this page will not reload &mdash; the "
      "device is joining the network you selected. Its address appears on the "
      "clock screen.</p></body></html>"));
}

static void handleBusJs() {
  // send_P streams from flash: nothing here is copied into a String.
  server.sendHeader("Cache-Control", "no-cache");
  server.send_P(200, "application/javascript", BUS_JS);
}

// One baked label. Separate from the settings form on purpose: a ~1 KB base64
// payload per label has no business inside sendPage()'s buffer, and this way a
// failed bake cannot take the whole save with it.
static void handleLabel() {
  const int slot = server.arg("s").toInt();
  const String which = server.arg("w");
  const String b64 = server.arg("d");

  if (slot < 0 || slot >= BUS_SLOTS || (which != "s" && which != "d") || b64.isEmpty()) {
    server.send(400, "text/plain", "bad request");
    return;
  }

  // Decoded output is always smaller than its base64, so the input length is a
  // safe bound for the buffer.
  size_t cap = (b64.length() * 3) / 4 + 4;
  uint8_t* buf = (uint8_t*)malloc(cap);
  if (!buf) { server.send(507, "text/plain", "out of memory"); return; }

  size_t n = 0;
  const int rc = mbedtls_base64_decode(buf, cap, &n,
                                       (const unsigned char*)b64.c_str(), b64.length());
  bool ok = (rc == 0) &&
            label_saveUser(slot, which == "s" ? UL_STOP : UL_DEST, buf, n);
  free(buf);

  if (ok) labelUploaded[slot] = true;
  server.send(ok ? 200 : 400, "text/plain", ok ? "ok" : "rejected");
}

static void handleNotFound() {
  if (apMode) {
    // Captive-portal bait: Android /generate_204, Apple /hotspot-detect.html and
    // Windows /connecttest.txt all land here and a 302 is what pops the portal.
    server.sendHeader("Location", "http://" + WiFi.softAPIP().toString() + "/", true);
    server.send(302, "text/plain", "");
  } else {
    server.send(404, "text/plain", "Not found");
  }
}

static void startServer() {
  if (running) return;
  server.on("/",      HTTP_GET,  handleRoot);
  server.on("/scan",  HTTP_GET,  handleScan);
  server.on("/save",  HTTP_POST, handleSave);
  server.on("/bus.js",HTTP_GET,  handleBusJs);
  server.on("/label", HTTP_POST, handleLabel);
  server.onNotFound(handleNotFound);
  server.begin();
  running = true;
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

static String makeApSsid() {
  const uint64_t mac = ESP.getEfuseMac();
  char buf[20];
  snprintf(buf, sizeof(buf), "CYD-Setup-%04X", (unsigned)((mac >> 32) & 0xFFFF));
  return String(buf);
}

void webconfig_beginSTA() {
  apMode = false;
  startServer();
  Serial.printf("webconfig: settings at http://%s/\n", WiFi.localIP().toString().c_str());
}

void webconfig_beginAP() {
  // AP_STA rather than AP: the station interface is what makes a scan possible,
  // and in the fallback case it also lets the existing reconnect loop keep
  // trying the real network while the portal stays reachable.
  WiFi.mode(WIFI_AP_STA);
  apSsidStr = makeApSsid();
  WiFi.softAP(apSsidStr.c_str());        // open network: nothing secret is
  delay(100);                            // served, and it saves typing a key
  dns.setErrorReplyCode(DNSReplyCode::NoError);
  dns.start(53, "*", WiFi.softAPIP());   // every lookup -> us, so any URL opens
  apMode = true;
  startServer();
  Serial.printf("webconfig: AP \"%s\" -> http://%s/\n",
                apSsidStr.c_str(), WiFi.softAPIP().toString().c_str());
}

void webconfig_stopAP() {
  if (!apMode) return;
  dns.stop();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_STA);
  apMode = false;
  Serial.println("webconfig: AP stopped, station connected");
}

void webconfig_tick() {
  if (!running) return;
  if (apMode) dns.processNextRequest();
  server.handleClient();
}

bool   webconfig_isAP()      { return apMode; }
bool   webconfig_isRunning() { return running; }
bool   webconfig_saved()     { return saved; }
String webconfig_apSsid()    { return apSsidStr; }

String webconfig_ip() {
  if (apMode)                        return WiFi.softAPIP().toString();
  if (WiFi.status() == WL_CONNECTED) return WiFi.localIP().toString();
  return String();
}
