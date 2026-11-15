package com.meow.ledger.ui.auto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** JVM 预览/测试没有位图解码：保留同样尺寸的占位，布局与真机一致。 */
@Composable
actual fun TicketThumb(path: String, modifier: Modifier) {
    Box(modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
}
