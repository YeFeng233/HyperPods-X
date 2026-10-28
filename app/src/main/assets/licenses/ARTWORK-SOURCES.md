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

## 0.4.7 超级岛单耳资源

- `:app:generateIslandEarbuds` 在构建时读取上述 `airpods5_pair.png`，沿中央透明间隔分离左右耳，按非透明像素的横向边界裁切，保留 219 像素高度，不缩放、不重绘。
- 左耳裁切区域 `(1, 0, 132, 219)`，输出 `airpods5_left.png`；右耳区域 `(158, 0, 131, 219)`，输出 `airpods5_right.png`。
- 输出位于 `app/build/generated/res/island-earbuds/drawable-nodpi/`，由 Android 资源管线自动打包；完整双耳原文件不变。单耳图片仍沿用原素材的权利说明。
- 本地左耳 PNG SHA-256：`2bffb5e17064db07f9cf5e1de58c6b3bc0f90d774eae1b6ca0a3165acbf07b7e`。
- 本地右耳 PNG SHA-256：`90b75531d4ab9fa191daebfdd35ab88c280b9e180c75110b5f7824f57cc2eb31`。

## iOS 界面素材（PR #1）

以下聆听模式 PNG 由 NEORUAA 的 HyperPods-X PR #1 提供，源提交 `ae9fa5ec8028e94e0dbbf8243228387756edd78e`。PR 作者的素材说明记录为 iOS 27.0 Simulator（24A434）中的 HeadphoneConfigs / CoreGlyphsPrivate 图标。本次整合直接复用 PR 文件，没有在本机重新提取模拟器资源。这些 Apple 素材保留独立版权，不属于项目 GPL 代码授权。

| 文件 | SHA-256 |
| --- | --- |
| listening_off.png | `9e0134932b97c94d2513559833a88dbc1dd56590cc63afa9910354b25c1aaf17` |
| listening_transparency.png | `bfd7056034d5a69fd7fa4ad71575c3f390b083d1d7fc474766e60b5b04dcd0c7` |
| listening_anc.png | `29e3ef950298e0599d217b57d164b1452aaa3cc271ee076f815ce384c2e3d7c0` |
| listening_adaptive.png | `8286ad51be85a13c1799ab7ac6ce08378a39ec964ee2185025b43682fab4e1ed` |

iOS 界面的 SF Pro 字体按用户提供的 SFWindows v27.0.1789118100 获取，下载来源为该固定标签下的 SF Pro 常规体与斜体。字体属于 Apple 的独立专有授权；SFWindows 仓库的 MIT 声明不覆盖字体二进制。字体存放于本机忽略目录，仅通过构建任务进入生成的 APK assets，不写入 Git 或源码 ZIP。构建输入摘要与无字体回退方法见 `docs/UI-STYLES.md`。
