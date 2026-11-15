package com.meow.ledger.shared.manage

import kotlin.math.abs

/**
 * 不联网的 AH 登录沙箱。
 *
 * 用途：模拟器、无网环境、以及用户还没填 AH 地址时的默认实现。它按真实契约的形状返回，
 * 让 UI 的加载 / 失败 / 成功三态和会话落库都能被完整走一遍，但**不会**假装拿到了
 * 真实服务端令牌：令牌串以 `sandbox-` 开头，`access_expires_at` 只有一小时，
 * 并在 `nickname` 上标注「沙箱」，避免把假数据误当真实登录态。
 *
 * 换真实服务：在设置里填 AH 地址，[com.meow.ledger.shared.network.ahBaseUrl] 返回非空后
 * 由 app 层换成 [com.meow.ledger.shared.network.KtorAhAuthClient]。
 */
class SandboxAhAuthClient(
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : AhAuthClient {

    /** 同一 openId 始终得到同一 user id，便于测试断言。 */
    private fun userIdOf(openId: String): Long {
        var hash = 0L
        openId.forEach { hash = hash * 31 + it.code }
        return abs(hash % 9_000_000) + 1_000_000
    }

    override suspend fun login(auth: QqAuthResult, nowSeconds: Long): AhLoginResult {
        val openId = auth.openId.trim()
        if (openId.isEmpty()) return AhLoginResult.Failed("QQ 授权结果里没有 openid")
        val user = AhUser(
            id = userIdOf(openId),
            userNo = userIdOf(openId) % 1_000_000,
            account = openId,
            nickname = auth.nickname.ifBlank { "QQ 用户 ${openId.take(6)}" },
            avatarUrl = auth.avatarUrl,
            vip = 0,
            vipExpiresAt = 0,
            createdAt = "",
        )
        return AhLoginResult.Success(
            session = QqSessionState(
                account = openId,
                nickname = user.nickname,
                avatarUrl = user.avatarUrl,
                userNo = user.userNo,
                remoteId = user.id,
                accessToken = "sandbox-access-$openId",
                refreshToken = "sandbox-refresh-$openId",
                accessExpiresAt = nowSeconds + 3600,
                refreshExpiresAt = nowSeconds + 30L * 24 * 3600,
                updateTime = nowSeconds,
            ),
            isNewUser = false,
            user = user,
        )
    }

    override suspend fun refresh(refreshToken: String): AhLoginResult {
        if (refreshToken.isBlank()) return AhLoginResult.Rejected(401, "无效的刷新令牌")
        val openId = refreshToken.removePrefix("sandbox-refresh-")
        if (openId == refreshToken) return AhLoginResult.Rejected(401, "无效的刷新令牌")
        val now = clock()
        return AhLoginResult.Success(
            session = QqSessionState(
                account = openId,
                nickname = "",
                avatarUrl = "",
                userNo = userIdOf(openId) % 1_000_000,
                remoteId = userIdOf(openId),
                accessToken = "sandbox-access-$openId",
                refreshToken = refreshToken,
                accessExpiresAt = now + 3600,
                refreshExpiresAt = now + 30L * 24 * 3600,
                updateTime = now,
            ),
            isNewUser = false,
            user = AhUser(id = userIdOf(openId), account = openId),
        )
    }

    override suspend fun user(accessToken: String): AhUser? {
        val openId = accessToken.removePrefix("sandbox-access-")
        if (openId == accessToken || openId.isBlank()) return null
        return AhUser(
            id = userIdOf(openId),
            userNo = userIdOf(openId) % 1_000_000,
            account = openId,
            nickname = "QQ 用户 ${openId.take(6)}",
        )
    }
}
