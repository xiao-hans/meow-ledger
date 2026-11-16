package com.meow.ledger.platform

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍采集入口。只收集窗口标题与可见文本作为候选，不代替用户点击，也不自动入账。
 *
 * 注意两点，都是实测踩出来的：
 *  - `TYPE_WINDOW_STATE_CHANGED` 的 `event.text` 通常是空的，只读它等于永远采不到东西；
 *    真正的文案要顺着 `rootInActiveWindow` 往下取。
 *  - 节流窗口必须在拿到有效文本之后才更新，否则一串空事件会把窗口占满，
 *    紧接着的内容变化事件全被丢掉。
 */
class AccessibilityCaptureService : AccessibilityService() {
    /** 同一包名的窗口变化会连发几十条，800ms 内只取最后一条文本。 */
    private val lastAt = HashMap<String, Long>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return
        val text = windowText(event)
        if (text.isBlank()) return
        val now = event.eventTime
        val last = lastAt[pkg] ?: 0L
        if (now - last < THROTTLE_MS) return
        lastAt[pkg] = now
        CandidateQueue.add(
            CaptureCandidate(
                source = "accessibility",
                sourceApp = pkg,
                title = event.className?.toString().orEmpty(),
                text = text,
                occurredAt = event.eventTime,
            ),
        )
    }

    /** 事件自带文本优先；为空时退回当前窗口的可见文案。超过上限直接丢弃，不截断成半句。 */
    private fun windowText(event: AccessibilityEvent): String {
        val own = event.text?.joinToString(" ") { it.toString() }.orEmpty().trim()
        if (own.isNotEmpty()) return own.takeIf { it.length <= MAX_TEXT } ?: ""
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return ""
        val collected = StringBuilder()
        runCatching { collect(root, collected, depth = 0) }
        return collected.toString().trim().takeIf { it.isNotEmpty() && it.length <= MAX_TEXT } ?: ""
    }

    private fun collect(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.length > MAX_TEXT) return
        val label = node.text?.toString()?.trim().orEmpty()
            .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
        if (label.isNotEmpty()) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(label)
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
            if (out.length > MAX_TEXT) return
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val THROTTLE_MS = 800L
        const val MAX_DEPTH = 12
        const val MAX_TEXT = 500
    }
}
