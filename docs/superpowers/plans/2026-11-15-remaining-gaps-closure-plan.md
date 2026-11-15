---
title: 剩余缺口收口实施计划
date: 2026-11-15
design: docs/superpowers/specs/2026-11-15-remaining-gaps-closure-design.md
status: W1.1–W1.4 完成；下一切片 W2.1
---

# 剩余缺口收口实施计划

依据：`docs/superpowers/specs/2026-11-15-remaining-gaps-closure-design.md`（`ca06d81`）。
顺序：W1 → W2 → W3；W4 贯穿。

## 验证环境

- 清 `JAVA_TOOL_OPTIONS` / `JDK_JAVA_OPTIONS` / `_JAVA_OPTIONS`
- `JAVA_HOME=D:\Android Studio\android studio\jbr`
- `GRADLE_USER_HOME=D:\Android Studio\HANXIAO\.gradle`
- Gradle：`D:\Android Studio\HANXIAO\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat`
- 任务：`:shared:jvmTest :app:assembleDebug checkAdDeps`；Migration 相关加 `--rerun-tasks`

## W1.1 ticket_image + 4→5 迁移

状态：完成（`ca3d62e`）。

- [x] `Ticket.sq` + `migrations/4.sqm` + `version=5`
- [x] `Clear.sq` / `Seed.clearLocalData` 补删
- [x] `v4.sql` fixture + `MigrationTest` 升到 v5
- [x] `:shared:jvmTest --tests MigrationTest --rerun-tasks` 绿

## W1.2 账户间调拨独立页

状态：完成（提交号见提交表）。

- [x] `AssetsPage` 路由 `asset-transfer`；列表顶栏「调拨」
- [x] `TransferPage`：from/to/金额/备注 + 二次确认 → `TYPE_TRANSFER` + `saveWithBalance`
- [x] from==to / 金额非法当场提示；不足两账户给「去新建」
- [x] 复用既有 `BalanceTest.transferMovesBetweenCards`；`:shared:jvmTest :app:assembleDebug checkAdDeps` 绿

## W1.3 OCR 多选 + 缩略图 + ticket_image 写入

状态：完成（提交号见提交表）。

- [x] `captureTicket(..., imagePath, thumbPath)` 写 `ticket_image`；重复事件可补图
- [x] `confirm` 拼 `Bill.images` 逗号路径；Pending「有图 N」标记（本切片不渲染位图）
- [x] `PickMultipleImagesButton`（Android `PickMultipleVisualMedia` / jvm 占位）
- [x] OcrPage 多选每图各调一次；粘贴/手动 `imagePath=null`
- [x] AutoTicketTest 补 images / 重复补图；`:shared:jvmTest :app:assembleDebug checkAdDeps` 绿（239 用例）

## W1.4 AcEvents 目录 + 本地上报队列

状态：完成（提交号见提交表）。

- [x] `AcEvents`：probe 的 123 个 `ac_*` + 在用 4 个 = 127；`isAllowed` 放行 `feedback` / `token_refresh`
- [x] `AccountRepository(db, sync?)`：未知 kind → null 不写库；合法 kind 写 `behavior_event` 并入队 `Behavior/Add`
- [x] `Bills` / `Auto` 私有 track 删除，统一走 `AccountRepository(db, sync).track`
- [x] `AppGraph.sync` 接到 `AutoRepository` / `CaptureBridge` / `ScreenCaptureService` / UI 各入口
- [x] 测试：未知 kind 拒绝、Behavior/Add 入队、`remoteEnabled=false` 仍入队、feedback/token 放行；243 用例绿
- [x] `:shared:jvmTest :app:assembleDebug checkAdDeps` 绿

## W2.1 生物识别解锁 + 口令回退

状态：完成（`a5b5e02`）。

- [x] `EntryHost.biometricAvailable()` / `requestBiometricUnlock()`；API 28+ 平台 `BiometricPrompt`
- [x] 取消 / 失败 / 无硬件回退四位口令；`LockGate` 保留「用指纹 / 面容解锁」按钮
- [x] 设置页可选「指纹 / 面容 + 口令」或「仅口令」，默认允许
- [x] `SettingsTest` 覆盖默认值与持久化；243 用例 + `assembleDebug` + `checkAdDeps` 绿

## W2.2 七小组件种类 + 系统配置入口

状态：完成（`a93fc2f`）。

- [x] `WIDGET_KINDS` 扩到 7 种：本月收支 / 预算剩余 / 存钱进度 / 总资产 / 今日速记 / 存钱计划 / 待确认票据
- [x] `WidgetConfigActivity` + `WidgetConfigScreen`：系统添加组件时选种类/尺寸，按 `appWidgetId` 绑定 `Widget_user`
- [x] `AppWidgetReceiver` 按绑定种类渲染；`onDeleted` 清理绑定与本地行
- [x] 计数类 `WidgetPreview.firstMoney/secondMoney=false`，不按金额格式化
- [x] `WidgetConfigTest` 覆盖 7 种、绑定/重配/解绑、计数语义；246 用例 + `assembleDebug` + `checkAdDeps` 绿

## W2 / W3 / W4

见 design §1–§5；本文件按切片追加验证记录与提交号。

## 提交表

| 切片 | 提交 | 验证 |
|---|---|---|
| W1.1 | `ca3d62e` | MigrationTest --rerun-tasks 绿 |
| W1.2 | `6d353fe` | jvmTest + assembleDebug + checkAdDeps 绿 |
| W1.3 | `1959120` | 239 用例 + assembleDebug + checkAdDeps 绿 |
| W1.4 | `308d4ef` | 243 用例 + assembleDebug + checkAdDeps 绿 |
| W2.1 | `a5b5e02` | 243 用例 + assembleDebug + checkAdDeps 绿 |
| W2.2 | `a93fc2f` | 246 用例 + assembleDebug + checkAdDeps 绿 |
| W1.3 | `1959120` | jvmTest 239 + assembleDebug + checkAdDeps 绿 |
| W1.4 | `308d4ef` | jvmTest 243 + assembleDebug + checkAdDeps 绿 |
