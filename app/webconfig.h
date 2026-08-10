// ===========================================================================
// webconfig.h -- settings web UI, in both STA and AP modes
// ===========================================================================
// The server runs whenever the device is powered, not only during setup:
//
//   STA connected  -> settings page on the LAN IP, e.g. http://192.168.1.42
//   WiFi failed    -> SoftAP + captive portal on http://192.168.4.1
//
// which is why the Clock scene prints the address in its footer. Without it
// there is no way to find the page short of a serial monitor or a router's
// client list.
//
// WebServer and DNSServer ship with the Arduino-ESP32 core, so this adds no
// third-party dependency. WiFiManager was considered and rejected: it handles
// credentials well but carries location/timezone/units awkwardly as "custom
// parameters", and is a heavier dependency than the handlers here.
#pragma once

#include <Arduino.h>

void webconfig_beginSTA();   // serve on the current STA address
void webconfig_beginAP();    // SoftAP + DNS captive portal + server
void webconfig_stopAP();     // drop the AP once STA comes up

// Call every loop: services HTTP and, in AP mode, captive-portal DNS.
void webconfig_tick();

bool   webconfig_isAP();
bool   webconfig_isRunning();

// True once a save has been accepted. The setup portal polls this to know it
// can restart; the caller owns the restart so the screen can say so first.
bool   webconfig_saved();

String webconfig_ip();       // address the settings page is reachable on
String webconfig_apSsid();   // "CYD-Setup-A1B2" -- last 4 hex of the MAC
