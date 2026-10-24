package dev.praxis.core.ai

/**
 * 上下文用量估算（1:1 移植 Operit `ChatUtils.estimateTokenCount`，
 * `util/ChatUtils.kt:104`）。
 *
 * ## 2026-10-23 重大修正：中文系数写反了 2.25 倍
 *
 * 上一版这里写着：
 *
 * ```kotlin
 * // 中文 1.5 字符/token → cjk / 1.5 ≈ cjk * 0.667
 * val tokens = (cjk / 1.5) + (other / 4.0)
 * ```
 *
 * 注释与代码自洽，但**前提是错的**。上游 `ChatUtils` 的原式是：
 *
 * ```kotlin
 * (chineseCharCount * 1.5 + otherCharCount * 0.25).toLong()
 * ```
 *
 * 即「一个汉字约等于 **1.5** 个 token」，不是「1.5 个汉字等于 1 个 token」。
 * 差别是 **2.25 倍**，后果非常具体：
 *
 * ```
 * 一段 20000 字的中文对话
 *   上游算：30000 token
 *   旧实现：13333 token   ← 低估 55%
 * ```
 *
 * 低估的直接后果是「压缩永不触发」：进度条显示 30%，用户继续聊，
 * 直到某一轮上游直接 400（context length exceeded）。这比高估严重得多 ——
 * 高估只是提前压缩，低估是整轮失败。
 *
 * ## 三档系数均取自上游
 *
 * | 内容 | 系数 |
 * |---|---|
 * | 汉字（`U+4E00..U+9FFF`） | **× 1.5** |
 * | 其余字符 | **× 0.25**（4 字符/token） |
 *
 * ## 与上游的三处有意差异（逐条说明）
 *
 * 1. 上游 `toLong()` 截断，这里同样截断（`toInt()`）；
 * 2. 上游只统计 `U+4E00..U+9FFF`，这里**回退到同一口径**（见 [estimate]）；
 * 3. 上游没有「每条消息固定开销」，这里保留 —— 上游估算的是
 *    `Pair<role, content>` 的裸文本，而 Praxis 估算的是要序列化成
 *    OpenAI chat JSON 的 `ChatMessage`，后者确实有结构开销。
 */
object TokenEstimator {

    /**
     * 粗略估算一段文本的 token 数。空串返回 0。
     *
     * ## 为什么汉字区间只认 `U+4E00..U+9FFF`
     *
     * 上一版还额外统计了 `U+3000..U+303F`（CJK 标点）与 `U+FF00..U+FFEF`
     * （全角字符）。上游只认基本汉字区，这里**回退到上游口径**。
     *
     * 理由不是「上游就一定对」，而是**两套区间造成偏差方向不一致**：
     * 中文标点（，。「」）在 BPE 里常常是 1 个 token，按「汉字 1.5」算会高估；
     * 而全角字母数字常常是 1-2 个 token，按 0.25 算又低估。
     * 把这两块混进汉字区会让误差不稳定 —— 有时高估有时低估，
     * 而「稳定地略高估」才是压缩触发逻辑需要的性质。
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF) cjk++
        }
        val other = text.length - cjk
        // 与上游同式：汉字 × 1.5 + 其余 × 0.25，再截断成整数。
        //
        // 截断（而不是 ceil）是照抄上游。单条文本截断最多少 1 token，
        // 而 [estimate] 带消息时补了 PER_MESSAGE_OVERHEAD，总体上仍偏高 ——
        // 这个「局部偏低、整体偏高」的组合正是上游的实际行为。
        return (cjk * 1.5 + other * 0.25).toInt()
    }

    /**
     * 估算一批消息。
     *
     * 每条额外 +4：角色字段、JSON 键名、分隔符与消息边界的固定开销。
     * 漏掉它会让「很多条短消息」的长对话被系统性低估 ——
     * 而短消息密集的对话（用户连续追问）恰恰是最常见的长会话形态。
     */
    fun estimate(messages: List<ChatMessage>): Int =
        messages.sumOf { estimate(it.text) + PER_MESSAGE_OVERHEAD + estimateTools(it) }

    /** 工具声明也占上下文，且常常是大头（一个工具 schema 几十 token）。 */
    private fun estimateTools(message: ChatMessage): Int {
        if (message.toolCalls.isEmpty() && message.toolResult == null) return 0
        var sum = message.toolCalls.sumOf { estimate(it.name) + estimate(it.argumentsJson) }
        message.toolResult?.let { sum += estimate(it.content) }
        return sum
    }

    private const val PER_MESSAGE_OVERHEAD = 4

    /** 把 token 数格式化成人类可读的 `12.3k` / `1.2M`。 */
    fun format(tokens: Int): String = when {
        // 不用 String.format：它依赖默认 Locale，在阿拉伯语等区域会把小数点
        // 渲染成别的字符（lint 的 DefaultLocale 也是这个原因），
        // 而这里是要显示给用户看「还剩多少上下文」，数字必须稳定可读。
        tokens >= 1_000_000 -> oneDecimal(tokens, 1_000_000) + "M"
        tokens >= 1_000 -> oneDecimal(tokens, 1_000) + "k"
        else -> tokens.toString()
    }

    /** 取一位小数，四舍五入。`12_345 / 1_000` → `"12.3"`。 */
    private fun oneDecimal(tokens: Int, unit: Int): String {
        // 先放大 10 倍再取整，等价于保留一位小数，但不碰浮点误差与 Locale。
        val scaled = (tokens * 10L + unit / 2) / unit
        return (scaled / 10).toString() + "." + (scaled % 10).toString()
    }
}

/**
 * 增量 token 计算（1:1 移植 Operit `TokenCacheManager`，`util/TokenCacheManager.kt`）。
 *
 * ## 上游要解决的问题
 *
 * 长对话里每发一轮消息，历史几乎没变 —— 只多了一两条。但每次都要
 * 从头算一遍全部历史的 token。上游的做法是**公共前缀缓存**：
 *
 * ```
 * 上一次历史：[m1, m2, m3, m4]      已算出 total = 5000
 * 这一次历史：[m1, m2, m3, m4, m5]  公共前缀 = 4，只需算 m5
 * ```
 *
 * 前缀比较用**消息相等**（data class 的 `equals`），不是逐字符比较 ——
 * 一条消息只要文本或工具字段变了就判为不同；逐字符比反而更慢（长消息），
 * 还会漏掉工具字段的变化。
 *
 * ## 为什么 Praxis 也需要它
 *
 * `ChatViewModel.publishUsage` 在每次 `buildPreparedMessages` 之后都调用一次，
 * 而它位于流式期间的开销路径上。这是**随对话变长而恶化**的成本，
 * 而对话本来就是要变长的。
 *
 * ## 与上游的差异：不把 cached 当作「服务端缓存命中」
 *
 * 上游还用 `updateActualTokens` 把服务端 prompt caching 的命中量写进同一个
 * 字段。Praxis 的 provider 不返回这类字段，所以这里只做**本地计算加速**，
 * 不对外声称「缓存命中」—— 混用会让 UI 上的数字变成假的。
 */
class TokenCache {

    /** 上一次算过的消息列表。 */
    private var previous: List<ChatMessage> = emptyList()

    /** 与 [previous] 对应的总 token 数。 */
    private var previousTotal: Int = 0

    /** 上一次计算里「公共前缀」贡献的 token 数（仅诊断用）。 */
    var cachedPrefixTokens: Int = 0
        private set

    /** 上一次计算里「新增部分」贡献的 token 数（仅诊断用）。 */
    var incrementalTokens: Int = 0
        private set

    /**
     * 清空缓存。
     *
     * 两个必须调用的时机：切会话（历史完全换了）、压缩生效后
     * （历史的头部被换成了一段摘要，公共前缀必然断裂但旧值仍在）。
     */
    fun reset() {
        previous = emptyList()
        previousTotal = 0
        cachedPrefixTokens = 0
        incrementalTokens = 0
    }

    /**
     * 计算 [messages] 的 token 总数，尽量复用上次的公共前缀。
     *
     * @return 总 token 数，与 [TokenEstimator.estimate] 对同一输入的结果一致。
     */
    fun count(messages: List<ChatMessage>): Int {
        if (messages.isEmpty()) {
            reset()
            return 0
        }

        val common = commonPrefixLength(messages, previous)
        if (common == 0) {
            // 没有公共前缀（刚压缩完、或换了会话）：全量算。
            val total = TokenEstimator.estimate(messages)
            previous = messages
            previousTotal = total
            cachedPrefixTokens = 0
            incrementalTokens = total
            return total
        }

        // 公共前缀的 token 数怎么来？
        //
        // ① 前缀完整覆盖旧列表（`common == previous.size`）：
        //    直接复用上次总数。这是最常见路径（追加一轮对话）。
        // ② 前缀只是旧列表的一部分：中间某条消息被改了（编辑消息）。
        //    旧总数不可用，必须重算前缀 —— 但仍然省掉了尾部。
        val prefixTokens = if (common == previous.size) {
            previousTotal
        } else {
            TokenEstimator.estimate(messages.take(common))
        }

        val tailTokens = TokenEstimator.estimate(messages.drop(common))
        val total = prefixTokens + tailTokens

        previous = messages
        previousTotal = total
        cachedPrefixTokens = prefixTokens
        incrementalTokens = tailTokens
        return total
    }

    /** 两个列表从头开始逐条相等的长度。 */
    private fun commonPrefixLength(
        current: List<ChatMessage>,
        old: List<ChatMessage>,
    ): Int {
        val limit = minOf(current.size, old.size)
        var i = 0
        while (i < limit) {
            if (current[i] != old[i]) break
            i++
        }
        return i
    }
}