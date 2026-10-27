# Third-party sources

HyperPods-X is distributed under GPL-3.0-or-later. The complete license is in `LICENSE`.

The following LibrePods sources informed the rewritten wire protocol, socket compatibility,
and BLE cryptography implementation. Original copyright: (C) 2025 LibrePods contributors.
The new code retains attribution and adds input bounds checking, separate confirmed state,
key redaction, and explicit battery freshness semantics.

| Upstream file | Audited Git blob SHA |
| --- | --- |
| `bluetooth/AACPManager.kt` | `ac6d356b7fb5b2e320b70524218f5fd0bd8d8c84` |
| `bluetooth/BLEManager.kt` | `52fa055124501160e686c5feebd01c31c0b10300` |
| `utils/BluetoothCryptography.kt` | `80d3a456cc632f64a2bf9965d59e17897c1868b3` |
| `bluetooth/BluetoothConnectionManager.kt` | See upstream link below |
| `data/Packets.kt` | `b5ec18ca696b94a00bcb76c97d871243f36818fa` |
| `data/CustomEq.kt` | `38fe3b7940eb1efb03597030e69aa304eb47180d` |
| `presentation/viewmodel/AirPodsViewModel.kt` | `0b43370e0e61a54b92ca8f3b564aadca3ecf8ade` |

Source: https://github.com/librepods-org/librepods/tree/main/android/app/src/main/java/me/kavishdevar/librepods

HyperPods was researched for Xiaomi integration ideas. Its old Xposed/Yuki entry and
bundled artwork were not copied. Source: https://github.com/Mo-SeTian/HyperPods/tree/aap

Runtime dependencies include Miuix 0.9.4 (Apache-2.0), libxposed API/Service 102.0.0
(Apache-2.0), HiddenApiBypass 6.2 (Apache-2.0), Kotlin, Compose, AndroidX and coroutines.
GPL-3.0 and Apache-2.0 license texts and this notice are included in the app's
`assets/licenses/`. Dependency META-INF license/notice resources are merged, not excluded.
Miuix: https://github.com/compose-miuix-ui/miuix/tree/v0.9.4
libxposed: https://github.com/libxposed
HiddenApiBypass: https://github.com/LSPosed/AndroidHiddenApiBypass

The launcher vector is an original code asset. AirPods 5 product photographs are
copyright Apple Inc. and are excluded from this project's GPL grant. Sources and
the local-test usage note are recorded in `docs/ARTWORK-SOURCES.md`.

The AACP field layout was also cross-checked against the public protocol dissector
at https://github.com/pabloaul/apple-wireshark/tree/main/plugins/aacp.
No dissector source code or artwork is included.

The spatial-audio experiment uses factual RTBuddy/DEVMOTION wire-layout research
from FIocker/aap-head-tracker and Untrosss/HeadTracker (GPL-3.0). The bounded
protobuf reader, quaternion math and three-axis calibration are rewritten for
HyperPods-X; no device-specific AirPods Pro 2 calibration constants are copied.
Relevant Untrosss/HeadTracker audited Git blobs:

| Upstream file | Git blob SHA |
| --- | --- |
| `Source/Protocol/RtBuddy/RtBuddy.cpp` | `e6fab5bd5eb005c3a39edc79d6db89932b812351` |
| `Source/Protocol/Aap/Aap.cpp` | `dce841794a58b111d3ef1cb0696288b4d1e15acd` |

Sources: https://github.com/Untrosss/HeadTracker and https://github.com/FIocker/aap-head-tracker.
The installed ROM's system libraries are invoked in their existing host; their
APKs, native binaries and decompiled code are not bundled or redistributed.

The 0.3.1 service-discovery correction uses wire-format facts from:

- LibrePods `android/rewrite`, `android/core/src/main/kotlin/me/kavishdevar/librepods/core/bluetooth/aacp/RTBuddyManager.kt` (blob `c65b4b5a1805d535e1ca69c24c15cbd14db3e451`), and the original AirPodsService ownership request: https://github.com/librepods-org/librepods
- SensorDataWX schema and trailer layout: https://github.com/pabloaul/apple-wireshark/tree/main/plugins/rtbuddy (proto blob `54b0f6795bd6e8798b7b4b2fb36661d7fcb46312`).
- Apple XNU OS serialization binary format: https://github.com/apple-oss-distributions/xnu/blob/main/libkern/c%2B%2B/OSSerializeBinary.cpp (blob `5d6f1de756d00507cfddce1a46568e4629513120`).

The bounded metadata reader is independently implemented; no XNU or dissector source
is copied. Raw accessory descriptors can contain device identifiers and are excluded
from source, fixtures and distributable artifacts.

## 0.4.1 automatic ear detection and transparent artwork

With the user's authorization, the installed LibrePods `2.0.0-alpha.3` APK
(`me.kavishdevar.librepods`, versionCode `200000003`) was inspected locally.
APK SHA-256: `6f4547a4cb7af89d9e8ec11dcdc550585518b1d8ef5f7c651a246645fd9418e9`.
The upstream project is https://github.com/librepods-org/librepods (GPL-3.0).

The release-obfuscated implementations `xo0.F` / `jz3.d` / `bs0`
(`EarDetectionResponsePacket`), `un0.t` (`NONE` / `PARTIAL` / `FULL` presence),
`yw1` (LibrePodsService state collector), and `d52` / `b52` (`MediaController`)
informed the new implementation. They preserve wire states 0=in-ear, 1=out,
2=in-case, 3=disconnected and pause on FULL -> PARTIAL as well as removal of
the last worn bud. The wire fields are primary/secondary, not fixed left/right.

HyperPods-X rewrites this logic as a JVM-tested state machine and a main-thread
Android adapter. It adds selected-device route and call guards, initial-report
suppression, asynchronous pause acknowledgement, a five-minute resume limit,
and resets on disable/disconnect. It does not copy LibrePods' profile
connect/disconnect, routing takeover, or other-device automation. Existing
preferences are retained; the user's opt-in controls Android media automation
independently of the optional headset firmware control `0x0a`.

`img_airpods_5_wireless_buds` / APK entry `res/E1.png` was extracted byte-for-byte
as `airpods5_pair.png` (291x219 RGBA); `img_airpods_5_buds` is identical.
Asset provenance, hash and separate artwork licensing caveats are recorded in
`docs/ARTWORK-SOURCES.md`. The APK and decompiled files remain local audit material
under `.reference/`; they are not shipped in the app.

## 0.4.2 adaptive listening control

The same locally inspected LibrePods APK informed the adaptive control's wire
format: `ni.i` / `i.e` / `i90` send `AUTO_ANC_STRENGTH` (`0x2e`) as an integer
byte, zero-padded to four value bytes. The integrated listening selector
(`qr0.e`, `dd2`, `nd2`, `fd2`) places 0 toward transparency and 100 toward ANC.
The separate allowed-noise slider (`cj`, `f9`) inverts that value as `100 - x`.
HyperPods-X independently implements its Compose selector and accessible slider,
only sends on release, and confirms device readback rather than copying the
upstream optimistic state update. On the target headset, writes of 40 and 60
remained at 50, whereas 0, 100 and restoration to 50 were confirmed by readback.
That version's slider therefore snaps to the three verified values instead of claiming
continuous hardware adjustment. No decompiled UI code is redistributed.

## 0.4.6 LibrePods adaptive slider

`ui/LibrePodsAdaptiveSlider.kt` now adapts the integrated slider from the public
LibrePods `NoiseControlSettings.kt`, under GPL-3.0-or-later:
https://github.com/librepods-org/librepods/blob/80aa7461df414470f379ebdfe540b2e8889fcbe2/android/app/src/main/kotlin/me/kavishdevar/librepods/presentation/components/apple/NoiseControlSettings.kt

Copyright (C) 2025 LibrePods contributors. Source blob:
`7f86743f9b945c95915f581ad4eaecd9aacf5754`.

The adaptation retains continuous 0–100 travel/strength mapping, drag handling,
pressed-thumb animation and live updates. HyperPods-X supplies its Miuix colors,
existing glyphs, accessibility, keyboard and RTL handling, plus cancellation and
queued-write coalescing. This replaces the 0.4.2 three-detent implementation.
Sent targets and actual earbud reports remain separate; missing same-value replies
do not block further dragging or imply confirmed hardware adjustment.
No decompiled UI source is redistributed. See the included GPL license and the
repository's `THIRD_PARTY.md` for attribution and the comparison findings.
