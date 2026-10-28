# HyperPods-X 蓝牙宿主状态中心迁移计划

版本：v1.0 · 日期：2026-10-29 · 分支：feat/popup-logic-rebase · 状态：已确认，待实施

目标设备：2509FPN0BC / HyperOS（Android 17 / API 37）/ AirPods，libxposed API 102。

本计划合并了两部分内容：

- **原迁移计划**：把 BLE 扫描和广播解析从 app 搬进 `com.xiaomi.bluetooth` 主进程。
- **架构优化第 1–4 点**：
  1. 让主进程成为状态中心，远程偏好只放配置；
  2. app 和宿主之间改用统一的 binder 通道；
  3. 拆分 PodsService；
  4. 一批小项：诊断防抖、去掉定时器、合并两套 UI 状态、新建公共契约模块、兼容性上报。

工作区里有上一轮留下的未提交改动，在它们的基础上继续，不要回滚。具体改动见文末"附录 A"。

---

## 0. 背景与已核实事实

### 为什么要迁移（真机已核实）

- **app 的扫描会被系统静默拒绝。** 同一 app 在 30 秒内第 6 次 `startScan` 会被丢弃：logcat 里能看到 `BtScan.ScanController: ... com.hyper.podsrx is scanning too frequently` 和 `onScannerRegistered(status=6) Failed`，但 `onScanFailed` 不会被调用。亮熄屏几次以后，app 就一路扫描都没有了，电量和小组件随之停止更新。
- **app 有被冻结或被杀的风险。** 退出界面时，HyperOS GreezeManager 会执行 `triggerQuickFreezeMode reason=quit`。目前靠前台服务挡住了，但 app 仍然可能被杀。
- **主进程不受这两个限制。** `com.xiaomi.bluetooth` 以 `android.uid.bluetooth`（1002）身份运行，是 PRIVILEGED 系统应用，常驻进程（PERSISTENT_PROC_ADJ），从没被冻结过。模块已经注入在它的主进程里（`HyperPodsModule.installBluetooth`）。

### 2026-10-29 新核实的事实

**1. Apple 广播的真实来路。** 依据反编译 `C:\hpx-re\out\bt\sources\com\android\bluetooth\le_scan\ScanController.java:381-405`：

- 协议栈进程 `com.android.bluetooth` 的 native 层回调依次经过 `ScanNativeCallback.onAppleScanResult` → `ScanController.onAppleScanResultInternal`。
- 然后 `ContentResolver.call("content://com.android.bluetooth.ble.app.headsetdata.provider/AirpodsModel", "onAppleScanResult")`，也就是我们 hook 的 `MiuiBluetoothContentProvider`。
- 换句话说，**协议栈分流的是"正在运行的扫描"听到的 Apple 广播**。

**2. 当前快照里 ROM 自己没有 Apple 扫描。** 依据 `dumpsys bluetooth_manager`：

- uid 1002 名下只有一路扫描，只过滤 `fcfb`、`c62857aa` 两个 UUID，模式是 LOW_POWER，带 Forced 标记。
- 带 Apple 过滤条件（ManufacturerId=76）的扫描只有两家：我们 app 的 `07 19` 和 `07 11` 扫描，以及 GMS personalsafety 的 `76/[18]`。
- 结论：LidRelayHost 截到的 Apple 广播，主要是我们 app 的扫描触发的。**"ROM 会替我们扫描"这个前提不成立**，熄屏策略要在阶段 0 重新取证（见 0.3）。

**3. headsetdata provider 的调用方。**

- 这个 provider（authority 是 `com.android.bluetooth.ble.app.headsetdata.provider`）已经被 milink（u0_a182，普通应用 uid）通过 `airpodsRepository` 正常调用，说明它是导出的、外部可以调用。
- `com.miui.securitycenter.remote` 和 `com.android.settings` 都以 system（1000）身份运行。

**4. 远程偏好的读写限制。** 远程偏好只有 app 能通过 XposedService 写入，被注入的宿主只能读。所以宿主之间共享实时状态，不能再靠偏好。

### 0.1 目标分工

| 位置 | 负责 |
| --- | --- |
| `com.xiaomi.bluetooth` 主进程（**Hub**） | BLE 扫描；`07 19` / `07 11` 解析；开盖判断（LidSession）；电量合并、过期、远离降频；驱动原生弹窗、超级岛、小组件；保存密钥；作为唯一的实时状态源，供 app 和其他宿主读取 |
| app | 界面；AACP 控制通道（L2CAP）；空间音频、对话感知、入耳检测这些依赖 AACP 的功能；兼容卡片和通知兜底（app 活着时才有） |
| 其他宿主（:ui、milink、安全中心、设置） | 只做展示和适配，状态一律向 Hub 拉取 |
| LSPosed 远程偏好 | 只放 app 写的**配置**：address、各项开关、name；不再放实时状态 |

**目标效果：** app 被杀或冻结时，开盖弹窗、超级岛、小组件、电量、充电状态、原生面板的电量都照样更新。只有降噪等控制功能依赖 app 存活。

### 0.2 必须保留的行为

- **实时扫描（`07 19`）**：用 LOW_LATENCY、reportDelay 0，前两个字节做硬件过滤。不要用批量扫描（第三方批量扫描会延迟 5–20 秒）。LOW_POWER 会漏掉短暂的开盖广播，只能在耳机不在身边时用。
- **盒子扫描（`07 11`）**：用 BALANCED，过滤条件覆盖整个 payload 长度（`CaseStatusCodec.LENGTH + 2`）。
- **降频规则**：3 分钟没听到自己的耳机、并且控制通道没连着，实时扫描降为 LOW_POWER；一听到自己的广播、ACL 连上或控制通道连上，就恢复高频。
- **电量合并语义**（现在写在 `DeviceRepository.batteries` / `expireBatteries` 里）：
  - 读数分两种来源：控制通道（control=true）和广播。
  - 控制通道报告某个部件断开时，只删除它自己报过的那项读数。
  - 30 秒内的精确读数优先于粗略广播，但充电状态要立刻采用广播里的新值。
  - 广播来的读数 60 秒没刷新就删除。控制通道的读数在通道连着时不过期，断开后从断开时刻开始算 60 秒。
  - 只是重复收到同样的数值时，只刷新时间，不发布（用去掉时间后的内容比较）。
  - 盒子状态的去重缓存最长 20 秒就要强制刷新一次（`CASE_REFRESH_MS`）。
- **开盖逻辑**：
  - `LidSession(cooldownMs=0, settleMs=300)`。
  - 亮屏后 1.5 秒内听到的第一个状态只当作基线，不弹窗。
  - 关盖时立刻关闭原生弹窗，并在 700ms 和 1500ms 各补发一次关闭。
  - 开盖稳定后，如果控制通道没连着，就通知 app 重连。
- **NativePopupHost 的现有判断**：在桌面时弹原生对话框，否则走超级岛；ROM 自己的对话框已经在显示时不叠加第二个；不是预览时让 ROM 重连 A2DP/HFP。
- **稳定性**：代码跑在常驻的系统进程里。每个入口都要捕获 `Throwable`，不能拖垮宿主。在 `ReloadLifecycle` 里注册清理逻辑，热重载时要停止扫描、注销接收器和 binder、清空 handler。入口处检查 `ReloadLifecycle.isActive()`。
- **复用解码器**：core:protocol 的 `ProximityCodec`、`CaseStatusCodec`、`AppleAdvertisement`、`LidSession` 直接复用，不要复制一份。

> **原约束"不要破坏 `updated`、`panel_heartbeat`、native popup `fresh` 这些新鲜度依赖"，被本计划第 1 点有意取代。** 规则是：每个消费方切换到 Hub 之后，才能删掉它对这些键的依赖；切换之前，app 照常写这些键。

### 0.3 熄屏策略（2026-10-29 已定：方案 B）

**阶段 0 取证结论**（依据反编译 `com.android.bluetooth` 的 `le_scan` 包、本机 dumpsys 和 device_config）：

1. **熄屏时系统会挂起普通 app 的扫描。** `ScanManager.handleScreenOff()` / `checkScreenOffScans()` 会对所有常规扫描执行 `handleStopScan` 并放进 `mSuspendedScanClients`，只豁免 appId 1002、`android` 和 CTS 包。dumpsys 里所有扫描方的 `Number of results (ScreenOff/...)` 都是 0。也就是说，现在 app 熄屏时本来就收不到广播。
2. **ROM 自己不做 Apple 扫描。** uid 1002 名下只有 `fcfb` / `c62857aa` 两路 UUID 过滤，没有 ManufacturerId=76。亮屏后的 Apple 扫描必须由 Hub 自己发起。
3. **uid 1002 不受限频。** `ScanController.registerAndStartScan` 只在调用方没有 `BLUETOOTH_PRIVILEGED` 时才检查 `isScanningTooFrequently`（5 次 / 30 秒）。`com.xiaomi.bluetooth` 已获授该权限。
4. **过滤扫描跑满 5 分钟会被强制降级。** 本机 `device_config` 的 `scan_timeout_millis=300000`。超时后 `handleRegularScanTimeout` 会把过滤扫描降到 LOW_POWER，并打上 `isTimeout` 标记（dumpsys 里显示为 `(Forced)`）。uid 1002 同样适用，只豁免 opportunistic、FIRST_MATCH 和 CTS。重新 `startScan` 会生成新的扫描记录，降级随之清除。

**策略（用户确认）：熄屏时不绕过系统限制，只要求亮屏后更新正常。**

- 熄屏时 Hub 停掉两路扫描，过期计时暂停，10 秒定时器停止。
- 亮屏后立即重启两路扫描，恢复过期计时和定时器。亮屏后 1.5 秒内听到的第一个状态照旧只当基线。
- 熄屏期间电量冻结在最后的值。熄屏时小组件和弹窗都看不到，所以不影响体验。
- 不利用 uid 1002 熄屏免挂起的特权在熄屏期间继续扫描。

---

## 1. 目标架构

```
AirPods ──BLE──► com.android.bluetooth ──onAppleScanResult──► ┐
   │                    ▲ 扫描登记（uid 1002，Hub 发起）       │
   │                    └──────────────────────────────────────┤
   │                                                    ┌──────▼─────────────────────────────┐
   │                                                    │ com.xiaomi.bluetooth 主进程 · Hub   │
   │                                                    │  ScanEngine → AdvertisementParser   │
   │                                                    │  BatteryLedger · LidController      │
   │                                                    │  HubState（唯一实时状态）            │
   │                                                    │  KeyVault（DE 存储 + Keystore）     │
   │                                                    │  PopupDriver · WidgetDriver         │
   │                                                    │  NativeControlHost / Spatial / Conv │
   │                                                    └──┬───────────┬──────────────┬──────┘
   │                        binder（HubWire，双向）        │           │ provider.call │ HUB_CHANGED 广播
   │                                                       │           │ hyperpods_state
   ▼                                                       ▼           ▼              ▼
 app（L2CAP AACP）◄────────────────────────────────── HubClient   :ui 弹窗 · milink 面板 · 安全中心小组件 · 设置摘要
   └─ FrameworkBridge → 远程偏好（仅配置）
```

### 1.1 三条通道

1. **app ⇄ Hub：binder，按 HubWire 协议**
   - **握手**：沿用 `SpatialWire` 的做法。app 发有序广播 `HUB_CONNECT`（signature 权限 `NATIVE_POPUP`，`created` 1 秒内有效），Hub 在 result extras 里返回自己的 binder。
   - **Hub 侧**：每次 transact 都校验 `Binder.getCallingUid() == appUid`（appUid 用 PackageManager 解析得到，和 SpatialHost 一样）。
   - **app 侧**：调用 `HELLO` 时把自己的回调 binder 传过去。Hub 对它 `linkToDeath`，这就是判断 app 是否存活的依据，**不再需要心跳**。回调 binder 校验 `Binder.getCallingUid() == Process.BLUETOOTH_UID`。
   - **版本**：`DESCRIPTOR = "com.hyper.podsrx.Hub.v1"`。版本不一致时返回错误码，app 提示"请重载宿主"（HostReloadCoordinator 已经会在包更新时触发热重载）。
   - **重连**：app 对 Hub binder 也 `linkToDeath`，宿主重启或热重载后按 1、2、4…30 秒退避重连。Hub 安装完成时发一次 `HUB_READY`（`setPackage(app)`，带 shareIdentity），活着的 app 收到后马上重连。
2. **其他宿主 → Hub：拉取状态**
   - 在已经 hook 的 `MiuiBluetoothContentProvider.call` 里加一个方法 `hyperpods_state`，返回状态快照的 Bundle。
   - 调用方 uid 只接受 1000（system）、1002（bluetooth）和 `com.milink.service` 的 uid（启动时解析）。
   - 它运行在 binder 线程上，只读一个 `volatile` 的不可变快照，不能阻塞。
3. **Hub → 其他宿主：变化通知**
   - 状态内容变化时（不含时间戳），分别向 `com.xiaomi.bluetooth`（:ui）、`com.milink.service`、`com.miui.securitycenter`、`com.android.settings` 发广播 `HUB_CHANGED`。
   - 每个包一次，`setPackage`，带 shareIdentity 和 `FLAG_RECEIVER_FOREGROUND`，**不带数据**。
   - 接收方校验 `getSentFromUid() == 1002`，然后通过通道 2 拉取。
   - 同一类变化 250ms 内合并成一次。

### 1.2 HubWire 协议（放在 `:core:wire`，详见第 4 节）

**app → Hub：**

| code | 名称 | 载荷 |
| --- | --- | --- |
| 1 | `HELLO` | 版本、回调 binder、address、弹窗凭证 PendingIntent、原生控制回调 PendingIntent |
| 2 | `PUSH_CONTROL` | 控制状态快照，见下 |
| 3 | `PUSH_KEYS` | address、IRK（类型 1）、ENC（类型 4） |
| 4 | `REQUEST_POPUP` | `preview`、`island`（预览按钮用） |
| 5 | `GET_STATE` | 返回 HubState 和诊断计数 |
| 6 | `GET_DIAGNOSTICS` | 返回各宿主的兼容性上报（见 7.5）和扫描状态 |

`PUSH_CONTROL` 的内容：connected、model、name、firmware、控制通道电量（Battery 列表，带 connected 标志）、controls 里面板要用的项（0x0d / 0x0a / 0x1b / 0x34）、pending 0x0d、`controlRefreshUntil`、spatial 的 route / mode / modeChanging / bridge / capable、wearing。它取代偏好里全部 `panel_*` 和 `native_connected`。

**Hub → app（回调，oneway）：**

| code | 名称 | 载荷 |
| --- | --- | --- |
| 101 | `ON_STATE` | 合并后的电量表、lidOpen、product、诊断计数（broadcasts / trusted / candidates / matches）、扫描模式 |
| 102 | `ON_LID` | `opened` / `confirmed` / `closed`，以及原生弹窗是否成功 |
| 103 | `ON_POPUP_RESULT` | 对应 `REQUEST_POPUP` 的结果 |

载荷统一用 Bundle，编解码集中放在 `:core:wire` 的 `HubBundles`，app 和 Hub 共用这一份代码。

---

## 2. 分步实施

每一步只改代码。**编译、单测、lint 都要先问用户。** 一共设三个检查点（A / B / C），每个检查点按第 8 节的命令一次跑完单测、lint 和出包。

### 阶段 0：现场取证（已完成，2026-10-29）

结论见 0.3。源码和 dumpsys 已足以回答原定问题，用户确认不再做开盖实测。

### 阶段 1：公共模块与纯逻辑（可单测，不改变行为）

**1.1 新建 `:core:wire`**（Android library，Java，namespace `com.hyper.podsrx.wire`）

- 把这些文件从 `:hook` 移进来：`LidWire`、`WidgetWire`（之后会删）、`NativeControlWire`、`SpatialWire`、`ConversationWire`、`HeadsetModels`。包名保持 `com.hyper.podsrx.hook`，这样不用改调用方的 import，混淆规则 `-keep class com.hyper.podsrx.hook.**` 也继续生效。
- 新增 `HubWire`（action、descriptor、transaction code、Bundle 键名）和 `HubBundles`（编解码）。
- 依赖关系：`:app` 依赖 `:core:wire`、`:hook`、`:core:protocol`；`:hook` 依赖 `:core:wire`、`:core:protocol`。
- 在 `settings.gradle.kts` 里 include `:core:wire`。

**1.2 `:hook` 依赖 `:core:protocol`**

- 加 `implementation(project(":core:protocol"))`。
- Kotlin stdlib 已经在 app APK 的 dex 里，模块的类加载器能拿到。

**1.3 core:protocol 新增纯逻辑**，所有时间都由调用方传入，便于测试：

- `BatteryLedger`：0.2 里的全部电量合并语义。API 大致是：
  - `apply(values, precise, control, now): Boolean`，返回内容是否变化；
  - `expire(maxAgeMs, controlFreshAt, now): Boolean`；
  - `content()`；
  - `snapshot(): Map<Int, Reading>`。
  - `Reading` 从 app 移到这里。
- `CaseThrottle`：盒子状态去重，最长 20 秒强制刷新一次。
- `PresenceTracker`：记录上次听到耳机的时间，判断 3 分钟远离和降频，提供 `heard(now)`、`shouldIdle(now, controlConnected)`。
- `ControlCommands`（第 3 点）：从 PodsService 抽出的命令状态机，见阶段 3。

**1.4 单测**

- `BatteryLedgerTest`：断开时只删自己报的那项；精确读数优先但充电状态立刻生效；广播 60 秒过期；控制通道连着时不过期、断开后从断开时刻算；重复数值时内容不变。
- `CaseThrottleTest`、`PresenceTrackerTest`。
- `HubBundles` 往返编解码测试，放在 `:core:wire` 的 test 下；如果需要 Robolectric，就改放到 `:app` 的 test 下。

**1.5 app 改用 `BatteryLedger`**

- `DeviceRepository.batteries` / `expireBatteries` 改为委托给 `BatteryLedger`，行为不变。
- 这一步是为了验证 ledger 和旧逻辑等价，并且让 app 和 Hub 共用同一套语义。

### 阶段 2：Hub 骨架与 binder 通道（宿主侧，先不接管扫描）

**2.1 `HubHost.java`**（主进程，在 `installBluetooth` 里安装，排在 `LidRelayHost` 之前）

- `HubState`：不可变快照，用 `volatile` 引用保存，包含：电量、lidOpen、product、控制状态、配置、诊断计数、app 是否存活、扫描模式。
- binder 实现（`extends Binder`，`attachInterface(null, HubWire.DESCRIPTOR)`），`onTransact` 整体包一层 try/catch。
- 用有序广播接收 `HUB_CONNECT`，返回 binder。
- 用 `linkToDeath` 跟踪 app 是否存活；app 死亡时，把 `controlLostAt` 设成当前时间，控制通道状态标为断开。
- 在 provider 的 hook 里加 `hyperpods_state` 方法，按 1.1 的规则校验调用方 uid。
- `HubNotifier`：负责发 `HUB_CHANGED`，带合并窗口。
- 在 `ReloadLifecycle.add` 里注销接收器、断开死亡监听、清空 handler。用 `ReloadLifecycle.rejectWhile` 拦住"弹窗正在显示"这种不宜重载的瞬间（可选）。

**2.2 `KeyVault.java`**

- 存在 `host.createDeviceProtectedStorageContext().getSharedPreferences("hyperpods_hub", MODE_PRIVATE)`。
- 用 AndroidKeyStore（uid 1002 下的别名 `hyperpods_hub_keys`）做 AES-GCM 包装，格式和 app 的 `SecureKeys` 一致。
- 换绑地址时清掉旧地址的密钥。
- **不要**写进 LSPosed 远程偏好：其他被注入的进程也能读到那里。

**2.3 app 侧 `HubClient`**（`device/HubClient.kt`）

- 负责握手、`HELLO`、重连退避和 `HUB_READY` 接收器。
- 把 `DeviceRepository.state` 中和控制有关的字段做 `distinctUntilChanged`，变化时调用 `PUSH_CONTROL`。**事件驱动，没有 10 秒循环。**
- 拿到 Keys 事件、服务启动、绑定新耳机时调用 `PUSH_KEYS`。
- 回调 binder 收到 `ON_STATE` / `ON_LID` 后转交 `DeviceRepository` 和 `PodsService`。

这一阶段结束时，Hub 能收到控制状态和密钥，但还没有扫描。app 的旧路径保持不变，便于对照。

### 阶段 3：拆分 PodsService（第 3 点，纯重构）

- `ControlCommands`（放在 core:protocol，纯逻辑）接管这些：
  - `desired`、command 的 token；
  - `enqueue` 的准入规则：0x2e 要求处于自适应模式、0x0d 和 0x2e 互斥、EQ 的取值校验、0x24 的规则；
  - `complete`、回读匹配（`event.value.take(expected.values.size) == expected.values`）；
  - 0x0d 切出自适应模式时撤销 0x2e；
  - `readingBack` / `readbackStartedInSession` 的会话判定。
  - 输出用"动作"表示（发送 / 确认 / 超时 / 安排回读），由调用方执行。
- `ControlSession.kt`（app）负责传输层：connectLoop、静默握手看门狗、重连退避、`spatial.packet` 分流、`onEvent` 分发。它持有 socket 和命令通道。
- `PodsService` 只剩生命周期、通知、连接卡片、各个 Controller 的接线，以及 `HubClient`。
- 补 `ControlCommandsTest`：覆盖准入、匹配、超时、回读会话、0x0d 撤销 0x2e。
- **检查点 A**：征得用户同意后，跑单测、lint 并出包。上机做回归：降噪、自适应、EQ、写后回读、断线重连都和原来一样。这时 Hub 已经能收到状态，可以用 `GET_STATE` 在诊断页核对。

### 阶段 4：Hub 接管扫描、解析与开盖（切换的核心）

**4.1 `ScanEngine.java`**（Hub，只在主线程运行）

- 两路扫描：实时扫描（`07 19`，LOW_LATENCY / LOW_POWER）和盒子扫描（`07 11`，BALANCED），参数和现在 PodsService 里的完全一致。
- 降频由 `PresenceTracker` 驱动，控制通道是否连着来自 `PUSH_CONTROL` 加上 app 是否存活。
- ACL 连接广播由 Hub 自己注册监听，过滤绑定的地址后调用 `heard()`。
- 每 10 秒跑一次过期和降频判断；熄屏时按 0.3 选定的方案处理。
- 屏幕开关、蓝牙开关的监听由 Hub 自己注册。
- **重启扫描要有节流：** 同一路扫描两次启动至少间隔 2 秒，并且记录启动次数。阶段 0 已确认 uid 1002 不受限频，不需要预算制。
- **避开 5 分钟强制降级：** 亮屏期间，实时扫描每 4.5 分钟主动 stop/start 一次。盒子扫描本来就是 BALANCED，降到 LOW_POWER 影响较小，也一并刷新。刷新和降频切换共用同一个重启入口。
- 所有 ScanCallback 都包 try/catch，`onScanFailed` 要写日志，并在 5 秒后重试一次。

**4.2 `AdvertisementParser.java`**

- 先做身份匹配：地址相同，或者 `ProximityCodec.matchesIdentity` 用 IRK 匹配上。
- 然后 `AppleAdvertisement.proximityMessages`，再分别用 `ProximityCodec.decode` / `CaseStatusCodec.decode` 解码（加密密钥来自 KeyVault）。
- 维护诊断计数。

**4.3 `LidRelayHost` 改成进程内交接**

- 把 ScanResult 直接交给 `AdvertisementParser`，不再发 `APPLE_ADVERTISEMENT` 广播。
- 1 秒去重保留。`bound()` 的判断改为"Hub 有绑定地址，并且 KeyVault 里有 IRK"。

**4.4 `LidController.java`**

- 照搬 `PodsService.advertisement()` 里的开盖逻辑：`LidSession(0, 300)`、亮屏基线、`confirmLid`、关盖时立刻关闭弹窗并在 700 / 1500ms 补发、开盖稳定后通过 `ON_LID(confirmed)` 通知 app 重连控制通道。

**4.5 `PopupDriver.java`**

- 把 `NativePopupHost` 的展示部分抽成可以直接调用的 `show(request)`。广播入口先保留，供过渡期使用，阶段 6 删除。
- 弹窗凭证用 app 在 `HELLO` 时交过来的长期 PendingIntent（`FLAG_IMMUTABLE`）。PendingIntent 由系统持有，app 进程死后仍然有效。
- 在 Hub 内部判断什么时候弹：开盖且已确认、`popup_enabled`、`native_popup` 打开、不是亮屏基线、没有锁屏。
- 锁屏或原生弹窗失败时，通过 `ON_LID(opened, nativeShown=false)` 交给 app 兜底，app 用 `PopupController` 显示兼容卡片或通知。app 不在时就不兜底。
- 关闭：在进程内发 `CLOSE_POPUP`（`setPackage("com.xiaomi.bluetooth")`）。
- 是否接管 ROM 自己弹窗（`replace_system_popup`）由 Hub 判断：已绑定、并且 `popup_enabled && native_popup`。**不再要求 app 正在运行。**

**4.6 `WidgetDriver.java`**

- 把 `WidgetRelayHost` 的发送逻辑合并进来：grip 广播、延迟 600ms 发免提电量广播、60 秒重发。数据来自 HubState，不再依赖 `widget_*` 偏好。

**4.7 `NativeControlHost`**

- `matches()` / `state()` 改为读 HubState，不再读 `panel_*` 偏好和 `panel_heartbeat`。
- 控制命令的回调 PendingIntent 改为 `HELLO` 时提供的那个。
- `REGISTER_NATIVE_CONTROLS` 接收器到阶段 6 再删。

### 阶段 5：其他宿主改为向 Hub 拉取

每个进程新增一个小工具类 `HubReader.java`：

- 发起 `ContentResolver.call(headsetdata, "hyperpods_state")`；
- 注册 `HUB_CHANGED` 接收器，校验 `getSentFromUid() == 1002`；
- 把读到的快照缓存在本进程，避免同一帧里重复跨进程调用。

| 宿主 | 改动 |
| --- | --- |
| `:ui`（`NativeAirPodsDialogAdapter`） | 填充弹窗时优先使用 Intent extras，因为 Hub 启动弹窗时已经写进去了。ROM 自己启动的弹窗（adopt）从 HubReader 取值，取代 `fresh(15s)` 和 `native_*` 偏好。`replacing()` 改用快照里的 `replaceSystemPopup`。`followLid` 不变。 |
| milink（三个 Adapter） | `fresh` / `freshTarget` / 心跳判断改为"快照里 `controlAlive && connected`"。面板刷新从监听偏好改为 `HUB_CHANGED`。 |
| 安全中心 `WidgetBatteryHost` | 在 query hook 里用 HubReader 的缓存值替换 `widget_headset`。缓存为空时同步拉取一次。 |
| 设置 `HyperPodsModule.refreshSummary` | 摘要改从 HubReader 取。 |

### 阶段 6：app 侧删减与收尾

**PodsService 删除：**

- 方法：`startScan`、`startLiveScan`、`setLiveIdle`、`reserveScanStarts`、`heard`、`advertisement`、`caseStatus`、`stopScan`
- 字段：`scanStarts`、`retryScan`、`scanner`、三个 callback、`lid` 相关字段、`expireReadings`、`relayReceiver`
- 其他：屏幕开关的处理、`diagnoseScan`、`scanOnlyUntil`

**其他文件：**

- `DeviceRepository`：广播电量改为直接采用 `ON_STATE` 下发的合并结果。控制通道的电量照常本地写入（界面即时显示），同时 `PUSH_CONTROL` 给 Hub。去掉 `diagnosticScanning`。
- 删除 `WidgetBridge.kt`、`WidgetWire`、`WidgetRelayHost` 的接收器部分、`NativePopupBridge`（预览改走 `REQUEST_POPUP`）、`NativeControlBridge` 的 10 秒循环和 REGISTER 广播（由 `HELLO` 取代；`NativeControlReceiver` 保留）、`LidWire.ACTION`。
- `FrameworkBridge.publish()` 只写配置：address、name、`popup_enabled`、`native_popup`、`enabled`、`summary`。`summary` 在设置页切换到 Hub 后删除。`updated`、`native_*`、`widget_*`、`panel_*`、`panel_heartbeat`、`replace_system_popup` 在**所有消费方都切换完**以后删除。
- UI：删掉"2 分钟广播诊断"按钮，Material 和 iOS 两套界面都要删。keepScreenOn 只保留空间音频校准一个条件。诊断页里的扫描计数改为显示 Hub 上报的值和扫描模式。
- 权限：`permissionsGranted()` 不再要求 `BLUETOOTH_SCAN` 和定位权限。清单里的这两项等验收通过、确认没有其他用途后再删。
- HostReloadPolicy 和 HostRestartPolicy：确认 Hub 所在的主进程仍在重载和重启名单里。新增"Hub 版本不一致"时的提示。
- **检查点 B**：征得用户同意后编译，上机跑完整验收（第 8 节）。

### 阶段 7：第 4 点的小项（检查点 B 通过后做，单独出包）

1. **诊断写盘防抖**：`DeviceRepository.saveDiagnostics()` 改成 1.5 秒防抖，用单线程执行器异步写。服务 `onDestroy`、导出日志前、崩溃前强制写一次。
2. **去掉定时器**：确认 app 里没有任何周期任务（`expireReadings` 和 NativeControlBridge 的循环在阶段 6 已经删掉）。Hub 的 10 秒定时器在熄屏时停止，亮屏时恢复。
3. **合并两套 UI 状态**：比较 `ui/DeviceUiState.kt` 和 `ui/ios/DeviceUiState.kt`，抽出一份共享的 `DeviceUiState`（推导规则放一处），两套界面只负责渲染。两份 `DeviceUiStateTest` 合并成一份。
4. **契约常量**：在阶段 1 已经完成。这里只检查 `:app` 里是否还有直接引用 `:hook` 中非契约类的地方，有的话改成依赖 `:core:wire`。
5. **兼容性上报**：
   - 各宿主进程安装 hook 时，把结果（组件名 → 成功/失败 + 异常类名）通过广播 `HUB_CAPABILITIES` 发给 Hub（`setPackage("com.xiaomi.bluetooth")`，Hub 校验发送方 uid 属于作用域内的宿主）。
   - Hub 自己的结果直接写入。
   - app 用 `GET_DIAGNOSTICS` 读取，诊断页按进程显示，例如"原生面板：✓"、"小组件：✗ NoSuchMethodException"。
   - 用来替代"失败只记一条日志"的现状。
6. **检查点 C**：征得用户同意后编译，上机回归。

---

## 3. 文件清单（预计）

**新增：**

- `core/wire/`：`build.gradle.kts`、`HubWire.java`、`HubBundles.java`，以及从 hook 移过来的 Wire 类和 `HeadsetModels`
- `core/protocol/`：`BatteryLedger.kt`、`CaseThrottle.kt`、`PresenceTracker.kt`、`ControlCommands.kt` 及对应的测试
- `hook/`：`HubHost.java`、`HubState.java`、`HubNotifier.java`、`KeyVault.java`、`ScanEngine.java`、`AdvertisementParser.java`、`LidController.java`、`PopupDriver.java`、`WidgetDriver.java`、`HubReader.java`
- `app/`：`device/HubClient.kt`、`device/ControlSession.kt`

**大改：**

- `PodsService.kt`、`DeviceRepository.kt`、`FrameworkBridge.kt`
- `NativePopupHost.java`、`NativeControlHost.java`、`LidRelayHost.java`、`WidgetRelayHost.java`（并入 WidgetDriver 后删除）
- `WidgetBatteryHost.java`、`NativeAirPodsDialogAdapter.java`、`NativeControlPanelAdapter.java`、`NativeSpatialControlAdapter.java`、`NativeSettingsAdapter.java`、`HyperPodsModule.java`
- 两套 UI 的 `HyperPodsScreen` / `IosSettingsPages` / `DeviceUiState`

**删除：**

- `WidgetBridge.kt`、`NativePopupBridge.kt`、`WidgetWire.java`
- `NativeControlBridge.kt` 里 REGISTER 那一半

## 4. 风险与对策

| 风险 | 对策 |
| --- | --- |
| Hub 抛异常拖垮常驻的系统蓝牙辅助进程 | 每个入口（receiver、binder、ScanCallback、provider hook、Runnable）都 catch `Throwable`；连续失败 3 次就停掉这个子功能并上报兼容性 |
| 扫描被系统强制降级（`dumpsys` 里的 Forced 标记，5 分钟超时） | 阶段 0 已确认 uid 1002 不受限频；ScanEngine 每 4.5 分钟重启实时扫描，设 2 秒启动间隔并计数 |
| securitycenter.remote 在 query hook 里同步跨进程调用卡住 | Hub 的 provider 方法只读 volatile 快照，没有锁、不做 IO；调用方优先用缓存 |
| app 和 Hub 版本不一致（app 已更新、宿主未重载） | DESCRIPTOR 带版本号，握手时校验；不一致时提示重载，HostReloadCoordinator 处理 |
| 热重载时有 binder 客户端在线 | `ReloadLifecycle` 清理时断开死亡监听；app 收到 binderDied 后退避重连；新一代模块安装后发 `HUB_READY` |
| 密钥泄露 | 只走带 uid 校验的 binder；在 DE 存储里用 Keystore 加密；不进远程偏好、不进日志 |
| 切换期间新旧路径同时弹窗 | 阶段 4 和阶段 6 的 app 侧删除放在同一次出包里，不单独出只完成一半的包 |
| R8 移除或改名 core:protocol 里被 hook 用到的类 | 它们在 app 的调用图里本来就被引用；保险起见，给 `com.hyper.podsrx.protocol.**` 加 keep 规则，体积影响可以忽略 |

## 5. 构建与命令

- 编译、单测、lint 之前**都要先问用户**。出包前在同一次脚本调用里跑单测和 lint：
  ```
  scripts\build-windows.ps1 -Tasks ':app:testDebugUnitTest',':core:protocol:test',':core:wire:testDebugUnitTest',':app:lintDebug',':app:assembleDeviceTest'
  ```
  `:core:wire` 没有测试时去掉对应任务。deviceTest 变体没有单测任务。lint 偶尔会在分析器内部崩溃，重跑通常就好。
- 上机：用 `adb mdns services` 找到设备后 `adb connect`，端口会变。装完要 `am force-stop com.xiaomi.bluetooth` 并重启 app，新模块代码才会生效。其他宿主（milink、securitycenter、settings）也要重启，或者走 app 内的宿主重载。
- 日志 tag 是 `HyperPods-X`。`dumpsys bluetooth_manager` 看各 app 的扫描登记，`dumpsys activity broadcasts history` 看广播是否送达。

## 6. 验收

**原有 5 项：**

1. app 不再登记任何扫描（`dumpsys bluetooth_manager` 里 com.hyper.podsrx 名下没有扫描），所有扫描都由 uid 1002 发起。
2. 连续快速亮熄屏 10 次以上，扫描仍然正常，logcat 里没有 `scanning too frequently`。
3. 强杀 app 后，开盖弹窗、超级岛、小组件电量、充电状态都照样更新。
4. 耳机远离约 70 秒后电量隐藏，3 分钟后降频；回来后恢复高频，开盖照常弹窗。
5. 插拔充电，几秒内显示变化。

**新增：**

6. 强杀 app 后，原生耳机面板（milink）的电量仍然正确；降噪按钮显示为不可用，或者点了提示需要 app，不会卡在"处理中"。
7. app 重启后 5 秒内重新连上 Hub，降噪、自适应、EQ、空间音频、对话感知都正常。
8. `am force-stop com.xiaomi.bluetooth` 以后，Hub 恢复；app 在 30 秒内重连；密钥从 DE 存储恢复，不需要重新连接 AACP 就能解密广播。
9. securitycenter.remote 被杀后，小组件刷新时能拉到正确的值。
10. LSPosed 热重载模块以后，没有残留的扫描登记（重载前后对比 `dumpsys`），也没有重复的接收器。
11. 系统设置里的耳机摘要、详情页入口、"更多设置"跳转都正常。
12. 诊断页能看到各宿主的兼容性上报和 Hub 的扫描模式。
13. 降噪、自适应、EQ 写入和回读的行为和重构前一致（检查点 A 已回归过，检查点 B 再测一次）。
14. 功耗：熄屏静置 30 分钟后，`dumpsys bluetooth_manager` 里 uid 1002 的扫描时长增量符合选定的熄屏策略。

## 附录 A：开工时工作区里未提交的改动

- **`DeviceRepository.kt`**
  - Reading 增加了 control 字段。
  - 新增 `Map<Int,Reading>.content()` 和 `expireBatteries(maxAgeMs, controlFreshAt)`。
  - `batteries(values, precise, control)` 已包含断开删除、充电立刻生效、数值不变时不发布。
  - 阶段 1 迁到 `BatteryLedger`。
- **`PodsService.kt`**
  - 已有每 10 秒的 `expireReadings`、`controlLostAt`、`STALE_READING_MS`、`CASE_REFRESH_MS`、`IDLE_SCAN_MS`。
  - 已经去掉批量扫描，拆出了 `startLiveScan` / `setLiveIdle` / `heard`。
  - 有一段扫描限额修复 `reserveScanStarts`，**没编译过，阶段 6 删除**。
  - 服务停止时清空电量。
- **`NativeControlBridge.kt`**：已改为用 `batteries.content()` 判断是否有变化。
- **`WidgetBridge.kt` / `FrameworkBridge.kt` / `WidgetWire.java` / `WidgetRelayHost.java`**：app 在序号变化时用 REFRESH_WIDGET 直接唤醒宿主。阶段 4 并入 WidgetDriver，阶段 6 删除。
- 其余改动（HostReload/RestartPolicy、DiagnosticLogs、两套 UI、`CaseStatus.kt` 及其测试、scope.list 新增 securitycenter）保留。
