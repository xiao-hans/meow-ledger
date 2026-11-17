---
title: 16 个未实现模块核实收口实施计划
date: 2026-11-17
status: 实施中
---

# 16 个未实现模块核实收口实施计划

## 依据

- 设计：`docs/superpowers/specs/2026-11-17-16-modules-gap-closure-design.md`
- 用户决定：按方案 A 实施（2026-11-17）

## 工作范围

本计划交付三个增量：文档订正、本地回环契约测试、门禁与提交。

## 增量 1：W1 文档订正

**目标**：让差距表 §1、功能页面对照、接口实现对照与主计划反映当前状态。

**验收标准**：

- 16 个模块的状态分为「已实现」「待证据」「边界」三类，与核实结论一致。
- 状态为「待证据」的模块写明缺哪一项验证。
- 状态为「边界」的模块写明按设计不实现哪项外部服务。
- 文档不把代码存在写成验证通过。

**步骤**：

1. 更新 `docs/功能差距表.md` §1 状态与缺口列。
2. 更新 `docs/功能差距表.md` §7 的「真实自建后端」条目。
3. 同步 `docs/功能页面对照.md` 与 `docs/接口实现对照表.md`。
4. 运行 `git diff --check`。

**提交**：文档订正后单独提交。

## 增量 2：W2 本地回环契约测试

**目标**：在不改生产网络配置的前提下，验证客户端能按明文 JSON 契约调用核心端点，并处理错误与断网。

**验收标准**：

- 新增测试文件只放 `kmp/shared/src/jvmTest/`。
- 测试使用 `MockEngine` 绑定本机回环地址，返回统一响应信封。
- 覆盖登录、账单增改查、错误码、断网重试与 remoteEnabled=false。
- 不改 `KtorMeowApi.kt` 的默认基址。
- `:shared:jvmTest` 全部通过。

**步骤**：

1. 读 `KtorMeowApiTest.kt` 与 `SyncQueueTest.kt` 的现有写法。
2. 新增 `LocalBackendContractTest.kt`。
3. 跑 `:shared:jvmTest`。

**提交**：测试通过后单独提交。

## 增量 3：W3 门禁与提交

**目标**：拿到可引用的验证证据并记录提交号。

**验收标准**：

- `:app:assembleDebug` 通过。
- `checkAdDeps` 通过。
- `git diff --check` 通过。
- 主计划记录提交号与验证结果。

**步骤**：

1. 跑门禁。
2. 更新 `docs/superpowers/plans/2026-11-11-kmp-full-replica-plan.md` 与差距表 §7。
3. commit + push。

## 风险与处理

- 本地测试后端只用于 JVM 测试，不写进生产默认值。
- 边界项不用沙箱冒充真实服务。
- 文档状态更正不等于新增功能。
- 任一门禁失败，不提交，先修到通过。

## 当前进度

- [x] 设计通过并推送（`defe8c2`）
- [x] 增量 1 文档订正（提交 `3d145c3`）
- [x] 增量 2 本地回环契约测试（提交号见下方提交记录）
- [x] 增量 3 门禁与提交

## 验证记录（2026-11-17）

- `:shared:jvmTest`：49 套件 / 273 用例 / 0 失败 / 0 错误。新增 `LocalBackendContractTest` 3 个用例覆盖 6 个核心端点的明文 JSON、整数分金额、`client_id` 幂等键、统一信封、业务错误码 `42` 与 `trace_id`、断网退避重试、`remoteEnabled=false` 零外发。
- `:app:assembleDebug`：`BUILD SUCCESSFUL`。
- `checkAdDeps`：通过，无广告 SDK 命中。
- Gradle 命令：`JAVA_HOME='D:\\Android Studio\\android studio\\jbr' ...gradle :shared:jvmTest :app:assembleDebug checkAdDeps --offline --console=plain`（Gradle 8.11.1）。

## 剩余边界

- 真实运营商一键登录、个推与 4 厂商推送、真实支付商户资质：按设计不实现。
- 真实自建后端联调：本地回环契约测试只验证 JVM 侧契约，不代替真实服务端联调。
- UI 全量仪器测试：现有 3 个用例通过，不覆盖 Compose 全量界面。
