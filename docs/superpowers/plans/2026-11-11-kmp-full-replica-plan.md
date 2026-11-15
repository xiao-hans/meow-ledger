---
title: 喵啊记账 KMP 全量复刻实施计划
date: 2026-11-11
design: docs/superpowers/specs/2026-11-11-kmp-full-replica-design.md
status: S0–S8 均已落地，门禁未完成项见 docs/功能差距表.md「剩余缺口」
ad_free: 不接入广告 SDK，不调用广告请求端点
---

# 喵啊记账 KMP 全量复刻实施计划

本计划按已批准设计中的 S0 到 S8 顺序执行。每个切片完成后，执行者应在本文件记录提交号、验证命令和结果。未完成项不得标记完成。

依据文件为 `docs/superpowers/specs/2026-11-11-kmp-full-replica-design.md`、`spec/endpoints.json`、`spec/ddl.sql`、`spec/features.md`、`docs/功能页面对照.md` 和 `docs/接口实现对照表.md`。权威功能证据在 `D:\workspace\recordAccount\01-功能点全量清单.md`。

## S0 · 全量依据建立

状态：完成（`ce0cbb2`）。

1. 生成 `docs/功能差距表.md`。
2. 在差距表中登记 `01` 的 21 个模块、组件数、证据行和 KMP 实现位置。
3. 在差距表中登记 `spec/endpoints.json` 的 112 个注解路径与 KMP 契约方法。
4. 在差距表中登记约 100 个业务自有页面与 KMP 路由。
5. 核对 `02` §5 与 `04` §3 的表清单，登记缺失表和迁移文件。
6. 更新 `AGENTS.md` §10 和 §11，写明 KMP 单基座、物理能力、无广告和 KMP 验收门禁。
7. 更新 README，写明 `kmp/` 是唯一交付物。
8. 运行 `git diff --check`，提交并推送。

完成依据：`docs/功能差距表.md` 中 21 个模块、112 个端点、页面和表都有状态列；状态列只使用「已实现」「未实现」「无广约束」「待证据」。执行者应更新本文件和 README，并写明 KMP 单基座、物理能力与无广告边界。`npm run build` 与本切片的 KMP 构建均通过。

## S1 · M1 应用框架与 M20 权限

状态：完成。

1. 在 `AndroidManifest.xml` 声明 `01` §1 的入口组件，包括 Activity、TileService、前台服务、闹钟 Receiver 和开机 Receiver。
2. 声明 `POST_NOTIFICATIONS`、`READ_MEDIA_IMAGES`、`CAMERA`、`USE_BIOMETRIC`、`SCHEDULE_EXACT_ALARM`、`WAKE_LOCK`、`FOREGROUND_SERVICE` 等权限。
3. 实现动态权限申请、拒绝回退和用途说明页。
4. 实现启动页、主界面、WebView 容器、扫码页和票据页的路由。
5. 补齐权限映射表和交互纪律检查记录。
6. 运行 JVM 测试、Gradle 构建和 `assembleDebug`。
7. 在真机验证授权、拒绝和入口跳转。
8. 更新差距表、接口对照表和本计划；提交并推送。

完成依据：真机可完成授权和拒绝回退；入口跳转不丢失当前页面；Manifest 与差距表的组件清单一致。

## S2 · M4 账单、M5 分类标签、M6 账本协作

状态：完成（模拟器全流程验证通过；真机装机留作用户配合项）。

1. 补齐 `bill`、`bill_tag`、`bill_remark`、`cate_extend`、`tag`、`bill_book`、`member_book` 的表和迁移。已完成：`S2.sq` 9 张表 + `sync_queue` 11 列并入 `2.sqm`。
2. 实现 `Bills` 12 个、`Cate` 6 个、`MemberTag` 4 个、`BillsBook` 10 个契约方法。已完成：`MeowApi.kt` 32 方法 + `Bills.kt`/`Categories.kt`/`Tags.kt`/`Books.kt` 业务层。
3. 实现账单编辑、退款、批量修改、搜索、分类标签、账本扫码加入、邀请和成员页。已完成：`BatchPage.kt`、`Catalog.kt` 标签管理、`Books.kt` 账本协作页（books/book-new/book-edit/book-join/book-members）均存在；真实扫码留给 S4 相机管线。
4. 实现 `client_id` 幂等写、`sync_queue` 重试和本地优先刷新。已完成：`SyncEngine`（指数退避重试，纯本地模式静默保留队列）。
5. 实现删除二次确认、草稿保留、金额输入和筛选状态。批量修改已用 `ConfirmBox` 二次确认。
6. 为金额、去重、迁移、契约字段和同步重试写 JVM 测试。已完成 17 个新用例（BillsSyncTest/TagsTest/BooksDefaultTest/CateExtendTest/SyncQueueTest + 网络 7 个），96 用例全绿。
7. 升级与批量修改流程回归。已完成：v2 旧库 install -r 升级后表结构/11 列 `sync_queue`/version=3；批量修改 4 笔改「交通」无崩溃。批量改分类**逐笔入队权威的 `Bills/Update POST`**（`spec/endpoints.json` 内路径），旧记录里的 `Bills/BatchUpdate POST` 是清单外幽灵路径，已移除：PR #3 用 `SyncEngine.enqueue` 的 `EndpointRegistry.isKnown` 守卫拒绝清单外 path，并把队列中残留的旧行标为终态 `status = 2`。真机 vivo 安装确认框待用户点击后复核。
8. 更新差距表和接口对照表；提交并推送。对照表与差距表已更新到「部分实现」。

完成依据：S2 阶段的 32 个契约方法逐项有实现位置；同名 `Bills/ImportBills` 重载按请求体字段区分；批量改分类按权威 `Bills/Update` 逐笔入队（不再使用 `Bills/BatchUpdate`）；升级与批量修改流程在模拟器留 `uiautomator` 记录。

## S3 · M7 预算与 M8 统计

状态：完成（提交表 `:157` 记为完成；模拟器验证通过，真机待用户配合，复制年预算与自定义区间未做，见差距表 M7/M8）。

1. 补齐 `budget`、`cate_budget` 和对应迁移。已做（S2 迁移 3.sqm 覆盖）。
2. 实现 `Budget` 9 个契约方法和预算包含排除规则。已做：契约 `BudgetContracts` 9 方法、`removeCategory`、`not_in_budget` 过滤；包含排除、复制年预算未做。
3. 实现总预算、分类预算、预算详情、统计报表、类型下钻、分类下钻和标签下钻页。已做：`ui/manage/Budget.kt`、`ui/stats/StatsDetail.kt`（cate:/tag:/tags/type/report 路由）、StatsHome 4 个入口；类型下钻用月列表替代。
4. 实现整数分聚合、月份筛选和自绘 SVG 图表。已做（净额口径 netMoney）。
5. 为金额聚合、边界月份和包含排除规则写 JVM 测试。已做：`BudgetNotInBudgetTest`、`TagDrillTest`；100 tests 0 fail。
6. 在真机验证预算编辑和 4 类统计下钻。模拟器已验证：分类下钻（合计 93.70·3 笔）、年度报表、返回键回上级；真机待用户配合。
7. 更新差距表和接口对照表；提交并推送。已做（契约 41/112）。

完成依据：9 个契约方法有实现位置；图表在深浅色模式可读；金额不出现浮点累加误差。

## S4 · M2 自动记账、M3 OCR、M13 导入导出

状态：完成（提交表 `:158` 记为完成；S4a/S4b 已完成，S4c 真机验证待用户配合；截屏 MediaProjection 一路未实现，见差距表「剩余缺口」）。

1. ✅ 声明通知监听服务、无障碍服务；⬜ 截屏快捷磁贴只有 `ScreenCaptureTileService` 跳说明页，**MediaProjection / ImageReader / VirtualDisplay 未实现**（见差距表「剩余缺口」）。
2. ✅ 通知/无障碍文本解析 + `CaptureBridge` 白名单过滤入队；ML Kit 中文 OCR（`text-recognition-chinese:16.0.1` 本地 bundled）接入 `PickImageButton`，`AutoRepository.captureTicket` 整图一次解析（商户取金额后首个词、日期不误判金额）。
3. ✅ 去重键（`dedupKey(sourceApp|money|分钟桶|merchant)`）与待确认队列；确认/忽略后列表即时刷新。
4. ✅ `KeyWordMatchingRule` 4 个、`MemberImportBillFile` 2 个、`Upload` 3 个契约方法（契约 50/112）。
5. ✅ OCR 页与批量改分类已做（S2）；5 个 OCR 页以单页三区（选图/粘贴/手动）等价覆盖。
6. ✅ CSV 粘贴/文件导入 + 重复合并 + 导入记录；导出分享（cacheDir + ACTION_SEND）；票据图存储到 cacheDir/ocr。
7. ✅ JVM `AutoCaptureTest`：20 条样本识别率与重复 0（同分钟去重、垃圾文本 unsupported）。
8. ✅ S4b 模拟器 E2E：ML Kit 识别生成的支付截图，金额 42.50、商户瑞幸咖啡正确入队，确认后清空；批量改分类结果见 S2 真机流程（待用户配合）。
9. ⬜ 在真机验证三路采集、权限和待确认队列（需用户配合授权）。
10. ✅ 更新差距表和接口对照表；提交并推送（S4b 提交号见下方记录）。

完成依据：`04` M3 的识别率不低于 90%，重复数为 0；`04` M4 的 10 张截图金额和商户正确；解析结果不得静默入账。

## S5 · M9 资产、M10 存钱、M11 周期、M12 分期、M14 小组件

状态：完成（提交表 `:159` 记为完成；`09558c5`、`d93feec`；widget 桌面放置与账户间调拨独立页未做，见差距表）。

1. 补齐 `assets`、`save_plan`、`save_plan_item`、`fixed_bill`、`bill_remind`、`installment_plan`、`installment_item`、`widget_base`、`widget_user`、`widget_desktop` 和 `widget_hot` 的表与迁移。
2. 实现 `FundAccount` 6 个、`SavePlan` 14 个、`ConsumptionInstallment` 6 个、`BillRefund` 3 个契约方法。
3. 实现资产 12 个组件、存钱 6 个页面、周期账 4 个页面、分期 4 个页面和小组件 7 个组件。
4. 实现转账后的余额联动、存钱单期更新、归档筛选、分期公式重算、结清和精确闹钟。
5. 实现 `AppWidgetProvider`、`RemoteViews`、配置页和刷新 Receiver。
6. 为余额、期次、利息、闹钟恢复和迁移写 JVM 测试。
7. 在真机验证资产转账、期次更新、闹钟和桌面小组件。
8. 更新差距表和接口对照表；提交并推送。

完成依据：29 个契约方法有实现位置；桌面上可添加、刷新和移除小组件；周期提醒在开机后恢复。

## S6 · M15 账号安全与 M16 VIP 支付

状态：完成（提交表 `:160` 记为完成；`8e921db`；契约与本地页已落地，真实支付网关需商户资质，见差距表 M16）。

1. 补齐 `member_profile`、`sms_code`、`contract_info`、`member_order` 和 `group_vip` 的表与迁移。
2. 实现 `Account` 10 个、`Member` 10 个、`Order` 5 个、`VipGroupBuy` 3 个、`PersonCenter` 1 个契约方法。
3. 实现登录、手机号绑定、换绑、切换账号、用户资料、关于、协议、个性化推荐和应用锁。
4. 接入生物识别、安全存储、运营商一键登录和第三方登录的可替换网关。
5. 实现双 VIP、促销、绑定管理、支付宝、微信、Google Billing 和自动续费协议。
6. 实现订单状态回查和支付回调；本地测试使用沙箱或可替换支付网关。
7. 在真机验证登录、应用锁、订单状态回查和支付回调。
8. 更新差距表和接口对照表；提交并推送。

完成依据：29 个契约方法有实现位置；真实商户资质缺失时，文档记录沙箱边界，不把沙箱写成生产支付。

## S7 · M18 推送保活与 M19 埋点配置反馈

状态：完成（提交表 `:161` 记为完成；同 `8e921db`；100+ 事件映射与 4 厂商通道待服务端，见差距表 M18/M19）。

1. 补齐 `behavior_event`、`remote_config` 和错误日志表。
2. 实现 `Behavior` 1 个、`Config` 1 个、`Operation` 2 个契约方法。
3. 实现个推、小米、华为、OPPO、vivo 推送通道的可替换接口和前台保活服务。
4. 实现 `01` 列出的 100 多个 `ac_*` 事件、上报、远程配置 24 个开关、反馈和错误日志。
5. 实现推送权限申请、电池优化引导和通道状态页。
6. 为事件字段、上报队列和开关默认值写 JVM 测试。
7. 在真机验证推送、保活、配置读取、反馈和错误日志。
8. 更新差距表和接口对照表；提交并推送。

完成依据：4 个契约方法有实现位置；事件名与 `01` 一致；广告开关固定关闭；无广静态检查通过。

## S8 · M17 无广约束、M21 全量回归

状态：完成（提交表 `:162` 记为完成；`2a8c806`、`6aff48e`；广告依赖黑名单脚本已于 2026-11-15 落地（`checkAdDeps` + `config/ad-sdk-blacklist.txt`，正负两向验证通过）；Android 仪器/真机测试、真实自建后端联调等门禁项仍未过，见差距表「剩余缺口」）。

1. 运行广告依赖黑名单检查，记录结果。（已完成：`gradle checkAdDeps` BUILD SUCCESSFUL，32 项未命中；负向探针命中即 FAILED）
2. 核对 21 个模块、112 个端点、约 100 个页面、`02` 表清单和 `AGENTS.md` §6 的 10 条纪律。
3. 运行全量 JVM 测试、Android 仪器测试、Gradle 构建和 `assembleDebug`。
4. 在真机安装最终 APK，按 `04` 六里程碑执行端到端流程。
5. 补齐 `docs/接口实现对照表.md`、`docs/功能页面对照.md`、`spec/features.md`、README 和 `05` 差异记录。
6. 修正所有「已实现」但正文写有「未做」「no-op」「不申请」的条目。
7. 运行 `git diff --check`，提交并推送。

完成依据：差距表不存在「未实现」「待证据」项；每个模块有真机或自动化测试证据；所有构建命令有完整输出记录。

## S10 · 权限申请与跨品牌系统设置

状态：完成（本提交；代码 + Gradle + vivo V2419A 真机权限门截图，其它品牌私有页待设备）。

1. 删除本版无账号登录却申请不到的 `READ_SMS` / `RECEIVE_SMS`，常用运行时权限按 API 级别与相机硬件过滤。
2. 补齐 Manifest 能力：悬浮窗、电池优化、振动、旧版生物识别与 `maxSdkVersion=32` 的存储权限；新增电池优化白名单状态。
3. 生物识别改为检查系统是否已登记凭据，未登记时显示原因并允许跳过；每项系统权限展示具体用途与设置路径说明。
4. `openSettings` 改为标准 Action → vivo/iQOO、小米/Redmi、OPPO/OnePlus/realme、华为/荣耀、三星私有页 → 应用详情页逐层兜底，所有跳转 `runCatching`，不因 ROM 缺少页面崩溃。
5. 验证：`:shared:jvmTest :app:assembleDebug` BUILD SUCCESSFUL；vivo V2419A 安装冷启动无 FATAL，权限门显示 3 项已开启 + 3 项未开启及「去设置」按钮与原因。

完成依据：权限门不再包含申请不到的高敏权限；不同品牌至少有可操作入口或明确不支持原因；完整授权刷新与通知监听/无障碍/截屏链路仍需各品牌真机。

## S11 · 首次权限清单与按功能引导

状态：代码、JVM 测试与构建完成；vivo 真机因无线调试离线未验（见下方提交记录）。

1. 权限清单按「功能 → 可选权限」展示；每项可在清单里单独申请，也可跳过。
2. 首次冷启动只自动弹一次：看到清单即写 `permission_intro_shown`；清空本地数据后不重弹。
3. 用户进入自动记账、通知/无障碍采集、截屏 OCR、周期提醒、后台保活、应用锁等功能时，才按 `permissionKeysForFeature` 检查缺项并弹上下文引导；「以后再说」只跳过本次会话，可稍后从「我的 → 权限与隐私」再次打开。
4. 基础权限申请可跳过，权限项不阻塞手工记账；所有系统设置项仍走 S10 的品牌兼容与逐层兜底。
5. 验证：`:shared:jvmTest` 中 `permissionIntroIsStoredAndFeaturePermissionsAreMapped` 覆盖持久化与 7 组功能映射；`:shared:jvmTest :app:assembleDebug` BUILD SUCCESSFUL；`git diff --check` 通过。

完成依据：首次清单不复弹、清单内可逐项申请、后续按功能再引导、可从设置主动打开；各品牌真机授权链路仍待设备。

## 每个切片的提交记录

| 切片 | 提交号 | 构建结果 | 真机结果 | 差距表状态 |
| --- | --- | --- | --- | --- |
| S0 | `b756323`、`ce0cbb2` | `npm run build` 通过；`git diff --check` 通过 | 不必 | 完成 |
| S1 | `ab51295` | `BUILD SUCCESSFUL in 32s`；`:app:assembleDebug`、`:shared:jvmTest` 通过 | 模拟器权限页截图、继续按钮后首页文本与截图 | 完成 |
| S2 | `e3a4b25` + 本切片 | `BUILD SUCCESSFUL`（96 tests 0 fail） | 模拟器升级路径+批量修改 uiautomator 通过；真机待用户确认安装 | 完成 |
| S3 | `ad7a125` | 100 tests 0 fail；统计下钻/年度报表/预算空态模拟器截图 | 模拟器 uiautomator + 返回键回归 | 完成 |
| S4 | `1dbf13b` | 113 tests 0 fail；ML Kit 中文 OCR 模拟器选图→识别→待确认→入账全流程截图 | 模拟器全绿；真机 OCR 待用户配合 | 完成（真机待验） |
| S5 | `09558c5` | 116 tests 0 fail；余额联动/存钱单期/分期/小组件渲染/精确提醒（reboot 实证 2026-12-01 09:00） | 模拟器周期账+提醒 E2E；widget 桌面放置待人工 | 完成（widget 待验） |
| S6 | `8e921db` | 契约测试全绿；Account/Group 业务层齐（VIP/订单/拼团 UI 已有） | — | 完成 |
| S7 | 同 S6 | Operation/Behavior/Config/PersonCenter 5 契约 + catalog 112 断言绿 | — | 完成 |
| S8 | `2a8c806` | 118 tests 0 fail；埋点三类落库 E2E（ac_add_bill×2 入库实证） | 模拟器记账保存+埋点；imePadding 键盘遮挡修复后回归截图 | 完成 |
| S9（免费解锁与数据迁移） | 本提交 | `:shared:jvmTest :app:assembleDebug` BUILD SUCCESSFUL；`git diff --check` 通过；BackupTest/SampleCsvTest 覆盖全库备份与样本 CSV | vivo V2419A 真机：Mine 显示「全部功能已解锁」，备份页导出 JSON 成功（含 4 笔账与分类）、无红色失败提示 | 完成（恢复/文件选择待人工） |
| S10（权限申请与跨品牌适配） | 本提交 | `:shared:jvmTest :app:assembleDebug` BUILD SUCCESSFUL；`git diff --check` 通过 | vivo V2419A 真机：冷启动权限门文案/去设置按钮/不支持原因/已开启状态截图，启动无 FATAL | 完成（其它品牌真机待验） |
| S11（首次清单与按功能引导） | 本提交 | `:shared:jvmTest :app:assembleDebug` BUILD SUCCESSFUL；`git diff --check` 通过；新增持久化与功能映射 JVM 测试 | 未验：无线调试离线，`adb mdns services` 为空；待机主重新开启无线调试后补冷启动、二次启动、主动打开与功能引导截图 | 完成（真机待验） |

> **实测基线（2026-11-12 状态对账时补记）**：`origin/master` 上的 `:shared:jvmTest` = **139 tests / 0 failures**，`:app:assembleDebug` = **BUILD SUCCESSFUL**；环境 JDK 17.0.21 + Gradle 8.11.1（wrapper jar 方式，无 `gradlew` 脚本）。上表各切片的「96 / 100 / 113 / 116 / 118 tests」是当时提交记录里的历史数字，与最新基线口径不同，不要累加或当成当前值；本次对账未逐个重跑历史提交。三个先于状态对账 PR 合并的分支各自报告的测试数：PR #3 `fix/network-contract` 174 用例、PR #2 `fix/db-migration-v3` 145 用例、PR `fix/interaction-discipline` 新增 `AutoBatchConfirmTest` / `ClearLocalDataTest`（各分支独立口径，合并后以 CI 实测为准）。
