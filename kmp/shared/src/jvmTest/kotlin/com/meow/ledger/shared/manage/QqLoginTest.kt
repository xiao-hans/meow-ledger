package com.meow.ledger.shared.manage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.meow.ledger.shared.auto.SettingsStore
import com.meow.ledger.shared.db.MeowDatabase
import com.meow.ledger.shared.network.AH_DEFAULT_BASE
import com.meow.ledger.shared.network.KEY_AH_BASE_URL
import com.meow.ledger.shared.network.ahAuthClient
import com.meow.ledger.shared.network.validateAhBaseUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QQ 登录的会话层与沙箱客户端。
 *
 * 真实服务端（`/auth/login` 需要未取到的 `app_auth_token` 签名）不在这里测：
 * 文档 §8.3 明确「未取得令牌前恒 403」，把它写成「登录成功」的用例就是撒谎。
 * 这里测的是能在本机确定的那部分：QQ 回调解析、令牌形状、会话落库、刷新与退出。
 */
class QqLoginTest {

    private fun newDb(): MeowDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MeowDatabase.Schema.create(driver)
        return MeowDatabase(driver)
    }

    private val sample = QqAuthResult(
        openId = "70D1DFD19403EE18CD7B6EF1443CAAE8",
        nickname = "eclipse",
        avatarUrl = "http://thirdqq.qlogo.cn/ek_qqapp/abc/0",
    )

    // ------------------------------------------------------------ QQ 回调解析

    @Test
    fun parseCallbackReadsFragmentParameters() {
        val raw = "tencent101983842://auth#access_token=AT1&expires_in=7776000&openid=ABCDEF0123456789"
        val parsed = assertNotNull(QqAuth.parseCallback(raw))
        assertEquals("ABCDEF0123456789", parsed.openId)
        assertEquals("AT1", parsed.accessToken)
    }

    @Test
    fun parseCallbackReadsQueryParametersAndAvatar() {
        val raw = "tencent101983842://auth?openid=OPENID42&nickname=eclipse" +
            "&figureurl_qq=http%3A%2F%2Fthirdqq.qlogo.cn%2Fx%2F0"
        val parsed = assertNotNull(QqAuth.parseCallback(raw))
        assertEquals("OPENID42", parsed.openId)
        assertEquals("eclipse", parsed.nickname)
    }

    @Test
    fun parseCallbackReadsOpenidJsonFromMeEndpoint() {
        // `/oauth2.0/me` 的响应是 JSONP：callback( {"client_id":"...","openid":"..."} );
        val raw = """callback( {"client_id":"101983842","openid":"JSONOPENID"} );"""
        val parsed = assertNotNull(QqAuth.parseCallback(raw))
        assertEquals("JSONOPENID", parsed.openId)
    }

    @Test
    fun parseCallbackRejectsTextWithoutOpenid() {
        assertNull(QqAuth.parseCallback("tencent101983842://auth#access_token=only"))
        assertNull(QqAuth.parseCallback(""))
    }

    @Test
    fun authorizeUrlCarriesAppId() {
        assertTrue(QqAuth.authorizeUrl().contains("client_id=${QqAuth.APP_ID}"))
        assertEquals("101983842", QqAuth.APP_ID)
    }

    // -------------------------------------------------------- app_auth_token

    @Test
    fun appAuthTokenMatchesSampleShape() {
        // 文档 §7：64 字符 base64，解码 48 字节，前 32 字节恒定。
        // 48 字节正好是 3 的整数倍，所以编码结果是满 64 字符、**不带** `=` 填充。
        val token = AppAuthToken.build(1794755442L)
        assertEquals(64, token.length, "长度应与样本一致")
        assertFalse(token.contains('='), "48 字节不需要填充")
        val decoded = decodeBase64(token)
        assertEquals(48, decoded.size)
        val prefix = decoded.take(32).joinToString("") { "%02x".format(it) }
        assertEquals("3a134b1e906b5e96e4d6befe3e9041e30b221918eb7d0ea9eb52eaada95f4e67", prefix)
    }

    @Test
    fun appAuthTokenTailVariesWithTimestamp() {
        val a = AppAuthToken.build(1794755442L)
        val b = AppAuthToken.build(1794756830L)
        assertTrue(a != b, "末 16 字节应随 ts 变化")
        // 按解码后的字节比：32 字节边界不落在 base64 的 4 字符组上，
        // 第 43 个字符已经沾到第 33 字节，用字符下标比会误判。
        assertEquals(decodeBase64(a).take(32), decodeBase64(b).take(32))
        assertTrue(decodeBase64(a).drop(32) != decodeBase64(b).drop(32))
    }

    // ------------------------------------------------------------- 沙箱登录

    @Test
    fun sandboxLoginStoresSessionAndProfile() {
        val db = newDb()
        val repo = QqLoginRepository(db, SandboxAhAuthClient(clock = { 1_700_000_000L }), clock = { 1_700_000_000L })
        assertNull(repo.session())

        val result = kotlinx.coroutines.runBlocking { repo.login(sample) }
        val success = result as AhLoginResult.Success
        assertEquals(sample.openId, success.session.account)
        assertEquals("eclipse", success.session.nickname)
        assertTrue(success.session.accessToken.startsWith("sandbox-"))

        val stored = assertNotNull(repo.session())
        assertEquals(sample.openId, stored.account)
        assertEquals(1_700_000_000L + 3600, stored.accessExpiresAt)
        assertTrue(repo.loggedIn())

        // member_profile 同步为 QQ 身份，账号页看到的是同一个人。
        val profile = AccountRepository(db).profile()
        assertEquals("qq", profile.login_type)
        assertEquals("qq", profile.bind_type)
        assertEquals(sample.openId, profile.bind_id)
        assertEquals("eclipse", profile.nickname)
    }

    @Test
    fun sandboxLoginRejectsEmptyOpenId() {
        val db = newDb()
        val repo = QqLoginRepository(db, SandboxAhAuthClient(), clock = { 1_700_000_000L })
        val result = kotlinx.coroutines.runBlocking { repo.login(QqAuthResult(openId = "  ")) }
        assertTrue(result is AhLoginResult.Failed)
        assertNull(repo.session(), "失败不写本地会话")
        assertEquals("", AccountRepository(db).profile().login_type, "失败不改身份")
    }

    // ------------------------------------------------------------- 刷新

    @Test
    fun refreshReplacesAccessTokenButKeepsNickname() {
        val db = newDb()
        var now = 1_700_000_000L
        val repo = QqLoginRepository(db, SandboxAhAuthClient(clock = { now }), clock = { now })
        kotlinx.coroutines.runBlocking { repo.login(sample) }
        val before = assertNotNull(repo.session())
        assertEquals("eclipse", before.nickname)

        // 把时间推到过期之后，refreshIfNeeded 才会真的发请求。
        now += 7200
        val result = kotlinx.coroutines.runBlocking { repo.refreshIfNeeded() }
        val success = assertNotNull(result) as AhLoginResult.Success
        assertEquals(before.refreshToken, success.session.refreshToken, "refresh_token 不变")
        assertEquals("eclipse", assertNotNull(repo.session()).nickname, "刷新响应没昵称时保留旧值")
    }

    @Test
    fun refreshIfNeededSkipsWhenAccessStillValid() {
        val db = newDb()
        val repo = QqLoginRepository(db, SandboxAhAuthClient(clock = { 1_700_000_000L }), clock = { 1_700_000_000L })
        kotlinx.coroutines.runBlocking { repo.login(sample) }
        assertNull(kotlinx.coroutines.runBlocking { repo.refreshIfNeeded() }, "有效期内不发请求")
    }

    @Test
    fun refreshWithoutSessionReturnsNull() {
        val repo = QqLoginRepository(newDb(), SandboxAhAuthClient(), clock = { 1_700_000_000L })
        assertNull(kotlinx.coroutines.runBlocking { repo.refreshIfNeeded() })
        assertNull(kotlinx.coroutines.runBlocking { repo.refreshNow() })
    }

    @Test
    fun refreshWithBogusTokenIsRejectedAndKeepsSession() {
        val db = newDb()
        val repo = QqLoginRepository(db, SandboxAhAuthClient(), clock = { 1_700_000_000L })
        kotlinx.coroutines.runBlocking { repo.login(sample) }
        // 手工把 refresh_token 改坏，模拟服务端拒绝。
        val current = assertNotNull(repo.session())
        db.qqSessionQueries.upsertSession(
            com.meow.ledger.shared.db.Qq_session(
                id = QqLoginRepository.SESSION_ID,
                account = current.account,
                nickname = current.nickname,
                avatar_url = current.avatarUrl,
                user_no = current.userNo,
                remote_id = current.remoteId,
                access_token = current.accessToken,
                refresh_token = "bogus",
                access_expires_at = current.accessExpiresAt,
                refresh_expires_at = current.refreshExpiresAt,
                create_time = current.updateTime,
                update_time = current.updateTime,
            ),
        )
        val result = kotlinx.coroutines.runBlocking { repo.refreshNow() }
        assertTrue(result is AhLoginResult.Rejected)
        assertEquals(401, (result as AhLoginResult.Rejected).code)
        assertNotNull(repo.session(), "刷新失败不清会话")
    }

    // ------------------------------------------------------------- 退出

    @Test
    fun logoutClearsSessionAndReturnsToDeviceIdentity() {
        val db = newDb()
        val repo = QqLoginRepository(db, SandboxAhAuthClient(), clock = { 1_700_000_000L })
        kotlinx.coroutines.runBlocking { repo.login(sample) }
        assertTrue(repo.loggedIn())

        repo.logout()
        assertNull(repo.session())
        assertFalse(repo.loggedIn())
        assertEquals("device", AccountRepository(db).profile().login_type)
    }

    // ------------------------------------------------- 地址开关与客户端选择

    @Test
    fun clientFollowsBaseUrlSetting() {
        val db = newDb()
        // 空白 = 沙箱：没配地址就不该有任何联网客户端。
        assertTrue(ahAuthClient(db) is SandboxAhAuthClient)

        SettingsStore(db).setText(KEY_AH_BASE_URL, AH_DEFAULT_BASE)
        assertFalse(
            ahAuthClient(db) is SandboxAhAuthClient,
            "填了 AH 地址还走沙箱，等于让用户以为登录了真服务",
        )
    }

    @Test
    fun validateAhBaseUrlAllowsSampleHostAndRejectsJunk() {
        // 与 validateSyncUrl 不同：样本原域名在这里是**合法**的，用户就是要连真服务。
        assertNull(validateAhBaseUrl(AH_DEFAULT_BASE))
        assertNull(validateAhBaseUrl(""))
        assertNotNull(validateAhBaseUrl("ftp://x.com"))
        assertNotNull(validateAhBaseUrl("http://localhost"))
    }

    // ------------------------------------------------------------- 工具

    private fun decodeBase64(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = text.trimEnd('=')
        val out = ArrayList<Byte>(clean.length / 4 * 3)
        var buffer = 0
        var bits = 0
        clean.forEach { ch ->
            buffer = (buffer shl 6) or alphabet.indexOf(ch)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        return out.toByteArray()
    }
}
