package com.meow.ledger.shared.manage

/**
 * QQ 登录的客户端侧契约（AH 账号体系，样本 `com.ai.h`）。
 *
 * 一手证据：`D:\workspace\AHVideo\AH视频_QQ登录接口速查.md`、
 * `D:\workspace\AHVideo\AH视频_账号体系登录注册接口文档.md`。
 *
 * 三个事实决定了本文件的形状：
 *  1. QQ 登录**没有专用换取端点**：客户端把 QQ 授权结果直接提交 `POST /auth/login`，
 *     带 `is_third_party=true`。`account` 是 QQ 侧 32 位大写十六进制标识，`password` 与
 *     `account` 同值。
 *  2. 登录请求必须带秒级 `ts` 与 `app_auth_token`；`ts` 窗口 −299..+300 秒，
 *     令牌窗口 300 秒。签名算法在 Dart AOT 里，未取到（文档 §8 标 C 级）。
 *  3. `POST /auth/refresh` **不校验** `ts` 与 `app_auth_token`，只读 `refresh_token`，
 *     是当前唯一不依赖签名的登录态入口。
 *
 * 因此本文件把「拿 QQ 授权」与「换 AH 登录态」分开：
 * [QqAuthResult] 是 QQ 侧产物，[AhAuthClient] 是与 AH 服务端的三次调用。
 * 签名令牌拿不到时，[AhAuthClient.login] 的失败会被原样带回 UI，而不是假装成功。
 */

/** QQ 授权结果。`openId` 即 AH 服务端认的 `account`。 */
data class QqAuthResult(
    val openId: String,
    val nickname: String = "",
    val avatarUrl: String = "",
    val accessToken: String = "",
)

/** 登录成功后落库的会话。 */
data class QqSessionState(
    val account: String,
    val nickname: String,
    val avatarUrl: String,
    val userNo: Long,
    val remoteId: Long,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val updateTime: Long,
) {
    /** 访问令牌是否还在有效期内；差 30 秒就当作过期，避免边界上白跑一次 401。 */
    fun accessValid(nowSeconds: Long): Boolean = accessToken.isNotBlank() && accessExpiresAt - 30 > nowSeconds
}

/** AH 服务端返回的用户对象。字段名与文档 §2 的响应一致。 */
data class AhUser(
    val id: Long = 0,
    val userNo: Long = 0,
    val account: String = "",
    val nickname: String = "",
    val avatarUrl: String = "",
    val vip: Int = 0,
    val vipExpiresAt: Long = 0,
    val createdAt: String = "",
)

/** `POST /auth/login` 的结果。 */
sealed interface AhLoginResult {
    data class Success(val session: QqSessionState, val isNewUser: Boolean, val user: AhUser) : AhLoginResult

    /** 服务端明确拒绝：`403` / `401` / `422` / `429`。`message` 是服务端原话。 */
    data class Rejected(val code: Int, val message: String) : AhLoginResult

    /** 网络不可达、超时、响应不是合法 JSON。 */
    data class Failed(val message: String) : AhLoginResult
}

/**
 * 与 AH 账号服务的三次调用。实现必须可替换：默认沙箱不联网。
 */
interface AhAuthClient {
    /**
     * `POST /auth/login`。`appAuthToken` 为空时按样本形状现算一个；
     * 服务端签名校验不通过会返回 `403 Invalid app_auth_token`，如实带回。
     */
    suspend fun login(auth: QqAuthResult, nowSeconds: Long): AhLoginResult

    /** `POST /auth/refresh`，只读 `refresh_token`，不依赖签名。 */
    suspend fun refresh(refreshToken: String): AhLoginResult

    /** `GET /user`，带 `Authorization: Bearer`。 */
    suspend fun user(accessToken: String): AhUser?
}

/**
 * `app_auth_token` 的形状复刻（**不是**真签名）。
 *
 * 实测形态：64 字符 base64 / 48 字节，前 32 字节恒定，末 16 字节随 `ts` 变。
 * 生成算法在 Dart AOT 运行时里，未取到（文档 §7）。这里按同样形状拼一个：
 * 前 32 字节用实测常量，末 16 字节由 `ts` 派生。服务端会回
 * `403 Invalid app_auth_token`——这正是「签名未接入」的诚实表现，UI 会照实显示。
 */
object AppAuthToken {
    /** 实测前 32 字节（文档 §7）。 */
    private val PREFIX = hexToBytes("3a134b1e906b5e96e4d6befe3e9041e30b221918eb7d0ea9eb52eaada95f4e67")

    fun build(ts: Long): String {
        val tail = ByteArray(16)
        // FNV-1a 64 位混入 ts，再展开成 16 字节；只为形状正确，不承担安全职责。
        var hash = -0x340d631b7bdddcdbL
        for (i in 0 until 8) {
            hash = hash xor ((ts ushr (i * 8)) and 0xFF)
            hash *= 0x100000001b3L
        }
        for (i in 0 until 16) {
            hash = hash xor (hash ushr 33)
            hash *= 0xff51afd7ed558ccdUL.toLong()
            tail[i] = (hash ushr 24).toByte()
        }
        return base64(PREFIX + tail)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 标准 base64（带 `=` 填充）。commonMain 没有现成实现，这里自带一份。 */
    fun base64(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n ushr 18) and 63])
            out.append(ALPHABET[(n ushr 12) and 63])
            out.append(ALPHABET[(n ushr 6) and 63])
            out.append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n ushr 18) and 63])
                out.append(ALPHABET[(n ushr 12) and 63])
                out.append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n ushr 18) and 63])
                out.append(ALPHABET[(n ushr 12) and 63])
                out.append(ALPHABET[(n ushr 6) and 63])
                out.append('=')
            }
        }
        return out.toString()
    }
}

/** QQ 侧参数。appid 与 scheme 取自样本 Manifest（文档 §3，B 级静态证据）。 */
object QqAuth {
    const val APP_ID = "101983842"
    const val REDIRECT_SCHEME = "tencent101983842"

    /** 授权页地址。`response_type=token` 让回调直接带 `access_token`，少一次换取。 */
    fun authorizeUrl(redirect: String = "https://openmobile.qq.com/oauth2.0/close_success"): String =
        "https://graph.qq.com/oauth2.0/authorize?response_type=token" +
            "&client_id=$APP_ID&redirect_uri=$redirect&scope=get_user_info"

    /** `mqq://` 拉起本机 QQ 客户端；未安装时由调用方回退到 Web 授权。 */
    fun mqqUrl(): String = "mqq://"

    /**
     * 解析 QQ 授权回调。同时兼容 `#` 片段与 `?` 查询串，字段名大小写不敏感。
     *
     * 样本回调形如：
     * `tencent101983842://auth#access_token=xxx&expires_in=7776000&openid=XXXX`
     * 也支持把 `openmobile.qq.com/oauth2.0/me` 的 JSON 结果直接粘进来（含 `openid`）。
     */
    fun parseCallback(raw: String): QqAuthResult? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val params = linkedMapOf<String, String>()
        val query = text.substringAfter('#', "").ifEmpty { text.substringAfter('?', "") }
        if (query.isNotEmpty()) {
            query.split('&').forEach { pair ->
                val key = pair.substringBefore('=', "").lowercase()
                val value = pair.substringAfter('=', "")
                if (key.isNotEmpty() && value.isNotEmpty()) params[key] = value
            }
        }
        // `callback( {"client_id":"...","openid":"..."} );` 这种 JSONP 形态也认。
        // 注意不能只判 `startsWith("{")`：`/oauth2.0/me` 的响应被 `callback( ... );` 包着。
        if (params.isEmpty() && text.contains('{')) {
            Regex("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"").findAll(text).forEach { m ->
                params[m.groupValues[1].lowercase()] = m.groupValues[2]
            }
        }
        val openId = params["openid"] ?: params["open_id"] ?: params["openId"]?.lowercase()
        val open = openId?.trim().orEmpty()
        if (open.isEmpty()) return null
        return QqAuthResult(
            openId = open,
            nickname = params["nickname"].orEmpty(),
            avatarUrl = params["figureurl_qq"] ?: params["figureurl_qq_2"] ?: params["avatar_url"].orEmpty(),
            accessToken = params["access_token"].orEmpty(),
        )
    }
}
