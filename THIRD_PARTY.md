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

Runtime dependencies include Glasmio 0.1.0 (Apache-2.0), its Miuix 0.9.3 navigation
and overscroll dependencies (Apache-2.0), libxposed API/Service 102.0.0
(Apache-2.0), HiddenApiBypass 6.2 (Apache-2.0), Kotlin, Compose, AndroidX and coroutines.
GPL-3.0 and Apache-2.0 license texts and this notice are included in the app's
`assets/licenses/`. Dependency META-INF license/notice resources are merged, not excluded.
Miuix: https://github.com/compose-miuix-ui/miuix/tree/v0.9.3
Glasmio: https://github.com/NEORUAA/Glasmio/tree/5f1b5214057870228fcb69a3aed8fe2d0575e3c1
libxposed: https://github.com/libxposed
HiddenApiBypass: https://github.com/LSPosed/AndroidHiddenApiBypass

SF Pro and SF Symbols are supplied by the build host as generated application
assets. They are Apple resources and are not included in the GPL source grant.
Glasmio's library artifacts do not contain these fonts.

The launcher uses the maintainer-supplied adaptive icon foreground and background.
AirPods 5 product photographs are
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
The slider therefore snaps to the three verified values instead of claiming
continuous hardware adjustment. No decompiled UI code is redistributed.

## Conversation-awareness experiment (in progress)

The conversation-awareness parser and state mapping were cross-checked against
the original LibrePods `main` implementation and the same locally inspected
`2.0.0-alpha.3` APK identified above. Sources inspected on 2026-10-26:

- [`bluetooth/AACPManager.kt`](https://github.com/librepods-org/librepods/blob/main/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt),
  [`data/Packets.kt`](https://github.com/librepods-org/librepods/blob/89356c08cea3db0b6b54792d9329d09a87cc85fa/android/app/src/main/java/me/kavishdevar/librepods/data/Packets.kt),
  [`services/AirPodsService.kt`](https://github.com/librepods-org/librepods/blob/main/android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt),
  and [`utils/MediaController.kt`](https://github.com/librepods-org/librepods/blob/main/android/app/src/main/java/me/kavishdevar/librepods/utils/MediaController.kt).
  The original implementation uses control ID `0x28`, event opcode `0x4b`,
  status byte 9 in the common ten-byte packet, active states 1/2 and end
  states 6/8/9.
- APK classes `h90`, `ni.j`, and `i.e` confirm control values 1=on and 2=off;
  `n52` and `i` retain opcode `0x4b` and payload offset 3 (packet offset 9).
  The `yw1` service collector also handles active state 3 and end state 7.
  Its `d52` media adapter directly adjusts `STREAM_MUSIC`; HyperPods-X does
  not copy that volume-write and restore strategy.
- APK initializer `i.f`, packet serializer `j.d`, and opcode/type classes
  `h70` / `k` inform the AACP 1.3 handshake
  (`00 00 04 00 01 00 03 00 00 00 00 00 00 00 00 00`),
  source-capabilities payload `D7 00 00 00 00 00 00 00`, all-notifications
  subscription, and 200 ms initialization spacing. The model/feature tables
  `v4` / `yn` list A3441 among the AirPods 5 models with conversation awareness.
- The public [AACP dissector](https://github.com/pabloaul/apple-wireshark/blob/main/plugins/aacp/aacp_parse_message.lua)
  informs the little-endian payload length and subtype layout. HyperPods-X
  independently implements bounded framing, subtype validation, connection
  generations, duplicate handling, and cleanup policy.

The target headset has produced real subtype-1 events, including states
1/2/3/4/6/7/8/9. State 4 remains an unclassified observation; its presence is
not treated as evidence for an additional start or end mapping. Receiving
events does not establish working media attenuation. The Android transient
audio-focus trial was ineffective with the user's retained HyperOS concurrent
media setting. The replacement uses an independently implemented, scoped
VolumeShaper bridge. Its first foreground trial has user-confirmed attenuation
and restoration without volume jumps. Instrumented event-to-host submission
was 5–24 ms at start and 3–6 ms at end; these are not acoustic latency measurements.
The user narrowed the issue to quiet-speech sensitivity. Neither the inspected
main implementation nor alpha.3 provided a verified sensitivity control; no
undocumented threshold write was added. Background, interruption and player compatibility tests
remain pending. No APK, decompiled source, or upstream media-control
implementation is redistributed with this experiment.
