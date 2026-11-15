package com.meow.ledger.shared.manage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.meow.ledger.shared.db.MeowDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 全库备份与恢复。按当前 schema 的列名逐表读写，不用私有数据库文件，
 * 因此可在 Android 与 JVM 之间互换，也不会把旧库的 user_version 带进新安装。
 */
class BackupRepository(private val db: MeowDatabase, private val driver: SqlDriver) {

    /** 一次备份的概要，供恢复前预览与二次确认。 */
    fun summary(text: String): BackupPreview = parseBackup(text).preview()

    /** 导出当前数据库的全部用户数据。密码、令牌一类字段不在本库表内。 */
    fun export(): String {
        val tables = TABLES.associate { (table, columns) ->
            val rows = mutableListOf<JsonArray>()
            driver.select(table, columns) { cursor ->
                while (cursor.next().value) rows += JsonArray(columns.mapIndexed { index, column ->
                    val value = if (column.type == ColumnType.INTEGER) {
                        cursor.getLong(index)?.let { JsonPrimitive(it) }
                    } else {
                        cursor.getString(index)?.let { JsonPrimitive(it) }
                    }
                    value ?: JsonNull
                })
                QueryResult.Value(Unit)
            }
            table to JsonArray(rows)
        }
        val backup = BackupEnvelope(format = BACKUP_FORMAT, version = BACKUP_VERSION, tables = JsonObject(tables))
        return Json.encodeToString(backup)
    }

    /**
     * 从备份恢复：先校验格式/版本/列名，再在单个事务里清空当前数据并写入备份。
     * 恢复前会先把当前数据导出一份，调用方应把它保存下来以便撤销。
     */
    fun restore(text: String): RestoreResult {
        val backup = parseBackup(text)
        val before = export()
        db.transaction {
            TABLES.forEach { (table, _) -> driver.exec("DELETE FROM $table") }
            // 外键按依赖顺序不启用；显式顺序保证父表先于子表。
            TABLES.forEach { (table, columns) ->
                val rows = backup.tables[table]?.jsonArray ?: JsonArray(emptyList())
                rows.forEach { element ->
                    val cells = element.jsonArray
                    require(cells.size == columns.size) { "$table 的列数与备份不一致" }
                    val values = columns.mapIndexed { index, column ->
                        if (column.type == ColumnType.INTEGER) (cells[index].jsonPrimitive.longOrNull ?: 0L).toString()
                        else "'" + cells[index].jsonPrimitive.contentOrNull.orEmpty().replace("'", "''") + "'"
                    }
                    driver.exec("INSERT OR REPLACE INTO $table (${columns.joinToString(",") { it.name }}) VALUES (${values.joinToString(",")})")
                }
            }
        }
        return RestoreResult(backup.preview(), before)
    }

    private fun parseBackup(text: String): BackupEnvelope {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw IllegalArgumentException("这不是有效的备份文件")
        }
        val format = root["format"]?.jsonPrimitive?.contentOrNull
        val version = root["version"]?.jsonPrimitive?.longOrNull
        require(format == BACKUP_FORMAT) { "不是喵啊记账的备份文件" }
        require(version == BACKUP_VERSION) { "备份版本不兼容：需要 $BACKUP_VERSION，文件是 $version" }
        val tables = root["tables"]?.jsonObject ?: throw IllegalArgumentException("备份文件缺少数据表")
        TABLES.forEach { (table, columns) ->
            val rows = tables[table] ?: throw IllegalArgumentException("备份文件缺少 $table")
            val first = rows.jsonArray.firstOrNull() ?: return@forEach
            require(first.jsonArray.size == columns.size) { "$table 的列数与当前版本不一致" }
        }
        return BackupEnvelope(format!!, version!!, tables)
    }

    private fun BackupEnvelope.preview(): BackupPreview = BackupPreview(
        version = version,
        bills = tables["bill"]?.jsonArray?.size ?: 0,
        books = tables["bill_book"]?.jsonArray?.size ?: 0,
        categories = tables["category"]?.jsonArray?.size ?: 0,
        rows = tables.values.sumOf { it.jsonArray.size },
    )

    private fun SqlDriver.select(table: String, columns: List<Column>, mapper: (SqlCursor) -> QueryResult<Unit>) {
        executeQuery(null, "SELECT ${columns.joinToString(",") { it.name }} FROM $table", mapper, 0) {}
    }

    private fun SqlDriver.exec(sql: String) {
        execute(null, sql, 0) {}
    }
}

private enum class ColumnType { TEXT, INTEGER }
private data class Column(val name: String, val type: ColumnType)
private fun C(name: String, type: ColumnType = ColumnType.TEXT) = Column(name, type)
private val I = ColumnType.INTEGER

// 与 sqldelight 的 CREATE TABLE 列顺序一致；恢复按列名写入，不依赖列序。
private val TABLES: List<Pair<String, List<Column>>> = listOf(
    "member_profile" to listOf(C("id", I), C("nickname"), C("phone"), C("login_type"), C("bind_type"), C("bind_id"), C("vip", I), C("vip_expire", I)),
    "sms_code" to listOf(C("phone"), C("code"), C("expires", I)),
    "member_import_bill_file" to listOf(C("id"), C("name"), C("type"), C("count", I), C("status", I), C("create_time", I), C("content")),
    "member_order" to listOf(C("id"), C("money", I), C("method"), C("type", I), C("product_id"), C("product_name"), C("status", I), C("create_time", I)),
    "contract_info" to listOf(C("id", I), C("signed", I), C("provider"), C("product_name"), C("money", I), C("next_date")),
    "behavior_event" to listOf(C("id"), C("kind"), C("content"), C("contact"), C("create_time", I)),
    "assets" to listOf(C("id", I), C("type_id", I), C("type", I), C("type_name"), C("type_title"), C("name"), C("desc"), C("icon"), C("currency"), C("create_date", I), C("update_date", I), C("credit_line", I), C("repayment_date", I), C("statement_date", I), C("is_remind", I), C("is_total_assets", I), C("is_next_bills", I), C("status", I), C("balance_cents", I)),
    "auto_event" to listOf(C("id"), C("hash"), C("source"), C("source_app"), C("merchant"), C("money", I), C("type", I), C("occurred_at", I), C("raw_text"), C("status", I), C("member_cate_id", I), C("assets_id", I), C("tag_ids"), C("book_id", I), C("desc"), C("rule_id", I), C("create_time", I)),
    "bill" to listOf(C("member_id", I), C("client_id"), C("money", I), C("refund_money", I), C("type", I), C("exchange", I), C("book_id", I), C("member_cate_id", I), C("currency"), C("day", I), C("desc"), C("not_in_budget", I), C("images"), C("update_date", I), C("create_date", I), C("status", I), C("to_assets_id", I), C("from_assets_id", I), C("interest", I), C("interest_reckon_type", I), C("tag_ids"), C("source", I), C("import_id", I), C("repayment_day", I), C("local_create_time", I), C("local_update_time", I), C("installment_plan_id"), C("installment_num", I), C("installment_money", I), C("installment_interest", I)),
    "bill_book" to listOf(C("id", I), C("name"), C("icon_id", I), C("icon"), C("tag"), C("create_date", I), C("month_start_date", I)),
    "member_book" to listOf(C("member_id", I), C("book_id", I), C("role", I)),
    "budget" to listOf(C("book_id", I), C("day", I), C("money", I), C("acf_money", I), C("is_remind", I), C("remind_rate", I), C("carry_down", I), C("is_copy", I)),
    "cate_budget" to listOf(C("book_id", I), C("day", I), C("member_cate_id", I), C("money", I), C("acf_money", I)),
    "category" to listOf(C("id", I), C("member_id", I), C("cate_id", I), C("type", I), C("title"), C("icon_id", I), C("base_icon"), C("icon"), C("is_custom", I), C("status", I), C("sort", I), C("hidden", I)),
    "remote_config" to listOf(C("key"), C("value"), C("update_time", I)),
    "user_setting" to listOf(C("key"), C("value")),
    "fixed_bill" to listOf(C("id"), C("member_cate_id", I), C("money", I), C("type", I), C("fixed_type", I), C("fixed_day", I), C("book_id", I), C("assets_id", I), C("desc"), C("tag_ids"), C("status", I), C("not_in_budget", I), C("next_execute_date", I), C("create_time", I), C("update_time", I)),
    "bill_remind" to listOf(C("id"), C("title"), C("time"), C("day", I), C("hour", I), C("fixed_bill_id"), C("repeat_type", I), C("enabled", I), C("create_time", I), C("update_time", I)),
    "group_vip" to listOf(C("id"), C("show_id"), C("status", I), C("create_time", I)),
    "installment_plan" to listOf(C("id"), C("member_id", I), C("name"), C("deduction_asset_id", I), C("start_date", I), C("money", I), C("total_count", I), C("executed_count", I), C("interest", I), C("interest_type", I), C("remainder_process_type", I), C("remaining_money", I), C("remaining_interest", I), C("next_execute_date", I), C("book_id", I), C("member_cate_id", I), C("not_in_budget", I), C("desc"), C("images"), C("tag_ids"), C("status", I), C("create_time", I), C("update_time", I)),
    "save_plan" to listOf(C("id"), C("name"), C("icon"), C("type_id", I), C("target_money", I), C("saved_money", I), C("status", I), C("archived", I), C("desc"), C("create_time", I), C("update_time", I)),
    "save_plan_item" to listOf(C("id"), C("plan_id"), C("num", I), C("money", I), C("status", I), C("remark"), C("create_time", I), C("update_time", I)),
    "installment_item" to listOf(C("id"), C("plan_id"), C("num", I), C("money", I), C("interest", I), C("status", I), C("execute_date", I)),
    "refund_bill" to listOf(C("id"), C("client_id"), C("member_id", I), C("money", I), C("asset_id", I), C("day", I), C("desc"), C("images"), C("source", I), C("status", I), C("create_date", I), C("update_date", I)),
    "rule" to listOf(C("id", I), C("key_word"), C("source", I), C("member_id", I), C("type", I), C("book_id", I), C("member_cate_id", I), C("assets_id", I), C("tag_ids"), C("create_date", I), C("update_date", I)),
    "bill_tag" to listOf(C("client_id"), C("member_id", I), C("tag_id", I)),
    "bill_remark" to listOf(C("client_id"), C("member_id", I), C("content"), C("images"), C("create_date", I), C("update_date", I)),
    "cate_extend" to listOf(C("member_cate_id", I), C("book_id", I), C("member_id", I), C("type", I), C("sort", I), C("hidden", I), C("status", I), C("create_date", I), C("update_date", I)),
    "member_assets" to listOf(C("member_id", I), C("book_id", I), C("appear", I), C("income", I), C("is_master", I), C("join_date", I)),
    "image_info" to listOf(C("id"), C("client_id"), C("path"), C("url"), C("source", I), C("create_date", I)),
    "widget_base" to listOf(C("id"), C("kind"), C("background"), C("transparency", I), C("text_color"), C("show_bills", I), C("update_time", I)),
    "widget_desktop" to listOf(C("id"), C("widget_id"), C("size"), C("position", I), C("config"), C("update_time", I)),
    "widget_hot" to listOf(C("id"), C("title"), C("path"), C("count", I), C("update_time", I)),
    "sync_queue" to listOf(C("id"), C("path"), C("method"), C("payload"), C("client_id"), C("status", I), C("retry_count", I), C("next_attempt", I), C("create_time", I), C("update_time", I), C("error")),
    "tag" to listOf(C("tag_id", I), C("book_id", I), C("member_id", I), C("name"), C("sort", I), C("create_date", I), C("update_date", I)),
    "widget_user" to listOf(C("id"), C("name"), C("size"), C("kind"), C("create_time", I)),
)

private const val BACKUP_FORMAT = "meow-ledger-backup"
private const val BACKUP_VERSION = 1L

@Serializable
private data class BackupEnvelope(
    val format: String,
    val version: Long,
    val tables: JsonObject,
)

data class BackupPreview(val version: Long, val bills: Int, val books: Int, val categories: Int, val rows: Int)
data class RestoreResult(val preview: BackupPreview, val previousBackup: String)
