# miuix 与 iOS 界面

“更多设置 → 外观”提供“UI 风格”下拉菜单，选项为 `miuix` 和 `iOS`。新安装默认使用 miuix；升级保留已有选择。两边的更多设置都有这个入口，切换立即生效并停留在更多设置。

外观保存在独立的 `appearance` 偏好中，应用重启后恢复，不随耳机绑定、重连或控制通道重建而清空。两种界面共用 `DeviceRepository`、耳机控制服务、权限和校准。系统蓝牙箭头打开设置时也使用所选风格。

iOS 界面来自 NEORUAA 的 [PR #1](https://github.com/YeFeng233/HyperPods-X/pull/1)，固定源提交 `ae9fa5ec8028e94e0dbbf8243228387756edd78e`，在 `ui/ios/` 下适配。iOS 分支采用 PR 的分组设置、玻璃导航、媒体音量、名称编辑及嵌入式自适应滑条。miuix 分支按同一页面层级使用原生 miuix 页面和设置控件。同时接入 PR 的融合设备中心 Hook，两种风格均可使用该系统入口，详见 [融合设备中心](NATIVE-FUSION.md)。

## 准备构建依赖

Glasmio 尚未使用 Maven 发布包，本项目通过 Gradle composite build 编译固定源码。需要 Git、Python 3.11+、JDK 21 和 Android SDK；原生渲染还需要 NDK `27.1.12297006`、CMake `3.22.1`。SDK 接受许可后，AGP 可安装缺少的组件。

在仓库根目录运行：

```bash
python scripts/prepare-ui.py --fonts
./gradlew :app:testDebugUnitTest :core:protocol:test :app:lintDebug :app:assembleDeviceTest
```

Windows 使用 `gradlew.bat`。若仓库路径含非 ASCII 字符，Glasmio 的 CMake 会配置失败，可改用 `scripts/build-windows.ps1`：它通过临时 `subst` 盘符构建，`-Device <adb 序列号>` 可在构建后直接安装。准备脚本把 Glasmio 固定到 `bbba63b2f2584c4819bd88043332e241820d1ca0`，保存在忽略的 `.reference/Glasmio`。若已有不同提交的源码，脚本会提示保留或移走它，不会自动覆盖。自定义位置可使用 `-Pglasmio.sourceDir=/path/Glasmio`。

`--fonts` 按用户指定的 [SFWindows v27.0.1789118100](https://github.com/bradleyhodges/SFWindows/releases/tag/v27.0.1789118100) 下载 SF Pro 常规和斜体，并验证 SHA-256。字体位于忽略的 `.tooling/fonts/`，通过 `local.properties` 传入，再由构建任务复制到 APK 的生成资源目录。字体文件不进入 Git 或源码包，许可与 GPL 代码分开，见素材说明。

| 文件 | SHA-256 |
| --- | --- |
| SF-Pro.ttf | `7270e1593be7b88597d8ab302e4d156a33084c85b8161e042ff6510cdaf10e10` |
| SF-Pro-Italic.ttf | `9c80b0d5ec685fc6408c40f8698f7c5f6f8115be3981a5c23e00f11726b20c5f` |

也可自行设置 `glasmio.symbolFont`、`glasmio.italicFont` 或环境变量 `HYPERPODS_SF_PRO`、`HYPERPODS_SF_PRO_ITALIC`。不提供字体时，iOS 分支采用系统字体与 Glasmio 的 Material 图标回退，仍可使用全部设置。CI 使用相同准备脚本和字体校验，不需要额外配置字体下载 Secret。

## 两套界面的共享设置配置

页面路由、入口标题、控制项顺序与能力过滤统一维护在 `ui/SettingsCatalog.kt`，设备支持的原始控制定义仍由 `device/Controls.kt` 提供。两套界面读取同一份 `PodsState`，写入仍通过 `DeviceRepository` 与 `PodsService`，切换外观不会产生第二套耳机设置。

首页入口由 `rootFeatureLinks`、`batteryLink`、`rootSettingsLinks` 管理；音频、手势与无障碍页面读取对应的 `*SectionOrder`，更多设置读取 `moduleSections` 及其中的行顺序。两套渲染器按这些配置生成现有组件。

共享配置只描述设置内容，不包含组件、图标绘制或布局。iOS 页面继续使用原有 Glasmio 组件和层级；miuix 使用自身页面与设置组件。应用侧“自动入耳检测”与耳机侧的传感器控制保持独立。

## miuix 页面与控件

页面骨架、卡片间距、滚动行为与组件使用遵循项目适配后的 [Miuix UI 规范](MIUIX-UI-GUIDELINES.md)。手机使用可折叠大标题，宽屏与低高度窗口使用紧凑顶栏。耳机主页使用系统耳机设置的开盖图、按钮外观和 HyperOS 电量符号；聆听模式内部图标与 iOS 主题共用，原 iOS 素材保持不变。

聆听模式顺序为“关闭、通透模式、自适应、降噪”；miuix 始终显示四个模式，使用自适应时仍可选择关闭；只有耳机显式禁用关闭模式时才将其置灰。iOS 保持原有显示策略。自适应强度使用 miuix Slider，支持 0～100 连续调整。两主题复用同一套即时反馈、串行发送和超时回退逻辑。空间音频、EQ、通话控制和无障碍选项位于与 iOS 相同的页面，所有写入仍受连接、设备能力与校准状态约束。

共享配置只替换 iOS 的等价数据引用，不改变其材质、排版、间距、导航或生命周期。
