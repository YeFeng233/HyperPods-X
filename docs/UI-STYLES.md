# miuix 与 iOS 界面

“更多设置 → 外观”提供一个开关。关闭时显示 `miuix风格`，开启时显示 `iOS风格`。新安装默认使用 miuix；旧版本升级也保持 miuix。两边的更多设置都有这个入口，切换立即生效并停留在更多设置。

外观保存在独立的 `appearance` 偏好中，应用重启后恢复，不随耳机绑定、重连或控制通道重建而清空。两种界面共用 `DeviceRepository`、耳机控制服务、权限和校准。系统蓝牙箭头打开设置时也使用所选风格。

iOS 界面来自 NEORUAA 的 [PR #1](https://github.com/YeFeng233/HyperPods-X/pull/1)，固定源提交 `ae9fa5ec8028e94e0dbbf8243228387756edd78e`，在 `ui/ios/` 下适配。保留原 miuix 页面与 LibrePods 连续滑条；iOS 分支采用 PR 的分组设置、玻璃导航、媒体音量、名称编辑及嵌入式自适应滑条。控制中心融合面板的 Hook 属于 PR 的另一部分，不在本次 UI 设计整合范围内。

## 准备构建依赖

Glasmio 尚未使用 Maven 发布包，本项目通过 Gradle composite build 编译固定源码。需要 Git、Python 3.11+、JDK 21 和 Android SDK；原生渲染还需要 NDK `27.1.12297006`、CMake `3.22.1`。SDK 接受许可后，AGP 可安装缺少的组件。

在仓库根目录运行：

```bash
python scripts/prepare-ui.py --fonts
./gradlew :app:testDebugUnitTest :core:protocol:test :app:lintDebug :app:assembleDeviceTest
```

Windows 使用 `gradlew.bat`。准备脚本把 Glasmio 固定到 `5fcc8aa4c9ea4ed229fd181849f2c5e1982dda5d`，保存在忽略的 `.reference/Glasmio`。若已有不同提交的源码，脚本会提示保留或移走它，不会自动覆盖。自定义位置可使用 `-Pglasmio.sourceDir=/path/Glasmio`。

`--fonts` 按用户指定的 [SFWindows v27.0.1789118100](https://github.com/bradleyhodges/SFWindows/releases/tag/v27.0.1789118100) 下载 SF Pro 常规和斜体，并验证 SHA-256。字体位于忽略的 `.tooling/fonts/`，通过 `local.properties` 传入，再由构建任务复制到 APK 的生成资源目录。字体文件不进入 Git 或源码包，许可与 GPL 代码分开，见素材说明。

| 文件 | SHA-256 |
| --- | --- |
| SF-Pro.ttf | `7270e1593be7b88597d8ab302e4d156a33084c85b8161e042ff6510cdaf10e10` |
| SF-Pro-Italic.ttf | `9c80b0d5ec685fc6408c40f8698f7c5f6f8115be3981a5c23e00f11726b20c5f` |

也可自行设置 `glasmio.symbolFont`、`glasmio.italicFont` 或环境变量 `HYPERPODS_SF_PRO`、`HYPERPODS_SF_PRO_ITALIC`。不提供字体时，iOS 分支采用系统字体与 Glasmio 的 Material 图标回退，仍可使用全部设置。CI 使用相同准备脚本和字体校验，不需要额外配置字体下载 Secret。
