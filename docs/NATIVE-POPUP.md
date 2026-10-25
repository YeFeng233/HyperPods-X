# HyperOS 4 原生 AirPods 界面适配

目标为 DEVICE-BASELINE.md 中的单台真机，日期 2026-10-22。

## 复用的系统实现

- `com.xiaomi.bluetooth`，版本 17 / code 37。
- 动态快连插件 `preloadedFeature`，已安装路径版本 `1.14@1`；本次分析 APK 的 SHA-256 为 `90EC6AF9792D82350E44E1E01C05B6B74C6C1429765AA15D947C6200661A315A`。
- 底部弹窗：`MiuiFastConnectActivity`，进程 `com.xiaomi.bluetooth:ui`，由 `MiuiAirPodsFastConnectController` 与系统 `fc_airpods_dialog` 布局显示。
- 超级岛：使用系统 AirPods 适配器相同的 `StatusBarManager.setStatus(1, "strong_toast_action", bundle)` 入口，经 SystemUI 的 `DeviceNotificationListenerImpl` / `DeviceNotificationHandlerImpl` 渲染。

真实开盖事件仍由本 App 进行 IRK 校验、解密、按采集时间排序与 900ms 稳定确认。
本次没有将设备身份伪装成 AirPods Pro 3，也没有依赖名称匹配附近耳机。

## 请求与数据

App 通过只允许本应用签名发送的有序广播请求“小米蓝牙”宿主，携带有效期 1 秒的时间戳和本应用创建的不可变 PendingIntent。
在桌面选择系统底部弹窗，在其他应用中选择系统超级岛；两者均可单独预览。

底部弹窗保留系统窗口、布局及关闭行为，适配已绑定设备的真实电量和产品图片。
只有带有效 App 标识且设备地址匹配远程偏好的弹窗，才会使用适配数据；其他原生 AirPods 界面继续走系统实现。
不向系统提供 IRK/ENC 密钥，不修改系统蓝牙库，不触发第二套配对或 AACP 控制连接。
未确认的电量显示未知值，避免系统原布局将音频断开时的电量写成 0%。

超级岛的左右区域空间有限，双耳电量按“左/右%”显示，另一区域显示充电盒电量。
产品图片由系统按本 App 的 drawable 名称读取；两张资源明确保留，避免优化构建删除仅由外部宿主使用的图片。

界面源码与读取的原生实现均保存在本机，系统 APK、反编译文件和截图不作为源码发布。
图片来源及权利说明见 ARTWORK-SOURCES.md。

## 当前验证

已在实际设备截图中看到原生底部弹窗及超级岛，确认 App 首页图片显示；预览标题明确标记，未知电量显示“—”。

桌面真实开合盖回归已通过：用户针对“合盖误弹、开盖原生底部弹窗、左右耳和盒体电量”回复“无误弹，显示正常”；日志同时记录经过身份验证的开盖稳定事件和系统弹窗启动。
App 前台真实开盖回归通过：用户回复“有正常显示”，日志在 06:51:54 与 06:52:16 记录真实开盖稳定后提交 SystemUI。

超级岛点击实测没有跳转。此 ROM 的 `island_param` 路径将图片、文字和时长传给岛组件，没有转发请求中的 `target`；当前将超级岛作为电量提示，高级设置仍使用 App 或系统蓝牙详情的入口。此项未标记为点击功能通过。
完整结果记入 TEST-REPORT.md。
