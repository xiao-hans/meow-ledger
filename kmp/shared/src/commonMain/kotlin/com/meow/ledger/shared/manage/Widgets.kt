package com.meow.ledger.shared.manage

import com.meow.ledger.shared.bills.TYPE_EXPENSE
import com.meow.ledger.shared.bills.TYPE_INCOME
import com.meow.ledger.shared.bills.netPaid
import com.meow.ledger.shared.db.MeowDatabase
import com.meow.ledger.shared.db.Widget_user
import kotlin.random.Random

const val WIDGET_SIZE_SMALL = "small"
const val WIDGET_SIZE_MEDIUM = "medium"
const val WIDGET_SIZE_LARGE = "large"

const val WIDGET_KIND_MONTH = "month"
const val WIDGET_KIND_BUDGET = "budget"
const val WIDGET_KIND_PLAN = "plan"
const val WIDGET_KIND_ASSETS = "assets"
const val WIDGET_KIND_QUICK = "quick"
const val WIDGET_KIND_SAVE = "save"
const val WIDGET_KIND_TICKET = "ticket"

data class WidgetSize(val id: String, val name: String)

val WIDGET_SIZES = listOf(
    WidgetSize(WIDGET_SIZE_SMALL, "小"),
    WidgetSize(WIDGET_SIZE_MEDIUM, "中"),
    WidgetSize(WIDGET_SIZE_LARGE, "大"),
)

data class WidgetKind(val id: String, val name: String)

val WIDGET_KINDS = listOf(
    WidgetKind(WIDGET_KIND_MONTH, "本月收支"),
    WidgetKind(WIDGET_KIND_BUDGET, "预算剩余"),
    WidgetKind(WIDGET_KIND_PLAN, "存钱进度"),
    WidgetKind(WIDGET_KIND_ASSETS, "总资产"),
    WidgetKind(WIDGET_KIND_QUICK, "今日速记"),
    WidgetKind(WIDGET_KIND_SAVE, "存钱计划"),
    WidgetKind(WIDGET_KIND_TICKET, "待确认票据"),
)

/**
 * 小组件预览上的两行数字。金额默认按分格式化；笔数、账户数这类计数把对应 [firstMoney] /
 * [secondMoney] 置 false，避免把 3 笔显示成 0.03 元。
 */
data class WidgetPreview(
    val firstLabel: String,
    val firstValue: Long,
    val secondLabel: String,
    val secondValue: Long,
    val firstMoney: Boolean = true,
    val secondMoney: Boolean = true,
)

/** 桌面小组件额外行配置，存 user_setting:widget_rows，逗号分隔。 */
const val WIDGET_ROWS_KEY = "widget_rows"
const val WIDGET_ROW_BUDGET = "budget"
const val WIDGET_ROW_PLAN = "plan"
const val WIDGET_ROW_REFUND = "refund"

/**
 * 「添加到桌面」预选的种类与尺寸，存 user_setting。
 * 系统固定组件后会按 `android:configure` 拉起配置页，配置页把它们当默认值。
 */
const val KEY_WIDGET_PIN_KIND = "widget_pin_kind"
const val KEY_WIDGET_PIN_SIZE = "widget_pin_size"

/** 桌面小组件的本地配置。只写本地表，不挂到系统桌面。 */
class WidgetRepository(private val db: MeowDatabase) {

    /** 桌面小组件显示的额外行。 */
    fun extraRows(): Set<String> =
        (db.configQueries.selectSetting(WIDGET_ROWS_KEY).executeAsOneOrNull() ?: "")
            .split(',').filter { it.isNotBlank() }.toSet()

    /** 设置桌面小组件显示的额外行。 */
    fun setExtraRows(rows: Set<String>) {
        db.configQueries.upsertSetting(
            com.meow.ledger.shared.db.User_setting(WIDGET_ROWS_KEY, rows.filter { it.isNotBlank() }.sorted().joinToString(",")),
        )
    }

    fun all(): List<Widget_user> = db.widgetQueries.selectWidgets().executeAsList()

    fun get(id: String): Widget_user? = db.widgetQueries.selectWidget(id).executeAsOneOrNull()

    /** 新建或修改。修改不存在的组件返回 null。 */
    fun save(name: String, size: String, kind: String, id: String = ""): Widget_user? {
        val old = if (id.isEmpty()) null else get(id) ?: return null
        val row = Widget_user(
            id = old?.id ?: "w${System.currentTimeMillis()}${Random.nextInt(1000, 9999)}",
            name = name.ifBlank { WIDGET_KINDS.firstOrNull { it.id == kind }?.name ?: "小组件" },
            size = WIDGET_SIZES.firstOrNull { it.id == size }?.id ?: WIDGET_SIZE_SMALL,
            kind = WIDGET_KINDS.firstOrNull { it.id == kind }?.id ?: WIDGET_KIND_MONTH,
            create_time = old?.create_time ?: System.currentTimeMillis(),
        )
        db.widgetQueries.upsertWidget(row)
        return row
    }

    fun delete(id: String) {
        db.widgetQueries.deleteWidget(id)
    }

    /** 桌面「配置」入口保存：一个系统 appWidgetId 固定对应一行，重复配置只更新。 */
    fun saveConfig(appWidgetId: Int, name: String, size: String, kind: String): Widget_user {
        val id = boundId(appWidgetId)
        val old = get(id)
        val row = Widget_user(
            id = id,
            name = name.ifBlank { WIDGET_KINDS.firstOrNull { it.id == kind }?.name ?: "小组件" },
            size = WIDGET_SIZES.firstOrNull { it.id == size }?.id ?: WIDGET_SIZE_SMALL,
            kind = WIDGET_KINDS.firstOrNull { it.id == kind }?.id ?: WIDGET_KIND_MONTH,
            create_time = old?.create_time ?: System.currentTimeMillis(),
        )
        db.widgetQueries.upsertWidget(row)
        bind(appWidgetId, id)
        return row
    }

    fun boundId(appWidgetId: Int) = "aw$appWidgetId"

    /** 按组件种类取当前数字。yearMonth 形如 202611；today 形如 20261115。 */
    fun preview(kind: String, yearMonth: Long, today: Long = 0L): WidgetPreview {
        return when (kind) {
            WIDGET_KIND_BUDGET -> budget(yearMonth)
            WIDGET_KIND_PLAN -> plan()
            WIDGET_KIND_ASSETS -> assets()
            WIDGET_KIND_QUICK -> quick(today)
            WIDGET_KIND_SAVE -> save()
            WIDGET_KIND_TICKET -> ticket()
            else -> month(yearMonth)
        }
    }

    /** 系统 appWidgetId → 本地组件行 id。卸载组件时一起清掉。 */
    fun bind(appWidgetId: Int, widgetId: String) {
        setSetting(bindingKey(appWidgetId), widgetId)
    }

    fun bindingOf(appWidgetId: Int): String? =
        db.configQueries.selectSetting(bindingKey(appWidgetId)).executeAsOneOrNull()?.takeIf { it.isNotBlank() }

    fun unbind(appWidgetId: Int) {
        db.configQueries.deleteSetting(bindingKey(appWidgetId))
    }

    private fun bindingKey(appWidgetId: Int) = "widget_bind_$appWidgetId"

    private fun setSetting(key: String, value: String) {
        db.configQueries.upsertSetting(com.meow.ledger.shared.db.User_setting(key, value))
    }

    private fun month(yearMonth: Long): WidgetPreview {
        val from = yearMonth * 100 + 1
        val to = yearMonth * 100 + 31
        val bills = db.billQueries.selectByRange(0, from, to).executeAsList()
        val expense = bills.filter { it.type == TYPE_EXPENSE }.sumOf { netPaid(it.money, refunded(it.client_id)) }
        val income = bills.filter { it.type == TYPE_INCOME }.sumOf { it.money }
        return WidgetPreview("支出", expense, "收入", income)
    }

    private fun budget(yearMonth: Long): WidgetPreview {
        val view = BudgetRepository(db).view(yearMonth)
        val total = view.budget?.money ?: 0
        return WidgetPreview("预算", total, "剩余", total - view.spent)
    }

    private fun plan(): WidgetPreview {
        val plans = db.planQueries.selectSavePlans().executeAsList().filter { it.archived == 0L }
        return WidgetPreview("目标", plans.sumOf { it.target_money }, "已存", plans.sumOf { it.saved_money })
    }

    private fun assets(): WidgetPreview {
        val rows = db.assetQueries.selectAll().executeAsList().filter { it.status == 0L }
        return WidgetPreview(
            firstLabel = "总资产",
            firstValue = rows.sumOf { it.balance_cents },
            secondLabel = "账户",
            secondValue = rows.size.toLong(),
            secondMoney = false,
        )
    }

    /** 今日速记：当天支出与笔数；today 缺省时按本月 1 号兜底，避免显示跨月数据。 */
    private fun quick(today: Long): WidgetPreview {
        val day = if (today > 0L) today else 0L
        val bills = if (day == 0L) emptyList() else
            db.billQueries.selectByRange(0, day, day).executeAsList()
        val expense = bills.filter { it.type == TYPE_EXPENSE }.sumOf { netPaid(it.money, refunded(it.client_id)) }
        return WidgetPreview(
            firstLabel = "今日支出",
            firstValue = expense,
            secondLabel = "笔数",
            secondValue = bills.size.toLong(),
            secondMoney = false,
        )
    }

    private fun save(): WidgetPreview {
        val plans = db.planQueries.selectSavePlans().executeAsList().filter { it.archived == 0L }
        return WidgetPreview(
            firstLabel = "已存",
            firstValue = plans.sumOf { it.saved_money },
            secondLabel = "计划数",
            secondValue = plans.size.toLong(),
            secondMoney = false,
        )
    }

    private fun ticket(): WidgetPreview {
        val pending = db.autoQueries.selectPending().executeAsList()
        return WidgetPreview(
            firstLabel = "待确认金额",
            firstValue = pending.sumOf { it.money },
            secondLabel = "笔数",
            secondValue = pending.size.toLong(),
            secondMoney = false,
        )
    }

    private fun refunded(clientId: String): Long =
        db.refundQueries.selectByBill(clientId).executeAsList().sumOf { it.money }
}
