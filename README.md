# DiPlay Legacy Android — ORA Edition

[English](README.md) · [简体中文](README.zh-CN.md)

**CarPlay for the ORA Good Cat (欧拉好猫/黑猫) and compatible head units.** This is a fork of
[DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) that adds a
**native vehicle-data source** — real speed, battery (SOC), remaining range, gear and charging state
read directly from the car's CAN bus, surfaced inside CarPlay instead of the BYD-only ADB path the
upstream project uses.

> **Upstream**: https://github.com/shihabal3amri/DiPlay and its compatibility fork
> https://github.com/programmerguohuajing/DiPlay-Legacy-Android. This repository starts from the
> legacy-Android fork and adds the ORA vehicle-data source on top.

---

## What this fork adds

The upstream DiPlay targets **BYD** cars and reads vehicle data over **ADB** (the head unit's own
`autoservice`). ORA Good Cat / Black Cat head units run a different middleware that exposes the car's
CAN-bus data through an AIDL service instead of ADB.

This fork adds a **native vehicle-data source** so the same CarPlay vehicle-status features
(dashboard battery, wheel-speed dead reckoning, gear) work on ORA hardware:

| Capability | Upstream (BYD) | This fork (ORA) |
|---|---|---|
| Vehicle data transport | ADB `autoservice` | AIDL UART callback |
| Speed | BYD ADB | CAN frame (`project=44`) |
| Battery (SOC) | BYD ADB | CAN frame (`project=44`) |
| Remaining range | BYD ADB | CAN frame (`project=44`) |
| Gear (R/N/D) | BYD ADB | CAN frame (`project=44`) |
| Charging state | BYD ADB | CAN frame (`project=43`) |
| Tire pressure | — | CAN frame (`project=40`, parsed, not reported to iPhone) |

The native path is **preferred when an ORA head unit is detected**, with the BYD path kept as a
fallback for BYD hardware. Detection is automatic: the app checks for the vehicle-data service and
routes accordingly.

---

## How the vehicle data is read

```
DiPlay app
   │  bindService(vehicle-data service)
   ▼
Vehicle-data service (AIDL)
   │  register UART callback
   ▼
UART callback  —  pushes raw bytes (CAN frames) in real time
   │
   ▼
CAN frame parser  —  decodes 0x5A 0xA5 … frames
   │
   ▼
Vehicle data source  —  feeds VehicleSpeedSource / VehicleStatusProvider
   │
   ▼
iAP2 vehicle status (0xA101) → iPhone → CarPlay dashboard
```

The CAN frame format is `0x5A 0xA5 [len] 0xA9 [project] [subcmd] [payload…]`, with a trailing
checksum:

| `project` | Meaning | Parsed fields |
|---|---|---|
| `44` | New energy | speed, battery %, range, gear, mileage, consumption, power |
| `43` | Vehicle status | charging state, charge-limit % |
| `40` | Tire | 4× tire pressure (raw) |
| `37` | Air conditioning | A/C on/off, compressor, wind level, temperature, heating |

> **Note on `mode`:** the `mode` argument to the UART callback registration is a client dedup key,
> not a data filter. The service broadcasts UART data to every registered callback regardless of
> `mode`; any `int` value works.

---

## Build

This is a Gradle multi-module Android project. See [docs/BUILD.md](docs/BUILD.md) for the full
toolchain (JDK 25, Android SDK 37, NDK r25c for the API 19 native build).

```bash
./gradlew :mobile:assembleDebug      # main DiPlay app → mobile/build/outputs/apk/debug/
```

The `mobile` module is the main CarPlay app (`com.shihab.diplay`); `home` is a launcher sample and
`maphost` a map sample. The vehicle-data source (parser, AIDL client, data sources, and entry point)
is wired into `CarPlayHostActivity` and `DiPlayActivity`. Unit tests for the CAN parser are included.

---

## Install

Install the APK on the **car's head unit**, not the iPhone. No jailbreak, dongle, Mac, account or
authentication server is required. The head unit must permit APK installation, and for the ORA data
source it must ship the vehicle-data service — which is preinstalled on ORA Good Cat / Black Cat head
units.

- **Wired USB** and **wireless** CarPlay (Wi-Fi Direct, car hotspot, existing Wi-Fi / same LAN).
- Minimum Android **4.2 (API 17)**; tested baseline Android 4.4–6.0, also runs on current Android.

See the upstream [INSTALL](docs/INSTALL.md) and
[COMPATIBILITY](docs/COMPATIBILITY.md) guides.

---

## Vehicle-status features on ORA

Once connected and the native source is detected:

- **Battery (SOC) & remaining range** appear in CarPlay's EV/dashboard status.
- **Speed** feeds iPhone dead reckoning (tunnel navigation continuity).
- **Gear** (R/N/D) is reported where the vehicle-status protocol allows.

The **positioning** source is the car's own GPS, surfaced through the standard Android
`LocationManager` — DiPlay's `AndroidCarPlayLocationProvider` already consumes it, so no extra bridge
is required.

**Tire pressure** is parsed from the CAN frames but is *not* reported to the iPhone: the iAP2 vehicle
status channel has no tire-pressure field and Apple does not consume it.

---

## Status & caveats

This fork is a **hardware-dependent community adaptation**, not an Apple-certified product. The APK
bundles an experimental accessory identity recovered from public Carlinkit firmware (see
[SECURITY.md](SECURITY.md)). Acceptance after future iOS updates, reliability across head units, and
suitability of that identity for general distribution are unresolved.

The CAN field mapping has **not yet been verified on a physical ORA vehicle**. Before trusting
dashboard numbers, confirm on a real car that speed / SOC / range / gear update. If a value does not
move, the most likely causes are (1) the vehicle-data service is absent or not running, or (2) the
CAN frame layout differs on your firmware — both are inspectable via `adb logcat` with the
`HaokeVehicleData` / `HaokeCanParser` / `CarPlayLocation` tags.

---

## Credits

- Based on [DiPlay](https://github.com/shihabal3amri/DiPlay) ([xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0) and
  [DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android).
- CarPlay and its icon belong to Apple Inc.; no Apple, ORA or BYD affiliation or endorsement is implied.

See the upstream [release notes](CHANGELOG.md) and [third-party notices](docs/THIRD_PARTY_NOTICES.md).
