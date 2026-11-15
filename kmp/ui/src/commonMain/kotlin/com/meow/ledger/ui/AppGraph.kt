package com.meow.ledger.ui

import app.cash.sqldelight.db.SqlDriver
import com.meow.ledger.shared.db.MeowDatabase
import com.meow.ledger.shared.network.MeowApi
import com.meow.ledger.shared.network.SyncEngine

/** 进程级依赖入口。app 模块在启动时填充，ui 模块只读。 */
object AppGraph {
    lateinit var database: MeowDatabase

    /** 全库备份直接读写当前驱动；与 database 来自同一个 SQLite 连接。 */
    lateinit var driver: SqlDriver

    /** 同步引擎；app 启动时用 [api] 构造。 */
    var sync: SyncEngine? = null

    /** 真实 API（KtorMeowApi）；供后续需要直接发请求的页面/仓储使用。 */
    var api: MeowApi? = null
}
