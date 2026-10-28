# HyperPods-X

HyperPods-X 想做的事情很简单：让 AirPods 连接小米手机之后，不止是一副能听歌的蓝牙耳机。

打开充电盒时，手机可以显示 HyperOS 原生耳机弹窗和超级岛；点击系统蓝牙列表中耳机卡片右侧的箭头，可以直接打开耳机设置；听音乐时，可以选择固定空间音频，也可以让真实的耳机姿态参与头部追踪。电量、聆听模式、入耳检测和常用设置，可以使用熟悉的 miuix 界面，也可以切换到新增的 iOS 界面。

这是一个仍在完善的 Android 应用与 LSPosed 模块。目前围绕 **Xiaomi 17 Pro Max、HyperOS 4 和 AirPods 5** 开发并进行真机测试，包名为 `com.hyper.podsrx`，使用现代 **libxposed API 102**。当前开发版本是 **0.4.9 预览版**，已发布安装包请查看 Releases。

[下载 Releases](https://github.com/YeFeng233/HyperPods-X/releases) · [构建记录](https://github.com/YeFeng233/HyperPods-X/actions/workflows/build-release.yml) · [真机测试记录](docs/TEST-REPORT.md)

## 能做些什么

**两种界面，随时切换。** “更多设置 → 外观”的开关关闭时为 **miuix风格**，开启时为 **iOS风格**。首次安装与旧版本升级默认沿用 miuix；选择会在重启后保存。iOS 设计由 NEORUAA 的 PR #1 引入，使用 Glasmio 和 SF Pro。两种界面共用耳机设置、连接状态和校准，系统蓝牙入口也会打开选中的风格。

**开盖就能看到耳机。** 模块会识别已绑定耳机的开盖广播，在桌面调用 HyperOS 自带的底部耳机弹窗。在其他应用前台，超级岛左右两侧分别展示对应的单耳图片和电量百分比。底部弹窗和 App 可查看左右耳与充电盒电量；暂时没有新数据时会标明历史状态，超级岛中的未知电量显示“—”。

**设置入口也在系统蓝牙里。** 点击蓝牙列表中已绑定 AirPods 卡片右侧的箭头，可以直接进入 HyperPods-X 耳机设置。模块内的“音频与设备权限”仍能打开系统设备详情，管理配对、重命名、AAC、音量同步和通话权限。系统设备详情也保留“HyperPods-X 高级设置”入口；其他蓝牙设备继续使用自己的页面。

**日常操作尽量留在耳机上。** 自动入耳检测支持摘下一只暂停、戴回继续，也支持单耳使用。提示音量可以调整，滑动音量已在目标设备上恢复正常。应用只会自动恢复自己暂停的媒体，避免把用户原本暂停的音乐重新放出来。

**聆听模式和高级设置集中管理。** 首页提供通透、自适应和降噪。自适应模式复用 LibrePods 的整合式滑条，可在 0～100 之间连续调节，向右增强降噪。滑条会分别展示已发送的目标和耳机回读值；没有得到同值回执时，也能继续拖动。高级页还包含耳机实际报告并支持写入的 EQ、按压时长、通话按压配置、单耳降噪、个性化音量和入睡暂停等设置。这些高级配置以真实回读为准。

**对话感知已经接入。** 检测到耳机的讲话事件时，可以降低手机媒体音轨的音量，并在对话结束后恢复，保留 HyperOS 的多应用同时播放设置。轻声讲话的识别仍受耳机事件灵敏度限制，后台和复杂中断场景也还需要继续验证。

## 空间音频

空间音频提供三个选项，首页和空间音频页面使用同一套控制。

| 模式 | 使用方式 |
| --- | --- |
| 关闭 | 关闭这副耳机的系统空间渲染，同时停止姿态采集和头部追踪。 |
| 固定 | 使用 HyperOS 的空间渲染，不启用头部追踪，也不需要校准。 |
| 头部追踪 | 将耳机传回的真实姿态接入系统，转头时保持声源方向，需要先完成三轴校准。 |

头部追踪使用真实耳机数据，已有动态试听和摘下清理的真机验证。切换到固定或关闭时，会结束姿态订阅并清理系统头追传感器；启动过程中也可以改选其他模式。

首次使用头部追踪，请先进入“头部追踪与校准”，按照提示完成右转、抬头和侧倾。之后会复用保存的校准；手机位置改变时，可以使用“正视手机并回中”。摘下双耳或连接中断后，追踪会停止，需要手动重新选择。空间化偏好仍开启时，页面会回到固定模式。

这里使用的是 HyperOS 系统渲染器，没有包含 Apple 的个性化空间音频档案。实际是否经过空间渲染，还取决于播放器、音轨和系统支持情况。详细实现与验证范围见[空间音频记录](docs/SPATIAL-AUDIO-TEST.md)。

## 安装与使用

1. 从 [Releases](https://github.com/YeFeng233/HyperPods-X/releases) 下载 APK，先在系统蓝牙中配对耳机。当前发布的是经过 R8 优化的 `deviceTest` 预览包。
2. 在支持现代 API 102 的 LSPosed 中启用 HyperPods-X，勾选“系统设置” `com.android.settings` 和“小米蓝牙” `com.xiaomi.bluetooth`。更新模块后，重新启动对应宿主进程或重启手机。
3. 打开应用，授予附近设备、精确位置和通知权限，并允许应用在后台运行。精确位置权限用于接收完整 BLE 广播。
4. 点击“连接耳机”。之后可以从应用首页，或系统蓝牙 → 已绑定 AirPods 卡片右侧箭头进入耳机设置。设备详情中的“HyperPods-X 高级设置”入口也会保留。
5. 在“更多设置”中管理开盖弹窗、超级岛、电量卡片和连接诊断。系统原生通道不可用时，兼容电量卡片需要悬浮显示权限。

当前安装包最低要求 Android API 37。真机测试覆盖 HyperOS `OS4.0.4.0.XPBCNXM` 与 `OS4.0.5.0.XPBCNXM`，已移除精确系统版本门禁，改为检查运行时接口。其他手机、系统版本和耳机型号还没有完整验证，不能将这份适配范围直接扩展到所有 AirPods 或所有 HyperOS 设备。

超级岛目前用于展示电量，点击不会进入设置。后台功耗、长期头追漂移、来电及切换输出等场景仍在继续完善。通话按压等部分高级配置已验证写入与恢复，但实际使用效果还没有逐项验收。更多结果和限制记录在[联调报告](docs/TEST-REPORT.md)中。

## 从源码构建

需要 Git、Python 3.11+、JDK 21、Android SDK Platform 37.0 和 Build Tools 37.0.0。在 `local.properties` 中设置本机 `sdk.dir` 后，先准备固定版本的 Glasmio 源码和字体，再运行：

```bash
python scripts/prepare-ui.py --fonts
./gradlew :app:testDebugUnitTest :core:protocol:test :app:lintDebug :app:assembleDeviceTest
```

Windows 使用 `gradlew.bat`。构建结果位于 `app/build/outputs/apk/deviceTest/`。项目使用 Gradle Wrapper，并锁定 Gradle 9.7.1、AGP 9.4.1、Kotlin 2.4.21 和 Miuix 0.9.4。Glasmio 使用源码 composite build，需要 NDK 27.1.12297006、CMake 3.22.1；字体也可省略并回退到系统字体，详见[双界面构建说明](docs/UI-STYLES.md)。

仓库提供 [Build and release](.github/workflows/build-release.yml) 工作流。在 Actions 中手动运行，可以完成单元测试、协议测试、lint、APK 签名验证和源码打包；启用发布选项后，通过检查的 APK、源码 ZIP 和 SHA-256 校验文件会上传到 Releases。签名材料通过仓库加密 Secret 提供，不保存在源码中；维护方法见[发布说明](docs/RELEASING.md)。

构建覆盖 App 界面状态与协议测试。测试和 lint 通过是构建检查，真机功能的验收范围以对应文档为准。

## 项目结构与数据

`app` 负责耳机连接、设置界面、电量、弹窗和媒体联动；`hook` 负责系统蓝牙入口、原生界面和空间音频桥接；`core/protocol` 包含不依赖 Android 的协议解析、广播验证、姿态解码和状态策略。设计、适配与实测记录放在 `docs` 中。

应用不申请互联网权限。耳机身份材料保存在本机，并使用 Android Keystore 加密。设备抓包、系统 APK、反编译资料、个人诊断、签名文件和开发环境不会进入仓库或源码包。

## 致谢与许可

项目参考了 [LibrePods](https://github.com/librepods-org/librepods) 的协议研究与耳机控制实现，以及 [HyperPods](https://github.com/Mo-SeTian/HyperPods) 的小米系统适配思路；界面基于 [Miuix](https://github.com/compose-miuix-ui/miuix)，模块使用 [libxposed](https://github.com/libxposed)。姿态服务发现和数据格式也参考了公开的 RTBuddy、AACP 与头追研究，完整来源见[第三方声明](THIRD_PARTY.md)。

源码采用 **GPL-3.0-or-later**，详见 [LICENSE](LICENSE)。耳机产品图片的版权与代码许可分开，来源见[素材说明](docs/ARTWORK-SOURCES.md)。HyperPods-X 是独立开发项目，与 Apple、小米没有隶属关系。
