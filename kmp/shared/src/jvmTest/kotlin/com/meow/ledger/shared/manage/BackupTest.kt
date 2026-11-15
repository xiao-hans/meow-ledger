package com.meow.ledger.shared.manage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.meow.ledger.shared.db.MeowDatabase
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupTest {
    private fun open(): Pair<MeowDatabase, JdbcSqliteDriver> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MeowDatabase.Schema.create(driver)
        return MeowDatabase(driver) to driver
    }

    @Test
    fun exportAndRestoreRoundTripAllTables() {
        val (sourceDb, sourceDriver) = open()
        val source = BackupRepository(sourceDb, sourceDriver)
        val clientId = UUID.randomUUID().toString()
        sourceDriver.execute(null, "INSERT INTO bill (member_id, client_id, money, day, desc) VALUES (0, '$clientId', 1234, 20261115, '午餐')", 0) {}
        sourceDriver.execute(null, "INSERT INTO bill_book (id, name) VALUES (7, '测试账本')", 0) {}
        sourceDriver.execute(null, "INSERT INTO category (id, title) VALUES (9, '餐饮')", 0) {}
        val exported = source.export()
        val preview = source.summary(exported)
        assertEquals(1, preview.bills)
        assertEquals(1, preview.books)
        assertEquals(1, preview.categories)
        // 真机出现过索引 -1：必须在导出阶段真的移动游标，并把已存在的行带出来。
        val exportedRow = exported.substringAfter("\"bill\":").substringAfter("1234")
        assertTrue(exportedRow.contains("午餐"))

        val (targetDb, targetDriver) = open()
        val target = BackupRepository(targetDb, targetDriver)
        val restored = target.restore(exported)
        assertEquals(preview, restored.preview)
        assertEquals("meow-ledger-backup", restored.previousBackup.substringAfter("\"format\":\"").substringBefore("\""))
        val names = mutableListOf<String>()
        targetDriver.executeQuery(null, "SELECT name FROM bill_book", { cursor ->
            while (cursor.next().value) names += cursor.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Value(Unit)
        }, 0) {}
        assertEquals(listOf("测试账本"), names)
    }

    @Test
    fun rejectsBadBackupWithoutChangingData() {
        val (db, driver) = open()
        val backup = BackupRepository(db, driver)
        val clientId = UUID.randomUUID().toString()
        driver.execute(null, "INSERT INTO bill (member_id, client_id, money, day, desc) VALUES (0, '$clientId', 999, 20261115, '保留')", 0) {}
        assertFailsWith<IllegalArgumentException> { backup.restore("not json") }
        assertFailsWith<IllegalArgumentException> { backup.restore("""{"format":"wrong","version":1,"tables":{}}""") }
        val count = driver.executeQuery(null, "SELECT COUNT(*) FROM bill", { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)!!)
        }, 0) {}
        assertEquals(1L, count.value)
    }
}
