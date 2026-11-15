package com.meow.ledger.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * 旧安装的库可能缺列，但 `user_version` 已经被历史构建盖成新版本，SQLDelight 不会再跑迁移，
 * 第一次查询就会 `no such column` 崩在启动路径上。打开库后按列名补齐：列已存在就跳过，
 * 重复执行安全。只补历史上确认漏过迁移的列，不做通用 DDL 同步。
 */
object SchemaRepair {

    private data class Column(val table: String, val column: String, val ddl: String)

    private val columns = listOf(
        Column(
            table = "fixed_bill",
            column = "not_in_budget",
            ddl = "ALTER TABLE fixed_bill ADD COLUMN not_in_budget INTEGER NOT NULL DEFAULT 0",
        ),
    )

    fun run(driver: SqlDriver) {
        columns.forEach { fix ->
            val names = columnNames(driver, fix.table)
            // 表还不存在说明是全新库，schema 创建时会带齐列，这里不用管。
            if (names.isNotEmpty() && fix.column !in names) {
                driver.execute(null, fix.ddl, 0)
            }
        }
    }

    private fun columnNames(driver: SqlDriver, table: String): List<String> {
        val result = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) {
                    names += cursor.getString(1).orEmpty()
                }
                QueryResult.Value(names)
            },
            parameters = 0,
        )
        return (result as? QueryResult.Value)?.value.orEmpty()
    }
}
