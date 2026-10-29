# miuix 与 iOS 界面

“更多设置 → 外观”提供一个开关。关闭时显示 `miuix风格`，开启时显示 `iOS风格`。新安装默认使用 miuix；旧版本升级也保持 miuix。两边的更多设置都有这个入口，切换立即生效并停留在更多设置。

外观保存在独立的 `appearance` 偏好中，应用重启后恢复，不随耳机绑定、重连或控制通道重建而清空。两种界面共用 `DeviceRepository`、耳机控制服务、权限和校准。系统蓝牙箭头打开设置时也使用所选风格。

iOS 界面来自 NEORUAA 的 [PR #1](https://github.com/YeFeng233/HyperPods-X/pull/1)，固定源提交 `ae9fa5ec8028e94e0dbbf8243228387756edd78e`，在 `ui/ios/` 下适配。保留原 miuix 页面与 LibrePods 连续滑条；iOS 分支采用 PR 的分组设置、玻璃导航、媒体音量、名称编辑及嵌入式自适应滑条。同时接入 PR 的融合设备中心 Hook，两种风格均可使用该系统入口，详见 [融合设备中心](NATIVE-FUSION.md)。

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

## miuix 的模式选择与局部更新

聆听模式从左到右为“关闭、通透、自适应、降噪”；自适应模式继续使用 LibrePods 连续强度滑条。窄屏或大字体下将强度滑条放到下一行。空间音频采用相同的圆角轨道与选中圆形样式，支持点击或横向拖动选择“关闭、固定、头部追踪”，拖动结束才提交，取消拖动不写入。不可用的追踪模式仍受佩戴、连接和校准条件约束；显示值跟随实际确认状态。

miuix 的主题对象仅在系统明暗模式变化时更新。设备状态订阅下移到内容和具体控件，更多设置的开关分别订阅自身值，高级设置行只订阅自身的回读和待确认状态。关闭原生弹窗时保留相关行的位置，仅禁用不可用的预览按钮。导航、滚动容器和主题不再订阅完整设备状态。iOS 分支的更新行为未在本次修改范围内。
