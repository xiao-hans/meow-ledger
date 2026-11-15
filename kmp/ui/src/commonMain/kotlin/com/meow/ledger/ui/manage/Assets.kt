package com.meow.ledger.ui.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.ledger.shared.bills.BillsRepository
import com.meow.ledger.shared.bills.TYPE_TRANSFER
import com.meow.ledger.shared.bills.centsToYuan
import com.meow.ledger.shared.bills.toCents
import com.meow.ledger.shared.db.Bill
import com.meow.ledger.shared.manage.AssetRepository
import com.meow.ledger.shared.manage.DEFAULT_ASSET_ID
import com.meow.ledger.ui.AppGraph
import com.meow.ledger.ui.auto.ActionButton
import com.meow.ledger.ui.auto.BackHeader
import com.meow.ledger.ui.theme.LocalPalette
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 资产页：assets / asset-new / asset-edit:<id> / asset-flow:<id> / asset-hidden / asset-transfer。 */
@Composable
fun AssetsPage(repository: AssetRepository, page: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var notice by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            page == "assets" -> AssetList(repository, onOpen, onBack) { notice = it }
            page == "asset-new" -> AssetForm(repository, null, onBack) { notice = it }
            page.startsWith("asset-edit:") -> AssetForm(repository, page.substringAfter(":").toLongOrNull(), onBack) { notice = it }
            page.startsWith("asset-flow:") -> AssetFlow(repository, page.substringAfter(":").toLongOrNull(), onBack)
            page == "asset-hidden" -> HiddenAssets(repository, onBack) { notice = it }
            page == "asset-transfer" -> TransferPage(repository, onOpen, onBack) { notice = it }
        }
        if (notice.isNotEmpty()) Text(notice, color = LocalPalette.current.danger, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun AssetList(repository: AssetRepository, onOpen: (String) -> Unit, onBack: () -> Unit, onNotice: (String) -> Unit) {
    val palette = LocalPalette.current
    var version by remember { mutableStateOf(0) }
    val assets = remember(version) { repository.visible() }
    val total = assets.filter { it.is_total_assets == 1L }.sumOf { it.balance_cents }
    BackHeader("资产", onBack)
    Text("负债用负数记，例如信用卡已用额度。现金是默认账户，不能删。", color = palette.textSecondary)
    GlassCard {
        Text("资产净值", color = palette.textSecondary, fontSize = 12.sp)
        Text("${centsToYuan(total)} 元", color = palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChip("新增账户", true) { onOpen("asset-new") }
        ChoiceChip("调拨", false) { onOpen("asset-transfer") }
        ChoiceChip("已隐藏", false) { onOpen("asset-hidden") }
    }
    if (assets.isEmpty()) Empty("没有可见账户", "新增一个，或到已隐藏里恢复", "新增账户") { onOpen("asset-new") }
    assets.forEach { asset ->
        GlassCard(onClick = { onOpen("asset-edit:${asset.id}") }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(asset.name, color = palette.textPrimary, fontWeight = FontWeight.Medium)
                Text("${centsToYuan(asset.balance_cents)} 元", color = palette.textPrimary)
            }
            Text(asset.type_title, color = palette.textSecondary, modifier = Modifier.padding(top = 4.dp))
            Text(
                "流水 ›",
                color = palette.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp).clickable { onOpen("asset-flow:${asset.id}") },
            )
        }
    }
    if (version < 0) onNotice("")
}

@Composable
private fun AssetForm(repository: AssetRepository, id: Long?, onBack: () -> Unit, onNotice: (String) -> Unit) {
    val palette = LocalPalette.current
    val existing = remember(id) { id?.let(repository::one) }
    if (id != null && existing == null) { Missing("账户不存在", onBack); return }
    val types = remember { repository.types() }
    var typeId by remember { mutableStateOf(existing?.type_id ?: types.firstOrNull()?.id ?: 1L) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var balance by remember { mutableStateOf(existing?.let { centsToYuan(it.balance_cents) } ?: "") }
    var credit by remember { mutableStateOf(existing?.takeIf { it.credit_line > 0 }?.let { centsToYuan(it.credit_line) } ?: "") }
    var statement by remember { mutableStateOf(existing?.statement_date?.takeIf { it > 0 }?.toString() ?: "") }
    var repayment by remember { mutableStateOf(existing?.repayment_date?.takeIf { it > 0 }?.toString() ?: "") }
    var inTotal by remember { mutableStateOf(existing?.is_total_assets != 0L) }
    var confirm by remember { mutableStateOf(false) }
    val type = types.firstOrNull { it.id == typeId }
    val creditCard = type?.key == "credit"
    BackHeader(if (existing == null) "新增账户" else "编辑账户", onBack)
    Text("账户类型", color = palette.textSecondary)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        types.forEach { item -> ChoiceChip(item.title, item.id == typeId) { typeId = item.id; if (name.isBlank()) name = item.title } }
    }
    Field("名称", name, { name = it }, "例如 工资卡")
    Field("余额（元，负债填负数）", balance, { balance = it }, "0.00")
    if (creditCard) {
        Field("信用额度（元）", credit, { credit = it }, "可选")
        Field("账单日", statement, { statement = it }, "1-28，可选")
        Field("还款日", repayment, { repayment = it }, "1-28，可选")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        ChoiceChip("计入总资产", inTotal) { inTotal = true }
        ChoiceChip("不计入", !inTotal) { inTotal = false }
    }
    ActionButton("保存", name.isNotBlank() && toCents(balance) != null) {
        val cents = toCents(balance)
        if (cents == null) { onNotice("金额格式不对，例如 12.50"); return@ActionButton }
        val creditCents = toCents(credit) ?: 0
        repository.save(
            name = name.trim(),
            typeId = typeId,
            balanceCents = cents,
            creditLine = creditCents,
            repaymentDate = repayment.toLongOrNull() ?: 0,
            statementDate = statement.toLongOrNull() ?: 0,
            isTotalAssets = inTotal,
            id = existing?.id ?: 0,
        )
        onNotice("账户已保存")
        onBack()
    }
    if (existing != null) {
        ActionButton("隐藏账户", true) { repository.setVisible(existing.id, false); onNotice("已隐藏，可以恢复"); onBack() }
        if (existing.id == DEFAULT_ASSET_ID) Text("现金是默认账户，不能删除。", color = palette.textSecondary)
        else if (confirm) ConfirmBar("删除账户「${existing.name}」？已记的账单仍保留。", { confirm = false }, {
            if (repository.delete(existing.id)) onBack() else onNotice("默认账户不能删除")
        }, "删除")
        else ActionButton("删除账户", true) { confirm = true }
    }
}

@Composable
private fun HiddenAssets(repository: AssetRepository, onBack: () -> Unit, onNotice: (String) -> Unit) {
    var version by remember { mutableStateOf(0) }
    val hidden = remember(version) { repository.all().filter { it.status == 0L } }
    BackHeader("已隐藏账户", onBack)
    if (hidden.isEmpty()) Empty("没有隐藏的账户", "在账户编辑页隐藏后会出现在这里")
    hidden.forEach { asset ->
        GlassCard {
            Text(asset.name, color = LocalPalette.current.textPrimary)
            Box(Modifier.padding(top = 8.dp)) {
                ChoiceChip("恢复显示", true) {
                    repository.setVisible(asset.id, true)
                    version += 1
                    onNotice("已恢复 ${asset.name}")
                }
            }
        }
    }
}

/** 资产流水：逐笔账单对该账户的变动与余额。 */
@Composable
private fun AssetFlow(repository: AssetRepository, id: Long?, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val asset = remember(id) { id?.let(repository::one) }
    if (id == null || asset == null) {
        Missing("账户不存在", onBack)
        return
    }
    val rows = remember(id) { repository.flow(id) }
    Column(Modifier.fillMaxSize().background(palette.appBg)) {
        BackHeader("${asset.name} · 流水", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassCard {
                Text("当前余额", color = palette.textSecondary, fontSize = 12.sp)
                Text("${centsToYuan(asset.balance_cents)} 元", color = palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            if (rows.isEmpty()) {
                Empty("还没有流水", "记一笔收支就会出现在这里", "去记一笔", onBack)
            }
            rows.forEach { row ->
                GlassCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.desc.ifBlank { "账单" }, color = palette.textPrimary)
                        Text(
                            (if (row.cents >= 0) "+" else "") + centsToYuan(row.cents) + " 元",
                            color = if (row.cents >= 0) palette.textPrimary else palette.danger,
                        )
                    }
                    Text(
                        "day ${row.day} · 余额 ${centsToYuan(row.balanceAfter)} 元",
                        color = palette.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 账户间调拨：写 TYPE_TRANSFER=3，复用 saveWithBalance；不新建表。 */
@Composable
private fun TransferPage(
    repository: AssetRepository,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val assets = remember { repository.visible() }
    var fromId by remember { mutableStateOf(assets.firstOrNull()?.id) }
    var toId by remember { mutableStateOf(assets.drop(1).firstOrNull()?.id ?: assets.firstOrNull()?.id) }
    var money by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }

    BackHeader("调拨", onBack)
    if (assets.isEmpty()) {
        Empty("没有可用账户", "先新建一个资产账户", "去新建", { onOpen("asset-new") })
        return
    }
    if (assets.size < 2) {
        Empty("至少需要两个账户", "再建一个账户才能调拨", "去新建", { onOpen("asset-new") })
        return
    }

    Text("转出账户", color = palette.textSecondary, fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        assets.forEach { asset ->
            ChoiceChip(
                "${asset.name} ${centsToYuan(asset.balance_cents)}",
                fromId == asset.id,
            ) { fromId = asset.id }
        }
    }
    Text("转入账户", color = palette.textSecondary, fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        assets.forEach { asset ->
            ChoiceChip(
                "${asset.name} ${centsToYuan(asset.balance_cents)}",
                toId == asset.id,
            ) { toId = asset.id }
        }
    }
    Field("金额（元）", money, { money = it }, "例如 100.00")
    Field("备注（可选）", desc, { desc = it }, "例如 转存")

    if (!confirm) {
        ActionButton("确认调拨", true) {
            val cents = toCents(money)
            when {
                fromId == null || toId == null -> onNotice("请选择转出和转入账户")
                fromId == toId -> onNotice("转出和转入不能是同一账户")
                cents == null || cents <= 0L -> onNotice("请输入大于 0 的金额")
                else -> {
                    onNotice("")
                    confirm = true
                }
            }
        }
    } else {
        val fromName = assets.firstOrNull { it.id == fromId }?.name ?: "?"
        val toName = assets.firstOrNull { it.id == toId }?.name ?: "?"
        ConfirmBar(
            text = "将从「$fromName」转 ${money.trim()} 元到「$toName」，确认后立即改余额。",
            onCancel = { confirm = false },
            onConfirm = {
                val cents = toCents(money)
                val from = fromId
                val to = toId
                if (from == null || to == null || from == to || cents == null || cents <= 0L) {
                    onNotice("调拨参数无效")
                    confirm = false
                    return@ConfirmBar
                }
                val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                val today = (now.year * 10000 + now.monthNumber * 100 + now.dayOfMonth).toLong()
                val bill = Bill(
                    member_id = 0,
                    client_id = "transfer-$today-${kotlin.random.Random.nextLong()}",
                    money = cents,
                    refund_money = 0,
                    type = TYPE_TRANSFER,
                    exchange = 0,
                    book_id = 1,
                    member_cate_id = 0,
                    currency = "CNY",
                    day = today,
                    desc = desc.trim().ifBlank { "调拨" },
                    not_in_budget = 0,
                    images = "",
                    update_date = today,
                    create_date = today,
                    status = 0,
                    to_assets_id = to,
                    from_assets_id = from,
                    interest = 0,
                    interest_reckon_type = 0,
                    tag_ids = "",
                    source = 0,
                    import_id = 0L,
                    repayment_day = 0,
                    local_create_time = today,
                    local_update_time = today,
                    installment_plan_id = "",
                    installment_num = 0,
                    installment_money = 0,
                    installment_interest = 0,
                )
                BillsRepository(AppGraph.database, AppGraph.sync).saveWithBalance(bill)
                onNotice("已调拨 ${money.trim()} 元")
                onBack()
            },
            confirmLabel = "确认调拨",
        )
    }
}
