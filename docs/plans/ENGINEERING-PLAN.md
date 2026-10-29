# HyperPods-X 工程规范化计划

版本：v1.1 · 日期：2026-10-29 · 状态：进行中（A1、A3、A5、A8 的 Actions 部分、B1 已提交到 `ci/pr-checks`）

本计划在 [Hub 迁移](../archive/HOST-HUB-MIGRATION-PLAN.md)（阶段 0–7 已完成）之后，统一安排 CI、代码规范、构建配置、代码结构和 AACP 运行时迁移这几条线。CI 部分参照 HyperMusicCover（`zyl6932/HyperMusicCover`）已经跑通的四个工作流。

**规则不变：** 本地编译、单测、lint 都要先问用户（Hub 迁移各阶段完成后可以直接跑单测）。推送会触发 GitHub Actions 的改动，推送前也先确认。

---

## 0. 现状

### 0.1 两个项目的 CI 对照

| 项目 | HyperMusicCover | HyperPods-X 现状 |
| --- | --- | --- |
| 日常 CI | `ci.yml`：push 到 main、每个 PR、手动触发；改动只有 `*.md` / LICENSE 时跳过；同一分支有新推送就取消旧的运行 | **没有。** 只有 `build-release.yml`，只在打 tag 或手动运行时触发 |
| CI 内容 | debug + release 两个变体都编译，跑 `testDebugUnitTest` 和 `lintDebug`，上传 debug APK 和 lint 报告 | 发布时才跑 `:app:testDebugUnitTest :core:protocol:test :app:lintDebug :app:assembleDeviceTest`，**漏了 `:core:wire` 的测试** |
| Gradle 缓存 | `gradle/actions/setup-gradle`（依赖缓存 + 配置缓存 + 构建摘要） | 只有 `setup-java` 的 `cache: gradle` |
| SDK | `android-actions/setup-android`，只装 platform-tools，platform 37 由 AGP 自己下载 | 手动 `sdkmanager --install 'platforms;android-37.0' 'build-tools;37.0.0'`，可以照用 |
| 版本号 | tag 是版本的唯一来源：`versionCode = (主*10000 + 次*100 + 修订) * 1000`，通过 `-P` 参数传给 Gradle | `versionName`/`versionCode` 手写在 `app/build.gradle.kts`，`package_release.py` 只检查 tag 和源码是否一致 |
| 发布说明 | 优先取附注 tag 的说明，没有就用提交标题 | `docs/releases/<tag>.md`，**保留这个做法** |
| R8 存活检查 | 解压 APK，确认 `java_init.list` 里有入口类、`scope.list` 里有作用域 | **没有**，目前只校验签名证书 |
| 签名 | 正式 keystore；nightly 没有 Secret 时退回 debug 签名并在说明里警告 | 项目测试签名，核对证书 SHA-256 指纹，**比 MusicCover 严，保留** |
| Action 版本 | `@v4` 浮动标签 | 固定到提交 SHA，**保留** |
| nightly | `nightly.yml`：每天 02:00（北京时间），main 没变就跳过；预发布版本号排在上一个正式版之后；只保留最近 7 个 | 没有 |
| 推送 | `telegram.yml`：发版后推送到 Telegram 群，正式版置顶 | 没有 |
| fork 保护 | 发布类 job 带 `if: github.repository == 'zyl6932/HyperMusicCover'` | 没有 |

HyperPods-X 特有的难点：Glasmio 用源码 composite build，需要 NDK 27.1.12297006 和 CMake 3.22.1，冷启动构建很慢（发布工作流的超时设成了 35 分钟）。日常 CI 要靠缓存，并且不下载字体（`prepare-ui.py` 不带 `--fonts`，构建会回退到系统字体）。

### 0.2 代码与构建

- 没有 `.editorconfig`，也没有格式化或静态检查工具。Kotlin 文件风格差异很大，比如 `SpatialAudio.kt` 是压成一行的写法，`ConversationBridge.kt` 是标准格式；hook 是 Java。
- lint 最近一次记录是 0 错误、18 条提醒（阶段 4–6 提交），没有 baseline。
- 没有 `gradle/libs.versions.toml`，依赖版本写在各模块的构建脚本里。`hook` 和 `core:wire` 的 android 配置块（compileSdk 37、Java 21）重复。`app/build.gradle.kts` 里放着两个自定义 Task 类。
- app 源码里有 27 个文件、约 785 处硬编码中文字符串，`strings.xml` 基本是空的。设备逻辑层直接生成提示文字，例如 `SpatialAudio.notice("请将此耳机设为音频输出…")`。
- UI 直接读写 `DeviceRepository` 单例。阶段 7 已经把两套界面的状态推导合并成一份。
- 测试集中在 `core:protocol`（101 项）、`core:wire`（9 项）和 app 的 UI 状态（98 项）。`ControlSession`、`ConversationController`、`SpatialAudio`、`HubHost` 没有测试。

### 0.3 Hub 迁移的遗留项

- 小组件不显示耳机的充电图标（ROM 的 provider 只有电量列，绘制方是桌面和负一屏运行时加载的代码，尚未定位；用户决定暂不处理）。
- 验收第 4 项（远离 70 秒隐藏、3 分钟降频）、第 10 项（热重载后没有残留的扫描登记和重复接收器）、第 14 项（熄屏 30 分钟功耗）没有上机验证。

---

## 1. 计划表

"本地构建"一列：**是** 表示要在本机编译、跑单测或 lint，开始前先问用户。

**进度（2026-10-29）：** 分支 `ci/pr-checks` 已提交以下各项，等合并后按各行的"验收"一列验证：

- **A1**：`ci.yml` 已加，按计划执行。
- **A3**：`:core:wire` 的测试已由上游 0.5.0 补上。R8 存活检查写成 `.github/scripts/check_module_entry.py`：APK 里 `META-INF/xposed/` 下的文件要和 hook 源码逐字一致，`java_init.list` 中的每个类都要以原名出现在 dex 里。已用本地已有的 deviceTest APK 验证通过；把入口类改名后的 APK 会被拒绝。
- **A5**：发布 job 限定在 `YeFeng233/HyperPods-X`（第 3 节第 1 项）。
- **A8**：先只覆盖 github-actions，每周一个合并的 PR；Gradle 部分等 C1 完成。
- **B1**：`.editorconfig` 已加，没有重排现有文件。
- **D4**：已写进 [文档索引](../README.md) 的代码约定一节。

### A. CI 与发布

| 编号 | 事项 | 内容 | 依赖 | 本地构建 | 验收 |
| --- | --- | --- | --- | --- | --- |
| A1 | 新增 `ci.yml` | 触发条件：push 到 main、PR、手动；忽略 `**.md`、`docs/**`、LICENSE。`concurrency` 设为取消旧运行，`permissions: contents: read`。步骤：checkout → setup-java 21 → setup-gradle → sdkmanager 装 platform 37 / build-tools 37 → `prepare-ui.py`（不带字体）→ 运行 `:core:protocol:test :core:wire:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。上传 lint 报告和测试报告（`if: always()`）。**不上传 APK**：CI 的 debug 签名和已安装的测试包不同，装不上 | — | 否 | 开一个只改文档以外内容的 PR，CI 绿；改出一个失败的测试，CI 红 |
| A2 | CI 缓存 Glasmio | 缓存 `.reference/Glasmio` 的固定提交（以 `prepare-ui.py` 里固定的提交号作为缓存键）和 NDK/CMake 目录 | A1 | 否 | 第二次运行的耗时明显短于第一次，记录两次耗时 |
| A3 | 发布工作流补漏 | `build-release.yml` 加上 `:core:wire:testDebugUnitTest`；新增"R8 存活检查"：解压 deviceTest APK，确认 `META-INF/xposed/java_init.list` 包含 `com.hyper.podsrx.hook.HyperPodsModule`，`scope.list` 包含四个作用域（settings、xiaomi.bluetooth、milink.service、securitycenter） | — | 否 | 手动运行一次发布工作流（`publish_release=false`），检查通过 |
| A4 | 版本号由 tag 推导 | 照搬 MusicCover 的公式：`versionCode = (主*10000 + 次*100 + 修订) * 1000`。0.4.10 → 410000，大于现在的 20，可以直接覆盖安装。`app/build.gradle.kts` 改为读 `-PhpxVersionName` / `-PhpxVersionCode`，本地默认值仍是 `x.y.z-dev`。`package_release.py` 改为检查 APK 里的版本和 tag 一致。`-rN` 修订版怎么办见第 3 节 | A3 | 否 | 打一个测试 tag 跑发布工作流（不发布），APK 的 versionCode 符合公式 |
| A5 | fork 保护 | 发布 job 加 `if: github.repository == '<发布仓库>'`，仓库名见第 3 节 | — | 否 | 在 fork 里手动运行发布工作流，job 被跳过 |
| A6 | nightly（可选） | 照搬 `nightly.yml`：main 没变就跳过；版本号 = 上一个正式版的 code + 距离它的提交数 + 1；versionName 带 `-nightly.日期.短提交号`；用同一套测试签名；只保留最近 7 个 | A4 | 否 | 手动触发两次：第一次发布，第二次因为没有变化而跳过 |
| A7 | Telegram 推送（可选） | 照搬 `telegram.yml`，需要 `TG_BOT_TOKEN` / `TG_CHAT_ID` 两个 Secret | A6 或单独做 | 否 | 手动重发一个已有版本 |
| A8 | 依赖更新提醒 | 加 `.github/dependabot.yml`，覆盖 github-actions 和 gradle，每周一次 | C1 | 否 | 出现第一条 dependabot PR |

### B. 代码规范

| 编号 | 事项 | 内容 | 依赖 | 本地构建 | 验收 |
| --- | --- | --- | --- | --- | --- |
| B1 | `.editorconfig` | UTF-8、LF、4 空格缩进，行宽 120；Kotlin 用 ktlint 的 `android_studio` 风格 | — | 否 | IDE 打开后缩进和换行符正确 |
| B2 | lint baseline | 三个 Android 模块的 `lint {}` 设置 `baseline`、`abortOnError = true`、`checkDependencies = true`，生成 baseline 记下现有的提醒 | — | **是** | `lintDebug` 通过；新加一条提醒时失败 |
| B3 | 接入 spotless | Kotlin 用 ktlint，Java 用 google-java-format 的 AOSP 风格（4 空格，和现有 Java 一致），`*.gradle.kts` 也一起格式化。根目录统一配置 | B1, C1 | **是** | `spotlessCheck` 能跑 |
| B4 | 一次性全量格式化 | 单独一个提交，只跑 `spotlessApply`，不改任何逻辑；把这个提交的 SHA 写进 `.git-blame-ignore-revs`，避免 blame 被格式化提交淹没。**最好等 E 线的大改完成后再做，或者在 E 开始前做，不要在中途做**，否则会和迁移改动大量冲突 | B3 | **是** | 单测全过；`git diff -w --stat` 显示只有空白和换行变化 |
| B5 | CI 检查格式 | `ci.yml` 加 `spotlessCheck` | A1, B4 | 否 | 格式不对的 PR，CI 失败 |

### C. 构建配置

| 编号 | 事项 | 内容 | 依赖 | 本地构建 | 验收 |
| --- | --- | --- | --- | --- | --- |
| C1 | 版本目录 | 新建 `gradle/libs.versions.toml`，把 AGP、Kotlin、Compose BOM、Miuix、Glasmio、libxposed、coroutines、JUnit、Robolectric 等版本都移进去，构建脚本改用 `libs.*` | — | **是** | `assembleDebug` 通过，依赖树不变（`dependencies` 输出前后对比） |
| C2 | `build-logic` | 新建 `build-logic` 包含构建：把 `GenerateIslandEarbuds`、`GenerateHyperPodsFontAssets` 两个 Task 移进去；加一个 `hyperpods.android.library` 约定插件，统一 compileSdk 37、minSdk 37、Java 21，供 `hook` 和 `core:wire` 使用 | C1 | **是** | 生成的岛图标、字体资源和改动前逐字节一致 |

### D. 代码结构（和 E 线一起推进）

| 编号 | 事项 | 内容 | 依赖 | 本地构建 | 验收 |
| --- | --- | --- | --- | --- | --- |
| D1 | 文案进资源文件 | 两步走：① UI 层的字符串移进 `strings.xml`；② 设备逻辑层（`SpatialAudio`、`ConversationController`、`ControlSession` 等）的 `notice` / `status` 改成枚举或错误码，由 UI 翻译成文字。迁进 Hub 的代码直接按 ② 写，HubWire 只传错误码 | ② 跟着 E 线做 | **是** | `grep` app 的 Kotlin 源码，不再有中文字符串字面量（日志除外）。lint 的 `HardcodedText` 只检查 XML，查不到 Compose 里的字符串，所以这里用 grep 验收 |
| D2 | ViewModel 层 | 加 `DeviceViewModel`，UI 只通过它读状态、发操作，不再直接引用 `DeviceRepository`。迁完 E 线以后，数据源从 `DeviceRepository` 换成 `HubClient`，只改 ViewModel | E 线完成后 | **是** | `ui/` 下不再 import `DeviceRepository` |
| D3 | 状态机下沉、补测试 | 把 `ControlSession` 的重连和看门狗决策、`ConversationController` 的状态转换、`SpatialAudio` 的模式切换规则抽成纯 Kotlin，放进 `core:protocol`，时间由调用方传入，补单测。做法和已有的 `ControlCommands`、`ConversationPolicy` 一样 | 跟着 E 线逐项做 | **是** | 每个状态机都有对应的测试类 |
| D4 | hook 的语言约定 | hook 里只写 Java 胶水代码（注册回调、调用系统接口、`ReloadLifecycle`）；逻辑一律写在 `core` 的 Kotlin 里。把这条写进 `docs/README.md` 的约定一节 | — | 否 | 代码审查时按这条检查 |

### E. AACP 运行时迁入 Hub（阶段 8）

目标：强杀 app 以后，入耳暂停、对话感知降音量、原生面板切降噪照样能用；app 只负责界面、空间音频试听、校准界面、root 重启宿主和日志导出。每一步开始前先写详细计划书 `docs/plans/AACP-HUB-PLAN.md`，经用户确认后再动手。

| 编号 | 事项 | 内容 | 依赖 | 本地构建 | 验收 |
| --- | --- | --- | --- | --- | --- |
| E0 | 先清 Hub 迁移的遗留项 | 上机验证验收第 4、10、14 项。小组件充电图标暂不处理 | — | 否（只上机） | 三项验收通过，结果写进测试记录 |
| E1 | 取证并写计划书 | 确认 uid 1002 下打开 BR/EDR L2CAP PSM 0x1001 的方法（隐藏构造函数在系统应用里是否可直接调用）；确认 Hub 自己持有通道以后，如何处理 ROM 的 `ConnectL2cap`（现在的做法是 app 存活时答复不支持）；设计 HubWire 新增的控制状态字段和版本号 | E0 | 否 | 计划书经用户确认 |
| E2 | AACP 会话和控制命令 | Hub 持有 AACP 会话（连接、握手看门狗、重连退避、写后回读），复用 `core:protocol` 里的 `Aacp`、`ControlCommands`；原生面板的命令直接在 Hub 里执行，不再经 PendingIntent 绕回 app；删除 app 的 `ControlSession` 和 `ClassicTransport` | E1 | **是** | 强杀 app 后原生面板能切降噪；降噪、自适应、EQ 写入和回读跟迁移前一致 |
| E3 | 入耳联动 | `EarDetectionController` 移进 Hub，在 Hub 里发媒体键；只恢复自己暂停的媒体这条规则不变 | E2 | **是** | 强杀 app 后摘下一只耳机暂停、戴回继续 |
| E4 | 对话感知 | `ConversationController` 的逻辑移进 Hub，直接调用同一进程里的 `ConversationAudioHost`；删除 `ConversationBridge` 和 `ConversationWire` | E2 | **是** | 强杀 app 后说话时降音量，结束后恢复；没有残留的 VolumeShaper |
| E5 | 头部追踪 | 姿态数据在 Hub 内直接交给 `SpatialHost`，不再经过 app；校准和试听的界面留在 app，通过 HubWire 下指令；删除 `SpatialWire` 的姿态转发 | E2 | **是** | 动态头追、回中、摘下清理都正常；端到端延迟不高于迁移前 |
| E6 | 收尾 | app 不再申请 L2CAP 相关能力；删除 `HiddenApiBypass` 依赖（如果已经没有其他用途）；更新 README 的"项目结构" | E2–E5 | **是** | `dumpsys` 中 app 不持有任何 L2CAP 通道 |

---

## 2. 里程碑

| 里程碑 | 包含 | 特点 |
| --- | --- | --- |
| M1 工程底座 | A1、A2、A3、A5、B1、B2、C1 | 纯配置，不改运行行为，风险最低。完成后每次推送都有自动检查 |
| M2 发布与构建整理 | A4、C2、A6、A7、A8 | 版本号和构建脚本的改动，需要跑一次发布工作流验证 |
| M3 AACP 迁移 | E0–E6，同时做 D1②、D3 | 改动最大，按 E 的顺序逐步出包，每步单独上机验收 |
| M4 收尾 | B3、B4、B5、D1①、D2、D4 | 全量格式化放在 E 线之后，避免和迁移改动冲突 |

B3、B4 也可以提到 M3 之前做，代价是 M1 之后先停下来做一次全量格式化；好处是 E 线的新代码一开始就按统一格式写。默认按上表放在 M4。

## 3. 待用户决定

1. ~~**发布仓库。**~~ 已定为 `YeFeng233/HyperPods-X`（upstream，README 的 Releases 链接指向这里）。A5 已按此设置，A6 的 nightly 也绑定它。
2. **`-rN` 修订版。** 已经发过 `v0.4.10-r1`。MusicCover 的公式只认 `vX.Y.Z`。建议以后不再用 `-rN`，有修订就升修订号（0.4.11）。如果要保留，可以规定 `-rN` 的 versionCode 为 `基数 + 900 + N`，nightly 限制在 `基数 + 1` 到 `基数 + 899` 之间。
3. **nightly 和 Telegram 要不要做**（A6、A7）。
4. **全量格式化的时机**（第 2 节末尾）。
