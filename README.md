# HyperPods-X

[简体中文](README.md) | [English](README_EN.md)

HyperPods-X 是面向小米 HyperOS 的 AirPods 系统级集成模块（Android 应用 + LSPosed 模块）。项目通过对接系统蓝牙、SystemUI 与设备互联接口并实现 Apple AACP 协议，在澎湃 OS 上提供原生开盖弹窗、超级岛实时电量、融合设备中心控制、动态头部追踪空间音频与双风格控制面板。

[下载 Releases](https://github.com/YeFeng233/HyperPods-X/releases) · [CI 构建记录](https://github.com/YeFeng233/HyperPods-X/actions/workflows/build-release.yml) · [真机联调记录](docs/TEST-REPORT.md)

## 运行基线

- **系统环境**：Xiaomi 17 Pro Max / HyperOS 4（Android 17, API 37）及后续兼容机型
- **支持型号**：AirPods 5、AirPods Pro 3
- **注入架构**：libxposed API 102，基于小米蓝牙宿主状态中心（Hub）持久化架构
- **当前版本**：0.5.1 预览版（包名 `com.hyper.podsrx`）

> **0.5.0 架构说明**：0.5.0 起将 BLE 扫描、开合盖检测与原生弹窗调度迁入小米蓝牙宿主状态中心（Hub），以提升开盖响应速度并降低后台功耗。维护 fork 分支请参考 [0.5.0 发布说明](docs/releases/v0.5.0.md) 与 [状态中心迁移记录](docs/archive/HOST-HUB-MIGRATION-PLAN.md)。

---

## 核心特性

- **原生开盖弹窗与超级岛**
  - 开盖弹出 HyperOS 原生底部耳机卡片，合盖即时收回。
  - 其他应用处于前台时，通过 SystemUI 超级岛显示左右耳独立电量。
  - 状态中心（Hub）常驻系统蓝牙进程，主应用退出后仍可响应开合盖与原生弹窗。
- **系统级入口集成**
  - **系统蓝牙设置直达**：点击系统蓝牙列表中 AirPods 右侧箭头，直接跳转至 HyperPods-X 配置页。
  - **融合设备中心卡片**：控制中心耳机大卡片支持电量查看、四档降噪模式切换与空间音频开关。
- **双风格控制面板**
  - 内置 **miuix**（HyperOS 原生风格）与 **iOS**（Glasmio 玻璃拟态）两套界面，可在「更多设置 → 外观」中切换，配置与校准数据完全互通。
- **降噪与手势定制**
  - **降噪模式**：支持关闭、通透、降噪，以及自适应降噪幅度 0–100 连续微调。
  - **手势与提示音**：支持连按速度、长按时长、柄部滑动音量、提示音音量、单耳降噪与入睡自动暂停。
  - **智能入耳检测**：摘下单耳自动暂停、戴回继续播放；仅自动恢复由本应用暂停的媒体，不干扰手动暂停状态。
- **对话感知**
  - 检测到开口说话时自动压低媒体音量，对话结束后平滑恢复，保留 HyperOS 多应用独立音轨能力。

---

## 空间音频与动态头追

HyperPods-X 读取 AirPods 运动传感器的六轴姿态数据，并桥接至 HyperOS 系统级 Spatializer 空间音频渲染器：

| 模式 | 行为说明 | 校准要求 |
| --- | --- | --- |
| **关闭** | 停用空间音频渲染，释放姿态订阅与头部追踪传感器 | 无 |
| **固定** | 启用 HyperOS 原生空间音频渲染（声场不随头部转动变化） | 无 |
| **头部追踪** | 实时读取耳机姿态驱动系统头部追踪器，转头时声源锚定在空间真实方位 | 需先完成三轴校准 |

首次使用头部追踪时，请在「头部追踪与校准」中按引导完成三轴校准（右转、抬头、侧倾）。日常使用若设备或身体方位改变，点击「正视手机并回中」即可重新归中。

> 空间音频依赖 HyperOS 系统渲染引擎，实际音轨生效情况取决于播放器、音源格式与系统支持，详见 [空间音频说明](docs/SPATIAL-AUDIO-TEST.md)。

---

## 安装与配置

1. **配对与安装**：在系统蓝牙中完成耳机配对，从 [Releases](https://github.com/YeFeng233/HyperPods-X/releases) 下载并安装最新 APK。
2. **启用模块作用域**：在支持 libxposed API 102 的 LSPosed 中激活 HyperPods-X，并勾选以下作用域：
   - **系统设置** (`com.android.settings`)
   - **系统界面** (`com.android.systemui`)
   - **小米蓝牙** (`com.xiaomi.bluetooth`)
   - **设备互联** (`com.milink.service`)
   - **手机管家** (`com.miui.securitycenter`)
   - **音质音效** (`com.miui.misound`)
   > 首次启用或升级模块后，请重启设备或在「更多设置」中点击「重启作用域」，确保各系统宿主加载最新代码。
3. **授予权限**：打开 HyperPods-X，授予「附近设备」与「通知」权限，并允许后台运行，以维持 AACP 控制通道、入耳检测与空间音频服务。
4. **连接使用**：点击「连接耳机」即可开始使用；后续可从 App 首页或系统蓝牙列表直接进入设置。

如遇连接或开盖异常，可在任意界面的「更多设置 → 导出日志」生成排查日志包（ZIP），详见 [日志说明](docs/LOGS.md)。

---

## 已知限制

- **验证基线**：当前仅在 Xiaomi 17 Pro Max（HyperOS 4 / Android 17 / API 37）与 AirPods 5、AirPods Pro 3 上完成完整验证。
- **超级岛交互**：目前仅展示电量状态，点击暂不触发页面跳转。
- **Apple 生态功能**：不支持 Apple 查找网络（Find My）、固件升级及导入 Apple 个人空间音频轮廓。
- **空间音频细节**：头部追踪的长周期漂移补偿仍在持续优化中。

---

## 从源码构建

### 环境要求

- Git、Python 3.11+、JDK 21
- Android SDK Platform 37.0、Build Tools 37.0.0
- NDK 27.1.12297006、CMake 3.22.1（Glasmio 原生渲染所需）

### 构建步骤

1. 在 `local.properties` 中配置 `sdk.dir`。
2. 运行初始化脚本拉取 Glasmio 源码与 SF Pro 字体依赖：
   ```bash
   python scripts/prepare-ui.py --fonts
   ```
3. 执行单测、静态检查与打包：
   ```bash
   ./gradlew :app:testDebugUnitTest :core:protocol:test :core:wire:testDebugUnitTest :app:lintDebug :app:assembleDeviceTest
   ```
   > Windows 环境请使用 `gradlew.bat`。生成的 APK 位于 `app/build/outputs/apk/deviceTest/`。

构建细节与字体回退方案见 [双界面构建说明](docs/UI-STYLES.md)，发布流水线与签名配置见 [发布说明](docs/RELEASING.md)。

---

## 项目架构

```text
HyperPods-X/
├── core/
│   ├── protocol/    # 纯 Kotlin 实现的协议编解码、广播解密、姿态解算与状态机（无 Android 依赖）
│   └── wire/        # App 与系统宿主之间的 Binder IPC 契约与 Parcelable 定义
├── hook/            # 各系统宿主（设置、小米蓝牙、设备互联等）的轻量注入胶水层
├── app/             # 应用主工程：双风格 UI、AACP 会话、入耳检测与空间音频管理
└── docs/            # 架构设计、测试记录与技术规范
```

- **隐私与本地存储**：应用不申请互联网权限；所有耳机身份材料与广播解密密钥均在本地通过 Android Keystore 加密存储。

---

## 致谢与开源许可

- 协议逆向与耳机控制参考了 [LibrePods](https://github.com/librepods-org/librepods)，HyperOS 适配思路参考了 [HyperPods](https://github.com/Mo-SeTian/HyperPods)；
- 界面组件基于 [Miuix](https://github.com/compose-miuix-ui/miuix) 与 [Glasmio](https://github.com/NEORUAA/Glasmio)；
- 注入框架基于 [libxposed](https://github.com/libxposed)；
- 姿态服务发现与运动数据解析参考了公开的 RTBuddy 及相关研究，完整清单见 [第三方开源声明](THIRD_PARTY.md)。

项目源码采用 **GPL-3.0-or-later** 许可协议，详见 [LICENSE](LICENSE)。耳机外观素材版权归属见 [素材说明](docs/ARTWORK-SOURCES.md)。HyperPods-X 为独立开源项目，与 Apple Inc. 及小米集团无隶属关系。
