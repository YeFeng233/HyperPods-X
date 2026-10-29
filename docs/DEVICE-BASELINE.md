# 目标真机环境基线

本文件记录 HyperPods-X 开发与真机调试所依据的参考硬件与系统环境基准。

---

## 1. 硬件与系统规格

| 配置项 | 基准参数 |
| --- | --- |
| **设备型号** | Xiaomi 17 Pro Max（代号 `popsicle`，型号代码 `2509FPN0BC`） |
| **系统版本** | HyperOS `OS4.0.4.0.XPBCNXM` / `OS4.0.5.0.XPBCNXM`（Android 17, API 37） |
| **系统指纹** | `Xiaomi/popsicle/popsicle:17/CP2A.260605.016/OS4.0.4.0.XPBCNXM:user/release-keys` |
| **CPU 架构** | `arm64-v8a` |
| **Root 授权** | KernelSU |
| **Hook 框架** | LSPosed `2.2.1 (7930)`，运行于现代 **libxposed API 102** 环境 |

---

## 2. 关键系统组件基线

- **系统设置**：`/system_ext/priv-app/Settings/Settings.apk`（版本 17 / code 37）
  - 核心入口类：`com.android.settings.bluetooth.DeviceProfilesSettings`
- **小米蓝牙扩展**：`/system_ext/app/BluetoothExtension/BluetoothExtension.apk`（版本 17 / code 37）
  - 动态快连插件：`preloadedFeature`（版本 `1.14@1`）
- **蓝牙服务**：`com.android.bluetooth`（位于 `com.android.bt` APEX，版本 17 / code 37）
- **空间渲染库**：`/system_ext/lib64/libspatial.so`

---

## 3. 测试耳机型号

- **AirPods 5（无线充电盒版）**：AACP 型号 `A3441`
- **AirPods Pro 3**：AACP 型号 `A3065`
- 通信通道：BR/EDR L2CAP `PSM 0x1001` 原生控制通道，广播采用标准 Apple 0x07 邻近配对广播。
