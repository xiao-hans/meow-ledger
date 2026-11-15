package com.meow.ledger.shared.network

import com.meow.ledger.shared.auto.SettingsStore
import com.meow.ledger.shared.db.MeowDatabase
import com.meow.ledger.shared.manage.AhAuthClient
import com.meow.ledger.shared.manage.SandboxAhAuthClient

/**
 * AH 账号体系（样本 `com.ai.h`）的服务地址与开关。
 *
 * 与喵啊记账主域完全无关：QQ 登录走的是另一套后端，路径是 `/auth/login`、`/user`、
 * `/auth/refresh`，请求体是明文 JSON，没有 `data=<密文>` 封装。
 *
 * **空白表示走本机沙箱**：`KtorAhAuthClient` 不联网，改用 [SandboxAhAuthClient]，
 * 保证模拟器/无网环境也能把登录流程走完。填了地址才会真的发请求。
 */
const val KEY_AH_BASE_URL = "ah_base_url"

/** 样本 `com.ai.h` 的账号服务地址（一手证据：`AH视频_账号体系登录注册接口文档.md` §1）。 */
const val AH_DEFAULT_BASE = "http://danmu.bmsp9.xyz"

/** 读设置里的 AH 服务地址；空白返回 null（= 沙箱）。 */
fun ahBaseUrl(db: MeowDatabase): String? =
    SettingsStore(db).text(KEY_AH_BASE_URL).trim().ifBlank { null }

/**
 * 按当前设置选客户端：填了地址走真实 HTTP，否则走本机沙箱。
 *
 * 每次取用都新建一个 [KtorAhAuthClient]（内部 HttpClient 是轻量的，登录是低频操作），
 * 这样用户改完地址立刻生效，不需要重启 App。
 */
fun ahAuthClient(db: MeowDatabase): AhAuthClient =
    ahBaseUrl(db)?.let { KtorAhAuthClient(it) } ?: SandboxAhAuthClient()

/**
 * 校验用户填的 AH 地址。
 *
 * 与 `validateSyncUrl` 不同，这里**不禁止**样本原域名：用户就是要连真服务。
 * 只要求是 http/https 且带主机名。
 */
fun validateAhBaseUrl(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (!value.startsWith("http://") && !value.startsWith("https://")) return "地址要以 http:// 或 https:// 开头"
    val host = value.removePrefix("http://").removePrefix("https://").substringBefore('/')
    if (host.isBlank() || !host.contains('.')) return "地址里要有主机名"
    return null
}
