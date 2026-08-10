# CYD Clock — Android build

`cyd-clock-android-v1.0.2.apk` — 1.2 MB, minSdk 26 (Android 8.0), targetSdk 35.
Checksum in `SHA256SUMS`.

**Check which build you are running** in Setup → About; it prints the version and
build number. Worth doing before reporting a layout problem — telling two builds
apart from a screenshot means measuring pixel positions, which is slow and easy
to get wrong.

## Installing

1. Copy the APK to the phone (USB, or upload it somewhere and download it).
2. Open it with a file manager. Android will ask permission for **that app** to
   install unknown apps — it is a per-app permission now, not a global setting,
   so allow it for whichever app you opened the file with.
3. Install.

Or over USB with debugging on:

```bash
adb install cyd-clock-android-v1.0.2.apk
```

## Signing

Signed with the standard Android **debug key**, so it installs by sideload
without anyone having to create and look after a private signing key. One
consequence: a future build signed with a real release key will not install over
this one — Android will ask you to uninstall first. Settings are lost when you do.

## What to expect

* **Landscape only.** It locks to landscape whichever way up the phone is held.
  That is the point — it is a wall clock, not a phone app.
* **The screen will not sleep** while the clock is in the foreground, so the
  phone never locks itself. On a phone you are carrying around, close it when
  you are done.
* **It shows over the lock screen.** Press the power button, wake the phone
  again, and the clock is there rather than the lock screen — and you can tap it
  to change scenes without unlocking. Nothing else is unlocked: press Home and
  you get the lock screen and your PIN as usual. The one thing worth knowing is
  that Setup is reachable from the clock, so anyone who can pick up the phone can
  change the coordinates without unlocking it. Turn it off in Setup → Display if
  you would rather the clock hid when the phone locks.
* **First run opens Setup.** Set latitude and longitude, or tap "Use my location".
  The defaults are Greenwich, which is deliberately somewhere obviously not
  yours so an unconfigured unit reads as unconfigured.
* **Set the time zone too if it differs from the location.** They are independent
  settings. Leaving the phone's zone while pointing the location at another
  continent gives sunrise and sunset in the wrong frame — correct, but startling.

## Using it

| | |
|---|---|
| Tap anywhere | next scene, and hold rotation still for 45 s |
| Hold 0.8–4 s | pin the scene until pressed again |
| Hold over 4 s | open Setup |
| Gear, top right | open Setup |

Scenes rotate on their own: Clock 35 s, then Weather, Sun & Moon and Air Quality
for 12 s each.

The status strip along the bottom shows the time, signal, a data-freshness dot
(green under 30 min, amber under 2 h, red older, grey for no data yet), the pin
and freeze indicators, and which scene is showing.

## Data

Weather and air quality come from [Open-Meteo](https://open-meteo.com) (CC BY 4.0)
and need no API key. Sunrise, sunset, golden hour and moon phase are computed on
the device, so they stay right with the network down.
