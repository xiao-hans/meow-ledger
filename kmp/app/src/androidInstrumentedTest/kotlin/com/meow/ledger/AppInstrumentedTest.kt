package com.meow.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.meow.ledger.shared.auto.parseDeepLink
import com.meow.ledger.shared.auto.DeepLinkTarget
import com.meow.ledger.shared.db.MeowDatabase
import com.meow.ledger.ui.AppGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机仪器测试的最小集合：应用能启动、数据库结构与版本一致、深链解析可用。
 * 启动用例专门防「旧库缺列但版本号已经是新高」这类只在真机升级时出现的崩溃。
 */
@RunWith(AndroidJUnit4::class)
class AppInstrumentedTest {

    /**
     * 进程已经跑完 `MeowApp.onCreate`（所有用例都依赖它），这里断言启动链路真的把库注入了。
     * 不在仪器测试里拉起 Compose 界面：vivo 会拦后台 Activity 启动，ActivityScenario 会无限等 RESUMED。
     */
    @Test
    fun applicationInitializedDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolved = android.content.Intent(context, MainActivity::class.java)
            .resolveActivity(context.packageManager)
        assertNotNull("MainActivity 未在 Manifest 声明", resolved)
        assertNotNull("AppGraph.database 未初始化", AppGraph.database)
    }

    @Test
    fun schemaHasColumnsIntroducedAfterV3() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)
        val driver = AndroidSqliteDriver(MeowDatabase.Schema, context, DB_NAME)
        val db = MeowDatabase(driver)
        try {
            // 先跑一次查询，驱动才会真正落盘建库；否则下面只读打开会找不到文件。
            assertTrue(db.ticketQueries.selectByEvent("none").executeAsList().isEmpty())
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
        } finally {
            driver.close()
            context.deleteDatabase(DB_NAME)
        }
    }

    @Test
    fun deepLinkResolvesBillEdit() {
        val target = parseDeepLink("bill-edit") { key -> if (key == "clientId") "42" else null }
        assertEquals(DeepLinkTarget.Kind.BILL_EDIT, target.kind)
        assertEquals("42", target.clientId)
    }

    private companion object {
        const val DB_NAME = "instrumented-check.db"
    }
}
