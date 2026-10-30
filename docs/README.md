# 文档索引

本目录收录 HyperPods-X 的架构设计、技术实现细节、真机适配基线与维护流程文档。

---

## 目录结构

| 目录 / 路径 | 说明 | 维护原则 |
| --- | --- | --- |
| `docs/` 根目录 | 长期有效的**功能技术方案**、**系统接入说明**与**测试记录** | 内容随代码演进实时更新 |
| `docs/plans/` | 正在推进中的**开发计划与重构方案** | 明确标记目标版本与当前进展 |
| `docs/archive/` | 已完成或被新方案替代的**历史规划与可行性分析** | 归档保留，仅在文首注明完成状态与去向，正文不再变更 |
| `docs/releases/` | 各版本**发布说明（Release Notes）** | 文件名严格与 Git Tag 对应（如 `v0.5.0.md`），供 CI 流水线直接提取 |

> **路径约束**：`ARTWORK-SOURCES.md` 与 `UI-STYLES.md` 被项目内多处配置（`THIRD_PARTY.md`、应用内置许可资源、`settings.gradle.kts`）直接引用，请勿移动或重命名。

---

## 核心技术文档

| 文档 | 说明 |
| --- | --- |
| [NATIVE-POPUP.md](NATIVE-POPUP.md) | HyperOS 原生耳机底部弹窗、超级岛对接方案与开合盖检测时序 |
| [NATIVE-FUSION.md](NATIVE-FUSION.md) | HyperOS 控制中心“融合设备中心”（设备互联）耳机卡片适配 |
| [BLUETOOTH-SETTINGS.md](BLUETOOTH-SETTINGS.md) | 系统蓝牙列表中耳机卡片设置直达逻辑 |
| [WIDGET.md](WIDGET.md) | 桌面电量小组件：系统组件图标不可替换的原因、快照推送通路与组件规格 |
| [UI-STYLES.md](UI-STYLES.md) | miuix 与 iOS（Glasmio）双风格切换机制及字体、渲染依赖准备 |
| [MIUIX-UI-GUIDELINES.md](MIUIX-UI-GUIDELINES.md) | 项目内遵循的 Miuix 页面与组件规范 |
| [LOGS.md](LOGS.md) | 统一诊断日志结构、脱敏机制与导出说明 |
| [RELEASING.md](RELEASING.md) | 构建环境准备、自动化流水线与签名密钥维护说明 |
| [ARTWORK-SOURCES.md](ARTWORK-SOURCES.md) | 耳机外观素材来源、哈希校验与版权说明 |

---

## 测试与基线记录

| 文档 | 说明 |
| --- | --- |
| [DEVICE-BASELINE.md](DEVICE-BASELINE.md) | 目标真机基线环境：系统指纹、Root / LSPosed 环境与宿主版本 |
| [TEST-REPORT.md](TEST-REPORT.md) | 各版本真机联调验收与回归测试记录 |
| [SPATIAL-AUDIO-TEST.md](SPATIAL-AUDIO-TEST.md) | 空间音频、姿态采集与动态头部追踪系统接入记录 |

---

## 开发计划与历史归档

- **进行中的计划**：
  - [plans/ENGINEERING-PLAN.md](plans/ENGINEERING-PLAN.md)：工程规范化、代码结构优化与 AACP 迁移
  - [plans/CONVERSATION-AWARENESS-PLAN.md](plans/CONVERSATION-AWARENESS-PLAN.md)：对话感知独立音轨调节与复杂场景优化
- **历史归档**：
  - [archive/HOST-HUB-MIGRATION-PLAN.md](archive/HOST-HUB-MIGRATION-PLAN.md)：蓝牙宿主状态中心迁移方案（0.5.0 已完成）
  - [archive/SPATIAL-AUDIO-FEASIBILITY.md](archive/SPATIAL-AUDIO-FEASIBILITY.md)：早期空间音频可行性调研
  - [archive/HyperPods-X-Development-Proposal-v0.1.md](archive/HyperPods-X-Development-Proposal-v0.1.md)：项目初期立项方案（2026-10-22）

---

## 模块设计约定

- **Hook 层最小化**：`hook` 仅保留宿主内的 Java 胶水逻辑（系统接口挂接、回调分发与生命周期管理）；所有业务状态判断与协议解析统一使用 Kotlin 沉淀至 `core:protocol`，以便开展纯 JVM 单元测试。
- **IPC 契约解耦**：App 界面与各系统宿主之间的通信协议、常量和序列化数据包统一在 `core:wire` 维护。
