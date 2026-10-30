# miuix 与 iOS 双界面规范与构建指南

HyperPods-X 提供两套原生级别的界面呈现：深度契合 HyperOS 视觉语言的 **miuix** 主题，以及基于 Glasmio 实现的精致 **iOS** 玻璃拟态主题。

---

## 1. 界面切换与状态共享

- **无缝切换**：在任一风格下的“更多设置 → 外观 → UI 风格”中均可即时切换，设置立刻生效并持久化存储，应用重启后自动保持当前偏好。
- **配置与数据集中管理**：两套主题完全解耦于具体的布局渲染，页面路由、控制项顺序、设备能力过滤等均统一在 `ui/SettingsCatalog.kt` 中维护。
- **状态无缝互通**：两套主题共用底层 `DeviceRepository` 与 `PodsService`，无论通过哪个界面修改降噪、EQ 或进行空间音频校准，耳机状态均实时同步。

---

## 2. 构建依赖准备

iOS 主题引入了开源玻璃拟态库 [Glasmio](https://github.com/NEORUAA/Glasmio) 与 SF Pro 字体。Glasmio 采用 Composite Build 形式引入源码进行编译。

### 2.1 环境准备
- **编译工具**：JDK 21、Android SDK Platform 37.0、NDK 27.1.12297006、CMake 3.22.1
- **自动化拉取**：在项目根目录运行初始化脚本，拉取固定版本的 Glasmio 源码并下载校验 SF Pro 字体：
  ```bash
  python -m pip install -r scripts/requirements-fonts.txt
  python scripts/prepare-ui.py --fonts
  ```

### 2.2 字体校验说明
`--fonts` 脚本下载并校验 SF Pro 常规字体，再生成 `SF-Pro-Subset.ttf`。APK 只打包子集字体，不包含斜体字体。

| 字体文件 | SHA-256 校验和 |
| --- | --- |
| `SF-Pro.ttf` | `7270e1593be7b88597d8ab302e4d156a33084c85b8161e042ff6510cdaf10e10` |

子集保留拉丁字母、西里尔字母、常用标点、符号及固定 Glasmio 图标目录中的 SF Symbols，并保留字重和光学尺寸可变轴。中文、日文、韩文使用 Android 系统字体回退。字体原文件和生成结果均留在本地 `.tooling/fonts/`，不提交到仓库。

若本地不配置自定义字体，构建流程会自动回退至系统默认字体与 Material 图标，不影响任何功能的使用。

### 2.3 常用构建命令
```bash
# 执行完整测试与设备预览包构建
./gradlew :app:testDebugUnitTest :core:protocol:test :app:lintDebug :app:assembleDeviceTest
```
> **Windows 环境提示**：若工作区路径包含非 ASCII 字符，CMake 可能报告路径异常。可运行 `scripts/build-windows.ps1`，脚本会自动创建临时盘符映射并执行编译。

---

## 3. 设计规范细节

- **miuix 风格**：遵循 [Miuix UI 指南](MIUIX-UI-GUIDELINES.md)。采用响应式可折叠大标题、标准 Squircle 平滑圆角卡片，以及 HyperOS 原生风格的电量图标与模式切换器。
- **iOS 风格**：采用 Glasmio 的毛玻璃背景渲染、动态卡片组与 SF Pro 字型排版，自适应滑条支持 0～100 连续拖动微调。
- **控制指令安全**：两套主题复用同一套串行命令队列、即时视觉反馈与超时自动回退机制，确保在弱网或蓝牙干扰时不出现界面状态卡死。
