package com.meow.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.ledger.shared.auto.DeepLinkTarget
import com.meow.ledger.shared.auto.DEFAULT_WEBVIEW_URL
import com.meow.ledger.shared.auto.THEME_SYSTEM
import com.meow.ledger.shared.auto.isAllowedWebViewUrl
import com.meow.ledger.shared.auto.permissionKeysForFeature
import com.meow.ledger.ui.theme.LocalPalette
import com.meow.ledger.ui.theme.glassCard
import com.meow.ledger.ui.theme.MeowTheme
import com.meow.ledger.ui.theme.paletteOf

/** 平台侧能力接口。Android 入口注入真实实现，预览可提供只读假实现。 */
interface EntryHost {
    fun permissions(): List<PermissionItem>

    fun requestPermissions()

    /** 只申请/打开清单中的一项；每项都是可选的。 */
    fun requestPermission(key: String)

    /** 首次权限引导是否已展示；由平台侧持久化，清库后仍按新首次处理。 */
    fun permissionIntroSeen(): Boolean

    fun markPermissionIntroSeen()

    /** 逐项跳转；实现方每次只启动一个系统设置页，避免连续覆盖。 */
    fun openSettings(key: String)

    /** 对未开启项按序引导；实现方维护索引，用户返回后继续下一项。 */
    fun startGuidedSettings()

    /** 扫码：优先系统扫码组件，不可用时返回兜底文案。 */
    fun openScan(): String

    /**
     * 外部网页：仅在 [allowed] 为 true 时用 ACTION_VIEW 打开；
     * 校验由 shared 的 [isAllowedWebViewUrl] 完成，false 时只回 App 内帮助页。
     */
    fun openWebView(url: String, allowed: Boolean): String

    /** 票据识别：确认 OCR 引擎可用并返回状态；真正的选图在 OcrPage。 */
    fun openTicket(): String

    /** 旧版支付回调：只返回状态说明，不打开付费页面。 */
    fun openPaymentCallback(result: String): String

    /** 启动 MediaProjection 截屏采集；拒绝或组件缺失时返回兜底文案。 */
    fun startScreenCapture(): String
}

data class PermissionItem(
    val key: String,
    val label: String,
    val purpose: String,
    val granted: Boolean,
    val canOpenSettings: Boolean = true,
    /** false：本机没有对应硬件/系统入口，只展示状态，不骗用户去申请。 */
    val actionable: Boolean = true,
    val hint: String = "",
)

/** 兼容旧引用：App 内帮助页对应的官方地址，与 shared 的白名单常量保持一致。 */
const val DEFAULT_HELP_URL = DEFAULT_WEBVIEW_URL

/**
 * 入口壳：只负责权限引导、三态主题、以及深链附带的系统动作。
 * 深链目标本身交给 content(MainApp) 落到真实功能页，不再渲染说明页。
 * content 的每个二级页都通过 BackHeader / BackButton 回到底部 Dock（§6.9）。
 */
@Composable
fun EntryNavHost(
    initialTarget: DeepLinkTarget,
    host: EntryHost?,
    theme: String = THEME_SYSTEM,
    content: @Composable (DeepLinkTarget) -> Unit,
) {
    // 入口页与主界面共用同一套三态主题（跟随系统 / 浅色 / 深色），避免手动切主题后配色错乱（§6.8）。
    val palette = paletteOf(theme)
    // 首次引导只自动弹一次；之后从「我的 → 权限与隐私」或对应功能按需打开。
    var introDismissed by remember { mutableStateOf(host?.permissionIntroSeen() == true) }
    var banner by remember { mutableStateOf("") }
    // 系统动作（打开浏览器/扫码/拉起截屏）只在同一个深链上执行一次；
    // launchId 让重复的磁贴点击也能重新触发，旋转屏幕则不会反复拉起外部 Activity。
    var handledKey by remember { mutableStateOf("") }
    val key = with(initialTarget) { "$kind|$clientId|$url|$paymentResult|$launchId" }

    // 看到首次清单就记账：即使强杀进程或跳过申请，下次启动也不再自动弹。
    LaunchedEffect(host, introDismissed, initialTarget.kind) {
        if (host != null && !introDismissed && initialTarget.kind == DeepLinkTarget.Kind.MAIN) {
            host.markPermissionIntroSeen()
        }
    }

    LaunchedEffect(key) {
        if (key == handledKey || host == null) return@LaunchedEffect
        handledKey = key
        // 平台侧的每个系统动作都自行兜底；这里再包一层，防御第三方实现抛异常。
        banner = runCatching {
            when (initialTarget.kind) {
                DeepLinkTarget.Kind.SCAN -> host.openScan()
                DeepLinkTarget.Kind.WEBVIEW -> {
                    val url = initialTarget.url.ifBlank { DEFAULT_WEBVIEW_URL }
                    host.openWebView(url, isAllowedWebViewUrl(url))
                }
                DeepLinkTarget.Kind.TICKET -> host.openTicket()
                DeepLinkTarget.Kind.CAPTURE -> host.startScreenCapture()
                DeepLinkTarget.Kind.PAYMENT -> host.openPaymentCallback(initialTarget.paymentResult)
                else -> ""
            }
        }.getOrDefault("系统功能暂不可用，已回到底部导航，可在对应页面手动操作。")
    }

    MeowTheme(theme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.appBg)
                .statusBarsPadding(),
        ) {
            // 只有第一次冷启动自动展示清单；深链直达功能和再次冷启动都不打断。
            val firstLaunch = initialTarget.kind == DeepLinkTarget.Kind.MAIN
            if (host != null && !introDismissed && firstLaunch) {
                PermissionGate(
                    items = host.permissions(),
                    onRequest = host::requestPermissions,
                    onOpenItem = host::requestPermission,
                    onContinue = {
                        host.markPermissionIntroSeen()
                        introDismissed = true
                    },
                )
            } else {
                content(initialTarget)
            }
            if (banner.isNotEmpty()) {
                StatusBanner(banner, Modifier.align(Alignment.TopCenter)) { banner = "" }
            }
        }
    }
}

/** 系统动作的状态条：权限被拒绝或组件缺失时给出可读的兜底说明，可手动关闭。 */
@Composable
private fun StatusBanner(message: String, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .glassCard(elevation = 2.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = palette.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            "关闭",
            color = palette.brand,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp).clickable(onClick = onDismiss),
        )
    }
}

/**
 * 按功能列权限的清单。首次引导、我的页和上下文引导共用同一套 UI；
 * 每项都是可选、可单独申请，也不会把用户一次性送去多个系统设置页。
 */
@Composable
fun PermissionChecklist(
    items: List<PermissionItem>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    items.forEach { item ->
        Row(
            modifier
                .fillMaxWidth()
                .glassCard(elevation = 2.dp)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.label, color = LocalPalette.current.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(item.purpose, color = LocalPalette.current.textSecondary, fontSize = 12.sp)
                Text(
                    if (item.granted) "已开启" else if (!item.actionable) "本机不支持" else "未开启 · 可稍后开启",
                    color = if (item.granted || !item.actionable) LocalPalette.current.textSecondary else LocalPalette.current.danger,
                    fontSize = 12.sp,
                )
                if (!item.granted && item.hint.isNotEmpty()) {
                    Text(item.hint, color = LocalPalette.current.textSecondary, fontSize = 11.sp)
                }
            }
            if (!item.granted && item.actionable && item.canOpenSettings) {
                Text(
                    "去开启",
                    color = LocalPalette.current.brand,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onOpen(item.key) },
                )
            }
        }
    }
}

/** 我的 → 权限与隐私：随时可回来看清单，并只申请自己需要的那一项。 */
@Composable
fun PermissionSettingsPage(onBack: () -> Unit) {
    val palette = LocalPalette.current
    val host = AppGraph.permissionHost
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("< 返回", color = palette.brand, modifier = Modifier.clickable(onClick = onBack))
        Text("权限与隐私", color = palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "每项权限对应一个功能，全部可选。可以现在开启，也可以以后用到该功能时再开启；不开启不影响手工记账和全部免费功能。",
            color = palette.textSecondary,
            fontSize = 13.sp,
        )
        if (host == null) {
            Text("当前预览没有系统权限入口。", color = palette.textSecondary, fontSize = 13.sp)
        } else {
            PermissionChecklist(host.permissions(), host::requestPermission)
        }
    }
}

/**
 * 使用功能时的上下文引导：只列该功能缺的、本机支持的可选权限。
 * 「以后再说」只抑制本次会话，不永久剥夺下次提醒。
 */
@Composable
fun ContextPermissionDialog(
    feature: String,
    items: List<PermissionItem>,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val keys = permissionKeysForFeature(feature)
    val missing = items.filter { it.key in keys && !it.granted && it.actionable }
    if (missing.isEmpty()) return
    val host = AppGraph.permissionHost
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.cardBg,
        title = { Text("这个功能可按需开启权限", color = palette.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("以下权限都可选；不开启仍可继续使用，之后可在「我的 → 权限与隐私」打开。", color = palette.textSecondary)
                missing.forEach { item ->
                    Text("${item.label}：${item.purpose}", color = palette.textPrimary, fontSize = 13.sp)
                    if (item.hint.isNotEmpty()) Text(item.hint, color = palette.textSecondary, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            if (host != null) {
                TextButton(onClick = {
                    missing.firstOrNull()?.let { host.requestPermission(it.key) }
                    onDismiss()
                }) { Text("去开启", color = palette.brand) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("以后再说", color = palette.textSecondary) }
        },
    )
}

@Composable
private fun PermissionGate(
    items: List<PermissionItem>,
    onRequest: () -> Unit,
    onOpenItem: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("进入账本前", color = palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "下面按功能列出所有权限，全部可选：可以现在逐项开启，也可以以后用到对应功能时再开启。拒绝不影响手工记账，本机不支持的项会写清原因。",
            color = palette.textSecondary,
            fontSize = 13.sp,
        )
        PermissionChecklist(items, onOpenItem)
        Button(
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = palette.brand, contentColor = palette.cardBg),
        ) { Text("申请基础权限（可跳过）") }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = palette.chipFill, contentColor = palette.textPrimary),
        ) { Text("继续使用本地记账") }
    }
}

/** 保留路由字符串常量，供平台侧显式 EXTRA_ROUTE 与旧文档对照；导航本身用 DeepLinkTarget。 */
const val ROUTE_MAIN = "main"
const val ROUTE_CAPTURE = "capture"
const val ROUTE_BILL_EDIT = "bill-edit"
