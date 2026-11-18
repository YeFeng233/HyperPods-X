# HyperPods-X

[简体中文](README.md) | [English](README_EN.md)

HyperPods-X is a system-level AirPods integration module (Android app + LSPosed module) for Xiaomi HyperOS. By hooking into System Bluetooth, SystemUI, and Device Interconnectivity interfaces alongside the Apple AACP protocol, it brings native open-case popups, HyperIsland battery indicators, Device Hub controls, dynamic head-tracking Spatial Audio, and a dual-style control panel to HyperOS.

[Releases](https://github.com/YeFeng233/HyperPods-X/releases) · [CI Builds](https://github.com/YeFeng233/HyperPods-X/actions/workflows/build-release.yml) · [Device Test Report](docs/TEST-REPORT.md)

## Target Baseline

- **OS & Device**: Xiaomi 17 Pro Max / HyperOS 4 (Android 17, API 37) and compatible devices
- **Supported Models**: AirPods 5, AirPods Pro 3
- **Hook Architecture**: libxposed API 102, backed by a persistent Xiaomi Bluetooth Host Hub
- **Current Version**: 0.5.1-dev (`com.hyper.podsrx`)

> **0.5.0 Architecture Note**: Starting with 0.5.0, BLE scanning, lid open/close detection, and native popup scheduling run inside the Xiaomi Bluetooth Host Hub for faster popup latency and lower background power usage. If you maintain a fork, refer to the [v0.5.0 Release Notes](docs/releases/v0.5.0.md) and [Host Hub Migration Plan](docs/archive/HOST-HUB-MIGRATION-PLAN.md).

---

## Core Features

- **Native Popups & HyperIsland**
  - Displays the native HyperOS bottom earbud card on lid open and dismisses it on lid close.
  - Shows independent left/right earbud battery levels via SystemUI HyperIsland when other apps are in the foreground.
  - The Host Hub stays resident in the system Bluetooth process, keeping lid detection and native popups functional even after the main app exits.
- **System-Level Integration**
  - **Bluetooth Settings Direct Entry**: Tapping the detail arrow next to your AirPods in System Bluetooth Settings opens the HyperPods-X configuration screen directly.
  - **Device Hub Card**: Control Center's headset card supports battery monitoring, four-mode noise control switching, and Spatial Audio toggles.
- **Dual UI Styles**
  - Ships with both **miuix** (native HyperOS) and **iOS** (Glasmio glassmorphism) themes. Switch anytime in **More Settings → Appearance** with shared configuration and calibration data.
- **Noise Control & Gesture Customization**
  - **Noise Control Modes**: Off, Transparency, Noise Cancellation, and continuous Adaptive Noise Control level adjustment (0–100).
  - **Gestures & Tones**: Customize press speed, press-and-hold duration, stem swipe volume, tone volume, single-earbud ANC, and sleep auto-pause.
  - **Ear Detection**: Auto-pauses media when one earbud is removed and resumes when reinserted. Only resumes media paused by HyperPods-X without overriding manual pauses.
- **Conversational Awareness**
  - Automatically lowers media volume when speech is detected and restores it smoothly afterward, preserving HyperOS per-app independent audio tracks.

---

## Spatial Audio & Dynamic Head Tracking

HyperPods-X reads 6-axis motion data from AirPods sensors and bridges it into the HyperOS system Spatializer renderer:

| Mode | Behavior | Calibration |
| --- | --- | --- |
| **Off** | Disables Spatial Audio rendering and releases attitude/head-tracking sensors | None |
| **Fixed** | Enables native HyperOS Spatial Audio rendering (soundstage stays fixed relative to head movement) | None |
| **Head Tracking** | Streams real-time earbud orientation to the system head tracker, anchoring audio sources in physical space | 3-axis calibration required |

For first-time Head Tracking use, complete the guided 3-axis calibration (turn right, look up, tilt side) in **Head Tracking & Calibration**. If your phone or seating position shifts during daily use, tap **Face Your Phone and Recenter** to realign.

> Spatial Audio relies on the HyperOS system rendering engine. Effective multichannel output depends on the media player, audio format, and system support. See [Spatial Audio Notes](docs/SPATIAL-AUDIO-TEST.md) for details.

---

## Installation & Setup

1. **Pair & Install**: Pair your AirPods in System Bluetooth Settings, then download and install the latest APK from [Releases](https://github.com/YeFeng233/HyperPods-X/releases).
2. **Enable LSPosed Scopes**: Activate HyperPods-X in an LSPosed build supporting libxposed API 102 and select the following scopes:
   - **Settings** (`com.android.settings`)
   - **System UI** (`com.android.systemui`)
   - **Xiaomi Bluetooth** (`com.xiaomi.bluetooth`)
   - **Device Interconnectivity** (`com.milink.service`)
   - **Security** (`com.miui.securitycenter`)
   - **Sound Effects** (`com.miui.misound`) to sync the Immersive sound switch with Spatial Audio on the active AirPods
   > After first activation or module updates, reboot your device or tap **Restart Scopes** in **More Settings** so system hosts load the updated code.
3. **Grant Permissions**: Open HyperPods-X, grant **Nearby Devices** and **Notifications** permissions, and allow unrestricted background execution for AACP control, ear detection, and Spatial Audio services.
4. **Connect**: Tap **Connect Earbuds** to begin. Afterwards, you can also open settings directly from System Bluetooth Settings.

If you encounter connection or popup issues, generate a diagnostic bundle (ZIP) via **More Settings → Export Logs**. See [Logging Guide](docs/LOGS.md).

---

## Known Limitations

- **Verified Baseline**: Fully tested only on Xiaomi 17 Pro Max (HyperOS 4 / Android 17 / API 37) with AirPods 5 and AirPods Pro 3.
- **HyperIsland Interaction**: Currently displays battery status only; tapping the island does not trigger navigation.
- **Apple Ecosystem Features**: Apple Find My, firmware updates, and Personalized Spatial Audio profile import are not supported.
- **Head Tracking**: Long-duration drift compensation is still under active refinement.

---

## Building from Source

### Prerequisites

- Git, Python 3.11+, JDK 21
- Android SDK Platform 37.0, Build Tools 37.0.0
- NDK 27.1.12297006, CMake 3.22.1 (required by Glasmio native rendering)

### Build Steps

1. Configure `sdk.dir` in `local.properties`.
2. Fetch Glasmio sources and SF Pro font dependencies:
   ```bash
   python scripts/prepare-ui.py --fonts
   ```
3. Run unit tests, lint, and build the APK:
   ```bash
   ./gradlew :app:testDebugUnitTest :core:protocol:test :core:wire:testDebugUnitTest :app:lintDebug :app:assembleDeviceTest
   ```
   > On Windows, use `gradlew.bat`. The output APK is generated under `app/build/outputs/apk/deviceTest/`.

For build options and font fallbacks, see [UI Styles Build Guide](docs/UI-STYLES.md). For release pipelines and signing, see [Releasing Guide](docs/RELEASING.md).

---

## Project Architecture

```text
HyperPods-X/
├── core/
│   ├── protocol/    # Pure Kotlin protocol codec, BLE decryption, attitude solver & state machine (no Android deps)
│   └── wire/        # Binder IPC contracts and Parcelables between the app and system hosts
├── hook/            # Lightweight Xposed glue layer for system hosts (Settings, Bluetooth, Interconnectivity, etc.)
├── app/             # Main application: dual-style UI, AACP session, ear detection & Spatial Audio management
└── docs/            # Architecture specs, test reports, and technical documentation
```

- **Privacy & Local Storage**: The app does not request internet permission. All earbud identity material and BLE decryption keys are stored locally and encrypted via Android Keystore.

---

## Acknowledgements & License

- Protocol reverse engineering and earbud controls reference [LibrePods](https://github.com/librepods-org/librepods); HyperOS integration patterns reference [HyperPods](https://github.com/Mo-SeTian/HyperPods).
- UI components are built with [Miuix](https://github.com/compose-miuix-ui/miuix) and [Glasmio](https://github.com/NEORUAA/Glasmio).
- Hook framework powered by [libxposed](https://github.com/libxposed).
- Attitude service discovery and motion data parsing reference public RTBuddy research; see [Third-Party Notices](THIRD_PARTY.md) for the full list.

Source code is licensed under **GPL-3.0-or-later**; see [LICENSE](LICENSE). For headset artwork licensing, see [Artwork Sources](docs/ARTWORK-SOURCES.md). HyperPods-X is an independent open-source project and is not affiliated with Apple Inc. or Xiaomi Corporation.
