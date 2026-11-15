---
title: 剩余缺口收口实施计划
date: 2026-11-15
design: docs/superpowers/specs/2026-11-15-remaining-gaps-closure-design.md
status: W1.1 完成，W1.2 进行中
---

# 剩余缺口收口实施计划

依据：`docs/superpowers/specs/2026-11-15-remaining-gaps-closure-design.md`（`ca06d81`）。
硬约束：不接支付 / 广告 / 私有加密 / 个推与厂商通道；推送只本地通知；真机验收禁止 adb 注入当输入。

验证环境（Windows）：

```text
清 JAVA_TOOL_OPTIONS / JDK_JAVA_OPTIONS / _JAVA_OPTIONS
JAVA_HOME = D:\Android Studio\android studio\jbr
GRADLE_USER_HOME = D:\Android Studio\HANXIAO\.gradle
gradle = D:\Android Studio\HANXIAO\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat
任务：:shared:jvmTest :app:assembleDebug checkAdDeps
PowerShell 用 @args splat；-D 属性勿加引号以免被当 task
```

## W1.1 · ticket_image + 4.sqm → version=5

状态：完成。

1. 新增 `Ticket.sq`（`ticket_image`：event_id/path/thumb_path/create_time，主键 `(event_id,path)`）。
2. 新增 `migrations/4.sqm`（`CREATE TABLE IF NOT EXISTS ticket_image`）。
3. `kmp/shared/build.gradle.kts` `version = 5`。
4. `Clear.sq` / `Seed.clearLocalData` 补删 `ticket_image`。
5. `MigrationTest`：全部升到 v5；新增 `v4.sql` fixture 与 `v4→v5` 用例；漂移用例断言 `ticketQueries` 可查。
6. 跑 `:shared:jvmTest`；失败不提交。

完成依据：v1/v2/v2_from_v1/v3_drifted/v4 → v5 全绿；全新安装含 `ticket_image`；`git diff --check` 通过。

## W1.2 · 调拨独立页

状态：未开始。

1. `TransferPage`（from/to/金额/备注 + 二次确认）→ `BillsRepository.saveWithBalance`（`TYPE_TRANSFER=3`）。
2. 路由挂资产入口；空态/非法金额/from==to 不写库。
3. jvmTest：余额两侧正确；UI 深浅色过一遍。

## W1.3 · OCR 多选 + 缩略图 + 票据图

状态：未开始。

1. `OcrPage` 多选；每图 `captureTicket`（可多候选）；确认前不写账。
2. 原图/缩略图写 `filesDir/tickets/`；路径入 `ticket_image`；确认入账写 `Bill.images`。
3. jvmTest：批量入队、确认写路径、单张失败可重试。

## W1.4 · AcEvents + 本地上报

状态：未开始。

1. `AcEvents` 常量目录；`AccountRepository.track` 校验 kind。
2. `behavior_event` → `SyncEngine.enqueue("Behavior/Add")`；`remoteEnabled=false` 只积压。
3. jvmTest：入队与不发送。

## W2 · Android 能力

状态：未开始。生物识别回退 PIN；本地提醒链路复核；7 小组件配置路由；MediaProjection 每次同意；深链/扫码/权限拒绝回退；`LoginGateway`/`PushGateway`/`SyncGateway` 沙箱。

## W3 · 验证

状态：未开始。`androidTest` + vivo 真机真实路径；其它 ROM 未测如实记录。

## W4 · 文档

状态：贯穿。差距表 §7、页面对照、接口对照、本计划、README；订正扫码/邀请码与推送漂移。

## 提交记录

| 日期 | 提交 | 切片 | 验证 |
|---|---|---|---|
| 2026-11-15 | `ca06d81` | design | docs only |
| 2026-11-15 | （待填） | W1.1 ticket_image + 4.sqm→v5 | `:shared:jvmTest --tests MigrationTest` 绿 |
