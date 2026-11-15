package com.meow.ledger.ui.manage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.ledger.ui.auto.ActionButton
import com.meow.ledger.ui.theme.LocalPalette

/**
 * 启动时的应用锁。口令为空时不拦截；输错只提示，不给重置入口。
 * [biometricAvailable] 为 true 时先自动拉起一次生物识别，同时保留「用指纹解锁」按钮；
 * 取消/失败不阻塞口令输入。
 */
@Composable
fun LockGate(
    pin: String,
    onUnlock: () -> Unit,
    biometricAvailable: Boolean = false,
    requestBiometric: ((Boolean) -> Unit) -> Unit = {},
) {
    val palette = LocalPalette.current
    var input by rememberSaveable { mutableStateOf("") }
    var wrong by rememberSaveable { mutableStateOf(false) }
    var biometricTried by rememberSaveable { mutableStateOf(false) }
    val unlockByBiometric: () -> Unit = {
        requestBiometric { ok -> if (ok) onUnlock() }
    }
    LaunchedEffect(biometricAvailable) {
        if (biometricAvailable && !biometricTried) {
            biometricTried = true
            unlockByBiometric()
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("应用锁", color = palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text("输入四位口令后进入", color = palette.textSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Field("四位数字", input, {
            if (it.length <= 4 && it.all(Char::isDigit)) {
                input = it
                wrong = false
            }
        }, "")
        if (wrong) Text("口令不对", color = palette.danger, modifier = Modifier.padding(top = 8.dp))
        ActionButton("进入", input.length == 4) {
            if (input == pin) onUnlock() else wrong = true
        }
        if (biometricAvailable) {
            Box(Modifier.padding(top = 12.dp)) {
                ActionButton("用指纹 / 面容解锁", true) { unlockByBiometric() }
            }
        }
    }
}
