package com.meow.ledger.shared.network

import com.meow.ledger.shared.manage.AhAuthClient
import com.meow.ledger.shared.manage.AhLoginResult
import com.meow.ledger.shared.manage.AhUser
import com.meow.ledger.shared.manage.AppAuthToken
import com.meow.ledger.shared.manage.QqAuthResult
import com.meow.ledger.shared.manage.QqSessionState
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * AH 账号体系的真实客户端。
 *
 * 与 [KtorMeowApi] 不同，这里**不复用** `MeowApi`：AH 的响应不是 `{code,message,data,trace_id}`
 * 加 `data` 密文那套，而是明文 JSON、HTTP 200 携带 `code:0`、错误用非 2xx + `{"code":403,...}`。
 * 混用两套信封只会让错误处理更绕。
 *
 * 三个端点（文档 §2）：
 *  - `POST {base}/auth/login`
 *  - `POST {base}/auth/refresh`
 *  - `GET  {base}/user`
 */
class KtorAhAuthClient(
    private val baseUrl: String,
    private val client: HttpClient = KtorMeowApi.defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    /** 允许测试注入固定的 `app_auth_token`；为空时按 [AppAuthToken.build] 现算。 */
    private val appAuthToken: (Long) -> String = { AppAuthToken.build(it) },
) : AhAuthClient {

    private fun url(path: String): String = baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')

    override suspend fun login(auth: QqAuthResult, nowSeconds: Long): AhLoginResult {
        val ts = nowSeconds.toString()
        val body = buildJsonObject {
            put("account", JsonPrimitive(auth.openId))
            // 样本里 password 与 account 同值（文档 §1 的真实请求体）。
            put("password", JsonPrimitive(auth.openId))
            put("is_third_party", JsonPrimitive(true))
            if (auth.nickname.isNotBlank()) put("nickname", JsonPrimitive(auth.nickname))
            if (auth.avatarUrl.isNotBlank()) put("avatar_url", JsonPrimitive(auth.avatarUrl))
            put("app_auth_token", JsonPrimitive(appAuthToken(nowSeconds)))
            put("ts", JsonPrimitive(ts))
        }
        return post("auth/login", body, nowSeconds, auth)
    }

    override suspend fun refresh(refreshToken: String): AhLoginResult {
        val body = buildJsonObject { put("refresh_token", JsonPrimitive(refreshToken)) }
        return post("auth/refresh", body, 0L, null)
    }

    override suspend fun user(accessToken: String): AhUser? {
        val response = client.request(url("user")) {
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: root
        return parseUser(data)
    }

    private suspend fun post(
        path: String,
        body: JsonObject,
        nowSeconds: Long,
        auth: QqAuthResult?,
    ): AhLoginResult {
        val response = try {
            client.request(url(path)) {
                method = HttpMethod.Post
                header(HttpHeaders.ContentType, "application/json;charset=UTF-8")
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(TextContent(json.encodeToString(JsonObject.serializer(), body), ContentType.Application.Json))
            }
        } catch (error: Exception) {
            return AhLoginResult.Failed(error.message ?: "网络不可达")
        }
        val text = try {
            response.bodyAsText()
        } catch (error: Exception) {
            return AhLoginResult.Failed(error.message ?: "读取响应失败")
        }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return AhLoginResult.Failed("响应不是 JSON：${text.take(120)}")
        val code = root["code"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }?.toInt() ?: 0
        val message = root["message"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
        if (response.status.value !in 200..299 || code != 0) {
            val effective = if (code != 0) code else response.status.value
            return AhLoginResult.Rejected(effective, message.ifBlank { "HTTP ${response.status.value}" })
        }
        return parseSuccess(root, auth, nowSeconds)
    }

    /**
     * 登录响应：`data.{is_new_user,user,access_token,refresh_token,access_expires_at,refresh_expires_at}`。
     * 刷新响应字段名文档标「待实测」，客户端按 `access_token` 与 `data.access_token` 两种形态读，
     * 这里两种都认。
     */
    private fun parseSuccess(root: JsonObject, auth: QqAuthResult?, nowSeconds: Long): AhLoginResult {
        val data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: root
        val userObj = data["user"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val user = userObj?.let { parseUser(it) } ?: AhUser(account = auth?.openId.orEmpty())
        val access = string(data, "access_token").ifBlank { string(root, "access_token") }
        val refresh = string(data, "refresh_token").ifBlank { string(root, "refresh_token") }
        if (access.isBlank() && refresh.isBlank()) {
            return AhLoginResult.Failed("响应里没有 access_token / refresh_token")
        }
        val accessExp = long(data, "access_expires_at").ifZero { nowSeconds + 3600 }
        val refreshExp = long(data, "refresh_expires_at").ifZero { nowSeconds + 30L * 24 * 3600 }
        val isNew = data["is_new_user"]?.let { runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull() } ?: false
        return AhLoginResult.Success(
            session = QqSessionState(
                account = user.account.ifBlank { auth?.openId.orEmpty() },
                nickname = user.nickname.ifBlank { auth?.nickname.orEmpty() },
                avatarUrl = user.avatarUrl.ifBlank { auth?.avatarUrl.orEmpty() },
                userNo = user.userNo,
                remoteId = user.id,
                accessToken = access,
                refreshToken = refresh,
                accessExpiresAt = accessExp,
                refreshExpiresAt = refreshExp,
                updateTime = nowSeconds,
            ),
            isNewUser = isNew,
            user = user,
        )
    }

    private fun parseUser(obj: JsonObject): AhUser = AhUser(
        id = long(obj, "id"),
        userNo = long(obj, "user_no"),
        account = string(obj, "account"),
        nickname = string(obj, "nickname"),
        avatarUrl = string(obj, "avatar_url"),
        vip = long(obj, "vip").toInt(),
        vipExpiresAt = long(obj, "vip_expires_at"),
        createdAt = string(obj, "created_at"),
    )

    private fun string(obj: JsonObject, key: String): String =
        obj[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

    private fun long(obj: JsonObject, key: String): Long =
        obj[key]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() } ?: 0L

    private inline fun Long.ifZero(fallback: () -> Long): Long = if (this == 0L) fallback() else this
}
