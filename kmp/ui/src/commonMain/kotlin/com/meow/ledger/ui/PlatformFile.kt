package com.meow.ledger.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 平台文件桥。
 * - ImportCsvButton：选一个 CSV/文本文件，把名字和内容交给 onPicked。
 * - ImportBackupButton：选一个 JSON 备份文件，把名字和内容交给 onPicked。
 * - shareTextFile：把文本写成文件并发起系统分享，返回是否发起成功。
 * - copyText：把文本放进系统剪贴板。
 * OCR 引擎未接入时 platformOcrName 为空，页面只提供手动与粘贴入口。
 */
@Composable
expect fun ImportCsvButton(enabled: Boolean, modifier: Modifier = Modifier, onPicked: (name: String, content: String) -> Unit)

@Composable
expect fun ImportBackupButton(enabled: Boolean, modifier: Modifier = Modifier, onPicked: (name: String, content: String) -> Unit)

expect fun shareTextFile(name: String, content: String): Boolean

expect fun copyText(text: String): Boolean

expect fun nowBackupName(): String

expect val platformOcrName: String?

/** 选中的票据图：原图路径、缩略图路径（平台生成不了时为空）、识别文本。 */
data class PickedImage(val path: String, val thumbPath: String = "", val ocrText: String? = null)

/**
 * 选一张票据图，拷贝到应用私有目录后把路径交给 onPicked。
 * 平台接了 OCR 引擎时同时回传识别文本（ocrText 非空）；没有引擎时 ocrText 为 null，按钮禁用。
 */
@Composable
expect fun PickImageButton(enabled: Boolean, modifier: Modifier = Modifier, onPicked: (path: String, ocrText: String?) -> Unit)

/**
 * 一次多选票据图。每张图拷贝到本地后回传 [PickedImage]；顺序与系统选择器返回顺序一致。
 * 没有 OCR 引擎时 ocrText 一律为 null，但仍回传路径，页面可提示「图片已登记」。
 */
@Composable
expect fun PickMultipleImagesButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onPicked: (List<PickedImage>) -> Unit,
)
