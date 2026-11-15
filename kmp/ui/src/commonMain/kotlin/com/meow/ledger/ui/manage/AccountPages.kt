package com.meow.ledger.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.ledger.shared.auto.KEY_APP_LOCK
import com.meow.ledger.shared.auto.KEY_LOCK_BIOMETRIC
import com.meow.ledger.shared.auto.KEY_SYNC_BASE_URL
import com.meow.ledger.shared.auto.KEY_SYNC_CONFIG_BASE_URL
import com.meow.ledger.shared.auto.KEY_THEME
import com.meow.ledger.shared.auto.SettingsStore
import com.meow.ledger.shared.auto.THEME_DARK
import com.meow.ledger.shared.auto.THEME_LIGHT
import com.meow.ledger.shared.auto.THEME_SYSTEM
import com.meow.ledger.shared.bills.toCents
import com.meow.ledger.shared.manage.BackupPreview
import com.meow.ledger.shared.manage.BackupRepository
import com.meow.ledger.ui.AppGraph
import com.meow.ledger.ui.ImportBackupButton
import com.meow.ledger.ui.copyText
import com.meow.ledger.ui.nowBackupName
import com.meow.ledger.ui.shareTextFile
import com.meow.ledger.shared.db.MeowDatabase
import com.meow.ledger.shared.network.validateSyncUrl
import com.meow.ledger.shared.manage.AccountRepository
import com.meow.ledger.shared.manage.MigrationState
import com.meow.ledger.shared.manage.REMOTE_SWITCHES
import com.meow.ledger.shared.manage.RemoteKind
import com.meow.ledger.ui.auto.ActionButton
import com.meow.ledger.ui.auto.BackHeader
import com.meow.ledger.ui.theme.DangerConfirmDialog
import com.meow.ledger.ui.theme.LocalPalette
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 账号、导入、迁移、反馈、应用锁、设置。 */
@Composable
fun AccountPage(
    page: String,
    database: MeowDatabase,
    account: AccountRepository,
    settings: SettingsStore,
    onTheme: (String) -> Unit,
    onClear: () -> Unit,
    onOpen: (String) -> Unit = {},
    onBack: () -> Unit,
    initialNotice: String = "",
    qqAuthRaw: String = "",
    onOpenQq: (String) -> String = { "" },
) {
    // 外部入口带来的状态先显示出来，不静默改写本机数据。
    var notice by remember { mutableStateOf(initialNotice) }
    var noticeIsError by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val onNotice: (String) -> Unit = { notice = it; noticeIsError = true }
        when (page) {
            "account" -> AccountHome(account, database, onOpen, onBack, onNotice)
            "qq-login" -> QqLoginPage(database, settings, qqAuthRaw, onOpenQq, onBack, onNotice)
            "change-phone" -> ChangePhoneHome(account, onBack, onNotice)
            "imports" -> ImportLogHome(account, onOpen, onBack, onNotice)
            "migration" -> MigrationHome(account, onBack, onNotice)
            "feedback" -> FeedbackHome(account, onBack, onNotice)
            "lock" -> LockHome(settings, onBack, onNotice)
            "push" -> PushHome(account, { onOpen("fixed") }, onBack, onNotice)
            "remote" -> RemoteHome(account, onBack, onNotice)
            "settings" -> SettingsHome(settings, onTheme, onClear, onBack, onNotice)
            "backup" -> BackupHome(database, onBack, onNotice)
            else -> UnknownPage(onBack)
        }
        if (notice.isNotEmpty()) {
            // 外部入口只是状态说明，不是危险操作；只有页面内的校验失败才用 danger（§6.10）。
            Text(
                notice,
                color = if (noticeIsError) LocalPalette.current.danger else LocalPalette.current.textPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun AccountHome(
    account: AccountRepository,
    database: MeowDatabase,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val palette = LocalPalette.current
    var version by remember { mutableStateOf(0) }
    val profile = remember(version) { account.profile() }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf(profile.nickname) }
    var confirm by remember { mutableStateOf(false) }
    BackHeader("账号", onBack)
    // QQ 登录排在最前：这是本 App 唯一的联网账号入口，手机号登录只是本机演示。
    QqLoginSummary(remember(database) { com.meow.ledger.shared.manage.QqLoginRepository(database) }) {
        onOpen("qq-login")
    }
    GlassCard {
        Text(profile.nickname.ifBlank { "未登录" }, color = palette.textPrimary)
        Text(
            when {
                profile.phone.isNotBlank() -> "手机 ${profile.phone}"
                profile.bind_type.isNotBlank() -> "已绑定 ${profile.bind_type}"
                else -> "还没有登录"
            },
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Field("昵称", nickname, { nickname = it }, "显示在我的页面")
    ActionButton("保存昵称", nickname.isNotBlank()) {
        if (account.updateNickname(nickname) == null) onNotice("昵称不能是空的")
        else { version += 1; onNotice("昵称已保存") }
    }
    Field("手机号", phone, { phone = it }, "11 位")
    ActionButton("获取验证码", phone.length == 11) {
        val value = account.sendCode(phone)
        if (value == null) onNotice("手机号要是 1 开头的 11 位")
        else { sent = value; onNotice("验证码已生成，填到下面") }
    }
    if (sent.isNotEmpty()) Text("本机验证码 $sent，五分钟内有效", color = palette.textSecondary)
    Field("验证码", code, { code = it }, "六位数字")
    ActionButton("登录", code.length == 6) {
        if (account.loginByPhone(phone, code) == null) onNotice("验证码不对或已过期")
        else { version += 1; onNotice("已登录") }
    }
    ActionButton("以本机身份登录", true) { account.loginByDevice(); version += 1; onNotice("已用本机身份登录") }
    if (profile.bind_type.isNotBlank()) {
        if (confirm) ConfirmBar("解除绑定？登录状态还在，只是不再关联 ${profile.bind_type}。", { confirm = false }, {
            account.unbind(); confirm = false; version += 1; onNotice("已解除绑定")
        }, "解除")
        else ActionButton("解除绑定", true) { confirm = true }
    }
}

/** 换绑：旧号和新号各验证一次，两次验证码都在本机生成。 */
@Composable
private fun ChangePhoneHome(account: AccountRepository, onBack: () -> Unit, onNotice: (String) -> Unit) {
    val palette = LocalPalette.current
    val current = remember { account.profile().phone }
    var oldCode by remember { mutableStateOf("") }
    var oldSent by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("") }
    BackHeader("换绑手机", onBack)
    if (current.isBlank()) {
        Empty("还没有绑定手机", "先用手机号登录，才能换绑", "去登录", onBack)
        return
    }
    GlassCard {
        Text("当前手机 $current", color = palette.textPrimary)
        Text("旧号和新号都要验证，验证码只在本机生成", color = palette.textSecondary, modifier = Modifier.padding(top = 4.dp))
    }
    ActionButton("获取旧号验证码", true) {
        val value = account.sendCode(current)
        if (value == null) onNotice("当前手机号不可用")
        else { oldSent = value; onNotice("旧号验证码已生成") }
    }
    if (oldSent.isNotEmpty()) Text("旧号验证码 $oldSent，五分钟内有效", color = palette.textSecondary)
    Field("旧号验证码", oldCode, { oldCode = it }, "六位数字")
    Field("新手机号", phone, { phone = it }, "11 位，不能和当前号码相同")
    ActionButton("获取新号验证码", phone.length == 11) {
        val value = account.sendCode(phone)
        if (value == null) onNotice("新手机号要是 1 开头的 11 位")
        else { sent = value; onNotice("新号验证码已生成") }
    }
    if (sent.isNotEmpty()) Text("新号验证码 $sent，五分钟内有效", color = palette.textSecondary)
    Field("新号验证码", code, { code = it }, "六位数字")
    ActionButton("确认换绑", oldCode.isNotBlank() && code.isNotBlank()) {
        when {
            phone == current -> onNotice("新手机号不能和当前号码相同")
            account.changePhone(oldCode, phone, code) == null -> onNotice("验证码不对、已过期，或新手机号不符合要求")
            else -> onNotice("已换绑到 $phone")
        }
    }
}

@Composable
private fun MigrationHome(account: AccountRepository, onBack: () -> Unit, onNotice: (String) -> Unit) {
    var version by remember { mutableStateOf(0) }
    val migration = remember(version) { account.migration() }
    BackHeader("老用户数据迁移", onBack)
    MigrationCard(migration) {
        val result = account.migrateOldUser()
        version += 1
        onNotice(if (result.already) "之前已经迁移过，没有重复写入" else "本机数据已标记为迁移完成")
    }
}

@Composable
private fun BackupHome(database: MeowDatabase, onBack: () -> Unit, onNotice: (String) -> Unit) {
    val palette = LocalPalette.current
    val backup = remember { BackupRepository(database, AppGraph.driver) }
    var pending by remember { mutableStateOf<BackupPreview?>(null) }
    var pendingText by remember { mutableStateOf("") }
    var pendingName by remember { mutableStateOf("") }
    var showText by remember { mutableStateOf("") }
    BackHeader("备份与恢复", onBack)
    GlassCard {
        Text("全库备份", color = palette.textPrimary)
        Text(
            "把账单、账本、分类、资产、预算和设置导出成一个 JSON 文件。文件保存在本机或由你选择分享位置。",
            color = palette.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        ActionButton("导出备份", true) {
            val content = runCatching { backup.export() }.getOrElse {
                onNotice("导出失败：${it.message ?: "无法读取本机数据"}")
                return@ActionButton
            }
            showText = content
            val name = "meow-backup-${nowBackupName()}.json"
            onNotice(if (shareTextFile(name, content)) "已发起分享 $name" else "已生成 $name，请在下方复制保存")
        }
    }
    if (showText.isNotEmpty()) {
        GlassCard {
            Text("备份内容", color = palette.textPrimary)
            Text(showText, color = palette.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            ActionButton("复制备份", true) { copyText(showText) }
        }
    }
    GlassCard {
        Text("从备份恢复", color = palette.textPrimary)
        Text("选择 .json 备份文件后先预览内容。恢复会替换当前全部数据，系统会先自动导出一份当前备份。", color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        ImportBackupButton(true, onPicked = { name, content ->
            val preview = runCatching { backup.summary(content) }.getOrElse {
                onNotice("备份文件无法读取：${it.message ?: "格式不正确"}")
                return@ImportBackupButton
            }
            pendingText = content
            pendingName = name
            pending = preview
        })
    }
    val preview = pending
    if (preview != null) {
        GlassCard {
            Text("待恢复：$pendingName", color = palette.textPrimary)
            Text("版本 ${preview.version} · ${preview.bills} 笔账单 · ${preview.books} 个账本 · ${preview.categories} 个分类 · 共 ${preview.rows} 行", color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        ConfirmBar("恢复这份备份？当前全部数据会被替换；恢复前的备份会自动导出，建议先保存。", { pending = null; pendingText = "" }, {
            val before = runCatching { backup.restore(pendingText) }.getOrElse {
                onNotice("恢复失败：${it.message ?: "数据没有改动"}")
                pending = null
                return@ConfirmBar
            }
            showText = before.previousBackup
            onNotice("已恢复 ${before.preview.bills} 笔账单。恢复前的数据已显示在下方，请复制保存")
            pending = null
            pendingText = ""
        }, "恢复")
    }
}

/** 导入记录页：复用通用 CSV 导入器，登记过的文件可重新导入。 */
@Composable
private fun ImportLogHome(
    account: AccountRepository,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
) {
    var version by remember { mutableStateOf(0) }
    val rows = remember(version) { account.imports() }
    var pending by remember { mutableStateOf("") }
    BackHeader("导入账单", onBack)
    Text("这里只登记导入记录。每行算一笔，账单正文原样留存，删除记录不会删账单。", color = LocalPalette.current.textSecondary)
    ActionButton("导入新账单", true) { onOpen("import") }
    if (rows.isEmpty()) Empty("还没有导入记录", "选择一份 CSV 或粘贴账单就能登记")
    rows.forEach { row ->
        GlassCard(onClick = { onOpen("import:${row.id}") }) {
            Text(row.name, color = LocalPalette.current.textPrimary)
            Text("${row.count} 笔", color = LocalPalette.current.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (pending == row.id) ConfirmBar("删除导入记录「${row.name}」？账单本身不会被删除。", { pending = "" }, {
            account.deleteImport(row.id); pending = ""; version += 1; onNotice("记录已删除")
        }, "删除")
        else ActionButton("删除记录", true) { pending = row.id }
    }
}

@Composable
private fun UnknownPage(onBack: () -> Unit) {
    BackHeader("页面不存在", onBack)
    Text("这个入口没有对应页面。请返回后重新选择。", color = LocalPalette.current.textSecondary)
    ActionButton("返回", true, onBack)
}

/** 老用户迁移：只给本机账本打标记，不删除任何账单，重复执行不改时间。 */
@Composable
private fun MigrationCard(state: MigrationState, onMigrate: () -> Unit) {
    val palette = LocalPalette.current
    GlassCard {
        Text("老用户数据迁移", color = palette.textPrimary)
        Text(
            if (state.done) "本机账本已完成迁移，再次执行不会重复写入"
            else "把旧版本留在本机的账本标记为已迁移。不会删除任何账单",
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (state.done && state.timeMillis > 0L) {
            val time = Instant.fromEpochMilliseconds(state.timeMillis).toLocalDateTime(TimeZone.currentSystemDefault())
            Text(
                "迁移时间 ${time.date} ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}",
                color = palette.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    ActionButton(if (state.done) "已迁移" else "迁移本机数据", !state.done, onMigrate)
}

@Composable
private fun FeedbackHome(account: AccountRepository, onBack: () -> Unit, onNotice: (String) -> Unit) {
    var version by remember { mutableStateOf(0) }
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    val rows = remember(version) { account.feedback() }
    BackHeader("反馈", onBack)
    Field("问题", content, { content = it }, "至少 5 个字")
    Field("联系方式", contact, { contact = it }, "可选")
    ActionButton("提交", content.trim().length >= 5) {
        if (account.addFeedback(content, contact) == null) onNotice("请至少写 5 个字")
        else { content = ""; contact = ""; version += 1; onNotice("已收到") }
    }
    rows.forEach { row ->
        Text(row.content, color = LocalPalette.current.textPrimary, modifier = Modifier.padding(top = 10.dp))
        if (row.contact.isNotBlank()) Text(row.contact, color = LocalPalette.current.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun LockHome(settings: SettingsStore, onBack: () -> Unit, onNotice: (String) -> Unit) {
    var version by remember { mutableStateOf(0) }
    val current = remember(version) { settings.text(KEY_APP_LOCK) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    BackHeader("应用锁", onBack)
    Text(if (current.isEmpty()) "还没有设置口令" else "已设置四位口令", color = LocalPalette.current.textSecondary)
    Field("四位数字", pin, { if (it.length <= 4 && it.all(Char::isDigit)) pin = it }, "例如 2580")
    ActionButton("保存口令", pin.length == 4) {
        settings.setText(KEY_APP_LOCK, pin)
        pin = ""
        version += 1
        onNotice("口令已保存")
    }
    if (current.isNotEmpty()) {
        val bioOn = remember(version) { settings.flag(KEY_LOCK_BIOMETRIC, true) }
        Text("解锁方式", color = LocalPalette.current.textSecondary, modifier = Modifier.padding(top = 12.dp))
        Text(
            "指纹/面容不可用或取消时，仍可输入口令进入",
            color = LocalPalette.current.textSecondary,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            ChoiceChip("指纹 / 面容 + 口令", bioOn) {
                settings.setFlag(KEY_LOCK_BIOMETRIC, true)
                version += 1
                onNotice("已允许生物识别解锁")
            }
            ChoiceChip("仅口令", !bioOn) {
                settings.setFlag(KEY_LOCK_BIOMETRIC, false)
                version += 1
                onNotice("已关闭生物识别，仅用口令")
            }
        }
        if (confirm) ConfirmBar("关闭应用锁？下次打开不再需要口令。", { confirm = false }, {
            settings.setText(KEY_APP_LOCK, "")
            confirm = false
            version += 1
            onNotice("应用锁已关闭")
        }, "关闭")
        else ActionButton("关闭应用锁", true) { confirm = true }
    }
}

@Composable
private fun SettingsHome(
    settings: SettingsStore,
    onTheme: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val palette = LocalPalette.current
    var version by remember { mutableStateOf(0) }
    val theme = remember(version) { settings.text(KEY_THEME, THEME_SYSTEM) }
    // 自建同步地址：草稿以已存值为初值，保存后 version + 1 重读库里的值（§6.2 草稿语义）。
    val syncMain = remember(version) { settings.text(KEY_SYNC_BASE_URL).trim() }
    val syncConfig = remember(version) { settings.text(KEY_SYNC_CONFIG_BASE_URL).trim() }
    var mainDraft by remember(syncMain) { mutableStateOf(syncMain) }
    var configDraft by remember(syncConfig) { mutableStateOf(syncConfig) }
    var confirm by remember { mutableStateOf(false) }
    BackHeader("设置", onBack)
    Text("主题", color = palette.textSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        listOf(THEME_SYSTEM to "跟随系统", THEME_LIGHT to "浅色", THEME_DARK to "深色").forEach { (value, label) ->
            ChoiceChip(label, theme == value) {
                settings.setText(KEY_THEME, value)
                onTheme(value)
                version += 1
            }
        }
    }
    Text("自建同步地址", color = palette.textSecondary)
    Text("空白则只保存在本机，不向任何服务器同步", color = palette.textSecondary, fontSize = 12.sp)
    Field("主域地址", mainDraft, { mainDraft = it }, "https://你的后端/api/")
    Text(
        if (syncMain.isEmpty()) "远程同步已关闭" else "远程同步开启，发往 $syncMain",
        color = palette.textSecondary,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(6.dp))
    Field("配置域地址（可选）", configDraft, { configDraft = it }, "留空则复用主域地址")
    ActionButton("保存同步地址", true) {
        val main = mainDraft.trim()
        val config = configDraft.trim()
        val mainError = validateSyncUrl(main)
        val configError = validateSyncUrl(config)
        when {
            mainError != null -> onNotice("主域地址：$mainError")
            configError != null -> onNotice("配置域地址：$configError")
            else -> {
                settings.setText(KEY_SYNC_BASE_URL, main)
                settings.setText(KEY_SYNC_CONFIG_BASE_URL, config)
                version += 1
                onNotice(if (main.isEmpty()) "已关闭远程同步" else "已保存")
            }
        }
    }
    ActionButton("清空本机数据", true) { confirm = true }
    // 清空是危险操作：用 danger token 的确认弹窗，并把全部后果逐项写清（§6.1 / §6.10）。
    if (confirm) {
        DangerConfirmDialog(
            title = "清空本机全部数据？",
            message = "账单、账本、分类、资产、预算、计划、规则、待确认记录、本机账号与全部设置都会删除，不能恢复；之后会重建默认账本和分类。",
            confirmLabel = "清空",
            onConfirm = { confirm = false; onClear() },
            onDismiss = { confirm = false },
        )
    }
    if (version < 0) onNotice("")
}

/** 消息推送。本复刻不接收厂商推送，也不申请通知权限，只保留入口。 */
@Composable
private fun PushHome(
    account: AccountRepository,
    onOpenReminders: () -> Unit,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val palette = LocalPalette.current
    BackHeader("消息推送", onBack)
    GlassCard {
        Text(
            "本复刻不接收厂商推送，也不申请通知权限。账单提醒仍按本机的周期账记录生效。",
            color = palette.textSecondary,
            fontSize = 13.sp,
        )
    }
    GlassCard(onClick = onOpenReminders) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("查看本机提醒", color = palette.textPrimary)
            Text(">", color = palette.textSecondary)
        }
    }
    GlassCard(onClick = {
        account.refreshLogin()
        onNotice("已刷新，只换了本机令牌，没有注册推送")
    }) {
        Column(Modifier.fillMaxWidth()) {
            Text("刷新登录态", color = palette.textPrimary)
            Text("只换本机令牌，不注册推送", color = palette.textSecondary, fontSize = 12.sp)
        }
    }
}

/** 远程开关。按字段类型分别用开关、数字和文本编辑，写入前由数据层校验。 */
@Composable
private fun RemoteHome(account: AccountRepository, onBack: () -> Unit, onNotice: (String) -> Unit) {
    val palette = LocalPalette.current
    var version by remember { mutableStateOf(0) }
    val values = remember(version) { account.remoteAll() }
    BackHeader("远程开关", onBack)
    REMOTE_SWITCHES.forEach { spec ->
        val value = values[spec.key].orEmpty()
        when (spec.kind) {
            RemoteKind.TOGGLE -> Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(spec.label, color = palette.textPrimary)
                ChoiceChip(if (value == "1") "开" else "关", value == "1") {
                    account.setRemote(spec.key, if (value == "1") "0" else "1")
                    version += 1
                }
            }
            RemoteKind.NUMBER, RemoteKind.TEXT -> {
                var draft by remember(spec.key) { mutableStateOf(value) }
                Field(spec.label, draft, { draft = it })
                if (draft != value) {
                    Spacer(Modifier.height(4.dp))
                    ActionButton("保存${spec.label}", true) {
                        if (account.setRemote(spec.key, draft)) {
                            version += 1
                            onNotice("已保存")
                        } else {
                            onNotice(if (spec.kind == RemoteKind.NUMBER) "请输入非负整数" else "没有保存")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

private fun dateOf(millis: Long): String {
    val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "${date.year}-${date.monthNumber.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"
}
