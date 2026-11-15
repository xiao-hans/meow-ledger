package com.meow.ledger.shared.manage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.meow.ledger.shared.db.MeowDatabase
import com.meow.ledger.shared.bills.TYPE_INCOME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 样本 App 导出的 CSV 列序：类型,分类,时间,金额,账户,账本,标签,记账人,备注[,已退款金额]。 */
class SampleCsvTest {
    private fun open(): ExchangeRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MeowDatabase.Schema.create(driver)
        return ExchangeRepository(MeowDatabase(driver))
    }

    @Test
    fun importsOriginalAppCsvWithBomAndQuotedCells() {
        val repo = open()
        val text = "\ufeff类型,分类,时间,金额,账户,账本,标签,记账人,备注,已退款金额\n" +
            "支出,餐饮,2026/11/10 12:30,45.90,微信,日常账本,,本机,\"超市, 水果\",0.00\n" +
            "收入,工资,2026/11/01 09:00,20000,银行卡,日常账本,,本机,十一月工资,0.00\n"
        assertTrue(isSampleCsv(text))
        val rows = parseSampleCsv(text)
        assertEquals(2, rows.size)
        assertEquals(20261110L, rows[0].day)
        assertEquals("餐饮", rows[0].category)
        assertEquals("45.90", rows[0].money)
        assertEquals("超市, 水果", rows[0].desc)
        assertEquals(TYPE_INCOME, rows[1].type)

        val outcome = repo.importCsv(text, SOURCE_IMPORT, 0)
        assertEquals(2, outcome.created)
        assertEquals(0, outcome.skipped)
        assertEquals(2, repo.all().size)
        assertEquals(20261110L, repo.all().first { it.desc == "超市, 水果" }.day)
    }

    @Test
    fun keepsLegacySelfFormatWorking() {
        val repo = open()
        val text = "日期,类型,分类,金额(元),备注\n20261115,支出,餐饮,12.50,午餐\n"
        val outcome = repo.importCsv(text, SOURCE_IMPORT, 0)
        assertEquals(1, outcome.created)
        assertEquals(12_50L, repo.all().single().money)
    }
}
