# Marauder Mobile

An Android controller for the **ESP32 Marauder v8**, over USB-OTG, with **stock
firmware**. Nothing is flashed, nothing is patched.

Built for a Samsung Galaxy S26 Ultra on Android 16 / One UI 8.

---

## What it does, and the one limit worth knowing first

The app drives the firmware's own CLI over serial, parses what comes back, and
shows it. It needs no modified firmware — unlike the abandoned project this
started from, which required a JSON-serial fork that was never merged upstream.

**The honest limit:** the v8's USB-C is a **CP2102N bridge chip on a real UART**,
not the ESP32-C5's native USB. So `Serial.begin(115200)` is binding, not
cosmetic. Measured sustained throughput on the actual board was **7,691 B/s — 67%
of everything 115200 8N1 can physically carry**. That works out to about **24
packets per second**.

The firmware's capture ring is 2 × 8 KB. At 24 pkt/s it fills in 2.2 s and drains
in 1.4 s, which is fine. Past roughly **40 pkt/s it drops frames silently** — the
diagnostic print is commented out in `Buffer.cpp`, so the host cannot know.

**Therefore the default mode does not stream packets at all**, and that turns out
to be strictly better:

| | default (no `-serial`) | streaming (`-serial`) |
|---|---|---|
| SD capture | complete, full speed | degraded — blocking writes stall the firmware loop |
| live telemetry on the phone | **yes, full rate** | yes |
| raw pcap on the phone | no | yes, lossy above ~40 pkt/s |

Live per-packet telemetry prints **regardless** of `-serial` (verified in
`WiFiScan.cpp`), so the default gets you a live UI *and* a complete card capture
at the same time. Streaming is an explicit opt-in with the ceiling stated in the
UI.

There is no way to pull a finished file off the SD over serial — `ls` lists
names, but no command exposes file contents, and the only outbound file path in
the firmware is a hardcoded WiGLE upload. Captures come off the card.

---

## Building

No Android toolchain needed on any of your machines. Push to GitHub and the
Actions workflow builds it.

1. Push this directory to a repo.
2. Actions → **Build APK** runs on push, or trigger it manually.
3. Download the `MarauderMobile-apk` artifact. You can do this from the phone's
   browser on the Actions run page.

To build locally instead you need JDK 17 and the Android SDK with API 37.

### Version constraints that will bite if you change them
- **compileSdk must be 37.** Compose 1.12.0+ requires compileSdk 37 and AGP 9.
  BOM `2026.09.00` maps to 1.12.1. compileSdk 36 with this BOM fails.
- **Do not add `org.jetbrains.kotlin.android`.** AGP 9 has built-in Kotlin on by
  default and the plugin conflicts with the new DSL.
- **Do not add `kotlinOptions { }`.** It is a hard compile error on KGP 2.4.x.
  `jvmTarget` follows `compileOptions.targetCompatibility` automatically.
- AGP 9.3.0 pairs with Gradle 9.5.0. Moving to AGP 9.4.0 means Gradle 9.6.0 —
  never change one alone.

---

## Installing on the S26 Ultra

Four independent gates, with confusingly similar failure messages.

1. **Auto Blocker is ON by default** and blocks sideloading even when you have
   granted the per-source permission. `Settings > Security and privacy > Auto
   Blocker` → off.
2. **Install unknown apps** permission for whichever app opens the APK.
3. **Play Protect** will show "App Scan Recommended" for a new build. Expected.
4. If **Advanced Protection** is on, sideloading is blocked outright and USB data
   is disabled while the phone is locked.

**Easier route: `adb install`.** Google's developer-verification FAQ states ADB
installs never require verification and the waiting period never applies, which
also future-proofs past the 2027 rollout. Wireless `adb pair` avoids swapping the
OTG adapter in and out.

## Keeping it alive

Samsung will kill this before Android does. Their own docs: an app unused ~3 days
enters "sleeping", where **foreground services are explicitly restricted**; ~16
days and it is "deep sleeping" and runs only while open.

- `Settings > Battery > Background usage limits` → turn off "Put unused apps to
  sleep", then add Marauder to **Never sleeping apps**
- `Settings > Apps > Marauder > Battery` → **Unrestricted**
- Turn off Adaptive battery and Auto-optimize daily
- Turn off "Remove permissions if app unused"
- Lock the app in Recents
- **Re-check all of this after every One UI update** — Samsung is documented to
  revert it

## Power

The phone supplies 500 mA in host mode. Espressif asks 600 mA for the C5 module
alone, and 5 GHz TX peaks at 403 mA before you add the display, GPS and SD. The
18650 makes it worse, not better, because the board charges from USB.

Run a charged cell. For anything long, use a powered USB-C OTG hub or Y-cable.
An unexpected disconnect during a 5 GHz scan is probably a brownout, and the app
says so rather than showing a generic error.

---

## Design

Built on Koko's palette. The colors are read out of the firmware's own TFT table
and converted from RGB565 — `TFT_VIOLET 0x915C` is `#9428E6` and `TFT_GREEN
0x07E0` is `#00FF00`, so the Kyber//Cypher purple and cyberpunk green were
already in there.

The deeper homage is the **semantic color grammar**, which exists nowhere but the
source: magenta is passive recon, cyan is BLE, green is navigate, orange is
metering, yellow is a targeted variant, white is raw — and **red means the radio
is transmitting**. Red is used for nothing else in this app. Errors are orange.
If you see red, the board is emitting.

The status strip mirrors the device's own, same fields in the same order.

The Marauder skull is not reproduced; it is Koko's mark. The firmware is credited
with a link.

## Scope

Transmit-class commands (deauth, badmsg, sleep, auth) have no buttons in this
build. They are reachable from the raw console for anyone who means it. Use this
only on networks you own or are authorised to test.

Firmware: https://github.com/justcallmekoko/ESP32Marauder by justcallmekoko
