package com.meow.ledger.ui.auto

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
actual fun TicketThumb(path: String, modifier: Modifier) {
    // 缩略图已经是 320px 级别，这里再降采样一次，避免列表滚动时反复分配大图。
    val bitmap = remember(path) {
        runCatching {
            val option = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(path, option)
        }.getOrNull()
    }
    val shape = RoundedCornerShape(6.dp)
    if (bitmap == null) {
        Box(modifier.size(44.dp).clip(shape))
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(44.dp).clip(shape),
        )
    }
}
