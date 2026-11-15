package com.meow.ledger.ui.auto

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 待确认队列里的票据缩略图。Android 从缩略图文件解码并按 4×4 dp 圆角裁切；
 * 桌面/JVM 测试环境没有位图解码，实际渲染为空占位，布局不受影响。
 */
@Composable
expect fun TicketThumb(path: String, modifier: Modifier = Modifier)
