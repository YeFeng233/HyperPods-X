# HyperPods-X

HyperPods-X 是专为小米 HyperOS 打造的 AirPods 深度集成工具（Android 应用 + LSPosed 模块）。通过深入对接 HyperOS 原生系统组件与底层蓝牙协议，让 AirPods 在澎湃 OS 上获得流畅、自然的生态体验。

支持开盖原生底部弹窗与超级岛实时电量、系统蓝牙列表设置直达、HyperOS 融合设备中心控制、基于真实耳机姿态的动态头部追踪空间音频，并提供精致的 miuix 与 iOS 双风格控制面板。

- **支持环境**：最低 Android 16（API 36）；当前主要在 Xiaomi 17 Pro Max / HyperOS 4（Android 17, API 37）上验证
- **支持耳机**：AirPods 5、AirPods Pro 3
- **底层架构**：现代 libxposed API 102，采用小米蓝牙宿主状态中心（Hub）持久化架构
- **当前版本**：0.5.1 预览版（包名 `com.hyper.podsrx`）

> **0.5.0 架构升级提示**：0.5.0 版本重构了状态分发架构，将 BLE 扫描、开合盖检测与原生弹窗调度迁入小米蓝牙宿主状态中心（Hub），大幅提升开盖响应速度并降低功耗。若维护 fork 分支，请参考 [0.5.0 发布说明](docs/releases/v0.5.0.md) 与 [状态中心迁移记录](docs/archive/HOST-HUB-MIGRATION-PLAN.md)。

[下载 Releases](https://github.com/YeFeng233/HyperPods-X/releases) · [CI 构建记录](https://github.com/YeFeng233/HyperPods-X/actions/workflows/build-release.yml) · [真机联调记录](docs/TEST-REPORT.md)

---

## 核心特性

- **原生弹窗与超级岛**
  - 开盖即弹 HyperOS 原生底部耳机卡片，合盖即时收回；
  - 在其他应用前台时，通过超级岛优雅呈现左右耳独立电量；
  - 状态中心（Hub）常驻系统蓝牙进程，即使 App 退出，开合盖与原生弹窗依然正常工作。
- **深度系统集成**
  - **系统蓝牙设置直达**：点击系统蓝牙列表中 AirPods 卡片右侧箭头，直接呼出 HyperPods-X 耳机配置；
  - **融合设备中心卡片**：控制中心的耳机大卡片直接支持电量查看、四档降噪模式切换与空间音频开关。
- **双风格控制面板**
  - 提供 **miuix**（原生澎湃质感）与 **iOS**（Glasmio 玻璃拟态）两套主题，可在“更多设置 → 外观”中无缝切换，耳机配置与校准数据完全互通。
- **手势控制与高级配置**
  - **降噪调节**：支持关闭、通透、降噪，以及自适应降噪幅度 0～100 连续平滑微调；
  - **完整手势定制**：连按速度、长按时长、柄部滑动音量、提示音音量、单耳降噪与入睡自动暂停；
  - **智能入耳检测**：摘下一只自动暂停、戴回继续播放；仅自动恢复由本应用暂停的媒体，不干扰用户原本手动暂停的状态。
- **对话感知**
  - 检测到开口说话时自动压低手机媒体音量，对话结束后平滑恢复，保留 HyperOS 多应用独立音轨能力。

---

## 空间音频与动态头追

HyperPods-X 读取 AirPods 内置运动传感器的六轴姿态数据，并桥接到 HyperOS 系统级 Spatializer 空间音频渲染器中：

| 模式 | 表现与特性 | 校准要求 |
| --- | --- | --- |
| **关闭** | 停用空间音频渲染，释放姿态订阅与头部追踪传感器 | 无 |
| **固定** | 启用 HyperOS 原生空间音频渲染（不随头部转动改变声场方位） | 无 |
| **头部追踪** | 实时获取耳机姿态驱动系统头部追踪器，转头时声源始终锚定在空间真实方位 | 需先完成三轴校准 |

首次使用头部追踪时，请在“头部追踪与校准”中跟随引导完成三轴校准（右转、抬头、侧倾）。日常使用若手机或身体方位改变，点击“正视手机并回中”即可快速校正。

> **说明**：空间音频基于 HyperOS 系统渲染引擎实现，暂不支持导入 Apple 个人空间音频轮廓。具体音轨是否生效取决于播放器、音源格式与系统支持情况，详见 [空间音频说明](docs/SPATIAL-AUDIO-TEST.md)。

---

## 安装与使用

1. 从 [Releases](https://github.com/YeFeng233/HyperPods-X/releases) 下载最新 APK，先在系统蓝牙中与耳机完成配对。
2. 在支持 API 102 的 LSPosed 中启用 HyperPods-X，并勾选以下四个作用域：
   - **系统设置** (`com.android.settings`)
   - **小米蓝牙** (`com.xiaomi.bluetooth`)
   - **设备互联** (`com.milink.service`)
   - **手机管家** (`com.miui.securitycenter`)
   > 升级或首次启用后，建议重启手机或在“更多设置”中点击“重启作用域”，确保各系统宿主成功加载新模块。
3. 打开 HyperPods-X 应用，授予附近设备和通知权限，并开启后台运行权限以保障 AACP 控制通道、入耳检测与空间音频持续运行。
4. 点击“连接耳机”即可开始使用。后续无论从 App 首页还是系统蓝牙列表均可快速进入耳机设置。

如遇连接或开盖异常，可在任意界面的“更多设置 → 导出日志”中生成排查日志包（ZIP），详见 [日志说明](docs/LOGS.md)。

---

## 支持环境与已知限制

- **系统基线**：最低安装版本为 Android 16 / API 36；功能完整性当前针对搭载 HyperOS 4（Android 17 / API 37）的 Xiaomi 17 Pro Max 验证；
- **耳机型号**：已完整覆盖 AirPods 5 与 AirPods Pro 3；
- **超级岛交互**：目前仅作为电量状态提示，点击暂不触发页面跳转；
- **生态差异**：不支持 Apple 查找网络（Find My）及固件升级；空间音频长周期漂移补偿等高级体验仍在持续优化中。

---

## 从源码构建

### 环境要求
- Git、Python 3.11+、JDK 21
- Android SDK Platform 37.0、Build Tools 37.0.0
- NDK 27.1.12297006、CMake 3.22.1（Glasmio 原生渲染所需）

### 构建步骤
1. 配置 `local.properties` 中的 `sdk.dir`。
2. 运行初始化脚本准备 Glasmio 源码及 SF Pro 字体依赖：
   ```bash
   python scripts/prepare-ui.py --fonts
   ```
3. 执行编译与打包：
   ```bash
   ./gradlew :app:testDebugUnitTest :core:protocol:test :core:wire:testDebugUnitTest :app:lintDebug :app:assembleDeviceTest
   ```
   > Windows 环境请使用 `gradlew.bat`。生成的 APK 位于 `app/build/outputs/apk/deviceTest/`。

更多构建细节与字体回退方案见 [双界面构建说明](docs/UI-STYLES.md)。发布流水线与签名配置见 [发布说明](docs/RELEASING.md)。

---

## 项目架构

```
HyperPods-X/
├── core/
│   ├── protocol/    # 纯 Kotlin 实现的底层协议解析、广播解密、姿态解算与状态机（无 Android 依赖）
│   └── wire/        # App 与各系统宿主之间的 Binder IPC 通信契约与数据结构
├── hook/            # 各系统宿主（设置、小米蓝牙、设备互联等）的轻量注入胶水层
├── app/             # 应用主工程：设置界面（miuix & iOS）、AACP 会话、入耳与空间音频管理
└── docs/            # 架构设计、测试记录与技术规范
```

- **数据与安全**：应用不申请互联网权限，所有耳机身份材料与广播解密密钥均保存在本地，通过 Android Keystore 加密存储。

---

## 致谢与开源许可

- 本项目参考了 [LibrePods](https://github.com/librepods-org/librepods) 的协议逆向与耳机控制方案，以及 [HyperPods](https://github.com/Mo-SeTian/HyperPods) 的 HyperOS 适配思路；
- 界面组件基于 [Miuix](https://github.com/compose-miuix-ui/miuix) 与 [Glasmio](https://github.com/NEORUAA/Glasmio)；
- 注入框架基于 [libxposed](https://github.com/libxposed)；
- 姿态服务发现与运动数据解析参考了公开的 RTBuddy 及相关研究，详见 [第三方开源声明](THIRD_PARTY.md)。

项目源码采用 **GPL-3.0-or-later** 许可，详见 [LICENSE](LICENSE)。耳机外观图片版权归属见 [素材说明](docs/ARTWORK-SOURCES.md)。HyperPods-X 为独立开源项目，与 Apple Inc. 和小米集团无隶属关系。
