---
title: 剩余缺口收口实施计划
date: 2026-11-15
design: docs/superpowers/specs/2026-11-15-remaining-gaps-closure-design.md
status: W1.1–W4 完成；L1（QQ 登录）完成；W3.1 模拟器手工路径已复核
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

状态：完成（`a93fc2f`；2026-11-16 补「添加到桌面」与写账后刷新，模拟器实测挂上桌面）。

- [x] `WIDGET_KINDS` 扩到 7 种：本月收支 / 预算剩余 / 存钱进度 / 总资产 / 今日速记 / 存钱计划 / 待确认票据
- [x] `WidgetConfigActivity` + `WidgetConfigScreen`：系统添加组件时选种类/尺寸，按 `appWidgetId` 绑定 `Widget_user`
- [x] `AppWidgetReceiver` 按绑定种类渲染；`onDeleted` 清理绑定与本地行
- [x] 计数类 `WidgetPreview.firstMoney/secondMoney=false`，不按金额格式化
- [x] `WidgetConfigTest` 覆盖 7 种、绑定/重配/解绑、计数语义；246 用例 + `assembleDebug` + `checkAdDeps` 绿
- [x] `EntryHost.pinWidget` + `AppWidgetManager.requestPinAppWidget`：小组件页「添加到桌面」走系统确认框；预选种类/尺寸写 `user_setting:widget_pin_kind|widget_pin_size`，配置页当默认值读
- [x] `EntryHost.refreshWidgets`：记一笔保存、删账单、调拨写库后立刻刷新桌面，不再只在回前台时刷新
- [x] 模拟器实测：组件选择器可见「喵啊记账」→ 应用内「添加到桌面」→ 系统弹「Add to home screen」→ 点确认后桌面出现组件（支出 0.00）→ 记一笔 12.34 → 桌面立即变「支出 12.34」
- [x] 266 用例 + `assembleDebug` + `checkAdDeps` 绿
- 限制：`adb shell input` 的 `swipe`/`draganddrop`/`motionevent` 都触发不了 Launcher 的拖拽，所以系统组件选择器里的「长按拖到桌面」路径无法自动化；应用内固定按钮是等价且可验证的真实路径。

## W2.3 登录与推送网关沙箱

状态：完成（`ef01067`）。

- [x] `LoginGateway` + `SandboxLoginGateway`：6 位码、5 分钟有效，验证码只留本机
- [x] `AccountRepository(db, sync, login)` 可换网关；`loginByPhone` / 换绑都走 `verify`
- [x] `PushGateway` + `SandboxPushGateway`：`remote=false`，`register()` 明确失败，不伪装可用
- [x] `AccountTest` 覆盖网关替换与沙箱不可用；248 用例 + `assembleDebug` + `checkAdDeps` 绿

## W2.4 票据缩略图渲染

状态：完成（`d46de72`）。

- [x] `TicketThumb` expect/actual：Android 降采样 + 44dp 圆角裁切，JVM 同尺寸占位
- [x] 待确认列表最多展示 3 张缩略图，保留「有图 N」
- [x] `MeowApp` pending 分支只查一次事件列表；248 用例 + `assembleDebug` + `checkAdDeps` 绿

## W3.0 仪器测试与应用启动补列

状态：完成代码与构建（`188b002`）；真机执行结果见下。

- [x] `androidInstrumentedTest` + `AndroidJUnitRunner`；3 个用例：启动、迁移后列存在、深链解析
- [x] 真机复现启动崩溃：历史构建给 `fixed_bill` 加 `not_in_budget` 却没写迁移，版本号已到新值
- [x] `SchemaRepair`：打开库后按列名幂等补列，先于任何仓储查询执行
- [x] vivo V2419A 执行：`am instrument -w -r ...AndroidJUnitRunner` → `OK (3 tests)`（2026-11-15 20:30）
- [x] 真机启动复核：`am start -W` COLD 822ms，落到权限引导页，logcat 无 FATAL / SQLiteException（20:31）
- [x] `:shared:jvmTest :app:assembleDebug :app:assembleDebugAndroidTest checkAdDeps` 绿
- 备注：仪器测试不拉起 Compose 界面——vivo 拦后台 Activity 启动会让 `ActivityScenario` 无限等 RESUMED；改为断言 `AppGraph.database` 已注入，Application 启动链路仍被每个用例覆盖。

## W3.1 模拟器手工路径复核

状态：完成（2026-11-16，`emulator-5554` / AVD `Pixel_9_Pro_API_36`）。

- [x] 调拨：银行卡 5000 / 支付宝 300 之间转 200，两边余额各变 4800 / 500，资产净值仍 5300，账单出现「转 调拨 200.00」，收支不变
- [x] 小组件：应用内「添加到桌面」→ 系统 `AddItemActivity` 确认 → `dumpsys appwidget` 出现本 App provider → 桌面显示；记一笔 12.34 后桌面立即刷新
- [x] 周期账提醒：新建「16 号 0 点」提醒（时刻已过）→ 系统闹钟立即触发 → 通知标题/正文正确 → 点通知落到「记一笔」页
- [x] 周期账自动入账：保存 `Rent 1500` 后本月支出 93.70 → 1606.04，账单列表出现 `-1500.00`，与 `generateDueBills` 的 `source=5` 幂等入账一致
- 修复：保存/删除周期账后立刻 `rescheduleReminders`（原先只在 App 启动与开机重排）；页面文案从「到期只提醒」改为「到期自动写入账单」
- 限制：模拟器无法 `adb root` 改系统时间；`exported="false"` 的 `BillAlarmReceiver` 也不接受 shell 广播，验证改走「创建已过时刻的提醒」这条真实路径

### W3.1 追加（2026-11-16 09:20，同日第二轮）

- [x] 生物识别：设备 PIN `1234` + `emu finger touch 1` 录入指纹 → 重启 App 自动弹「解锁喵啊记账」→ 指纹通过直接进主界面；点「用口令」切到四位口令页；口令页点「用指纹 / 面容解锁」再次弹窗并通过
- [x] 权限拒绝回退：`pm revoke` 掉 `POST_NOTIFICATIONS` / `READ_MEDIA_IMAGES` / `CAMERA` 三项后重启，仍能进主界面并记一笔 77.77（本月支出 1752.54 → 1830.31）
- [x] OCR 多选与票据图：两张自造票据（星巴克 36.50 / 全家 18.00）多选 → 「已处理 2 张（识别 2 · 无文字 0）」→ 待确认队列两条且「有图 1」
- 修复：`copyOcrUri` 从 `cacheDir/ocr` 改存 `filesDir/tickets`，并生成 320px 级缩略图写进 `ticket_image.thumb_path`（`134b645`）—— 原来存缓存目录会被系统清掉，`thumb_path` 永远为空
- 限制：`adb shell input text` 输不进 `#` 和 `&`；深链回调改用 `am start -a android.intent.action.VIEW -d "tencent101983842://auth#access_token=…&openid=…" com.meow.ledger` 投递

### W3.1 追加二（2026-11-16 09:30，同日第三轮）

- [x] MediaProjection 截屏：`meowledger://capture` 深链拉起系统「Share your screen with 喵啊记账?」→ 选应用 → logcat 出现 `Virtual Display: creating DisplayDevice`、`Changing state of virtual display meow-capture from UNKNOWN to ON`、`captureBounds= Rect(0, 0 - 1280, 2856)`，抓完一帧后 `Release VirtualDisplay meow-capture`，单帧取图链路成立
- [x] 扫码失败回退：`meowledger://scan` → 本机无 zxing 扫码组件 → 横幅「没有可用的扫码组件，已打开「加入账本」，可直接粘贴邀请码。」且页面已跳 `book-join`
- [x] 邀请码加入：粘贴 `DY1ABC23` → 账本列表出现「共享账本 / 1 位成员 · 每月 1 号起始」
- 未验：通知监听与无障碍需真实第三方 App 发通知/页面结构，模拟器无法构造；其它品牌 ROM 私有页与厂商推送按设计边界不实现

## L1 · QQ 登录（只登录，不注册）

状态：完成。代码、JVM 测试、构建与模拟器验证均已通过。

依据：`D:\workspace\AHVideo\AH视频_QQ登录接口速查.md`、`AH视频_账号体系登录注册接口文档.md`（用户 2026-11-16 指定）。

- [x] `qq_session` 表（`QqSession.sq`）+ `migrations/5.sqm`，schema `version = 6`；`Clear.sq` 加 `deleteAllQqSessions`，`Seed.clearLocalData` 同步调用
- [x] `v5.sql` fixture + `MigrationTest` 升到 v6，新增 `v5UpgradesToV6AddsQqSessionWithoutLosingData`
- [x] `QqAuth`：appid `101983842`、scheme `tencent101983842`、`authorizeUrl`、`parseCallback`（fragment / query / JSONP 三种形态）
- [x] `AhAuthClient` 三个端点：`POST /auth/login`、`GET /user`、`POST /auth/refresh`；`KtorAhAuthClient` 走明文 JSON，不复用 `MeowApi` 信封
- [x] `AppAuthToken`：按实测形状复刻（前 32 字节常量 + ts 派生尾）；签名算法未取到，真实服务端会 `403`
- [x] `SandboxAhAuthClient`：默认路径，令牌前缀 `sandbox-`，不冒充真实令牌
- [x] `QqLoginRepository`：登录成功才落库并同步 `member_profile`；刷新失败不清会话；退出只清本地
- [x] UI：`AccountHome` 顶栏 QQ 登录入口 + `qq-login` 子页（授权 / 粘贴回调 / 刷新 / 退出 / AH 地址）
- [x] 深链：`Kind.QQ_AUTH` + Manifest `tencent101983842` scheme + `EntryHost.openQqAuth`
- [x] `:shared:jvmTest` 266 用例 0 失败；`:app:assembleDebug`、`checkAdDeps` 绿
- [x] 模拟器验证（Pixel_9_Pro_API_36，`emulator-5554`，2026-11-16）：
  - 沙箱登录：`am start -a VIEW -d "tencent101983842://auth#access_token=…&openid=FINALCHECK"` → 页面显示「QQ 用户 FINALC / 账号 FINALCHECK / 用户号 524882 / 登录态有效 / 登录成功」；「我的」页顶部同步变成「QQ 用户 QQOPEN」。
  - 刷新：「刷新登录态」→「已刷新登录态」（原先与登录共用「登录成功」文案，会让人以为刷新没生效，已改）。
  - 退出：二次确认「退出登录？只清本机会话，服务端没有登出接口，QQ 那边不受影响。」→「已退出登录」，页面回到未登录态。
  - AH 地址填写 `http://danmu.bmsp9.xyz` → 真实请求返回 `403 app_auth_token`，页面如实写明「签名算法在样本的 Dart 运行时里，本复刻没有实现」。
  - AH 地址清空 → 「已切回本机沙箱，之前的会话已清掉」，旧会话不再冒充有效。
- 模拟器验证中发现并修掉的两个真 bug：
  1. `QqLoginPage` 固定用 `QqLoginRepository(database)`，忽略 AH 地址设置——填了地址仍走沙箱，界面显示「登录成功」是假的。改为按 `ahAuthClient(database)` 现取。
  2. 切换服务地址后旧会话仍显示「登录态有效」。改为换地址即 `logout()`，并补回归用例 `clientFollowsBaseUrlSetting`、`validateAhBaseUrlAllowsSampleHostAndRejectsJunk`。
- 已知边界（如实记录，不算完成）：`app_auth_token` 签名算法在样本 Dart AOT 运行时里，未取到；真实服务端登录必然 `403`，只有拿到合法 `refresh_token` 走 `/auth/refresh` 才能绕过签名（文档 §8.3）。`adb shell input text` 输不进 `#`，模拟器验证改用 `am start` 投递真实回调 intent，不走文本注入。

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
| W2.3 | `ef01067` | 248 用例 + assembleDebug + checkAdDeps 绿 |
| W2.4 | `d46de72` | 248 用例 + assembleDebug + checkAdDeps 绿 |
| W3.0 | `188b002` | jvmTest + assembleDebug + assembleDebugAndroidTest + checkAdDeps 绿；真机 `OK (3 tests)` |
| L1 | `918a271` | 266 用例 + assembleDebug + checkAdDeps 绿；模拟器走通登录/刷新/退出与地址切换 |
| W2.2 追加 | `3b6f90a` | 266 用例 + assembleDebug + checkAdDeps 绿；模拟器挂上桌面并随记账刷新 |
| W3.1 | `fe4395f` | 266 用例 + assembleDebug + checkAdDeps 绿；模拟器走通调拨/组件/提醒通知 |
| W3.1 追加 | `134b645` | 266 用例 + assembleDebug + checkAdDeps 绿；模拟器走通指纹解锁/口令回退/权限拒绝记账/OCR 多选 |
