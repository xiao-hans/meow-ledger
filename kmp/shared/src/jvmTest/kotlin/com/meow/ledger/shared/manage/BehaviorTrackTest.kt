package com.meow.ledger.shared.manage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.meow.ledger.shared.auto.AutoRepository
import com.meow.ledger.shared.bills.BillsRepository
import com.meow.ledger.shared.db.MeowDatabase
import com.meow.ledger.shared.network.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BehaviorTrackTest {
    private fun newDb(): MeowDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MeowDatabase.Schema.create(driver)
        return MeowDatabase(driver)
    }

    @Test
    fun `capture confirm and save write ac events`() {
        val db = newDb()
        val account = AccountRepository(db)
        val auto = AutoRepository(db)
        val result = auto.capture("微信支付 42.50 瑞幸咖啡", "ocr", sourceApp = "com.tencent.mm")
        assertEquals(1, result.created)
        auto.confirm(auto.pending().first())
        val bills = BillsRepository(db)
        bills.saveWithBalance(bill(clientId = "t1", money = 1000))
        assertEquals(1, account.events("ac_auto_capture").size)
        assertEquals(1, account.events("ac_auto_confirm").size)
        assertEquals(1, account.events("ac_add_bill").size)
    }

    @Test
    fun `unknown kind is rejected and not written`() {
        val db = newDb()
        val account = AccountRepository(db)
        assertNull(account.track("ac_not_in_catalog", "x"))
        assertEquals(0, account.events("ac_not_in_catalog").size)
    }

    @Test
    fun `track enqueues behavior add when sync present`() {
        val db = newDb()
        val engine = SyncEngine(db)
        val account = AccountRepository(db, engine)
        account.track("ac_add_bill", "c1|1000|1")
        assertEquals(1, engine.pendingCount())
        assertEquals(
            "Behavior/Add",
            db.s2Queries.selectPendingSync(Long.MAX_VALUE, 100L).executeAsList().single().path,
        )
    }

    @Test
    fun `track still enqueues when remote disabled`() {
        val db = newDb()
        val engine = SyncEngine(db, remoteEnabled = { false })
        val account = AccountRepository(db, engine)
        account.track("ac_add_bill", "c1|1000|1")
        assertEquals(1, engine.pendingCount())
    }

    @Test
    fun `feedback and token refresh remain allowed`() {
        val db = newDb()
        val account = AccountRepository(db)
        account.track(FEEDBACK_KIND, "这是反馈内容")
        account.track(TOKEN_REFRESH_KIND, "token")
        assertEquals(1, account.events(FEEDBACK_KIND).size)
        assertEquals(1, account.events(TOKEN_REFRESH_KIND).size)
    }

    private fun bill(clientId: String, money: Long) =
        com.meow.ledger.shared.db.Bill(
            member_id = 0, client_id = clientId, money = money, refund_money = 0,
            type = 1, exchange = 0, book_id = 1, member_cate_id = 1, currency = "CNY",
            day = 20261112, desc = "测试", not_in_budget = 0, images = "", update_date = 0,
            create_date = 20261112, status = 0, to_assets_id = 0, from_assets_id = 0,
            interest = 0, interest_reckon_type = 0, tag_ids = "", source = 0, import_id = 0,
            repayment_day = 0, local_create_time = 20261112, local_update_time = 20261112,
            installment_plan_id = "", installment_num = 0, installment_money = 0,
            installment_interest = 0,
        )
}
