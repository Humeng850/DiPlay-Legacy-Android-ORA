# DiPlay Legacy Android — 欧拉黑猫（好客）适配版

[English](README.md) · [简体中文](README.zh-CN.md)

**面向欧拉好猫 / 黑猫及其它好客（HAOKE）车机的 CarPlay 方案。** 本项目是
[DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) 的一个 fork，
新增了**好客车机原生车辆数据源**——直接从车机 CAN 总线读取真实车速、电量（SOC）、续航、挡位、充电状态和胎压，
并在 CarPlay 中显示，替代上游仅支持 BYD 的 ADB 读取路径。

> **上游项目**：https://github.com/shihabal3amri/DiPlay 及其兼容性 fork
> https://github.com/programmerguohuajing/DiPlay-Legacy-Android。本仓库基于 legacy-Android fork，
> 在其之上加入欧拉/好客数据源。

---

## 本 fork 新增了什么

上游 DiPlay 面向 **BYD** 车型，通过 **ADB**（车机自带的 `autoservice`）读取车辆数据。而欧拉好猫 / 黑猫
车机运行的是另一套中间件——**好客 CarManager**（`com.haoke.carmanager`），它通过 AIDL 服务（而非 ADB）
对外暴露车机 CAN 总线数据。

本 fork 新增了**好客数据源**，让同样的 CarPlay 车辆状态功能（仪表盘电量、车速死区推算、挡位）能在
欧拉/好客硬件上工作：

| 能力 | 上游（BYD） | 本 fork（欧拉/好客） |
|---|---|---|
| 车辆数据传输 | ADB `autoservice` | 好客 `ICarService` AIDL UART 回调 |
| 车速 | BYD ADB | 好客 CAN 帧（`project=44`） |
| 电量（SOC） | BYD ADB | 好客 CAN 帧（`project=44`） |
| 剩余续航 | BYD ADB | 好客 CAN 帧（`project=44`） |
| 挡位（R/N/D） | BYD ADB | 好客 CAN 帧（`project=44`） |
| 充电状态 | BYD ADB | 好客 CAN 帧（`project=43`） |
| 胎压 | — | 好客 CAN 帧（`project=40`，已解析但不上报 iPhone） |

**检测到好客车机时优先走好客路径**，BYD 路径保留作为 BYD 硬件的兜底。检测是全自动的：App 检查
`com.haoke.carmanager` 是否已安装，据此路由。

---

## 车辆数据是怎么读到的

```
DiPlay App
   │  bindService("action.haoke.CARSERVICE")     (com.haoke.carmanager)
   ▼
好客 CarService  (com.haoke.service.CarService)
   │  registerCallBackWithUart(mode, ICarCallBack, ICarUartCallBack)
   ▼
ICarUartCallBack  —  实时推送原始 UART 字节（CAN 帧）
   │
   ▼
HaokeCanParser.parse()  —  解码 0x5A 0xA5 … 帧
   │
   ▼
HaokeOutputs  —  喂给 VehicleSpeedSource / VehicleStatusProvider
   │
   ▼
iAP2 车辆状态（0xA101）→ iPhone → CarPlay 仪表盘
```

CAN 帧格式为 `0x5A 0xA5 [len] 0xA9 [project] [subcmd] [payload…]`，尾部带校验和。字段映射与社区
「黑猫助手」工具使用的欧拉逆向布局一致：

| `project` | 含义 | 解析出的字段 |
|---|---|---|
| `44` | 新能源 | 车速、电量、续航、挡位、总里程、能耗、功率 |
| `43` | 车辆状态 | 充电状态、充电限值 |
| `40` | 胎压 | 四轮胎压（原始值） |
| `37` | 空调 | 空调开关、压缩机、风量、温度、加热 |

> **关于 `mode` 的说明**：`registerCallBackWithUart` 的 `mode` 参数只是「客户端去重键」，不是数据过滤器。
> 好客 `CarService` 会无条件把 UART 数据广播给每一个注册过的回调，与 `mode` 无关；这里使用的 `32561`
> （沿用自黑猫助手）是任意值，传任何 `int` 都行。

---

## 构建

这是一个 Gradle 多模块 Android 工程。完整工具链见 [docs/BUILD.md](docs/BUILD.md)
（JDK 25、Android SDK 37、NDK r25c，用于 API 19 的原生构建）。

```bash
./gradlew :mobile:assembleDebug      # 主 DiPlay App → mobile/build/outputs/apk/debug/
```

`mobile` 模块是主 CarPlay App（`com.shihab.diplay`）；`home` 是桌面示例，`maphost` 是地图示例。
好客车辆数据源位于 `common/src/main/java/com/shilapi/xcertplay/haoke/`（解析器、AIDL 客户端、
数据源，以及入口 `HaokeOutputs`），装配进 `CarPlayHostActivity` 与 `DiPlayActivity`。

CAN 解析器的单元测试位于
`shared/src/test/java/com/shilapi/xcertplay/haoke/HaokeCanParserTest.kt`。

---

## 安装

APK 装在**车机**上，而不是 iPhone。无需越狱、外接盒子、Mac、账号或认证服务器。车机必须允许安装 APK；
使用欧拉数据源时，车机还需预装好客 CarManager（`com.haoke.carmanager`）——欧拉好猫 / 黑猫车机均已预装。

- **有线 USB** 与**无线** CarPlay（Wi-Fi Direct、车机热点、现有 Wi-Fi / 同一局域网）。
- 最低 Android **4.2（API 17）**；实测基线 Android 4.4–6.0，同样可运行在较新的 Android 上。

详见上游 [INSTALL](docs/INSTALL.md) 与 [COMPATIBILITY](docs/COMPATIBILITY.md) 指南。

---

## 欧拉车型上的车辆状态功能

连接成功并检测到好客数据源后：

- **电量（SOC）与剩余续航** 显示在 CarPlay 的电动车/仪表盘状态中。
- **车速** 供 iPhone 死区推算使用（隧道导航连续性）。
- **挡位**（R/N/D）在车辆状态协议允许时上报。

**定位**来源是车机自带 GPS——好客通过标准 Android `LocationManager` 暴露，DiPlay 的
`AndroidCarPlayLocationProvider` 已直接消费，无需额外桥接。

**胎压** 已从 CAN 帧解析，但**不上报** iPhone：iAP2 车辆状态通道没有胎压字段，苹果也不会消费它。

---

## 现状与注意事项

本 fork 是一个**依赖特定硬件的社区适配**，并非苹果认证产品。APK 内置了从公开 Carlinkit 固件中恢复的
实验性配件身份（见 [SECURITY.md](SECURITY.md)）。未来 iOS 更新后的兼容性、跨车机可靠性，以及该身份
是否适合广泛分发，均尚未解决。

好客 CAN 字段映射与 `mode` 值来自对社区「黑猫助手」APK 的逆向，**尚未在实车上验证**。在采信仪表盘
数字之前，请在实车上确认车速 / 电量 / 续航 / 挡位会更新。若某个值不动，最可能的原因是：
(1) 好客 CarManager 不存在或未运行；(2) 你固件的 CAN 帧布局不同——两者都可通过 `adb logcat`，
结合 `HaokeVehicleData` / `HaokeCanParser` / `CarPlayLocation` 标签排查。

---

## 致谢

- 基于 [DiPlay](https://github.com/shihabal3amri/DiPlay)（[xcertplay](https://github.com/shilapi/xcertplay)，GPL-3.0）与
  [DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android)。
- 好客车辆数据源移植自社区「黑猫助手」的 `VehicleDataParser` 字段映射。
- CarPlay 及其图标归 Apple Inc. 所有；与 Apple、欧拉或 BYD 无任何隶属或背书关系。

详见上游 [发布说明](CHANGELOG.md) 与 [第三方声明](docs/THIRD_PARTY_NOTICES.md)。
