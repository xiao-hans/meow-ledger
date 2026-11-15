package com.meow.ledger.shared.manage

import com.meow.ledger.shared.db.MeowDatabase
import com.meow.ledger.shared.db.Qq_session

/** QQ 登录会话的读写与刷新。UI 用 `LaunchedEffect` 调 suspend 方法。 */
class QqLoginRepository(
    private val db: MeowDatabase,
    private val client: AhAuthClient = SandboxAhAuthClient(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    companion object {
        /** 会话只有一行。 */
        const val SESSION_ID = 0L
    }

    /** 当前会话；未登录返回 null。 */
    fun session(): QqSessionState? =
        db.qqSessionQueries.selectSession(SESSION_ID).executeAsOneOrNull()?.toState()

    /** 是否已登录（有 refresh_token 即算登录过，哪怕 access 已过期）。 */
    fun loggedIn(): Boolean = session()?.let { it.refreshToken.isNotBlank() || it.accessToken.isNotBlank() } == true

    /**
     * 用 QQ 授权结果登录。
     *
     * 成功后同时把 `member_profile` 更新为 QQ 身份，让「账号」页与其它页面看到的是同一个人；
     * 失败（含 `403 Invalid app_auth_token`）不写任何本地状态，避免留下半登录。
     */
    suspend fun login(auth: QqAuthResult): AhLoginResult {
        val now = clock()
        val result = client.login(auth, now)
        if (result is AhLoginResult.Success) {
            save(result.session)
            val account = AccountRepository(db)
            account.loginThird("qq", result.session.account)
            // 账号页显示的是 member_profile，登录后昵称要跟着换成 QQ 的，
            // 否则「已登录」却还写着「本地用户」。
            result.session.nickname.takeIf { it.isNotBlank() }?.let { account.updateNickname(it) }
        }
        return result
    }

    /**
     * 访问令牌过期时用 refresh_token 换新的。
     *
     * `/auth/refresh` 不校验 `ts` 与 `app_auth_token`，是唯一不依赖签名的入口
     * （文档 §8.3），因此这也是「签名没取到」时的实际可用路径。
     * 刷新失败不清会话：refresh_token 可能只是暂时不可用，清掉会让用户重新走 QQ 授权。
     */
    suspend fun refreshIfNeeded(): AhLoginResult? {
        val current = session() ?: return null
        val now = clock()
        if (current.accessValid(now)) return null
        if (current.refreshToken.isBlank()) return AhLoginResult.Failed("没有 refresh_token，需要重新登录")
        val result = client.refresh(current.refreshToken)
        if (result is AhLoginResult.Success) {
            // 刷新响应可能不带昵称/头像，保留旧值。
            save(
                result.session.copy(
                    nickname = result.session.nickname.ifBlank { current.nickname },
                    avatarUrl = result.session.avatarUrl.ifBlank { current.avatarUrl },
                    account = result.session.account.ifBlank { current.account },
                ),
            )
        }
        return result
    }

    /** 主动刷新一次（用户在界面上点「刷新登录态」）。 */
    suspend fun refreshNow(): AhLoginResult? {
        val current = session() ?: return null
        if (current.refreshToken.isBlank()) return AhLoginResult.Failed("没有 refresh_token，需要重新登录")
        val result = client.refresh(current.refreshToken)
        if (result is AhLoginResult.Success) {
            save(
                result.session.copy(
                    nickname = result.session.nickname.ifBlank { current.nickname },
                    avatarUrl = result.session.avatarUrl.ifBlank { current.avatarUrl },
                    account = result.session.account.ifBlank { current.account },
                ),
            )
        }
        return result
    }

    /**
     * 退出登录。只清本地会话，并把 `member_profile` 退回本机身份。
     *
     * 服务端没有登出端点（文档 §2 的端点全表里没有 `/auth/logout`），
     * 所以这里如实只做本地清理，UI 文案也这么说。
     */
    fun logout() {
        db.qqSessionQueries.deleteSession(SESSION_ID)
        val account = AccountRepository(db)
        account.loginByDevice()
    }

    private fun save(state: QqSessionState) {
        db.qqSessionQueries.upsertSession(
            Qq_session(
                id = SESSION_ID,
                account = state.account,
                nickname = state.nickname,
                avatar_url = state.avatarUrl,
                user_no = state.userNo,
                remote_id = state.remoteId,
                access_token = state.accessToken,
                refresh_token = state.refreshToken,
                access_expires_at = state.accessExpiresAt,
                refresh_expires_at = state.refreshExpiresAt,
                create_time = session()?.updateTime ?: state.updateTime,
                update_time = state.updateTime,
            ),
        )
    }

    private fun Qq_session.toState() = QqSessionState(
        account = account,
        nickname = nickname,
        avatarUrl = avatar_url,
        userNo = user_no,
        remoteId = remote_id,
        accessToken = access_token,
        refreshToken = refresh_token,
        accessExpiresAt = access_expires_at,
        refreshExpiresAt = refresh_expires_at,
        updateTime = update_time,
    )
}
