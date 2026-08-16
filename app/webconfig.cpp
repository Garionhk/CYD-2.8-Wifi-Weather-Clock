#include "webconfig.h"
#include "settings.h"

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
  "</script></div></body></html>";

static void sendPage(const String& error = String()) {
  String h;
  h.reserve(6000);
  h += FPSTR(PAGE_HEAD);

  if (error.length()) { h += F("<div class=err>"); h += esc(error); h += F("</div>"); }

  h += F("<form method=POST action=/save>");

  // --- WiFi
  h += F("<fieldset><legend>WiFi</legend><label>Network (2.4 GHz only)</label>"
         "<input name=ssid list=nets required value=\"");
  h += esc(g_settings.wifiSsid);
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
  h += String(g_settings.latitude, 4);
  h += F("\"></div><div><label>Longitude</label>"
         "<input name=lon type=number step=any min=-180 max=180 required value=\"");
  h += String(g_settings.longitude, 4);
  h += F("\"></div></div><div class=hint>Decimal degrees. Longitude is negative "
         "west of Greenwich, so everywhere in the Americas is negative. "
         "Right-click your location in Google Maps to copy the pair. "
         "Two decimals (~1&nbsp;km) is plenty.</div></fieldset>");

  // --- Time zone
  h += F("<fieldset><legend>Time zone</legend><label>Zone</label><select name=tzsel>");
  bool matched = false;
  for (int i = 0; i < TZ_COUNT; i++) {
    const bool sel = (g_settings.tz == TZ_OPTIONS[i].tz);
    if (sel) matched = true;
    h += F("<option value=\"");
    h += TZ_OPTIONS[i].tz;
    h += sel ? F("\" selected>") : F("\">");
    h += TZ_OPTIONS[i].label;
    h += F("</option>");
  }
  h += F("</select><label>Or a custom POSIX TZ string</label>"
         "<input name=tzcustom placeholder=\"e.g. PST8PDT,M3.2.0,M11.1.0\" value=\"");
  if (!matched) h += esc(g_settings.tz);   // keep a custom value visible
  h += F("\"><div class=hint>Currently <b>");
  h += esc(g_settings.tz);
  h += F("</b>. A custom value overrides the dropdown. These are POSIX strings, "
         "not names like Europe/London.</div></fieldset>");

  // --- Units
  h += F("<fieldset><legend>Units</legend><div class=radio>"
         "<label><input type=radio name=units value=0");
  h += (g_settings.units == UNITS_METRIC) ? F(" checked>") : F(">");
  h += F("Metric (&deg;C, km/h, hPa)</label>"
         "<label><input type=radio name=units value=1");
  h += (g_settings.units == UNITS_IMPERIAL) ? F(" checked>") : F(">");
  h += F("Imperial (&deg;F, mph, inHg)</label></div></fieldset>");

  h += FPSTR(PAGE_TAIL);
  server.send(200, "text/html", h);
}

// ---------------------------------------------------------------------------
// Handlers
// ---------------------------------------------------------------------------

static void handleRoot() { sendPage(); }

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
  if (err.length()) { sendPage(err); return; }

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
