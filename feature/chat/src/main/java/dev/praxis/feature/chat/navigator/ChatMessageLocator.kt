package dev.praxis.feature.chat.navigator

import dev.praxis.feature.chat.BubbleRole
import dev.praxis.feature.chat.ChatBubble
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 定位器里的一条（1:1 对齐 Operit `ChatMessageLocatorPreview`）。
 *
 * ## 为什么单独一个模型，而不是直接传 [ChatBubble]
 *
 * Operit 的定位器支持「从数据库按关键词再查一次」（`loadLocatorEntries`），
 * 那次查询返回的条目可能**不在当前渲染窗口**内（历史被裁剪过）。
 * 若直接传 `ChatBubble`，就要假装那些条目也是气泡 —— 但它们没有 id、
 * 也不会被渲染。用独立模型说清楚「这是预览，不是气泡」。
 *
 * 字段与上游逐一对应：
 * - [timestamp] 跳转键（气泡 id 会随进程变，时间戳不会）
 * - [sender] 上游是 `user/ai/summary/system/think`；Praxis 只有 user/ai/tool/error
 * - [previewContent] 截断后的单行预览（[PREVIEW_CHAR_COUNT]）
 * - [contentLength] 原文长度（右侧数字 + 条形图比例）
 */
data class ChatMessageLocatorPreview(
    val timestamp: Long,
    val sender: String,
    val previewContent: String,
    val contentLength: Int,
) {
    /** 上游 `LOCATOR_PREVIEW_CHAR_COUNT = 48`。 */
    companion object {
        const val PREVIEW_CHAR_COUNT: Int = 48
    }
}

/**
 * 把气泡转成定位器条目。
 *
 * 与上游 `toLocatorPreview` 一致：先做空白归一化，再截断。
 * 不归一化的话，多行消息在单行 Text 里会显示成一堆空格（换行被折叠），
 * 用户看到的是「第 12 条：      」这种认不出来的条目。
 */
fun ChatBubble.toLocatorPreview(): ChatMessageLocatorPreview {
    val normalized = normalizeMessageSearchText(text)
    return ChatMessageLocatorPreview(
        timestamp = timestamp,
        sender = when (role) {
            BubbleRole.User -> "user"
            BubbleRole.Assistant -> "ai"
            BubbleRole.Tool -> "tool"
            BubbleRole.Error -> "error"
            // 与 Operit 的 `sender = "summary"` 同名，导航浮条/定位器
            // 才能把它与普通助手消息分开显示（见 `chat_sender_summary`）。
            BubbleRole.Summary -> "summary"
        },
        previewContent = normalized.take(ChatMessageLocatorPreview.PREVIEW_CHAR_COUNT),
        contentLength = normalized.length,
    )
}

/**
 * 空白归一化（1:1 照抄上游 `normalizeMessageSearchText`）。
 *
 * 规则：`\n` `\r` `\t` → 空格；连续空白压成一个；首尾 trim。
 *
 * ## 为什么不用 `replace(Regex("\\\\s+"), " ")`
 *
 * 正则每条消息都要编译一次匹配（定位器一次要处理几百条）。
 * 上游是手写单遍扫描，这里照抄 —— 顺带保证与上游逐字符一致
 * （比如它对「已经有空格在前」的处理不会产生前导空格）。
 */
fun normalizeMessageSearchText(text: String): String {
    if (text.isEmpty()) return ""
    val builder = StringBuilder(text.length)
    var pendingWhitespace = false
    for (char in text) {
        val normalized = when (char) {
            '\n', '\r', '\t' -> ' '
            else -> char
        }
        if (normalized.isWhitespace()) {
            pendingWhitespace = builder.isNotEmpty()
            continue
        }
        if (pendingWhitespace) {
            builder.append(' ')
            pendingWhitespace = false
        }
        builder.append(normalized)
    }
    return builder.toString().trim()
}

/**
 * 预览文本的条形长度比例（照抄上游 `messageBarFraction`）。
 *
 * 用平方根而不是线性：消息长度分布是长尾的（少数几条几千字、多数几十字）。
 * 线性映射下短消息的条会短到看不见（0.5%），平方根把差距压到可辨识范围。
 * 下限 0.18 保证「空消息」也有一条可见的色块（否则那一行看起来像渲染坏了）。
 */
fun messageBarFraction(messageLength: Int, maxMessageLength: Int): Float {
    if (maxMessageLength <= 0) return 0.18f
    return sqrt(messageLength.toFloat() / maxMessageLength.toFloat()).coerceIn(0.18f, 1f)
}

/**
 * 生成带搜索上下文的预览（照抄上游 `buildMessagePreview`）。
 *
 * ## 命中时为什么要往前留 18 个字
 *
 * 直接把匹配位置当开头，用户看到的第一个字就是关键词 ——
 * 他无法判断「这句话在讲什么」。往前留一段上下文，才能认出是哪条。
 */
fun buildMessagePreview(
    preview: ChatMessageLocatorPreview,
    searchQuery: String = "",
): String {
    val content = preview.previewContent
    if (content.isEmpty()) return preview.sender

    val normalizedQuery = normalizeMessageSearchText(searchQuery)
    if (normalizedQuery.isNotEmpty()) {
        val matchIndex = content.indexOf(normalizedQuery, ignoreCase = true)
        if (matchIndex >= 0) {
            val previewLength = 72
            val preferredStart = (matchIndex - 18).coerceAtLeast(0)
            val start = preferredStart.coerceAtMost((content.length - previewLength).coerceAtLeast(0))
            val end = (start + previewLength).coerceAtMost(content.length)
            val prefix = if (start > 0) "..." else ""
            val suffix = if (end < content.length) "..." else ""
            val snippet = content.substring(start, end).trim()
            if (snippet.isNotEmpty()) return prefix + snippet + suffix
        }
    }
    return content.take(72).let { if (it.length < content.length) it.trimEnd() + "..." else it }
}

/**
 * 居中消息的下标（LazyList 版本，照抄上游第一个重载）。
 *
 * ## 为什么要「离视口中心最近」而不是「第一个可见」
 *
 * 用户滚动后想知道的通常是「我现在看的是第几条」，指的是**屏幕中间的**那条。
 * 取第一个可见项会在「一条特别长的消息占满整屏」时给出上一条的下标。
 *
 * @param minVisibleIndex 列表里消息项之前的固定 item 数（header 等）
 * @param visibleMessageCount 消息项数（用于把 item index 换算回消息下标）
 */
fun resolveCenteredMessageIndex(
    visibleItems: List<VisibleItem>,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    minVisibleIndex: Int,
    visibleMessageCount: Int,
    totalMessageCount: Int,
): Int? {
    if (visibleMessageCount <= 0 || totalMessageCount <= 0) return null
    val viewportCenter = (viewportStartOffset + viewportEndOffset) / 2f
    val centered = visibleItems
        .filter { it.index in 0 until visibleMessageCount }
        .minByOrNull { abs((it.offset + it.size / 2f) - viewportCenter) }
        ?: return null
    return (minVisibleIndex + centered.index).coerceIn(0, totalMessageCount - 1)
}

/** 被测函数用的一屏可见项（对应 `LazyListItemInfo` 的可用子集）。 */
data class VisibleItem(
    val index: Int,
    val offset: Int,
    val size: Int,
)
