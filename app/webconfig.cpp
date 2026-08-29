#include "webconfig.h"
#include "settings.h"
#include "labels.h"
#include "scenes.h"
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

// Opens at <style>, not at <!doctype>: sendPage emits the head before this so
// it can put a translated <title> and the __zh flag in front of everything.
static const char PAGE_HEAD[] PROGMEM =
  "<style>"
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
  ".srow{display:flex;align-items:center;gap:10px;margin:8px 0}"
  ".schk{flex:1;display:flex;align-items:center;gap:8px;margin:0;color:#eee;font-size:15px}"
  ".schk input{width:auto;flex:none}"
  ".sdw{color:#888;font-size:13px;white-space:nowrap}"
  ".sdw input{width:64px;display:inline-block;text-align:right;margin-right:3px}"
  ".slot{border-top:1px solid #2a2a2a;padding-top:6px;margin-top:10px}"
  ".slot:first-of-type{border-top:0;margin-top:0}"
  ".bsum{color:#0dd;font-size:13px;margin:2px 0 6px;word-break:break-all}"
  "details summary{color:#888;font-size:12px;cursor:pointer;margin-top:6px}"
  ".bpick select{margin-bottom:6px}"
  ".prev{background:#000;border:1px solid #333;border-radius:4px;margin-top:6px;"
  "image-rendering:pixelated;max-width:100%}"
  "</style></head><body><div class=w>";

// The heading and its subtitle, split out of PAGE_HEAD for the same reason the
// submit button was: a PROGMEM blob holds one language.

// The submit button is no longer in here: it is the one piece of PAGE_TAIL
// that has words in it, and a PROGMEM blob cannot carry two languages.
static const char PAGE_TAIL[] PROGMEM =
  "</form>"
  "<script>"
  "function T(en,zh){return window.__zh?zh:en}"
  "function scan(){var m=document.getElementById('sm');m.textContent=T('Scanning...','掃描中…');"
  "fetch('/scan').then(function(r){return r.json()}).then(function(n){"
  "var d=document.getElementById('nets');d.innerHTML='';"
  "n.forEach(function(s){var o=document.createElement('option');o.value=s;d.appendChild(o)});"
  "m.textContent=n.length?n.length+T(' found - tap the field above to pick one',' 個網絡 - 點上面的欄位選擇'):T('none found','找不到網絡');"
  "}).catch(function(){m.textContent=T('Scan failed','掃描失敗')})}"
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

// ---------------------------------------------------------------------------
// Bilingual page text
// ---------------------------------------------------------------------------
// The panel needs a whole baked-bitmap pipeline to show a Chinese character.
// This page needs none of it -- the browser has real fonts and PAGE_HEAD has
// declared <meta charset=utf-8> since the beginning. So the settings page is
// the one surface where translating is just picking a different literal.
//
// L() returns a flash string so the two alternatives cost the same RAM as the
// one literal did: neither is copied until it is appended.
//
// The language shown is the SAVED one, not the one selected in the form. A page
// that re-rendered itself in a new language on a rejected save would move every
// label the user was reading while they were reading it; the device reboots on
// a successful save anyway, and the reloaded page comes back translated.
static const __FlashStringHelper* L(const __FlashStringHelper* en,
                                    const __FlashStringHelper* zh) {
  return (g_settings.lang == LANG_ZH) ? zh : en;
}

static void sendPage(const String& error = String()) {
  String h;
  // ~4.7-5.0 KB before the bus fieldset, which adds ~1.5-2 KB. A power of two
  // so the allocator can reuse the block rather than fragmenting a fresh one --
  // this String is built inside loop(), interleaved with clockEnter's 10 KB
  // contiguous sprite allocation, and that is the pairing that hurts.
  h.reserve(8192);
  // Before PAGE_HEAD's <style>: the title has words in it, and __zh has to be
  // set before /bus.js runs so the picker comes up in the same language.
  h += F("<!doctype html><html><head><meta charset=utf-8>"
         "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
         "<title>");
  h += L(F("CYD Clock Setup"), F("CYD 時鐘設定"));
  h += F("</title><script>window.__zh=");
  h += (g_settings.lang == LANG_ZH) ? F("true") : F("false");
  h += F("</script>");
  h += FPSTR(PAGE_HEAD);
  h += F("<h1>CYD Clock &amp; Weather</h1><p class=sub>");
  h += L(F("Settings are stored on the device. Saving restarts it."),
         F("設定儲存在裝置上。儲存後會重新啟動。"));
  h += F("</p>");

  // True only when re-rendering a rejected POST. Checkboxes need it: an
  // unticked box is simply absent from the body, which is indistinguishable
  // from a fresh GET unless something says a form was submitted at all.
  const bool posted = server.hasArg("posted");

  if (error.length()) { h += F("<div class=err>"); h += esc(error); h += F("</div>"); }

  h += F("<form method=POST action=/save>"
         "<input type=hidden name=posted value=1>");

  // --- WiFi
  h += F("<fieldset><legend>WiFi</legend><label>");
  h += L(F("Network (2.4 GHz only)"), F("網絡（只支援 2.4 GHz）"));
  h += F("</label><input name=ssid list=nets required value=\"");
  h += esc(field("ssid", g_settings.wifiSsid));
  h += F("\"><datalist id=nets></datalist>"
         "<button type=button class=sec onclick=scan()>");
  h += L(F("Scan for networks"), F("掃描網絡"));
  h += F("</button><div class=hint id=sm></div><label>");
  h += L(F("Password"), F("密碼"));
  h += F("</label><input name=pass type=password placeholder=\"");
  h += g_settings.wifiPass.length()
         ? L(F("leave blank to keep saved password"), F("留空即保留已儲存的密碼"))
         : L(F("network password"), F("網絡密碼"));
  h += F("\"><div class=hint>");
  h += L(F("The ESP32 has no 5 GHz radio, so a 5 GHz-only network will not appear."),
         F("ESP32 沒有 5 GHz 無線電，因此只提供 5 GHz 的網絡不會出現。"));
  h += F("</div></fieldset>");

  // --- Location
  h += F("<fieldset><legend>");
  h += L(F("Location"), F("位置"));
  h += F("</legend><div class=row><div><label>");
  h += L(F("Latitude"), F("緯度"));
  h += F("</label><input name=lat type=number step=any min=-90 max=90 required value=\"");
  h += esc(field("lat", String(g_settings.latitude, 4)));
  h += F("\"></div><div><label>");
  h += L(F("Longitude"), F("經度"));
  h += F("</label><input name=lon type=number step=any min=-180 max=180 required value=\"");
  h += esc(field("lon", String(g_settings.longitude, 4)));
  h += F("\"></div></div><div class=hint>");
  h += L(F("Decimal degrees. Longitude is negative west of Greenwich, so "
           "everywhere in the Americas is negative. Right-click your location "
           "in Google Maps to copy the pair. Two decimals (~1&nbsp;km) is plenty."),
         F("十進制度數。格林威治以西的經度為負數，因此整個美洲都是負數。"
           "在 Google 地圖上右鍵點擊你的位置即可複製座標。兩位小數（約 1&nbsp;公里）已經足夠。"));
  h += F("</div></fieldset>");

  // --- Time zone
  h += F("<fieldset><legend>");
  h += L(F("Time zone"), F("時區"));
  h += F("</legend><label>");
  h += L(F("Zone"), F("時區"));
  h += F("</label><select name=tzsel>");
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
  h += F("</select><label>");
  h += L(F("Or a custom POSIX TZ string"), F("或自訂 POSIX TZ 字串"));
  h += F("</label><input name=tzcustom placeholder=\"e.g. PST8PDT,M3.2.0,M11.1.0\" value=\"");
  h += esc(field("tzcustom", matched ? String() : g_settings.tz));
  h += F("\"><div class=hint>");
  h += L(F("Currently <b>"), F("目前為 <b>"));
  h += esc(g_settings.tz);
  h += F("</b>. ");
  h += L(F("A custom value overrides the dropdown. These are POSIX strings, "
           "not names like Europe/London."),
         F("自訂值會覆蓋下拉選單。這些是 POSIX 字串，並非 Europe/London 這類名稱。"));
  h += F("</div></fieldset>");

  // --- Units
  const bool imperial = field("units", String(g_settings.units)).toInt() == UNITS_IMPERIAL;
  h += F("<fieldset><legend>");
  h += L(F("Units"), F("單位"));
  h += F("</legend><div class=radio><label><input type=radio name=units value=0");
  h += imperial ? F(">") : F(" checked>");
  h += L(F("Metric (&deg;C, km/h, hPa)"), F("公制（&deg;C、km/h、hPa）"));
  h += F("</label><label><input type=radio name=units value=1");
  h += imperial ? F(" checked>") : F(">");
  h += L(F("Imperial (&deg;F, mph, inHg)"), F("英制（&deg;F、mph、inHg）"));
  h += F("</label></div></fieldset>");

  // --- Language
  // Applies to the panel and to this page. It does NOT touch the stop names in
  // the bus scene, which are Chinese either way because that is what is written
  // on the stop.
  const bool zh = field("lang", String(g_settings.lang)).toInt() == LANG_ZH;
  h += F("<fieldset><legend>Language / 語言</legend><div class=radio>"
         "<label><input type=radio name=lang value=0");
  h += zh ? F(">") : F(" checked>");
  h += F("English</label>"
         "<label><input type=radio name=lang value=1");
  h += zh ? F(" checked>") : F(">");
  h += F("中文 (繁體)</label></div></fieldset>");

  // --- Scenes
  //
  // Which screens appear, and for how long. Both belong to the owner: somebody
  // who never looks at air quality should not have to re-flash to stop it
  // coming round, which is the same argument that moved WiFi credentials out
  // of config.h.
  h += F("<fieldset><legend>");
  h += L(F("Screens"), F("畫面"));
  h += F("</legend>");
  for (int i = 0; i < sceneManager_total() && i < SCENE_SLOTS; i++) {
    // An unchecked checkbox is not submitted at all, so on a re-render after a
    // validation error the absence of `sc<i>` is the user's "off" -- but on a
    // fresh GET the same absence means nothing. `posted` tells the two apart.
    const bool on = posted ? server.hasArg(String("sc") + i) : g_settings.sceneOn[i];
    h += F("<div class=srow><label class=schk><input type=checkbox name=sc");
    h += String(i);
    h += on ? F(" checked>") : F(">");
    h += sceneManager_name(i);
    h += F("</label><span class=sdw><input type=number name=sd");
    h += String(i);
    h += F(" min="); h += String(SCENE_DWELL_MIN_S);
    h += F(" max="); h += String(SCENE_DWELL_MAX_S);
    h += F(" value=\"");
    h += esc(field((String("sd") + i).c_str(), String(g_settings.sceneDwellS[i])));
    h += F("\">s</span></div>");
  }
  h += F("<div class=hint>");
  h += L(F("Unticked screens are skipped by the rotation and lose their dot in "
           "the status bar; their timing is remembered for when you tick them "
           "again. At least one has to stay on. A tap still advances to the next "
           "screen, and a long press pins the one you are looking at."),
         F("未勾選的畫面會被輪播略過，狀態列上的圓點也會消失；再次勾選時會沿用原本的秒數。"
           "至少要保留一個畫面。輕觸仍可切換至下一個畫面，長按則會固定目前畫面。"));
  h += F("</div></fieldset>");

  // --- Bus stops
  //
  // Structured so the page is fully usable with no JavaScript at all: each slot
  // is a plain text input holding the packed string, and /bus.js later hangs a
  // route picker above it that writes into the very same field. That is not a
  // fallback bolted on afterwards -- it is the primary contract.
  //
  // In AP mode the whole section is hidden, because the device IS the access
  // point and has no route to the internet: every lookup would fail and every
  // name would bake against a stop the browser could not resolve. But the
  // values are still carried, as hidden inputs -- dropping the fields entirely
  // would make an ordinary save from the setup portal erase every stop the user
  // had configured, which is a far worse outcome than a section they cannot use.
  if (apMode) {
    for (int i = 0; i < BUS_SLOTS; i++) {
      char nm[8];
      snprintf(nm, sizeof(nm), "bus%d", i);
      h += F("<input type=hidden name=");
      h += nm;
      h += F(" value=\"");
      h += esc(field(nm, busStop_pack(g_settings.buses[i])));
      h += F("\">");
    }
    int configured = 0;
    for (int i = 0; i < BUS_SLOTS; i++) if (g_settings.buses[i].valid()) configured++;
    if (configured) {
      h += F("<fieldset><legend>");
      h += L(F("Next bus (Hong Kong)"), F("下一班車（香港）"));
      h += F("</legend><div class=hint>");
      h += String(configured);
      h += L(F(" stop(s) configured, and they are kept. Route lookup needs "
               "internet access, which this setup network does not have &mdash; "
               "reconnect the clock to your WiFi and open its settings page there "
               "to change them."),
             F(" 個車站已設定，並會保留。查詢路線需要連上互聯網，"
               "而這個設定網絡並沒有 &mdash; 請將時鐘接回你的 WiFi，"
               "再從那裏開啟設定頁修改。"));
      h += F("</div></fieldset>");
    }
  } else {
    h += F("<fieldset><legend>");
    h += L(F("Next bus (Hong Kong)"), F("下一班車（香港）"));
    h += F("</legend><div class=hint id=bne></div>");
    for (int i = 0; i < BUS_SLOTS; i++) {
      char nm[8];
      snprintf(nm, sizeof(nm), "bus%d", i);
      const String cur = field(nm, busStop_pack(g_settings.buses[i]));

      h += F("<div class=slot><label>");
      h += L(F("Stop "), F("車站 "));
      h += String(i + 1);
      h += F("</label><div class=bsum id=bsum");
      h += String(i);
      h += F(">");
      h += esc(busStop_describe(g_settings.buses[i]));
      h += F("</div><div id=bpick");
      h += String(i);
      h += F("></div><details><summary>");
      h += L(F("Manual entry"), F("手動輸入"));
      h += F("</summary><input name=");
      h += nm;
      h += F(" id=");
      h += nm;
      h += F(" value=\"");
      h += esc(cur);
      h += F("\"><div class=hint>op|route|stop|serviceType|dir|routeId|routeSeq|"
             "stopSeq|stopTC|stopEN|destTC|destEN &mdash; ");
      h += L(F("op 0=KMB 1=Citybus 2=minibus 3=Long Win. Empty clears the slot."),
             F("op 0=九巴 1=城巴 2=小巴 3=龍運。留空即清除該格。"));
      h += F("</div></details></div>");
    }
    h += F("<div class=hint>");
    h += L(F("Chinese stop names are drawn on the display as images baked by this "
             "browser, because the device has no Chinese font. If the names ever "
             "revert to English, open this page and save again to restore them."),
           F("中文站名是由這個瀏覽器製成圖像後顯示在螢幕上的，因為裝置本身沒有中文字型。"
             "若站名變回英文，開啟此頁再儲存一次即可復原。"));
    h += F("</div></fieldset>");
  }

  h += F("<button type=submit>");
  h += L(F("Save &amp; restart"), F("儲存並重新啟動"));
  h += F("</button>");
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
  if (ssid.isEmpty())                    err = L(F("Network name is required."),
                                                 F("必須填寫網絡名稱。"));
  else if (latS.isEmpty() || lonS.isEmpty())
                                         err = L(F("Latitude and longitude are required."),
                                                 F("必須填寫緯度和經度。"));
  else if (lat < -90.0f  || lat > 90.0f) err = L(F("Latitude must be between -90 and 90."),
                                                 F("緯度必須介乎 -90 至 90。"));
  else if (lon < -180.0f || lon > 180.0f)
                                         err = L(F("Longitude must be between -180 and 180."),
                                                 F("經度必須介乎 -180 至 180。"));
  // Bus slots. Only shape can be checked here: confirming that a stop id really
  // exists would mean an internet call inside a request handler, which is
  // exactly the blocking the appliance rule in docs/decisions.md forbids. The
  // picker is the real validation -- the /stop/{id} call that fetched the name
  // IS the proof the id resolves -- and the scene's 檢查車站 state catches the
  // rest by watching a slot return nothing for six hours.
  BusStop parsed[BUS_SLOTS];
  bool busPresent[BUS_SLOTS] = {};
  for (int i = 0; i < BUS_SLOTS && err.isEmpty(); i++) {
    char nm[8];
    snprintf(nm, sizeof(nm), "bus%d", i);
    // ABSENT and EMPTY are not the same thing. An empty field is the user
    // clearing the slot; an absent one means the form did not carry it, and
    // treating that as "clear" would let any save from a page that omitted the
    // section wipe every configured stop. The AP-mode page carries them as
    // hidden inputs so they are always present -- this is the belt to that
    // braces, and it is cheap.
    if (!server.hasArg(nm)) continue;
    busPresent[i] = true;
    String v = server.arg(nm);
    v.trim();
    if (v.isEmpty()) continue;                       // an empty field clears it
    if (!busStop_unpack(v, parsed[i]))
      err = String(L(F("Stop "), F("車站 "))) + String(i + 1)
          + L(F(" is not a valid entry."), F(" 的內容無效。"));
  }

  // Scenes. Rejecting an all-off configuration here rather than coping with it
  // at runtime: a panel with no scene left to show would sit frozen on whatever
  // was drawn last, and the address of the page that could undo it is itself
  // painted by a scene.
  bool     scOn[SCENE_SLOTS];
  uint16_t scDwell[SCENE_SLOTS];
  int      scOnCount = 0;
  for (int i = 0; i < SCENE_SLOTS; i++) {
    scOn[i] = server.hasArg(String("sc") + i);
    if (scOn[i]) scOnCount++;
    const long v = server.arg(String("sd") + i).toInt();
    scDwell[i] = (v >= SCENE_DWELL_MIN_S && v <= SCENE_DWELL_MAX_S)
                   ? (uint16_t)v : g_settings.sceneDwellS[i];
    if (err.isEmpty() && v != 0 &&
        (v < SCENE_DWELL_MIN_S || v > SCENE_DWELL_MAX_S))
      err = String(L(F("Screen times must be between "), F("畫面秒數必須介乎 ")))
          + SCENE_DWELL_MIN_S + L(F(" and "), F(" 至 ")) + SCENE_DWELL_MAX_S
          + L(F(" seconds."), F(" 秒。"));
  }
  if (err.isEmpty() && scOnCount == 0)
    err = L(F("At least one screen has to stay switched on."),
            F("至少要保留一個畫面開啟。"));

  if (err.length()) { sendPage(err); return; }

  for (int i = 0; i < BUS_SLOTS; i++) {
    if (!busPresent[i]) continue;            // not on this form; leave it alone
    // The baked bitmap is a picture of exactly these two strings. If either
    // changed and the browser did not upload a replacement, the old picture is
    // now a picture of a different stop -- worse than no picture at all.
    const bool textChanged = (parsed[i].stopTc != g_settings.buses[i].stopTc) ||
                             (parsed[i].destTc != g_settings.buses[i].destTc);
    if (textChanged && !labelUploaded[i]) label_clearUser(i);
    g_settings.buses[i] = parsed[i];
  }

  for (int i = 0; i < SCENE_SLOTS; i++) {
    g_settings.sceneOn[i]     = scOn[i];
    g_settings.sceneDwellS[i] = scDwell[i];
  }

  g_settings.wifiSsid  = ssid;
  if (pass.length()) g_settings.wifiPass = pass;   // blank means "keep current"
  g_settings.latitude  = lat;
  g_settings.longitude = lon;
  g_settings.tz        = tzc.length() ? tzc : server.arg("tzsel");
  g_settings.units     = (server.arg("units").toInt() == UNITS_IMPERIAL)
                           ? UNITS_IMPERIAL : UNITS_METRIC;
  g_settings.lang      = (server.arg("lang").toInt() == LANG_ZH)
                           ? LANG_ZH : LANG_EN;
  settings_save();
  saved = true;

  // Built rather than sent as one blob, because it has to say all this twice.
  // Note it uses the language that was just SAVED -- g_settings is already
  // updated by the time we get here, so the confirmation is the first page the
  // user sees in their new choice.
  String done;
  done.reserve(1024);
  done += F("<!doctype html><html><head><meta charset=utf-8>"
            "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
            "<title>");
  done += L(F("Saved"), F("已儲存"));
  done += F("</title><style>body{font-family:system-ui,sans-serif;"
            "background:#111;color:#eee;padding:40px 16px;text-align:center}"
            "h1{color:#0dd;font-size:20px}p{color:#999;font-size:14px}</style>"
            "</head><body><h1>");
  done += L(F("Saved"), F("已儲存"));
  done += F("</h1><p>");
  done += L(F("The clock is restarting with the new settings."),
            F("時鐘正在以新設定重新啟動。"));
  done += F("</p><p>");
  done += L(F("If you changed the network, this page will not reload &mdash; the "
              "device is joining the network you selected. Its address appears "
              "on the clock screen."),
            F("如果你更改了網絡，此頁不會重新載入 &mdash; 裝置正在加入你選擇的網絡。"
              "它的位址會顯示在時鐘畫面上。"));
  done += F("</p></body></html>");
  server.send(200, "text/html", done);
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
