package com.meow.ledger.shared.network

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.meow.ledger.shared.db.MeowDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 本地回环测试后端的契约验证。
 *
 * 这里用 ktor-client-mock 按本地地址（`http://127.0.0.1:18473/api/`）路由请求，
 * 不启动真实监听端口，也不连接外部网络。后端按明文 JSON 契约返回统一信封，
 * 验证字段名、整数分金额、幂等键、业务错误码和队列重试。
 */
class LocalBackendContractTest {
    private val base = "http://127.0.0.1:18473/api/"

    private class Backend(
        val base: String,
        val failurePath: String? = null,
    ) {
        val seen = mutableListOf<Pair<String, JsonObject?>>()
        val api: KtorMeowApi

        init {
            val engine = MockEngine { request ->
                seen += request.path() to request.bodyObject()
                respondFor(request)
            }
            api = KtorMeowApi(
                client = HttpClient(engine),
                headerProvider = DefaultHeaderProvider(
                    token = { "server-token" },
                    deviceId = { "device-test" },
                    appVersion = { "1.8.8" },
                    clock = { 1_700_000_000_000L },
                ),
                baseUrlProvider = BaseUrlProvider { _ -> base },
            )
        }

        private fun MockRequestHandleScope.respondFor(request: HttpRequestData): HttpResponseData {
            val path = request.path()
            if (path == failurePath) {
                return respondJson("""{"code":42,"message":"denied","data":null,"trace_id":"t-err"}""")
            }
            val data = when (path) {
                "Account/LoginByDevice" -> """{"userId":"u-1","token":"server-token"}"""
                "Bills/Create", "Bills/Update" -> """{"client_id":"c-1","saved":true}"""
                "Bills/PageList" -> """{"items":[{"client_id":"c-1","money":"1000"}]}"""
                "Cate/GetMainCates" -> """{"items":[{"id":"7","name":"午餐"}]}"""
                "Budget/GetBudgetList" -> """{"items":[{"day":"202611","amount":"50000"}]}"""
                "Behavior/Add" -> """{"accepted":true}"""
                else -> error("unexpected path: $path")
            }
            return respondJson("""{"code":0,"message":"","data":$data,"trace_id":"t-ok"}""")
        }

        private fun MockRequestHandleScope.respondJson(text: String): HttpResponseData =
            respond(text, headers = headersOf(HttpHeaders.ContentType, "application/json"))

        private fun HttpRequestData.path(): String = url.encodedPath.removePrefix("/api/")

        private fun HttpRequestData.bodyObject(): JsonObject? =
            (body as? io.ktor.http.content.TextContent)?.text?.let {
                Json.parseToJsonElement(it) as? JsonObject
            }
    }

    private fun billBody(): JsonObject = buildJsonObject {
        put("member_id", JsonPrimitive("0"))
        put("items", buildJsonArray {
            add(buildJsonObject {
                put("clientId", JsonPrimitive("c-1"))
                put("money", JsonPrimitive("1000"))
                put("billType", JsonPrimitive("1"))
            })
        })
    }

    private fun billPayload(): JsonObject = buildJsonObject { put("money", JsonPrimitive("1000")) }

    @Test
    fun `six core endpoints exchange plaintext json through unified envelope`() {
        val backend = Backend(base)
        runBlocking {
            backend.api.call(EndpointRequest("Account/LoginByDevice"))
            backend.api.call(EndpointRequest("Bills/Create", body = billBody()))
            backend.api.call(EndpointRequest("Bills/PageList", fields = mapOf("updateDate" to "2026-11-11", "pageNum" to "1")))
            backend.api.call(EndpointRequest("Cate/GetMainCates"))
            backend.api.call(EndpointRequest("Budget/GetBudgetList"))
            backend.api.call(EndpointRequest("Behavior/Add", body = buildJsonObject { put("event", JsonPrimitive("test")) }))
        }

        assertEquals(6, backend.seen.size)
        assertEquals("Account/LoginByDevice", backend.seen[0].first)
        val create = backend.seen[1].second!!
        val firstItem = create.getValue("items").jsonArray[0].jsonObject
        assertEquals("1000", firstItem.getValue("money").jsonPrimitive.content, "整数分金额")
        assertEquals("c-1", firstItem.getValue("clientId").jsonPrimitive.content, "端侧幂等键")
        assertEquals("1", backend.seen[2].second!!.getValue("pageNum").jsonPrimitive.content)
        assertEquals("2026-11-11", backend.seen[2].second!!.getValue("updateDate").jsonPrimitive.content)
        assertEquals("Cate/GetMainCates", backend.seen[3].first)
        assertEquals("Budget/GetBudgetList", backend.seen[4].first)
        assertEquals("test", backend.seen[5].second!!.getValue("event").jsonPrimitive.content)
    }

    @Test
    fun `business error code surfaces as ApiException with trace id`() {
        val backend = Backend(base, failurePath = "Bills/Create")
        val error = assertFailsWith<ApiException> {
            runBlocking { backend.api.call(EndpointRequest("Bills/Create", body = billPayload())) }
        }
        assertEquals(42, error.code)
        assertEquals("denied", error.message)
        assertEquals("t-err", error.traceId)
        assertEquals("Bills/Create", error.path)
    }

    @Test
    fun `offline queue retries with backoff and disabled remote sends nothing`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MeowDatabase.Schema.create(driver)
        val db = MeowDatabase(driver)
        val backend = Backend(base, failurePath = "Bills/Create")
        val engine = SyncEngine(db, backend.api)
        engine.enqueue("Bills/Create", "POST", billPayload(), "offline-1")

        val (ok, failed) = runBlocking { engine.flush() }
        assertEquals(0, ok)
        assertEquals(1, failed)
        assertEquals(1, engine.pendingCount())
        val row = db.s2Queries.selectPendingSync(Long.MAX_VALUE, 100L).executeAsList().single()
        assertEquals(1L, row.retry_count)
        assertTrue(row.error.contains("denied"), "业务错误写入 retry 原因")

        val offlineDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MeowDatabase.Schema.create(offlineDriver)
        val offlineDb = MeowDatabase(offlineDriver)
        val offlineEngine = SyncEngine(offlineDb, backend.api, remoteEnabled = { false })
        offlineEngine.enqueue("Bills/Create", "POST", billPayload(), "offline-2")
        val before = backend.seen.size
        val (ok2, failed2) = runBlocking { offlineEngine.flush() }
        assertEquals(0 to 0, ok2 to failed2)
        assertEquals(before, backend.seen.size, "remoteEnabled=false 不得发请求")
        assertEquals(1, offlineEngine.pendingCount())
        assertNotNull(offlineDb.s2Queries.selectPendingSync(Long.MAX_VALUE, 100L).executeAsList().single())
    }
}
