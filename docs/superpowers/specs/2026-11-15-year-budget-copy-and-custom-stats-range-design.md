---
title: 复制年预算与统计自定义起止区间设计
date: 2026-11-15
status: 已落地
---

# 复制年预算与统计自定义起止区间

## 背景

`docs/功能差距表.md` §1 的 M7、M8 与 §7 第 1 条把「复制年预算」「统计自定义起止区间」登记为代码缺口。本设计补齐这两项，范围限于 KMP 现有预算、统计功能，不新增端点、不改数据库表结构、不接私有加密。

## 权威来源与边界

- 样本 `D:\workspace\recordAccount\03-接口文档.md` 的 `Budget/GetYearlyBudgets(year)` 返回年度预算视图；`spec/endpoints.json` 的 9 个 Budget 端点里没有复制年预算专用端点。`budget` 表按 `(book_id, day)` 存月预算，`day` 为 `yyyyMM`。因此年预算是 12 个月预算的视图，不是独立表。
- 样本 `D:\workspace\recordAccount\apktool_out\res\values\strings.xml` 有 `budget_copy_only=仅复制预算`、`string_check_year=按年查看`、`time_check_custom_hint=自定义周期支持统计任意时间范围内的收支、结余、分类、标签、明细等数据`、`the_end_date_must_larger_start_date=结束日期必须大于开始日期`、`start_date=开始日期`、`end_date=结束日期`。样本没有「复制年预算」明文按钮，本设计按数据结构做功能等价。
- `kmp/shared/src/commonMain/kotlin/com/meow/ledger/shared/stats/Stats.kt` 的 `summary(fromDay, toDay, ...)` 与 `billsOfPeriod(fromDay, toDay, ...)` 已支持任意 `yyyyMMdd` 区间；`day` 列是 `yyyyMMdd` 整数。

## 设计

### 1. 复制年预算

- `BudgetRepository` 新增 `copyYear(fromYear: Int, toYear: Int, bookId: Long = DEFAULT_BOOK_ID): Int`，返回成功复制的月份数。
- 逐月处理 1–12 月：来源 `fromYear * 100 + month`，目标 `toYear * 100 + month`。目标月已有总预算时跳过，不覆盖用户输入；来源月没有总预算时跳过。
- 每月复制总预算（`is_copy = 1`）与该月全部分类预算；写目标月前先清掉目标月可能残留的分类预算行，避免重复。
- UI 在预算页底部新增「复制年预算」按钮，来源年固定为当前显示年份的上一年，目标年为当前显示年份。点击后弹二次确认，写明「从 X 年复制到 Y 年，已设置预算的月份会跳过，不会覆盖」。确认后显示「已复制 N 个月」或「来源年没有预算可复制」。
- 现有「复制上月预算」保持不变；两者互不影响。

### 2. 统计自定义起止区间

- `StatsRepository` 新增按区间分桶的趋势方法，保留原 `trend(period, count)` 的 12 周期快捷行为不变。
- `StatsHome` 新增自定义区间状态与入口：两个受控字符串输入起止 `yyyyMMdd`，显式显示当前区间，另有独立「重置」按钮。应用时校验格式与「开始 ≤ 结束」，非法输入显示错误，不吞字符、不改数量级。
- 自定义区间与周/月/年快捷周期互斥：应用区间后，收支汇总、分类占比、趋势、分类/标签/类型下钻清单都按同一区间；点周/月/年 chip 或「重置」清除区间，恢复快捷周期。切换收支类型不清空区间。
- 年度报表是独立入口，仍按所选年份展示，不受自定义区间影响。
- 下钻清单在自定义区间生效时用 `billsOfPeriod(from, to, type)` 与 `TagDrill.billsOfTag(from, to, tagId, type)`，避免「汇总变了、明细还是当月」。

## 数据流

1. 预算页选年份 → 二次确认 → `copyYear` 批量写 `budget` / `cate_budget` → 刷新视图与提示。
2. 统计页输入起止 → 校验 → 区间状态生效 → `MeowApp` 用区间计算 `summary`、趋势与下钻 bills → 「重置」恢复 `periodSpan(statsPeriod, 12)`。

## 错误与边界

- 来源年 0 个月可复制时返回 0，UI 提示来源年没有预算可复制。
- 目标月已有预算跳过；分类预算随总预算一起复制，不产生孤立分类行。
- 日期必须是 8 位 `yyyyMMdd` 整数，开始大于结束时显示「结束日期必须大于或等于开始日期」；单日区间合法，空区间不生效。
- 区间含起含止；退款按现有 `refundQueries.selectByRange` 口径扣减。
- 批量写入全部落在本地库，云端不可达不阻塞 UI。

## 测试与验收

- JVM 回归：跨年复制 12 个月、跳过已存在月份、分类预算联动、来源年为空返回 0；日期格式与倒序校验、区间含起含止、清除区间恢复快捷周期。
- 命令：`:shared:jvmTest`、`:app:assembleDebug`。
- 真机：打开预算页点复制年预算并看提示；统计页输入起止区间并看汇总与明细；截图或 UI dump 留证。
- 2026-11-15 验收：`:shared:jvmTest` 47 套件 / 236 用例 / 0 失败、`:app:assembleDebug` BUILD SUCCESSFUL；vivo V2419A 实证预算复制 2 个月且已有目标月不覆盖，统计 `20261101–20261102` 汇总 47.80 / 下钻 2 笔，`20261101–20261130` 汇总 93.70 / 下钻 3 笔，重置恢复快捷周期；无效日期、倒序日期、空来源年均有提示。
- 同步更新 `docs/功能差距表.md`、`docs/接口实现对照表.md`（无新端点时标注「复用现有端点」）、`docs/功能页面对照.md` 与计划表验证记录；按 AGENTS.md §9 commit + push。

## 非目标

- 不新增 `Budget/CopyYearBudget` 之类端点，不接广告、VIP 解锁或样本私有加密。
- 不做年度独立预算表、不做账单导出的「最长一年」限制、不改 112 端点清单。
- 不重写计划表里的历史里程碑行；计划表按时间线追加本次验证记录。
