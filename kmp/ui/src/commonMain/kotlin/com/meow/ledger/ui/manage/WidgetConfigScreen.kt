package com.meow.ledger.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.ledger.shared.auto.KEY_THEME
import com.meow.ledger.shared.auto.SettingsStore
import com.meow.ledger.shared.auto.THEME_SYSTEM
import com.meow.ledger.shared.bills.centsToYuan
import com.meow.ledger.shared.manage.KEY_WIDGET_PIN_KIND
import com.meow.ledger.shared.manage.KEY_WIDGET_PIN_SIZE
import com.meow.ledger.shared.manage.WIDGET_KINDS
import com.meow.ledger.shared.manage.WIDGET_KIND_MONTH
import com.meow.ledger.shared.manage.WIDGET_SIZES
import com.meow.ledger.shared.manage.WIDGET_SIZE_SMALL
import com.meow.ledger.shared.manage.WidgetRepository
import com.meow.ledger.ui.AppGraph
import com.meow.ledger.ui.theme.LocalPalette
import com.meow.ledger.ui.theme.MeowTheme

/**
 * 桌面组件配置页。系统添加组件时由 [com.meow.ledger.WidgetConfigActivity] 拉起，
 * 选种类和尺寸后回调 [onConfirm]；取消不写库。
 */
@Composable
fun WidgetConfigScreen(
    appWidgetId: Int,
    today: Long,
    onConfirm: (size: String, kind: String) -> Unit,
    onCancel: () -> Unit,
) {
    val repo = remember { WidgetRepository(AppGraph.database) }
    val existing = remember { repo.bindingOf(appWidgetId)?.let { repo.get(it) } }
    // 「添加到桌面」预选的种类/尺寸：新组件优先用它，老组件仍按已绑定的来。
    val pinned = remember {
        val store = SettingsStore(AppGraph.database)
        store.text(KEY_WIDGET_PIN_KIND) to store.text(KEY_WIDGET_PIN_SIZE)
    }
    var kind by remember {
        mutableStateOf(existing?.kind ?: pinned.first.ifBlank { WIDGET_KIND_MONTH })
    }
    var size by remember {
        mutableStateOf(existing?.size ?: pinned.second.ifBlank { WIDGET_SIZE_SMALL })
    }
    MeowTheme(SettingsStore(AppGraph.database).text(KEY_THEME, THEME_SYSTEM)) {
        val palette = LocalPalette.current
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("添加到桌面", color = palette.textPrimary, fontSize = 20.sp)
            Text("选一个内容；之后长按组件也能重新配置。", color = palette.textSecondary, fontSize = 12.sp)
            Text("内容", color = palette.textSecondary, fontSize = 12.sp)
            // 七个种类分两行，避免窄屏挤成一行难点的密集 chip。
            WIDGET_KINDS.chunked(4).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { item ->
                        FilterChip(item.id == kind, { kind = item.id }, { Text(item.name) })
                    }
                }
            }
            Text("尺寸", color = palette.textSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WIDGET_SIZES.forEach { item ->
                    FilterChip(item.id == size, { size = item.id }, { Text(item.name) })
                }
            }
            val preview = repo.preview(kind, today / 100, today)
            Text("预览", color = palette.textSecondary, fontSize = 12.sp)
            Text(
                preview.firstLabel + " " + widgetValue(preview.firstValue, preview.firstMoney) +
                    "   ·   " + preview.secondLabel + " " +
                    widgetValue(preview.secondValue, preview.secondMoney),
                color = palette.textPrimary,
            )
            Button(onClick = { onConfirm(size, kind) }) { Text("添加到桌面") }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

/** 计数类不按金额换算，避免 3 笔显示成 0.03。 */
private fun widgetValue(cents: Long, money: Boolean): String =
    if (money) centsToYuan(cents) else cents.toString()
