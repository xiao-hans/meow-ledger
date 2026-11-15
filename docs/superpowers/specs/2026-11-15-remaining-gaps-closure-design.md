---
title: 剩余缺口收口设计
date: 2026-11-15
status: 设计中
---

# 剩余缺口收口设计

## 背景

`docs/功能差距表.md` §7（2026-11-15 对账）把剩余工作分成代码缺口与验证缺口。用户确认全量推进：A 类本地能力全部落地；B 类按可替换网关 + 沙箱，真实凭证/服务端联调标记「未验」；订正文档漂移。支付 / VIP / 广告 / 私有加密 / 远程厂商推送按长期记忆与 `AGENTS.md` §10 不实现。

本设计覆盖一次总体方案；实施按 W1→W2→W3 推进，W4 文档贯穿。SQLDelight 当前 `kmp/shared/build.gradle.kts:66` `version = 4`，本轮全部表变更集中写一份 `4.sqm` 并升级 `version = 5`。

## 权威来源与边界

- 差距表 §7：`docs/功能差距表.md:159-173`。
- 长期记忆 `works/preferences/free-unlock-all-features.md`：不支付；推送只保留本地通知；远程同步与埋点不是功能前置条件；MediaProjection 每次同意；生物识别按硬件降级；权限首次只自动弹一次，拒绝不阻塞手工记账。
- 真机验收纪律：走真实操作路径，禁止 `adb shell input text` / `keyevent` 注入当输入验收。
- 接口权威：`spec/endpoints.json`；本轮不新增业务端点。埋点复用 `Behavior/Add`；调拨复用 `TYPE_TRANSFER=3` 与既有账单写路径；OCR/上传复用现有 Upload / Import 契约字段名。
- 硬约束：`checkAdDeps` 通过；不接私有加密 / 广告 SDK / 支付商户 / 个推与 4 厂商通道 / 一键登录真实 SDK。

## 1. 架构与工作包

### 依赖方向（不变）

```
app (Manifest / Service / Receiver / Biometric / MediaProjection)
  └─ ui (Compose 页 / 导航 / 灯影 token)
       └─ shared (Repository / SQLDelight / SyncEngine / 网关接口)
```

UI 不直接碰 DB；Android 组件通过 `shared` repository 读写。

### 四工作包

| 包 | 内容 | DoD 摘要 |
|---|---|---|
| W1 数据与业务 | 调拨独立页；OCR 多选批量解析 + 缩略图 + 票据图持久化；154 `ac_*` 事件目录 + 本地上报队列；`4.sqm`→`version=5` | jvmTest 绿；迁移 v1/v2/v3/v4→v5 回归；无新端点 |
| W2 Android 能力 | 生物识别可选解锁回退 PIN；本地通知（已有，补链路）；7 小组件配置入口；截屏每次同意 / 深链 / 扫码 / 权限拒绝回退；`LoginGateway` / `PushGateway` / `SyncGateway` 接口 + 沙箱 + 可配置基地址 | assembleDebug；权限拒绝不阻塞手工记账；`checkAdDeps` |
| W3 验证 | `androidTest` 覆盖 DB/通知/DeepLink/Compose/失败回退；vivo 真机真实路径；其它品牌 ROM 与权限矩阵如实记录 | 仪器测试可跑；真机证据写入计划表 |
| W4 文档 | 差距表 §7、页面对照、接口对照、计划、README；订正扫码/邀请码漂移；外部项写「按设计边界不实现，真实凭证/服务端未验」 | 与代码一致；commit + push |

顺序：W1→W2→W3；W4 贯穿。schema 变更只发生在 W1。

### 文档漂移订正（W4）

- 「扫码加入与邀请码 UI」：代码已有系统扫码失败回退 `book-join` 与邀请码粘贴加入（`MainActivity` / `Books.kt`）。差距表改为「待真机 E2E」，不再写「代码缺口」。
- 「个推与 4 厂商通道」：改为「按设计边界不实现；本地 `AlarmManager`/`NotificationManager` 已覆盖账单提醒」。
- 「一键登录」：改为「`LoginGateway` 沙箱占位；真实运营商 SDK 未接，服务端未验」。

## 2. 组件与数据流

### 2.1 账户间调拨（W1）

- 新页 `TransferPage`，路由挂在 Mine→资产→调拨（或资产详情入口），不塞进记一笔三态切换凑合用。
- 交互：选 from / to 资产 + 金额（受控字符串）+ 可选备注 → 二次确认 → `BillsRepository.saveWithBalance` 写 `TYPE_TRANSFER=3`。
- 复用 `bill.to_assets_id` / `from_assets_id` 与既有余额动作；**不新建表**。
- 空态：无可用资产时给「去新建资产」出口；from==to 或金额非法当场提示，不写库。

### 2.2 OCR 多选 + 缩略图 + 票据图（W1）

- `OcrPage` 支持多选图片；每张图调用扩展后的 `AutoRepository.captureTicket`（或批处理包装），每图可产出 1 条或多条待确认候选（多笔票据按解析结果拆分），**确认前不写账**（对齐交互纪律 §6.7）。
- 原图存 `filesDir/tickets/{hash}.jpg`；缩略图同目录 `{hash}_thumb.jpg`。确认入账时把持久化路径写入 `Bill.images`（路径串，不存二进制）。
- 新增表 `ticket_image(event_id TEXT NOT NULL, path TEXT NOT NULL, thumb_path TEXT NOT NULL DEFAULT '', create_time INTEGER NOT NULL DEFAULT 0)`，主键或唯一约束覆盖 `(event_id, path)`；`auto_event` 本身不加图片列。
- UI：待确认队列展示缩略图；失败项可重试单张，不静默丢图。

### 2.3 埋点目录 + 本地上报（W1）

- 新增只读常量目录 `AcEvents`（覆盖样本侧 100+ / 设计目标 154 个 `ac_*` 名）；`AccountRepository.track(kind, content)` 只接受目录内 kind 或显式允许的扩展 kind。
- 写入路径：`behavior_event` → `SyncEngine.enqueue(path="Behavior/Add", ...)` → `flush`。`remoteEnabled=false` 或 api 为空时只本地积压，不阻塞 UI。
- 不新增端点；不另造网络线程。上报状态复用 `sync_queue.status`（0/1/2）与既有重试策略。

### 2.4 生物识别（W2）

- `BiometricUnlock` 包一层 `BiometricPrompt`；成功打开现有 `LockGate`；失败 / 无硬件 / 用户取消 → **回退 PIN**（`AccountPages` 锁页不变）。
- 权限拒绝不阻塞手工记账；设置里可关生物识别，只留 PIN。

### 2.5 本地提醒（W2，已有为主）

- 保留 `RemindScheduler` + `BillAlarmReceiver`：`bill_remind` → 精确闹钟 → 本地通知 → deep link `bill-edit`。
- 不接个推 / 厂商通道；差距表按 §1 文档漂移订正。

### 2.6 七小组件配置入口（W2）

- **1 个** `AppWidgetReceiver` + **7 个配置路由** `widget-cfg/{slot}`，对齐样本可操作入口（Small / Middle / Large 配置 + Edit / Mine / 尺寸配置等价），不复制混淆类名、不复制 16+ `widget_base` 全表。
- `appWidgetId` ↔ `Widget_user`；预览走 `WidgetRepository.preview`。
- 种类覆盖可操作子集：month / budget / plan / assets / quick / save / ticket；扩展只改 `Widget_user.kind` 与预览数据，不加 VIP 门。
- 文档写清与样本种类的等价映射表；未 1:1 复制的种类标「功能等价 / 未单独实现」。

### 2.7 截屏 / 深链 / 扫码 / 权限回退（W2）

- MediaProjection **每次**用户同意；链路 `ScreenCaptureActivity` → Service → ML Kit → `CaptureBridge` → 待确认队列。
- 系统扫码失败回退邀请码粘贴（已有）；`DeepLinkTarget` + `MainActivity` 系统动作保持。
- `PermissionGateway`：拒绝 → 对应自动能力不可用，手工记账与本地库读写仍可用；首次自动弹一次。

### 2.8 可替换网关（W2）

- 接口：`LoginGateway` / `PushGateway` / `SyncGateway`；默认沙箱实现。
- 基地址来自 `BackendConfig`；`remoteEnabled=false` 时 `SyncEngine` 只入队不发送。
- 真实凭证、运营商一键登录、远程推送、自建后端联调一律标记「未验 / 按设计边界不实现」，不在 DoD 里冒充完成。

### 2.9 迁移（W1）

- 唯一迁移文件本轮：`kmp/shared/src/commonMain/sqldelight/migrations/4.sqm`。
- 内容至少：`CREATE TABLE IF NOT EXISTS ticket_image (...)`；若实现中发现必须补列，一并写入本文件，禁止再开 `5.sqm`。
- `kmp/shared/build.gradle.kts` `version = 5`。
- `MigrationTest`：现有 v1/v2/v3→v4 保留；新增 migrate 到 v5、v4 fixture、结构/数据断言；对「旧库缺列但 version 戳已到高版本」的漂移场景，延续 `3.sqm` 的 IF NOT EXISTS / 安全补列策略（本轮若只需新表则 CREATE IF NOT EXISTS 即可）。

## 3. 错误处理与降级

| 场景 | 行为 |
|---|---|
| 调拨 from==to / 余额不足 / 金额非法 | 当场错误文案；不写库；草稿保留 |
| OCR 单张失败 | 该张标失败可重试；其它张继续；不静默入账 |
| 票据文件写入失败 | 事件仍可进待确认（无图或占位）；提示「图片未保存」；确认时 `Bill.images` 可为空 |
| 生物识别不可用 | 回退 PIN；无 PIN 则走未锁定或引导设置 PIN |
| 通知权限拒绝 | 提醒不弹出；日程仍可在 App 内查看；不反复死循环申请 |
| 精确闹钟被系统限制 | 降级 `setAndAllowWhileIdle`；文档记录 ROM 差异 |
| 远程同步关闭 / 基地址非法 | 只本地积压；设置页提示；UI 不阻塞 |
| 上报 4xx（非 408/429） | 按现有 SyncEngine 丢弃；其它错误按 `retry²×30s`（上限 30min） |
| 小组件配置缺失 | 桌面显示空态 / 占位文案 + 「打开 App 配置」 |
| 扫码组件不存在 | 回退邀请码粘贴页 |
| MediaProjection 用户拒绝 | 截屏采集中止；通知/无障碍/手工入口仍可用 |

危险操作（删除票据文件、清空上报队列、删除小组件配置）必须二次确认并写明后果。

## 4. 测试与验收口径

### 自动化

- `:shared:jvmTest`：调拨余额、OCR 批量入队与确认写 `Bill.images`、`ticket_image` 关联、`AcEvents` 入队、`SyncEngine` 在 `remoteEnabled=false` 时不发送、迁移 v*→v5。
- `:app:assembleDebug` + `checkAdDeps`。
- 新建 `kmp/app/src/androidTest/`：至少覆盖 DeepLink 解析、权限拒绝后手工记账可达、Compose 调拨页/OCR 多选主路径的可仪器化断言（能在模拟器跑的优先；依赖系统 UI 的标手动）。

### 真机（vivo V2419A，真实路径）

- 调拨：两资产间转一笔，余额与账单列表正确。
- OCR：选 ≥2 张图 → 待确认出现对应条数 → 改一条再批量确认 → 账本有图路径/缩略图。
- 生物识别：有硬件则解锁；关闭或失败回退 PIN。
- 本地提醒：建一条提醒 → 到期通知 → 点进 `bill-edit`。
- 小组件：至少一个尺寸添加到桌面，配置路由可改 kind 并刷新。
- 扫码失败回退邀请码；权限拒绝后仍可手工记账。
- MediaProjection：同意后一帧入队；拒绝后其它入口可用。

其它品牌 ROM：能测则记矩阵；不能测则在差距表写「未测」，不写「已实现」。

### 明确不验收为「已完成」的项

- 真实个推 / 厂商推送 / 运营商一键登录 / 支付 / 广告 / 私有加密。
- 自建后端真实响应、错误码、鉴权续期（无动态抓包与联调环境时保持「未验」）。

## 5. 文档与提交节奏

- 每完成一个工作包：更新 `docs/功能差距表.md`、`docs/功能页面对照.md`、`docs/接口实现对照表.md`（无新端点时写「复用现有」）、`docs/superpowers/plans/2026-11-11-kmp-full-replica-plan.md` 追加验证记录与提交号、必要时 README。
- 提交信息中文短述；验证命令通过后再 commit + `git push origin master`。
- 不重写计划表历史里程碑行；只追加。
- 本设计落地后把 frontmatter `status` 改为「已落地」，并在差距表 §7 按实际结果改写，不把「按设计边界不实现」写成「已实现」。

## 非目标

- 不新增 `spec/endpoints.json` 业务路径；不实现样本 `DataNative` 加密。
- 不接 6 家 ADN / 自有 ADX；广告开关保持关闭。
- 不复制原 App 图标、协议 H5、教程视频资产。
- 不把远程同步或埋点成功当作功能可用性前置条件。
- 不一次性复制样本全部 `widget_base` 视觉皮肤；只做可操作功能等价。

## 验收命令（每个工作包）

```text
# 清 JAVA_TOOL_OPTIONS / JDK_JAVA_OPTIONS / _JAVA_OPTIONS
# JAVA_HOME = D:\Android Studio\android studio\jbr
# GRADLE_USER_HOME = D:\Android Studio\HANXIAO\.gradle
# gradle = D:\Android Studio\HANXIAO\.gradle\wrapper\dists\gradle-8.11.1-bin\...\gradle.bat
:shared:jvmTest :app:assembleDebug checkAdDeps
git diff --check
```
