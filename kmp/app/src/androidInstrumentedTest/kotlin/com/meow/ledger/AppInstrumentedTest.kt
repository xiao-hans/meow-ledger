package com.meow.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.meow.ledger.shared.auto.parseDeepLink
import com.meow.ledger.shared.auto.DeepLinkTarget
import com.meow.ledger.shared.db.MeowDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机仪器测试的最小集合：应用能启动、数据库结构与版本一致、深链解析可用。
 * 启动用例专门防「旧库缺列但版本号已经是新高」这类只在真机升级时出现的崩溃。
 */
@RunWith(AndroidJUnit4::class)
class AppInstrumentedTest {

    @Test
    fun appLaunchesWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(!activity.isFinishing)
            }
        }
    }

    @Test
    fun schemaHasColumnsIntroducedAfterV3() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)
        val driver = AndroidSqliteDriver(MeowDatabase.Schema, context, DB_NAME)
        val db = MeowDatabase(driver)
        try {
            val sqlite = android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath(DB_NAME).path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
            val hasNotInBudget = sqlite.rawQuery("PRAGMA table_info(fixed_bill)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "not_in_budget") found = true
                }
                found
            }
            sqlite.close()
            assertTrue(hasNotInBudget)
            // 表不存在时这里会抛错，等于断言 4.sqm 的 ticket_image 已在 schema 里。
            assertTrue(db.ticketQueries.selectByEvent("none").executeAsList().isEmpty())
        } finally {
            driver.close()
            context.deleteDatabase(DB_NAME)
        }
    }

    @Test
    fun deepLinkResolvesBillEdit() {
        val target = parseDeepLink("bill-edit") { key -> if (key == "id") "42" else null }
        assertEquals(DeepLinkTarget.Kind.BILL_EDIT, target.kind)
        assertEquals("42", target.clientId)
    }

    private companion object {
        const val DB_NAME = "instrumented-check.db"
    }
}
