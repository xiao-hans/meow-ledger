package com.meow.ledger.shared.manage

import com.meow.ledger.shared.db.MeowDatabase
import kotlin.random.Random

/**
 * 短信 / 一键登录网关。默认 [SandboxLoginGateway]：不联网、不发短信，验证码只留在本机，
 * 供 UI 直接展示。接入真实短信或运营商一键登录时实现本接口并替换构造参数即可，
 * 上层页面与仓储不用改。
 */
interface LoginGateway {
    /** 号码合法时返回本次验证码（沙箱用于页面展示），否则 null。 */
    fun sendCode(phone: String): String?

    /** 验证码正确且未过期。 */
    fun verify(phone: String, code: String): Boolean
}

/** 本地沙箱：6 位随机码写 `sms_code`，5 分钟有效。 */
class SandboxLoginGateway(
    private val db: MeowDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : LoginGateway {

    override fun sendCode(phone: String): String? {
        if (!phone.matches(Regex("^1\\d{10}$"))) return null
        val code = Random.nextInt(100000, 999999).toString()
        db.accountQueries.upsertCode(
            com.meow.ledger.shared.db.Sms_code(phone, code, clock() + 300_000),
        )
        return code
    }

    override fun verify(phone: String, code: String): Boolean {
        val stored = db.accountQueries.selectCode(phone).executeAsOneOrNull() ?: return false
        return stored.code == code && stored.expires >= clock()
    }
}

/**
 * 远程推送网关。默认沙箱明确返回不可用：账单提醒由本地 `AlarmManager` + 通知承担，
 * 个推与厂商通道需要 AppKey / 厂商开发者账号，没有凭证时不伪装成可用。
 */
interface PushGateway {
    val remote: Boolean
    fun register(): Result<Unit>
}

object SandboxPushGateway : PushGateway {
    override val remote: Boolean = false

    override fun register(): Result<Unit> =
        Result.failure(IllegalStateException("未接入远程推送；账单提醒由本地通知承担"))
}
