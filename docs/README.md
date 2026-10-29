# 文档索引

更新日期：2026-10-29。

## 目录约定

| 位置 | 放什么 |
| --- | --- |
| `docs/` 根目录 | 长期有效的**说明**和**记录**：功能怎么用、适配细节、真机基线、测试记录、发布流程。内容随代码更新 |
| `docs/plans/` | **正在进行**的计划书。每份开头写清版本、日期和状态 |
| `docs/archive/` | **已完成或已被取代**的计划和分析。只在开头补一段完成情况和后续去向，正文保持原样，不再更新 |
| `docs/releases/` | 每个版本的发布说明，文件名和 tag 一致（如 `v0.4.10.md`），发布工作流直接读取 |

计划完成后移进 `archive/`，在开头注明完成提交和遗留项的去向，并在本页更新。真机结果写进 [联调报告](TEST-REPORT.md)，不写在计划书里。

`ARTWORK-SOURCES.md` 和 `UI-STYLES.md` 的路径被 `THIRD_PARTY.md`、应用内许可页（`app/src/main/assets/licenses/`）和 `settings.gradle.kts` 引用，**不要移动**。

## 说明

| 文档 | 内容 |
| --- | --- |
| [MIUIX-UI-GUIDELINES.md](MIUIX-UI-GUIDELINES.md) | 适配 HyperPods 实机架构的 miuix 页面与组件规范 |
| [UI-STYLES.md](UI-STYLES.md) | miuix 与 iOS 两套界面的切换方式，以及 Glasmio 源码和字体的构建准备 |
| [NATIVE-POPUP.md](NATIVE-POPUP.md) | HyperOS 原生耳机弹窗、超级岛的复用方式，以及开盖检测与弹窗时序 |
| [NATIVE-FUSION.md](NATIVE-FUSION.md) | 融合设备中心（设备互联）耳机卡片的适配 |
| [BLUETOOTH-SETTINGS.md](BLUETOOTH-SETTINGS.md) | 系统蓝牙列表中耳机卡片箭头直达模块设置 |
| [LOGS.md](LOGS.md) | 日志导出的内容与格式 |
| [RELEASING.md](RELEASING.md) | 构建与发布工作流、签名 Secret 的维护 |
| [ARTWORK-SOURCES.md](ARTWORK-SOURCES.md) | 耳机图片的来源、取得日期和哈希 |

## 记录

| 文档 | 内容 |
| --- | --- |
| [DEVICE-BASELINE.md](DEVICE-BASELINE.md) | 目标真机的型号、系统版本、Root 与 LSPosed 环境 |
| [TEST-REPORT.md](TEST-REPORT.md) | 按版本记录的真机联调结果，最新内容在末尾 |
| [SPATIAL-AUDIO-TEST.md](SPATIAL-AUDIO-TEST.md) | 空间音频与动态头追的实现和验证记录 |

## 进行中的计划

| 文档 | 状态 |
| --- | --- |
| [plans/ENGINEERING-PLAN.md](plans/ENGINEERING-PLAN.md) | 工程规范化：CI、代码规范、构建配置、代码结构，以及 AACP 运行时迁入 Hub（阶段 8）。M1 的 A1、A3、A5、B1 和 A8 的 Actions 部分已提交，待合并 |
| [plans/CONVERSATION-AWARENESS-PLAN.md](plans/CONVERSATION-AWARENESS-PLAN.md) | 对话感知：独立音轨降音的听感已通过，轻声识别灵敏度、后台和中断恢复待验收。迁入 Hub 的部分见工程规范化计划 E4 |

## 归档

| 文档 | 说明 |
| --- | --- |
| [archive/HOST-HUB-MIGRATION-PLAN.md](archive/HOST-HUB-MIGRATION-PLAN.md) | 蓝牙宿主状态中心迁移，阶段 0–7 已于 2026-10-29 完成 |
| [archive/SPATIAL-AUDIO-FEASIBILITY.md](archive/SPATIAL-AUDIO-FEASIBILITY.md) | 0.2.0 阶段的空间音频可行性分析，已由 SPATIAL-AUDIO-TEST.md 取代 |
| [archive/HyperPods-X-Development-Proposal-v0.1.md](archive/HyperPods-X-Development-Proposal-v0.1.md) | 项目最初的开发方案书（2026-10-22） |

## 代码约定

- hook 模块里只写 Java 胶水代码：注册回调、调用系统接口、接入 `ReloadLifecycle`。判断和状态机写成纯 Kotlin，放进 `core:protocol`，时间由调用方传入，便于单测。
- app 和各宿主进程之间的协议常量、Bundle 编解码只放在 `core:wire`。
