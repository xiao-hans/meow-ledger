package com.meow.ledger.shared.manage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.meow.ledger.shared.db.MeowDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/** M14：桌面小组件额外行配置存取。 */
class WidgetConfigTest {

    private fun repo(): WidgetRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MeowDatabase.Schema.create(driver)
        return WidgetRepository(MeowDatabase(driver))
    }

    @Test
    fun emptyByDefault() {
        assertEquals(emptySet(), repo().extraRows())
    }

    @Test
    fun roundTripSorted() {
        val repo = repo()
        repo.setExtraRows(setOf(WIDGET_ROW_REFUND, WIDGET_ROW_BUDGET))
        assertEquals(setOf(WIDGET_ROW_BUDGET, WIDGET_ROW_REFUND), repo.extraRows())
        repo.setExtraRows(setOf(WIDGET_ROW_PLAN))
        assertEquals(setOf(WIDGET_ROW_PLAN), repo.extraRows())
        repo.setExtraRows(emptySet())
        assertEquals(emptySet(), repo.extraRows())
    }

    @Test
    fun sevenKindsCoverSampleEntries() {
        assertEquals(
            listOf(
                WIDGET_KIND_MONTH, WIDGET_KIND_BUDGET, WIDGET_KIND_PLAN, WIDGET_KIND_ASSETS,
                WIDGET_KIND_QUICK, WIDGET_KIND_SAVE, WIDGET_KIND_TICKET,
            ),
            WIDGET_KINDS.map { it.id },
        )
        assertEquals(7, WIDGET_KINDS.map { it.name }.toSet().size)
    }

    @Test
    fun configBindsSystemWidgetIdAndReconfiguresInPlace() {
        val repo = repo()
        val first = repo.saveConfig(42, "", WIDGET_SIZE_MEDIUM, WIDGET_KIND_ASSETS)
        assertEquals("aw42", first.id)
        assertEquals(first.id, repo.bindingOf(42))
        assertEquals(WIDGET_KIND_ASSETS, repo.get("aw42")?.kind)

        // 长按重新配置：同一个 appWidgetId 只更新，不新增行。
        repo.saveConfig(42, "资产", WIDGET_SIZE_LARGE, WIDGET_KIND_QUICK)
        assertEquals(1, repo.all().size)
        assertEquals(WIDGET_KIND_QUICK, repo.get("aw42")?.kind)
        assertEquals(WIDGET_SIZE_LARGE, repo.get("aw42")?.size)

        repo.unbind(42)
        assertEquals(null, repo.bindingOf(42))
    }

    @Test
    fun countKindsAreNotFormattedAsMoney() {
        val repo = repo()
        // 无数据时也应是计数语义：账户 0、笔数 0，不能被当成 0 元展示。
        assertEquals(false, repo.preview(WIDGET_KIND_ASSETS, 202611, 20261115).secondMoney)
        assertEquals(false, repo.preview(WIDGET_KIND_TICKET, 202611, 20261115).secondMoney)
        assertEquals(false, repo.preview(WIDGET_KIND_QUICK, 202611, 20261115).secondMoney)
        assertEquals(false, repo.preview(WIDGET_KIND_SAVE, 202611, 20261115).secondMoney)
    }
}
