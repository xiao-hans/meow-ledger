package com.meow.ledger.shared.stats

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.meow.ledger.shared.bills.TYPE_EXPENSE
import com.meow.ledger.shared.db.Bill
import com.meow.ledger.shared.db.MeowDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 统计自定义起止区间：日期校验、按区间分桶、含起含止与默认周期互不影响。 */
class CustomStatsRangeTest {

    private fun seed(): Pair<MeowDatabase, StatsRepository> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MeowDatabase.Schema.create(driver)
        val db = MeowDatabase(driver)
        return db to StatsRepository(db)
    }

    private fun bill(clientId: String, money: Long, day: Long) = Bill(
        member_id = 0, client_id = clientId, money = money, refund_money = 0, type = TYPE_EXPENSE,
        exchange = 0, book_id = 1, member_cate_id = 1, currency = "CNY", day = day, desc = clientId,
        not_in_budget = 0, images = "", update_date = 0, create_date = 0, status = 0,
        to_assets_id = 0, from_assets_id = 0, interest = 0, interest_reckon_type = 0, tag_ids = "",
        source = 0, import_id = 0L, repayment_day = 0, local_create_time = 0, local_update_time = 0,
        installment_plan_id = "", installment_num = 0, installment_money = 0, installment_interest = 0,
    )

    @Test
    fun validDayAcceptsRealCalendarDatesOnly() {
        assertTrue(validStatsDay(20261115L))
        assertTrue(validStatsDay(20240229L))
        assertFalse(validStatsDay(20231131L))
        assertFalse(validStatsDay(20260229L))
        assertFalse(validStatsDay(20261100L))
        assertFalse(validStatsDay(20261301L))
        assertFalse(validStatsDay(2026111L))
    }

    @Test
    fun parseStatsRangeRejectsBadTextAndReversedDates() {
        assertEquals(StatsRange(20261101L, 20261130L), parseStatsRange("20261101", "20261130"))
        assertEquals("日期必须是 8 位 yyyyMMdd", parseStatsRange("2026111", "20261130").errorOrNull())
        assertEquals("请输入有效日期", parseStatsRange("20260230", "20261130").errorOrNull())
        assertEquals("结束日期必须大于或等于开始日期", parseStatsRange("20261201", "20261130").errorOrNull())
        assertEquals("日期必须是 8 位 yyyyMMdd", parseStatsRange("", "").errorOrNull())
    }

    @Test
    fun summaryIncludesBothRangeEndpoints() {
        val (db, repo) = seed()
        db.billQueries.upsert(bill("before", 1000, 20261031))
        db.billQueries.upsert(bill("start", 2000, 20261101))
        db.billQueries.upsert(bill("end", 3000, 20261130))
        db.billQueries.upsert(bill("after", 4000, 20261201))
        val range = parseStatsRange("20261101", "20261130") as StatsRange
        val summary = repo.summary(range.from, range.to, TYPE_EXPENSE, categoryName = { _ -> "餐饮" })
        assertEquals(5000, summary.expense)
        assertEquals(2, summary.count)
    }

    @Test
    fun trendOfRangeBucketsByMonthAndKeepsEmptyMonths() {
        val (db, repo) = seed()
        db.billQueries.upsert(bill("oct", 1000, 20251015))
        db.billQueries.upsert(bill("nov", 2000, 20251102))
        db.billQueries.upsert(bill("jan", 3000, 20260131))
        val range = parseStatsRange("20251101", "20260131") as StatsRange
        val trend = repo.trendOfRange(range.from, range.to, TrendPeriod.MONTH)
        assertEquals(listOf(202511, 202512, 202601), trend.map { it.key })
        assertEquals(listOf(2000L, 0L, 3000L), trend.map { it.expense })
        assertEquals(12, repo.trend(TrendPeriod.MONTH).size)
    }

    @Test
    fun billsOfPeriodUsesCustomRangeInsteadOfCurrentMonth() {
        val (db, repo) = seed()
        db.billQueries.upsert(bill("old", 1000, 20250501))
        db.billQueries.upsert(bill("inside", 2000, 20250615))
        val range = parseStatsRange("20250601", "20250630") as StatsRange
        val bills = repo.billsOfPeriod(range.from, range.to, TYPE_EXPENSE)
        assertEquals(1, bills.size)
        assertEquals("inside", bills.first().client_id)
    }

    private fun StatsRangeResult.errorOrNull(): String? = (this as? StatsRangeError)?.message
}
