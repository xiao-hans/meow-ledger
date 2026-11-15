package com.meow.ledger.shared.manage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.meow.ledger.shared.db.MeowDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M7：预算包含/排除视图与复制上月（isCopy 语义）。 */
class BudgetIncludeTest {

    private fun repo(): Pair<BudgetRepository, MeowDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MeowDatabase.Schema.create(driver)
        val db = MeowDatabase(driver)
        return BudgetRepository(db) to db
    }

    @Test
    fun copyPreviousClonesBudgetAndCateRows() {
        val (repo, db) = repo()
        repo.save(202611, 100000, remind = true, remindRate = 80)
        repo.saveCategory(202611, 9, 30000)
        val copied = repo.copyPrevious(202612)!!
        assertEquals(100000, copied.money)
        assertEquals(1, copied.is_copy)
        assertEquals(30000, db.budgetQueries.selectByMonth(1, 202612).executeAsList().first().money)
        // 上月原样保留
        assertEquals(100000, db.budgetQueries.selectOne(1, 202611).executeAsOneOrNull()!!.money)
        assertEquals(0, db.budgetQueries.selectOne(1, 202611).executeAsOneOrNull()!!.is_copy)
    }

    @Test
    fun copyPreviousCrossesYear() {
        val (repo, db) = repo()
        repo.save(202612, 50000, remind = false, remindRate = 80)
        assertEquals(50000, repo.copyPrevious(202701)!!.money)
        assertEquals(50000, db.budgetQueries.selectOne(1, 202701).executeAsOneOrNull()!!.money)
    }

    @Test
    fun copyWithoutPreviousReturnsNull() {
        val (repo, db) = repo()
        assertNull(repo.copyPrevious(202611))
        assertEquals(0, db.budgetQueries.selectByMonth(1, 202611).executeAsList().size)
        assertNull(db.budgetQueries.selectOne(1, 202611).executeAsOneOrNull())
    }

    @Test
    fun copyYearClonesEveryMonthAndCateRows() {
        val (repo, db) = repo()
        for (month in 1..12) {
            val day = 2025 * 100L + month
            repo.save(day, 100000L + month, remind = false, remindRate = 80)
            repo.saveCategory(day, 9, 3000L + month)
        }
        assertEquals(12, repo.copyYear(2025, 2026))
        for (month in 1..12) {
            val source = 2025 * 100L + month
            val target = 2026 * 100L + month
            val copied = db.budgetQueries.selectOne(1, target).executeAsOneOrNull()!!
            assertEquals(100000L + month, copied.money)
            assertEquals(1, copied.is_copy)
            assertEquals(3000L + month, db.budgetQueries.selectByMonth(1, target).executeAsList().first().money)
            assertEquals(100000L + month, db.budgetQueries.selectOne(1, source).executeAsOneOrNull()!!.money)
        }
    }

    @Test
    fun copyYearSkipsExistingTargetMonthsAndEmptySourceMonths() {
        val (repo, db) = repo()
        repo.save(202501, 50000, remind = false, remindRate = 80)
        repo.saveCategory(202501, 9, 10000)
        repo.save(202503, 30000, remind = false, remindRate = 80)
        repo.save(202601, 99999, remind = true, remindRate = 90)
        repo.saveCategory(202601, 9, 77777)
        assertEquals(1, repo.copyYear(2025, 2026))
        assertEquals(99999, db.budgetQueries.selectOne(1, 202601).executeAsOneOrNull()!!.money)
        assertEquals(77777, db.budgetQueries.selectByMonth(1, 202601).executeAsList().first().money)
        assertEquals(30000, db.budgetQueries.selectOne(1, 202603).executeAsOneOrNull()!!.money)
        assertNull(db.budgetQueries.selectOne(1, 202602).executeAsOneOrNull())
    }

    @Test
    fun copyYearReplacesStaleTargetCateRows() {
        val (repo, db) = repo()
        repo.save(202505, 50000, remind = false, remindRate = 80)
        repo.saveCategory(202505, 9, 10000)
        db.budgetQueries.upsertCate(
            com.meow.ledger.shared.db.Cate_budget(
                book_id = 1,
                day = 202605,
                member_cate_id = 42,
                money = 9999,
                acf_money = 0,
            ),
        )
        assertEquals(1, repo.copyYear(2025, 2026))
        val cates = db.budgetQueries.selectByMonth(1, 202605).executeAsList()
        assertEquals(1, cates.size)
        assertEquals(9, cates.first().member_cate_id)
        assertEquals(10000, cates.first().money)
    }

    @Test
    fun copyYearWithoutSourceReturnsZero() {
        val (repo, db) = repo()
        assertEquals(0, repo.copyYear(2025, 2026))
        assertEquals(0, db.budgetQueries.selectByMonth(1, 202612).executeAsList().size)
        assertNull(db.budgetQueries.selectOne(1, 202612).executeAsOneOrNull())
    }

    @Test
    fun viewSplitsIncludedAndExcluded() {
        val (repo, db) = repo()
        val catRepo = com.meow.ledger.shared.bills.CategoryRepository(db)
        val food = catRepo.create("餐饮", com.meow.ledger.shared.bills.TYPE_EXPENSE, 0)
        val drink = catRepo.create("饮料", com.meow.ledger.shared.bills.TYPE_EXPENSE, 0)
        repo.save(202611, 100000, remind = false, remindRate = 80)
        repo.saveCategory(202611, food.id, 30000)
        val view = repo.view(202611)
        val included = view.categories.filter { it.money > 0 }
        val excluded = view.categories.filter { it.money == 0L }
        assertEquals(1, included.size)
        assertEquals("餐饮", included.first().title)
        assertTrue(excluded.any { it.categoryId == drink.id })
        assertTrue(excluded.none { it.categoryId == food.id })
    }
}
