# AirPods 5 复用 HyperOS 空间音频的可行性

日期：2026-10-22，Asia/Hong_Kong。目标手机、ROM 和耳机仍为 [真机基线](../DEVICE-BASELINE.md) 中的单设备组合。
本文保留以 0.2.0 为基线的分析阶段记录，包括真机只读诊断、系统 APK/库静态分析和公开原始资料核对。
**后续 0.3.0 已完成固定空间音频试听；0.3.1 已取得真实耳机姿态并通过动态试听与摘下双耳后的自动清理。最新结果以 [空间音频测试记录](../SPATIAL-AUDIO-TEST.md) 为准。** 下文“尚未调用/实验”的表述指分析阶段。

## 结论

**固定空间音频具备直接复用基础；动态头部追踪有明确接入路径，尚未验证完成。**

AirPods 5 佩戴连接后，系统已把其 A2DP 输出判为可空间化并启用渲染器。
当前缺口是系统没有发现与耳机对应的头部追踪传感器，而不是手机缺少空间渲染算法。
应优先复用本机 `miSpatializer` 和小米蓝牙姿态桥接，不以更改设备名称或伪装 Pro 3 型号作为实现前提。

这里的固定空间音频指手机对兼容音轨进行双耳空间渲染；动态头部追踪还需要随头部方向更新声场。
两者均不能等同于移植 Apple 的个性化 HRTF、TrueDepth 建模或账户同步。

## 真机证据

用户确认双耳已连接佩戴后读取 `dumpsys audio`、`sensorservice`、`media.audio_flinger` 和 `media.audio_policy`。
App 的实际控制连接状态同时为 connected=true。

| 项目 | 读取结果 | 意义 |
| --- | --- | --- |
| 输出设备 | `Current device: bluetooth_device` | 当前为空间音频检查所需的蓝牙输出路由 |
| 系统效果 | `mHasSpatializerEffect:true`、`isSpatializerEnabled:true`、`mSpatLevel:1` | 空间效果存在并在当前路由下启用 |
| 内容与路由判定 | 当前 A2DP 路由的 `can spatialize media 5.1:true` | 系统认可该设备上的兼容多声道空间渲染；不只是在扬声器路由可用 |
| 声道能力 | `spatialized speaker masks: 5.1 7.1.4` | 引擎声明的输入能力，不代表所有播放器都会提供这些音轨 |
| 渲染实现 | AudioFlinger 的 SPATIALIZER 输出线程，效果 `miSpatializer`，厂商 `Xiaomi Bsp Audio` | 有可以复用的系统音频处理链 |
| 追踪算法 | `mIsHeadTrackingSupported:true`，支持 `HEAD_TRACKING_MODE_RELATIVE_WORLD` | 手机引擎声明支持动态追踪 |
| 当前追踪状态 | `mActualHeadTrackingMode:HEAD_TRACKING_MODE_DISABLED`、`headtracker available:false` | 当前没有真正启用动态头部追踪 |
| 传感器 | SensorService 未列出 `android.sensor.head_tracker`；只有 Dynamic Sensor Manager 不代表头部传感器已存在 | 需要补上姿态数据接入和音频设备关联 |
| 当前播放 | 空间输出线程 standby、0 Tracks、Frames written=0 | 本轮没有实际播放多声道内容，不能将上述配置判定当作听感验收通过 |

空间渲染器的配置状态与单条音轨是否正在被空间化需要分别验证。
立体声音源、播放器主动关闭空间化、已预渲染音频和硬件直通路径，都不能仅凭系统总开关判断最终效果。

原始诊断保存在忽略目录 `.device/`；本报告不记录设备地址、用户耳机名称或身份密钥。

## 可复用的本机接口

### 系统空间音频设置

`Settings.apk` 的 `BluetoothDetailsSpatialAudioController` 使用系统 `Spatializer`：

- `isAvailableForDevice(...)` 判断音频设备是否可用。
- `addCompatibleAudioDevice(...)` / `removeCompatibleAudioDevice(...)` 控制设备的空间化偏好。
- `hasHeadTracker(...)` 判断是否存在对应头部传感器。
- `setHeadTrackerEnabled(...)` 控制已存在传感器的追踪偏好。

该控制器只有在 `hasHeadTracker` 为真时显示头部追踪选项。
因此强制显示开关或返回“支持”不能代替真正的姿态输入。
本次查看的 MiLink `AirPodsHeadsetStrategy` 中未发现专门转发头部姿态的实现；这不足以排除其他组件或后续插件存在实现。
目前没有 AirPods Pro 3 对照设备，不能据此认定其原生“空间音频”入口已经包含动态追踪。

### 小米蓝牙姿态桥接

当前已经加入模块作用域的 `com.xiaomi.bluetooth` 含有：

```text
com.android.bluetooth.ble.app.broadcast.BluetoothPluginHelperService
    createSpatialHidHelper(String)
    sendSpatialDataHelper(String, float[])
    destroySpatialHidHelper(String)

com.android.bluetooth.ble.app.headset.Spatialcontroller
    createspatialhid(String)
    sendspatialdata(String, float[])
    destroyspatialhid(String)
```

`Spatialcontroller` 加载本机 `/system_ext/lib64/libspatial.so`。
已读取该库，SHA-256 为 `D6C9742201F5223EC58959C0E84D59402A2A72C681DF97EC008A326FBD2198D0`；
库含对应 JNI 导出、`/dev/uhid` 和 UHID 报告处理字符串，支持其通过虚拟 HID 接入传感器链的判断。

快连插件的 `FragmentHandler` 在收到小米耳机通知 type 33 时读取三个 float，日志标记为 yaw、pitch、row，
存入长度为 3 的数组，并通过上述接口约每 30ms 提交一次。
传入地址使用 `0x` 加去掉冒号的蓝牙地址。这里是小米现有姿态输入的调用约定，不是已经验证的 AirPods 转换公式。

需要注意插件反射封装与当前宿主的销毁签名不一致：插件尝试无参调用，当前宿主实际为 `destroySpatialHidHelper(String)`。
后续适配应依据真机方法签名，并保证退出时销毁对应设备。

本轮没有调用这些创建、发送或销毁方法。库能否在该宿主正常创建传感器、传感器 UUID 能否匹配当前 A2DP 地址、
float 的单位和坐标系是否符合预期，均需实验确认；不能把静态发现的入口记为接入已成功。

## AirPods 5 数据侧

[Apple 官方规格](https://www.apple.com/ie/airpods-5/specs/)确认 AirPods 5 使用 H2，并支持动态头部追踪。
这说明产品具有相应能力，但不保证其姿态数据会按 Android 标准 HID 自动上报。

参考仓库当前源码提供了以下线索：

- [AACPManager.kt](https://github.com/librepods-org/librepods/blob/main/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt)：头部追踪 opcode 为 `0x17`，存在两组订阅/停止报文；本轮读取文件 blob 为 `ac6d356b7fb5b2e320b70524218f5fd0bd8d8c84`。
- [AirPodsService.kt](https://github.com/librepods-org/librepods/blob/main/android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt)：订阅时考虑 AACP 连接所有权；单纯写出报文不代表已经收到姿态。
- [HeadOrientation.kt](https://github.com/librepods-org/librepods/blob/main/android/app/src/main/java/me/kavishdevar/librepods/utils/HeadOrientation.kt)：当前 `HeadTracking` 工具只计算 pitch、yaw，含经验偏移与校准；blob 为 `ebdf91490abc10f1b13e5fba3fa2fd594c75e897`。不应把该显示/手势用途计算直接当作完整三轴空间音频姿态解码器。
- [LibrePods README](https://github.com/librepods-org/librepods#spatial-audio)仍说明没有把姿态提供给 Android 的 HRTF 引擎；参考项目本身不能作为系统动态空间音频已跑通的证据。

在 A3441 和当前报告版本上，仍需确认订阅格式、完整三轴数据、采样率、丢包、坐标方向和漂移。
这些订阅和回收实验尚未执行。本报告没有将前期未知的 `0x4E` 八个零状态猜成头部姿态。

## 建议实现路径与验收条件

```mermaid
flowchart LR
    A[AirPods 5 姿态报告] --> B[现有 AACP 连接与姿态解码]
    B --> C[带设备校验的 IPC 与坐标转换]
    C --> D[小米蓝牙宿主 libspatial 虚拟 HID]
    D --> E[SensorService 设备关联]
    E --> F[系统 miSpatializer]
    F --> G[现有 A2DP 音频输出]
```

1. **固定空间音频验收**：先查询本设备的 Spatializer 状态，播放受控的低音量 PCM 5.1 测试内容；检查实际音轨 `isSpatialized`、空间线程 Tracks/帧计数和 A2DP 路由，再由用户比较关闭/开启效果。通过后才能标为“固定空间音频已验证”。
2. **只验证姿态订阅**：复用现有 AACP 连接，先不注入系统。分别验证左右转头、抬头低头、侧倾及静止，确认有持续且方向正确的三轴数据；停止订阅后确认数据停止。姿态失效时不得用零值冒充有效传感器。
3. **桥接验证**：在已适配的蓝牙宿主内调用本机接口，确认动态 `TYPE_HEAD_TRACKER` 出现并绑定正确设备；设置追踪偏好后，检查 `isHeadTrackerAvailable` 和实际追踪模式，不能只看 desired 模式。
4. **动态听感与时延**：使用固定手机和多声道音源，确认转头后声源锚点稳定，校准/回中正确，端到端延迟可接受，且不会缓慢漂移或方向反转。
5. **生命周期**：摘下、入盒、断连、切换音频设备、来电或关闭功能时，停止姿态订阅并销毁虚拟设备，恢复原有偏好。按当前 ROM 和绑定设备限制桥接范围。

现有两个作用域已覆盖 Settings 与小米蓝牙宿主，初步路线无需新增 SystemUI 或 system_server 作用域。
高频姿态应采用有身份校验的 Binder/本地通道，带单调时钟时间戳、限频和过期处理；不复用用于偶发弹窗的高频有序广播。
若本机 JNI 桥接无法关联或不满足格式要求，再评估独立 UHID 适配；不以开启全局 SELinux 宽松模式作为依赖。

## 官方资料约束

[AOSP 空间音频架构](https://source.android.com/docs/core/audio/spatial)说明，系统渲染器消费与音频设备关联的动态头部传感器，标准链路通过 HID，且追踪体验取决于端到端低延迟。
[Android 开发文档](https://developer.android.com/media/grow/spatial-audio)要求分别检查支持、可用、启用和具体音轨可空间化四个条件。

[Apple 个性化空间音频说明](https://support.apple.com/zh-cn/102596)使用支持 TrueDepth 的 iPhone 创建个人轮廓，并列出 Apple 设备使用条件。
本项目可研究接入小米的空间渲染和追踪，不应在 UI 中将其标为已经获得 Apple 个性化空间音频档案或 Apple 算法。
