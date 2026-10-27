# AirPods 5 图片来源

取得日期：2026-10-22。用于用户授权的本机开发测试，App 运行时离线读取，不向官网发起请求。

Apple 官方型号资料将 A3441 列为 AirPods 5 无线充电盒版本：
https://support.apple.com/en-ie/109525

产品页面：https://www.apple.com/airpods-5/

| 本地资源 | 官方原图 |
| --- | --- |
| `app/src/main/res/drawable-nodpi/airpods5_pair.png` | 从用户手机已安装的 LibrePods 2.0.0-alpha.3 提取，详见下文 |
| `app/src/main/res/drawable-nodpi/airpods5_case.png` | https://www.apple.com/v/airpods-5/b/images/overview/stories/battery_case__e74v6o9y3mie_large_2x.png |

图片保留取得时的内容，未去除水印或改绘。0.4.1 详情页、兼容卡片和原生耳机提示使用透明双耳图片，
两张图片也提供给系统原生耳机提示。图片版权属于 Apple Inc.；公开下载不等于取得开源或商业再分发许可，
不包含在本项目 GPL 源码授权中。此开发测试版不将这些图片标注为 GPL 素材；公开发行前应另行取得相应许可或替换。

## 0.4.1 LibrePods 透明双耳图

- 提取日期：2026-10-22。来源为用户授权检查的已安装 APK，未读取或复制 LibrePods 私有数据。
- 包：`me.kavishdevar.librepods`，版本 `2.0.0-alpha.3` / code `200000003`。
- APK SHA-256：`6f4547a4cb7af89d9e8ec11dcdc550585518b1d8ef5f7c651a246645fd9418e9`。
- 资源：`drawable/img_airpods_5_wireless_buds` (`0x7f0700aa`) → APK `res/E1.png`；普通款 `img_airpods_5_buds` / `res/JA.png` 的内容相同。
- 新文件：`airpods5_pair.png`，291 × 219，32-bit RGBA，透明角点 alpha=0；未经重绘、抠图或重新编码。
- 图片 SHA-256：`674f62db242c73823ea71a976fa9d2dceb289ebfeee01360f0eed64b76789aa4`。
- 项目来源：https://github.com/librepods-org/librepods 。LibrePods 的开源代码许可不自动证明 Apple 产品图片的再分发授权；保留前述本机测试限制。
- 旧 `airpods5_pair.jpg` 来自 https://www.apple.com/v/airpods-5/b/images/overview/bento-gallery/bento_pair__c7i9mu5k2zee_large.jpg ，已退出 App 资源，原文件保留于本机 `.reference/`。

## iOS 27 listening-mode icons

Extracted on 2026-10-27 from the user's installed iOS 27.0 Simulator runtime,
build `24A434`, with the user's authorization. Runtime frameworks and analysis
databases remain outside the source tree. These Apple image resources retain
their separate copyright and are not covered by the project's GPL grant.

| Application resource | Original rendition | SHA-256 |
| --- | --- | --- |
| `listening_off.png` | `HeadphoneConfigs.framework/Assets.car`, `Off`, 32 pt @3x | `9e0134932b97c94d2513559833a88dbc1dd56590cc63afa9910354b25c1aaf17` |
| `listening_transparency.png` | Same catalog, `Transparency`, 32 pt @3x | `bfd7056034d5a69fd7fa4ad71575c3f390b083d1d7fc474766e60b5b04dcd0c7` |
| `listening_anc.png` | Same catalog, `Noise Cancellation`, 32 pt @3x | `29e3ef950298e0599d217b57d164b1452aaa3cc271ee076f815ce384c2e3d7c0` |
| `listening_adaptive.png` | `CoreGlyphsPrivate.bundle/Assets.car`, `person.and.sparkles.fill`, Regular | `8286ad51be85a13c1799ab7ac6ce08378a39ec964ee2185025b43682fab4e1ed` |

The first three files are original CoreUI-decoded 96×96 PNGs. The adaptive icon
is rendered from the original CoreSVG glyph, centered in a transparent 96×96
canvas with an 80-pixel visible height to match the other three renditions.
The application applies the current theme's foreground tint. The Off rendition
retains its gray ring; dark mode inverts the original grayscale instead of
flattening it into the ANC glyph.

Source catalog SHA-256:

- HeadphoneConfigs: `cfa5d2d79c5e6c16c3f96c1b997a91a51c16f79d624f26436059f3a31cce8b10`
- CoreGlyphsPrivate: `86675131b87b74b3a236883700161fa6719bf7453213fe883a583890cfe12ec7`

The lower menu glyphs were cross-checked with the same runtime's CoreGlyphs
catalog: `speaker.wave.3.fill`, `hand.pinch.fill`, and `accessibility`. The app
renders these names and `battery.100percent` directly from the build-host SF Pro
font through Glasmio's `SfIcon`. Battery status also uses the native 0/25/50/75/100
percent and charging glyphs. No custom battery paths or exported menu PNGs are
packaged.

IDA Pro MCP analysis of the runtime's `HeadphoneConfigs` implementation confirmed
`HPSListeningModeControl.setupConstraints` sets a 50-point segment height and a
5-point caption gap; `addModeOptions` configures 25-point symbols. The app supplies
these dimensions to Glasmio's segmented component. These values describe the
iOS 27.0 runtime; the connected iOS 27.2 device supplied the newer page reference.
AirPods 5 B868 product artwork was absent from the examined simulator catalogs,
so the existing AirPods 5 product images remain in use.
