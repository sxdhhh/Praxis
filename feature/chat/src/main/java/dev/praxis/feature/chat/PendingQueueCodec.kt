package dev.praxis.feature.chat

import dev.praxis.core.ai.ChatImage

/**
 * 待发队列的持久化编解码。
 *
 * ## 为什么不用 JSON 库
 *
 * 与 `DataStoreTerminalSnapshot` 同一取舍（那里有完整论证）：
 *
 * | 方案 | 代价 |
 * |---|---|
 * | kotlinx.serialization | `feature:terminal`/`feature:chat` 要加注解 → 模块得依赖 serialization 运行时 |
 * | 手写 JSON 拼装 | 要正确转义引号 / 反斜杠 / 换行 / 控制字符，用户输入里全都有 → 极易写出解析崩溃 |
 * | **逐行 + 类型标记 + base64**（本实现） | 无依赖、无转义坑（base64 字母表不含换行与制表符）、每行可校验 |
 *
 * 队列的正文是**用户随手打的字**，里面什么字符都可能有；base64 一次编码
 * 就彻底消除转义问题，代价是存档体积略大（队列上限 20 条，可忽略）。
 *
 * ## 格式（版本 1）
 *
 * ```
 * v1
 * Q<条数>
 * M<id>\t<base64(text)>\t<imageCount>
 * I<base64(mimeType)>\t<base64(base64OfImage)>    ← 每个图片一行，紧随其消息
 * M...
 * ```
 *
 * 每行首字符是类型标记；解析时遇到不认识的标记直接判整份存档无效 ——
 * 宁可当「没有存档」，也不要读出一份错位的队列（用户会以为消息丢了）。
 */
object PendingQueueCodec {

    private const val HEADER = "v1"
    private const val MARK_COUNT = 'Q'
    private const val MARK_MESSAGE = 'M'
    private const val MARK_IMAGE = 'I'
    private const val SEP = '\t'

    /** 编码。任何内部异常都返回空串（调用方据此跳过落盘，不覆盖旧存档）。 */
    fun encode(queue: PendingQueue): String = runCatching {
        buildString {
            append(HEADER).append('\n')
            append(MARK_COUNT).append(queue.items.size).append('\n')
            queue.items.forEach { item ->
                append(MARK_MESSAGE)
                append(item.id)
                append(SEP).append(b64(item.text))
                append(SEP).append(item.images.size)
                append('\n')
                item.images.forEach { image ->
                    append(MARK_IMAGE)
                    append(b64(image.mimeType))
                    append(SEP).append(b64(image.base64))
                    append('\n')
                }
            }
        }
    }.getOrDefault("")

    /**
     * 解码。
     *
     * 返回空队列表示「没有存档或存档损坏」。**不抛异常**：
     * 调用方在恢复路径上，一个坏存档不该让聊天页整体失败。
     */
    fun decode(text: String?): PendingQueue = runCatching {
        if (text.isNullOrBlank()) return PendingQueue()
        val lines = text.split('\n')
        if (lines.size < 2 || lines[0] != HEADER) return PendingQueue()
        if (lines[1].firstOrNull() != MARK_COUNT) return PendingQueue()
        val declared = lines[1].drop(1).toIntOrNull() ?: return PendingQueue()

        val items = mutableListOf<QueuedMessage>()
        var pendingImages = mutableListOf<ChatImage>()
        var pendingId: Long? = null
        var pendingText: String? = null

        fun flush() {
            val id = pendingId ?: return
            val body = pendingText ?: return
            items += QueuedMessage(id = id, text = body, images = pendingImages.toList())
            pendingId = null
            pendingText = null
            pendingImages = mutableListOf()
        }

        for (line in lines.drop(2)) {
            if (line.isBlank()) continue
            when (line[0]) {
                MARK_MESSAGE -> {
                    flush()
                    val parts = line.drop(1).split(SEP)
                    // 三段缺一不可：id \t text \t imageCount
                    if (parts.size < 3) return PendingQueue()
                    pendingId = parts[0].toLongOrNull() ?: return PendingQueue()
                    pendingText = unb64(parts[1]) ?: return PendingQueue()
                }
                MARK_IMAGE -> {
                    val parts = line.drop(1).split(SEP)
                    if (parts.size < 2) return PendingQueue()
                    val mime = unb64(parts[0]) ?: return PendingQueue()
                    val data = unb64(parts[1]) ?: return PendingQueue()
                    pendingImages += ChatImage(mimeType = mime, base64 = data)
                }
                // 不认识的标记：整份存档判无效，不做部分恢复。
                else -> return PendingQueue()
            }
        }
        flush()

        // 条数对不上说明存档写了一半（进程被杀）。
        // 这种情况宁可从空队列开始 —— 半份队列会静默丢掉后面几条，
        // 而用户以为自己排的都还在。
        if (items.size != declared) return PendingQueue()
        PendingQueue(items = items)
    }.getOrDefault(PendingQueue())

    /**
     * ## 为什么用 `java.util.Base64` 而不是 `android.util.Base64`
     *
     * 后者在**单元测试**里是桩（`unitTests.isReturnDefaultValues` 下返回 null），
     * 编解码测试会全部挂掉 —— 而这个类的价值恰恰全在「往返是否正确」上。
     *
     * `java.util.Base64` 是纯 JVM 实现（API 26+，本项目 minSdk 28），
     * 于是本类可在纯 JVM 单测里完整验证。
     */
    private fun b64(value: String): String =
        java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun unb64(value: String): String? = runCatching {
        String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
    }.getOrNull()
}
