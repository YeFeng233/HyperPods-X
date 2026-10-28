# 蓝牙设备卡片的设置入口

HyperPods-X 在系统蓝牙列表中接管已绑定 AirPods 的独立设备设置箭头，点击后启动 `com.hyper.podsrx.DeviceSettingsActivity`。耳机断开时也能查看模块设置；绑定地址保存在既有 `device_state` 远程偏好中，不根据设备名称推断身份。

当前 HyperOS 设置 APK 的设备行使用 `BluetoothDevicePreference`，小米样式的 `MiuiBluetoothDevicePreference` 继承其 `onClick(View)`。右侧 `preference_detail` 视图使用这个回调，随后才交给设备原有的设置监听器。模块在回调中同时检查视图 ID、模块启用状态和 `CachedBluetoothDevice.getDevice().getAddress()`，仅在与绑定地址相符时启动模块页面并结束本次回调。

设备行的连接操作不在这个回调的接管范围内。其他设备、未绑定状态、入口解析失败或启动异常时均执行原来的回调。Xiaomi 开放式耳机 Pro 的原生插件入口保持系统行为。

这里只改变列表箭头的去向，没有在设备详情生命周期中强制重定向。模块内“音频与设备权限”仍使用系统设备详情 Intent，方便管理 AAC、媒体/通话和权限；详情页内原有的“HyperPods-X 高级设置”条目继续保留。模块 Activity 自身也会核对传入的绑定地址。

该功能需要在 LSPosed 中启用“系统设置”作用域；更新后重新加载设置进程或重启手机。其他系统版本需重新核对设备行与箭头接口。
