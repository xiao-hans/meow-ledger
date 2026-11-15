package com.meow.ledger.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.ledger.shared.auto.SettingsStore
import com.meow.ledger.shared.manage.AhLoginResult
import com.meow.ledger.shared.manage.QqAuth
import com.meow.ledger.shared.manage.QqAuthResult
import com.meow.ledger.shared.manage.QqLoginRepository
import com.meow.ledger.shared.network.KEY_AH_BASE_URL
import com.meow.ledger.shared.network.validateAhBaseUrl
import com.meow.ledger.ui.auto.ActionButton
import com.meow.ledger.ui.auto.BackHeader
import com.meow.ledger.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * QQ 登录页。
 *
 * 只有登录，没有注册：QQ 授权结果直接提交 AH 的 `POST /auth/login`（`is_third_party=true`），
 * 首次登录由服务端决定是不是新用户（`is_new_user`），客户端不另做注册流程。
 *
 * 两条入口，都通向同一个 [QqLoginRepository.login]：
 *  1. 拉起 QQ 授权页（装了 QQ 走 `mqq://`，否则走 Web 授权）；回调由 Manifest 的
 *     `tencent101983842` scheme 接住，落到本页。
 *  2. 粘贴授权回调串。模拟器上没有 QQ 客户端，这是唯一能走完整链路的路径，
 *     也方便排查「回调解析对不对」。
 */
@Composable
fun QqLoginPage(
    database: com.meow.ledger.shared.db.MeowDatabase,
    settings: SettingsStore,
    authRaw: String,
    onOpenQq: (String) -> String,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val repo = remember { QqLoginRepository(database) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    val session = remember(version) { repo.session() }
    var busy by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }

    // 授权地址：空白表示连本机沙箱，页面要如实说明，不能让人以为已经登进了真服务。
    val ahBase = remember(version) { settings.text(KEY_AH_BASE_URL).trim() }

    suspend fun runLogin(auth: QqAuthResult) {
        busy = true
        val outcome = repo.login(auth)
        busy = false
        result = describe(outcome)
        version += 1
        if (outcome is AhLoginResult.Success) onNotice("已用 QQ 登录")
    }

    // 深链带来的回调串只消费一次；解析失败也要给出可操作的原因。
    LaunchedEffect(authRaw) {
        if (authRaw.isBlank()) return@LaunchedEffect
        val parsed = QqAuth.parseCallback(authRaw)
        if (parsed == null) {
            result = "这段回调里没有 openid，检查是不是复制漏了"
        } else {
            runLogin(parsed)
        }
    }

    BackHeader("QQ 登录", onBack)

    if (session != null) {
        GlassCard {
            Text(session.nickname.ifBlank { "QQ 用户" }, color = palette.textPrimary)
            Text("账号 ${session.account}", color = palette.textSecondary, fontSize = 12.sp)
            if (session.userNo > 0) {
                Text("用户号 ${session.userNo}", color = palette.textSecondary, fontSize = 12.sp)
            }
            Text(
                if (session.accessValid(System.currentTimeMillis() / 1000)) "登录态有效" else "登录态已过期，可刷新",
                color = palette.textSecondary,
                fontSize = 12.sp,
            )
        }
        ActionButton("刷新登录态", !busy) {
            scope.launch {
                busy = true
                result = describe(repo.refreshNow())
                busy = false
                version += 1
            }
        }
        if (confirmLogout) {
            ConfirmBar("退出登录？只清本机会话，服务端没有登出接口，QQ 那边不受影响。", { confirmLogout = false }, {
                repo.logout()
                confirmLogout = false
                version += 1
                result = "已退出登录"
            }, "退出")
        } else {
            ActionButton("退出登录", !busy) { confirmLogout = true }
        }
    } else {
        Text(
            "用 QQ 授权后直接登录，没有单独的注册步骤；第一次登录会由服务端创建账号。",
            color = palette.textSecondary,
            fontSize = 13.sp,
        )
        ActionButton("打开 QQ 授权", !busy) {
            val status = onOpenQq(QqAuth.authorizeUrl())
            if (status.isNotBlank()) result = status
        }
        Field("或粘贴授权回调", paste, { paste = it }, "tencent101983842://auth#access_token=…&openid=…")
        ActionButton("用这段回调登录", !busy && paste.isNotBlank()) {
            val parsed = QqAuth.parseCallback(paste)
            if (parsed == null) {
                result = "这段回调里没有 openid，检查是不是复制漏了"
            } else {
                scope.launch { runLogin(parsed) }
            }
        }
    }

    if (result.isNotEmpty()) {
        Text(result, color = palette.textPrimary, modifier = Modifier.padding(top = 8.dp))
    }

    AhBaseSection(settings, ahBase) { version += 1 }
}

/** 账号服务地址。空白 = 沙箱；填了才真的联网，登录页必须把这件事写明白。 */
@Composable
private fun AhBaseSection(settings: SettingsStore, current: String, onSaved: () -> Unit) {
    val palette = LocalPalette.current
    var draft by remember(current) { mutableStateOf(current) }
    var message by remember { mutableStateOf("") }
    Text("账号服务地址", color = palette.textSecondary, modifier = Modifier.padding(top = 16.dp))
    Text(
        if (current.isBlank()) "现在是本机沙箱：登录态是模拟的，不联网。" else "登录会发往 $current",
        color = palette.textSecondary,
        fontSize = 12.sp,
    )
    Field("AH 服务地址", draft, { draft = it }, "http://danmu.bmsp9.xyz（留空走沙箱）")
    ActionButton("保存地址", true) {
        val value = draft.trim()
        val error = validateAhBaseUrl(value)
        if (error != null) {
            message = error
        } else {
            settings.setText(KEY_AH_BASE_URL, value)
            message = if (value.isEmpty()) "已切回本机沙箱" else "已保存"
            onSaved()
        }
    }
    if (message.isNotEmpty()) Text(message, color = palette.textPrimary, fontSize = 12.sp)
}

/**
 * 把登录结果翻成人话。
 *
 * `403 Invalid app_auth_token` 是「签名没接进来」的确定表现（文档 §8.3）：
 * 不能写成「登录失败请重试」，那会让人一直重试同一件不可能成功的事。
 */
private fun describe(result: AhLoginResult?): String = when (result) {
    null -> ""
    is AhLoginResult.Success ->
        if (result.isNewUser) "登录成功，已创建账号" else "登录成功"
    is AhLoginResult.Rejected -> when {
        result.code == 403 && result.message.contains("app_auth_token", ignoreCase = true) ->
            "服务端拒绝了 app_auth_token 签名。签名算法在样本的 Dart 运行时里，本复刻没有实现，" +
                "所以真实服务端登录会一直停在这一步；换成沙箱地址可以走完界面流程。"
        result.code == 401 -> "刷新令牌无效，需要重新授权（${result.message}）"
        result.code == 422 -> "字段不合法：${result.message}"
        result.code == 429 -> "同一 IP 注册次数已达上限，稍后再试"
        else -> "服务端拒绝：${result.code} ${result.message}"
    }
    is AhLoginResult.Failed -> "连不上账号服务：${result.message}"
}

/** 登录状态摘要，给「我的」页一行用。 */
@Composable
fun QqLoginSummary(repo: QqLoginRepository, onOpen: () -> Unit) {
    val palette = LocalPalette.current
    val session = remember(repo.session()?.updateTime) { repo.session() }
    GlassCard(onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(if (session == null) "QQ 登录" else "已登录 ${session.nickname}", color = palette.textPrimary)
                Text(
                    if (session == null) "用 QQ 账号登录，不需要注册" else "账号 ${session.account}",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                )
            }
            Text(">", color = palette.textSecondary)
        }
    }
}
