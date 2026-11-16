package com.meow.ledger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meow.ledger.ui.theme.LocalPalette
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
actual fun ImportCsvButton(
    enabled: Boolean,
    modifier: Modifier,
    onPicked: (name: String, content: String) -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryName(context, uri)
        val content = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull().orEmpty()
        onPicked(name, content)
    }
    Button(
        onClick = { launcher.launch(arrayOf("text/*", "text/comma-separated-values", "application/csv", "text/csv")) },
        enabled = enabled,
        modifier = modifier,
    ) { Text("选择 CSV 文件") }
}


@Composable
actual fun ImportBackupButton(
    enabled: Boolean,
    modifier: Modifier,
    onPicked: (name: String, content: String) -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val content = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull().orEmpty()
        onPicked(queryName(context, uri), content)
    }
    Button(
        onClick = { launcher.launch(arrayOf("application/json", "text/plain", "text/*", "application/octet-stream")) },
        enabled = enabled,
        modifier = modifier,
    ) { Text("选择备份文件") }
}

actual fun copyText(text: String): Boolean = try {
    val manager = AndroidHost.appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("喵啊记账备份", text))
    true
} catch (_: Exception) {
    false
}

actual fun nowBackupName(): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())

private fun queryName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return uri.lastPathSegment ?: "导入文件.csv"
}

actual fun shareTextFile(name: String, content: String): Boolean = try {
    val file = File(fileShareDir(), name)
    file.writeText(content, Charsets.UTF_8)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, name)
        putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "分享 " + name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    AndroidHost.appContext.startActivity(chooser)
    true
} catch (_: Exception) {
    false
}

actual val platformOcrName: String? = "ML Kit 中文"

private fun fileShareDir(): File = File(AndroidHost.appContext.cacheDir, "share").apply { mkdirs() }

/**
 * 把相册图片复制进私有目录。用 filesDir 而不是 cacheDir：
 * 票据图会被记进账单的 images 字段长期引用，放缓存目录会被系统随时清掉。
 * 同时生成一张缩略图，列表渲染不必每次解大图。
 */
private fun copyOcrUri(context: Context, uri: Uri): String? = runCatching {
    val dir = File(context.filesDir, "tickets").apply { mkdirs() }
    val raw = queryName(context, uri).ifBlank { "ticket_${System.currentTimeMillis()}.jpg" }
    val name = "${System.currentTimeMillis()}_$raw"
    val out = File(dir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    writeThumb(out)
    out.absolutePath
}.getOrNull()

/** 原图对应的缩略图路径；生成失败返回空串，渲染时退回原图。 */
private fun thumbPathOf(original: String): String =
    File(original).let { File(it.parentFile, it.nameWithoutExtension + ".thumb.jpg") }
        .takeIf { it.exists() }?.absolutePath.orEmpty()

/** 缩略图写到同目录 `<name>.thumb.jpg`，失败返回 null，不影响原图。 */
private fun writeThumb(source: File): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
    val bitmap = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val target = File(source.parentFile, source.nameWithoutExtension + ".thumb.jpg")
    target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    bitmap.recycle()
    target.absolutePath
}.getOrNull()

private fun recognizeChinese(
    context: Context,
    path: String,
    onDone: (String?) -> Unit,
) {
    runCatching {
        val image = InputImage.fromFilePath(context, Uri.fromFile(File(path)))
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(image)
            .addOnSuccessListener { visionText -> onDone(visionText.text) }
            .addOnFailureListener { onDone(null) }
    }.onFailure { onDone(null) }
}

@Composable
actual fun PickImageButton(enabled: Boolean, modifier: Modifier, onPicked: (path: String, ocrText: String?) -> Unit) {
    val context = LocalContext.current
    // 系统照片选择器（Android 13+）：无需媒体权限，直接给图片网格。
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val copied = copyOcrUri(context, uri) ?: return@rememberLauncherForActivityResult
        recognizeChinese(context, copied) { text -> onPicked(copied, text) }
    }
    val palette = LocalPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) palette.brand else palette.divider)
            .clickable(enabled = enabled) { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        contentAlignment = Alignment.Center,
    ) {
        Text("选择票据图", color = palette.cardBg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
actual fun PickMultipleImagesButton(
    enabled: Boolean,
    modifier: Modifier,
    onPicked: (List<PickedImage>) -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val slots = arrayOfNulls<PickedImage>(uris.size)
        var remaining = uris.size
        fun finishOne() {
            remaining -= 1
            if (remaining == 0) {
                onPicked(slots.mapNotNull { it })
            }
        }
        uris.forEachIndexed { index, uri ->
            val copied = copyOcrUri(context, uri)
            if (copied == null) {
                finishOne()
                return@forEachIndexed
            }
            val thumb = thumbPathOf(copied)
            recognizeChinese(context, copied) { text ->
                slots[index] = PickedImage(copied, thumb, text)
                finishOne()
            }
        }
    }
    val palette = LocalPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) palette.brand else palette.divider)
            .clickable(enabled = enabled) {
                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("多选票据图", color = palette.cardBg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}