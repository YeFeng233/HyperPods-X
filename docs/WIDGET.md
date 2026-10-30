# 桌面电量卡片

## 修改内容

新增两张 1x3 桌面卡片，数据由模块自己的状态中心提供：**耳机电量**（图标 + 百分比 + 左/右/盒标签）与**耳机电量·环**（图标置于圆环内，绿弧占比为剩余电量，无文字）。手机管家自带的桌面组件未做改动，其摄影手柄图标的替换不在模块能力范围内。

## 技术路线

卡片刷新时向 `com.xiaomi.bluetooth` 定向发 `HUB_SNAPSHOT`，状态中心（`HubHost`）以 `HUB_CHANGED` + `state` extra 回包；接收端校验 `getSentFromUid() == BLUETOOTH_UID` 后落盘并重绘。内容变化由 Hub 主动推送，且仅在 `device_state.widget_installed` 为真时发送，没有卡片时不唤醒应用进程。环形卡片受 RemoteViews 限制（不支持自定义 View），每格用 `Canvas` 画成位图推送，尺寸取自 `getAppWidgetOptions` 的实际高度。

## 已实现

- 两张卡片、深浅色配色、充电标记、点击进入应用、无组件时停止推送；
- 单测覆盖快照解析（含越界与缺读数）与弧长换算。

## 未实现

- 替换系统手机管家卡片里的摄影手柄图标：图标由 `com.miui.home` 在 Rust 渲染路径内绘制，`WidgetProvider.query` 也无图标列，Java 层没有拦截点；
- 卡片内直接操作播放或降噪，目前点击仅进入应用。
