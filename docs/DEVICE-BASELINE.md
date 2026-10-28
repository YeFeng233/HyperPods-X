# 目标真机基线

记录日期：2026-10-22，Asia/Hong_Kong。通过用户提供的无线 ADB 地址连接。

| 项目 | 结果 |
| --- | --- |
| 设备 | Xiaomi 17 Pro Max，`2509FPN0BC`，`popsicle` |
| 系统 | Android 17，API 37，HyperOS `OS4.0.4.0.XPBCNXM` |
| 构建指纹 | `Xiaomi/popsicle/popsicle:17/CP2A.260605.016/OS4.0.4.0.XPBCNXM:user/release-keys` |
| ABI | `arm64-v8a` |
| Root | KernelSU，`su -c id` 返回 uid 0 |
| 框架安装版本 | LSPosed `2.2.1 (7930)`；模块加载日志与 App 服务确认运行 API **102** |
| 设置 | `/system_ext/priv-app/Settings/Settings.apk`，版本 17 / code 37 |
| 小米蓝牙扩展 | `/system_ext/app/BluetoothExtension/BluetoothExtension.apk`，版本 17 / code 37 |
| 蓝牙进程 | `com.android.bluetooth`，位于 `com.android.bt` APEX，版本 17 / code 37 |
| 耳机 | AACP 型号 **A3441**，AirPods 5 无线充电盒版本 |
| 设备报告版本 | `90.3431000025000000.7362`；保留原始设备版本字符串，未将其当作已确认的 Apple 市场固件版本 |

提取的系统 APK 仅保存在忽略目录 `.device/`，不作为源码发布。

- Settings.apk SHA-256: `2AD1F138457C5F60B045CF41E18BABC7E8BCC36C123526F7DDC6CA15B906D655`
- BluetoothExtension.apk SHA-256: `7FFE6FB2EFF8FF37817A2CADE86B1A7ADAD99941A628CAC3C1E9FB3D9532FD40`
- 原生快连插件 `preloadedFeature`：`1.14@1`，SHA-256 `90EC6AF9792D82350E44E1E01C05B6B74C6C1429765AA15D947C6200661A315A`。

实际设备详情由 `com.android.settings.bluetooth.DeviceProfilesSettings` 承载，地址字段为
`mDeviceMacAddress`。已在 `onResume()` 后插入可重复更新的高级设置入口，验证点击可进入
`DeviceSettingsActivity`，并显示左右耳及盒体电量摘要。AOSP 详情页作为同构建内的补充适配。
模块仅对上述系统指纹和已绑定的设备地址注入。0.2.0 静态作用域为 `com.android.settings` 与 `com.xiaomi.bluetooth`，后者用于原生 AirPods 快连弹窗和超级岛，不包含 `com.android.bluetooth` 或 SystemUI。原生适配细节见 [NATIVE-POPUP.md](NATIVE-POPUP.md)。

0.3.0 沿用上述作用域，在小米蓝牙宿主内调用现有 `/system_ext/lib64/libspatial.so`，不分发该库，完成固定空间音频与桥接自检。0.3.1 进一步发现耳机实际运动服务为 service 15 `devmotion6`，取得约 50 Hz 真实四元数并完成三轴校准；系统动态追踪试听与摘下双耳自动清理通过。详细证据与未验收边界见 [SPATIAL-AUDIO-TEST.md](SPATIAL-AUDIO-TEST.md)。

在当前真机环境中可直接建立 BR/EDR L2CAP `PSM 0x1001` 控制通道；本项目未修改原生蓝牙库或全局 VendorID。
取得 IRK/ENC 身份材料，存储于 Android Keystore 加密的应用私有偏好；不记录密钥值。

BLE 扫描需附近设备及完整定位权限，不能设置 `neverForLocation`。移除该声明后能收到广播。
初期扫描参数下，多轮开盖与长合盖测试、30 秒及 2 分钟全厂商扫描未出现目标耳机的可验证邻近广播。
后续采用传统 BLE / 1M PHY / 500ms 批处理，并暂缓主动控制连接的隔离诊断，
成功收到 **type 0x07、长度 27** 的目标广播，IRK 身份验证及 ENC 电量解密通过，
触发“充电盒已打开”卡片。此时左耳 100%、右耳 99%、充电盒 81%。
该组合实验没有独立证明是哪一个因素造成初期缺失；后续常规服务与桌面开盖回归通过，详见 [TEST-REPORT.md](TEST-REPORT.md)。
2026-10-28 查明：此 ROM 的蓝牙栈会把 Apple 广播从普通实时扫描中截出，经 `onAppleScanResult` 直接交给小米蓝牙，所以第三方实时扫描收不到；批量扫描绕开这一步，因此只有批处理有效。现在由模块在小米蓝牙中转发这些广播，见 [NATIVE-POPUP.md](NATIVE-POPUP.md)。
连接电量卡片以“耳机已连接”为标题，保留独立开关，与真实盒盖事件区分；控制通道报告电量且本次会话的 A2DP 已连上后才显示。

部分设备设置写入后不主动回传。EQ 与连按速度通过重新建立控制通道读出了改变后的值，
所以确认流程需要回读，不能只等待主动通知。全部测试均要求恢复用户原值。

安装 HyperPods-X 前的系统蓝牙日志中已存在该 AirPods 的 HFP 连接/断开循环；
当前 A2DP 活跃、HFP 未连接。通话测试需保留这一基线，避免把已有行为误归因于模块。
