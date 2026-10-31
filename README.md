# DiPlay Legacy Android — ORA (Haoke) Edition

[English](README.md) · [简体中文](README.zh-CN.md)

**CarPlay for the ORA Good Cat (欧拉好猫/黑猫) and other HAOKE-based head units.** This is a fork of
[DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) that adds a
**native vehicle-data source for HAOKE head units** — real speed, battery (SOC), remaining range,
gear, charging state and tire pressure read directly from the car's CAN bus, surfaced inside CarPlay
instead of the BYD-only ADB path the upstream project uses.

> **Upstream**: https://github.com/shihabal3amri/DiPlay and its compatibility fork
> https://github.com/programmerguohuajing/DiPlay-Legacy-Android. This repository starts from the
> legacy-Android fork and adds the ORA/HAOKE data source on top.

---

## What this fork adds

The upstream DiPlay targets **BYD** cars and reads vehicle data over **ADB** (the head unit's own
`autoservice`). ORA Good Cat / Black Cat head units run a different middleware — **HAOKE
CarManager** (`com.haoke.carmanager`) — which exposes the car's CAN-bus data through an AIDL service
instead of ADB.

This fork adds a **HAOKE data source** so the same CarPlay vehicle-status features (dashboard battery,
wheel-speed dead reckoning, gear) work on ORA/HAOKE hardware:

| Capability | Upstream (BYD) | This fork (ORA/HAOKE) |
|---|---|---|
| Vehicle data transport | ADB `autoservice` | HAOKE `ICarService` AIDL UART callback |
| Speed | BYD ADB | HAOKE CAN frame (`project=44`) |
| Battery (SOC) | BYD ADB | HAOKE CAN frame (`project=44`) |
| Remaining range | BYD ADB | HAOKE CAN frame (`project=44`) |
| Gear (R/N/D) | BYD ADB | HAOKE CAN frame (`project=44`) |
| Charging state | BYD ADB | HAOKE CAN frame (`project=43`) |
| Tire pressure | — | HAOKE CAN frame (`project=40`, parsed, not reported to iPhone) |

The HAOKE path is **preferred when a HAOKE head unit is detected**, with the BYD path kept as a
fallback for BYD hardware. Detection is automatic: the app checks whether `com.haoke.carmanager` is
installed and routes accordingly.

---

## How the vehicle data is read

```
DiPlay app
   │  bindService("action.haoke.CARSERVICE")     (com.haoke.carmanager)
   ▼
HAOKE CarService  (com.haoke.service.CarService)
   │  registerCallBackWithUart(mode, ICarCallBack, ICarUartCallBack)
   ▼
ICarUartCallBack  —  pushes raw UART bytes (CAN frames) in real time
   │
   ▼
HaokeCanParser.parse()  —  decodes 0x5A 0xA5 … frames
   │
   ▼
HaokeOutputs  —  feeds VehicleSpeedSource / VehicleStatusProvider
   │
   ▼
iAP2 vehicle status (0xA101) → iPhone → CarPlay dashboard
```

The CAN frame format is `0x5A 0xA5 [len] 0xA9 [project] [subcmd] [payload…]`, with a trailing
checksum. Field mapping follows the ORA reverse-engineered layout used by the community "Black Cat
helper" tool:

| `project` | Meaning | Parsed fields |
|---|---|---|
| `44` | New energy | speed, battery %, range, gear, mileage, consumption, power |
| `43` | Vehicle status | charging state, charge-limit % |
| `40` | Tire | 4× tire pressure (raw) |
| `37` | Air conditioning | A/C on/off, compressor, wind level, temperature, heating |

> **Note on `mode`:** the `mode` argument to `registerCallBackWithUart` is a client dedup key, not a
> data filter. HAOKE's `CarService` broadcasts UART data to every registered callback regardless of
> `mode`; the value used here (`32561`, inherited from the Black Cat helper) is arbitrary and any
> `int` works.

---

## Build

This is a Gradle multi-module Android project. See [docs/BUILD.md](docs/BUILD.md) for the full
toolchain (JDK 25, Android SDK 37, NDK r25c for the API 19 native build).

```bash
./gradlew :mobile:assembleDebug      # main DiPlay app → mobile/build/outputs/apk/debug/
```

The `mobile` module is the main CarPlay app (`com.shihab.diplay`); `home` is a launcher sample and
`maphost` a map sample. The HAOKE vehicle-data source lives in
`common/src/main/java/com/shilapi/xcertplay/haoke/` (parser, AIDL client, data sources, and the
`HaokeOutputs` entry point), wired into `CarPlayHostActivity` and `DiPlayActivity`.

Unit tests for the CAN parser are in
`shared/src/test/java/com/shilapi/xcertplay/haoke/HaokeCanParserTest.kt`.

---

## Install

Install the APK on the **car's head unit**, not the iPhone. No jailbreak, dongle, Mac, account or
authentication server is required. The head unit must permit APK installation, and for the ORA data
source it must ship the HAOKE CarManager (`com.haoke.carmanager`) — which is preinstalled on ORA Good
Cat / Black Cat head units.

- **Wired USB** and **wireless** CarPlay (Wi-Fi Direct, car hotspot, existing Wi-Fi / same LAN).
- Minimum Android **4.2 (API 17)**; tested baseline Android 4.4–6.0, also runs on current Android.

See the upstream [INSTALL](docs/INSTALL.md) and
[COMPATIBILITY](docs/COMPATIBILITY.md) guides.

---

## Vehicle-status features on ORA

Once connected and the HAOKE source is detected:

- **Battery (SOC) & remaining range** appear in CarPlay's EV/dashboard status.
- **Speed** feeds iPhone dead reckoning (tunnel navigation continuity).
- **Gear** (R/N/D) is reported where the vehicle-status protocol allows.

The **positioning** source is the car's own GPS, which HAOKE surfaces through the standard Android
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

The HAOKE CAN field mapping and `mode` value were derived by reverse-engineering the community
"Black Cat helper" APK and have **not yet been verified on a physical ORA vehicle**. Before trusting
dashboard numbers, confirm on a real car that speed / SOC / range / gear update. If a value does not
move, the most likely causes are (1) HAOKE CarManager is absent or not running, or (2) the CAN frame
layout differs on your firmware — both are inspectable via `adb logcat` with the
`HaokeVehicleData` / `HaokeCanParser` / `CarPlayLocation` tags.

---

## Credits

- Based on [DiPlay](https://github.com/shihabal3amri/DiPlay) ([xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0) and
  [DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android).
- HAOKE vehicle-data source ported from the community "Black Cat helper" (黑猫助手) `VehicleDataParser` field mapping.
- CarPlay and its icon belong to Apple Inc.; no Apple, ORA or BYD affiliation or endorsement is implied.

See the upstream [release notes](CHANGELOG.md) and [third-party notices](docs/THIRD_PARTY_NOTICES.md).
