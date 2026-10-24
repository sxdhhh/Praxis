package dev.praxis.feature.chat

/**
 * 聊天消息里的结构化标记与供应商协议元数据（1:1 移植 Operit `ChatMarkupRegex`）。
 *
 * ## 为什么菜单需要它
 *
 * 「复制」必须把**内部标记**洗干净，否则用户复制到的是：
 *
 * ```
 * <think>用户想改标题，我应该先看看文件…</think>
 * 好的，我来改。<tool name="read_file">{"path":"a.kt"}</tool>
 * <status type="done"></status>
 * ```
 *
 * 粘到别处是一堆不完整的 XML。Operit 的 `cleanMessageContentForCopy` 就是干
 * 这件事：把 status / think / search / tool / tool_result / emotion /
 * workspace_attachment / attachment / 多媒体链接全部移除，只留 Markdown 正文。
 *
 * ## 为什么自己一份而不是引用上游文件
 *
 * Praxis 没有 Operit 的 `MediaLinkParser` / `ChatMessageMenuItemRegistry`
 * 那些模块，直接引会拖入整条依赖链。这里只移植**菜单与复制链路真正用到**的
 * 部分，并把正则源码逐字照抄（含 `IGNORE_CASE` + `DOT_MATCHES_ALL` 的取舍），
 * 这样将来补全时行为与上游一致。
 *
 * ## 2026-10-23 修正：`searchTag` 曾经被写成空正则
 *
 * 更早一版这里写着 `val searchTag = Regex("")`，并附上一段「上游就是空的」的
 * 注释。那是取证失误：重读上游 `util/ChatMarkupRegex.kt:133` 得到的是
 * `Regex("<search\\b[\\s\\S]*?</search>", setOf(IGNORE_CASE, DOT_MATCHES_ALL))`。
 *
 * 空正则在 `replace` 时匹配每个位置但替换为空串，等于**不改动** ——
 * 于是成对的 search 块（联网搜索的来源）会原样进剪贴板。现已按上游修正。
 *
 * ## 为什么同时保留 `removeImageLinks` / `removeMediaLinks` 两个函数
 *
 * 上游就是两个：前者只删 `type="image"`，后者只删 audio/video/file。
 * 调用方按顺序各调一次才是「全部媒体」。合成一个会悄悄改变语义
 * （有人只想要其中一个时没法表达）。
 */
object ChatMarkupRegex {

    /** `tool` / `tool_xxx`，但**不含** `tool_result*`。 */
    private const val TOOL_TAG_SUFFIX_REGEX_SOURCE = "[A-Za-z0-9_]+"

    /**
     * 工具标签名。
     *
     * `(?!result(?:_|\\b))` 这个负向前瞻是**关键**：`tool_result` 也以 `tool_`
     * 开头，若不加断言，`toolTag` 会把 `tool_result_xxx` 也当成「工具调用」
     * 吃掉 —— 两者在业务上语义完全不同（一个是请求、一个是返回），
     * 清洗时不能混。
     */
    private const val TOOL_TAG_NAME_REGEX_SOURCE =
        "tool(?:_(?!result(?:_|\\b))$TOOL_TAG_SUFFIX_REGEX_SOURCE)?"

    private const val TOOL_RESULT_TAG_NAME_REGEX_SOURCE =
        "tool_result(?:_${TOOL_TAG_SUFFIX_REGEX_SOURCE})?"
    /** 搜索标签名。单独抽出常量：见 `searchTag` 的 KDoc。 */
    private const val SEARCH_TAG_NAME = "search"

    private const val GEMINI_THOUGHT_SIGNATURE_PROVIDER = "gemini:thought_signature"
    private const val OPENAI_RESPONSES_REASONING_PROVIDER = "openai:responses_reasoning"
    private const val OPENAI_RESPONSES_OUTPUT_ITEM_PROVIDER = "openai:responses_output_item"

    private val metaProviderAttrRegex =
        Regex("""\bprovider\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private val metaBodyRegex = Regex(
        """<meta\b[^>]*>([\s\S]*?)</meta>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * 多媒体链接块（上游 `MediaLinkParser.LINK_PATTERN`，逐字照抄）。
     *
     * ## 两个 lookahead 是干什么的
     *
     * `<link …>` 的属性顺序在模型输出里**不固定**（`type` 可能写在 `id` 后面），
     * 所以不能写成固定顺序的字面量。用两个 `(?=[^>]*\b名字\s*=\s*…)` 前瞻
     * 分别断言「同一个开标签里这两个属性都在」，顺序随意。
     *
     * `\\*["']?` 允许属性值两侧带**转义反斜杠**与可选引号 ——
     * 模型有时把引号也转义输出。
     *
     * 尾部 `(?:/>|>.*?</link>)` 同时覆盖自闭合与成对两种形态。
     */
    private val linkPattern = Regex(
        """<link\b(?=[^>]*\btype\s*=\s*\\*["']?(image|audio|video|file)\\*["']?)(?=[^>]*\bid\s*=\s*\\*["']?([^"'\\\s>]+)\\*["']?)[^>]*(?:/>|>.*?</link>)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** `<status ...>…</status>` 与 `<status .../>`。 */
    val statusTag = Regex(
        "<status\\b[\\s\\S]*?</status>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    val statusSelfClosingTag = Regex("<status\\b[^>]*/>", RegexOption.IGNORE_CASE)

    /** `<think>` / `<thinking>` 成对标签。 */
    val thinkTag = Regex(
        "<think(?:ing)?\\b[\\s\\S]*?</think(?:ing)?>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    val thinkSelfClosingTag = Regex("<think(?:ing)?\\b[^>]*/>", RegexOption.IGNORE_CASE)

    /**
     * 联网搜索的来源块标签（成对形式）。
     *
     * ## 为什么标签名用常量拼而不是直接写字面量
     *
     * 这样写与上游的 `"<" + "search"` 字节一致，但不会在源码里出现
     * 一段形如 XML 的字符串 —— 那种字面量在部分编辑器/校验工具里会被
     * 误当作标签处理。行为与上游完全相同（`IGNORE_CASE` + `DOT_MATCHES_ALL`）。
     */
    val searchTag = Regex(
        "<" + SEARCH_TAG_NAME + "\\b[\\s\\S]*?</" + SEARCH_TAG_NAME + ">",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    val searchSelfClosingTag = Regex("<search\\b[^>]*/>", RegexOption.IGNORE_CASE)

    /**
     * `<meta provider="…">…</meta>`（用于剥离供应商元数据）。
     *
     * ## 中间那串负向断言是干什么的
     *
     * 惰性的 `[\s\S]*?` 本身没问题。但一旦模型把 `<meta>` 写成**不闭合**，
     * 惰性匹配会一路吃到**下一个** `</meta>`，把中间的真实正文一起删掉。
     * `(?:(?!<meta\b)[\s\S])*?` 禁止跨越新的 `<meta`，于是不闭合的那段
     * 只会匹配到它自己的边界。
     */
    val metaTag = Regex(
        """<meta\b[^>]*>(?:(?!<meta\b)[\s\S])*?</meta>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    val toolTag = Regex(
        """<($TOOL_TAG_NAME_REGEX_SOURCE)\b[\s\S]*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    val toolSelfClosingTag =
        Regex("""<${TOOL_TAG_NAME_REGEX_SOURCE}\b[^>]*/>""", RegexOption.IGNORE_CASE)

    val toolResultTag = Regex(
        """<($TOOL_RESULT_TAG_NAME_REGEX_SOURCE)\b[\s\S]*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    val toolResultSelfClosingTag =
        Regex("""<${TOOL_RESULT_TAG_NAME_REGEX_SOURCE}\b[^>]*/>""", RegexOption.IGNORE_CASE)

    val emotionTag = Regex(
        "<emotion\\b[\\s\\S]*?</emotion>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    val attachmentTag = Regex(
        "<attachment\\b[\\s\\S]*?</attachment>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    val attachmentSelfClosingTag = Regex(
        "<attachment\\b[\\s\\S]*?/>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    val workspaceAttachmentTag = Regex(
        "<workspace_attachment\\b[\\s\\S]*?</workspace_attachment>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 全部 `provider=…` 元数据（Gemini 思考签名 + OpenAI Responses 两类）。 */
    fun removeOpenAiResponsesProtocolMeta(content: String): String =
        removeOpenAiResponsesOutputItemMeta(removeOpenAiResponsesReasoningMeta(content))

    fun removeGeminiThoughtSignatureMeta(content: String): String =
        removeMetaByProvider(content, GEMINI_THOUGHT_SIGNATURE_PROVIDER)

    private fun removeOpenAiResponsesReasoningMeta(content: String): String =
        removeMetaByProvider(content, OPENAI_RESPONSES_REASONING_PROVIDER)

    private fun removeOpenAiResponsesOutputItemMeta(content: String): String =
        removeMetaByProvider(content, OPENAI_RESPONSES_OUTPUT_ITEM_PROVIDER)

    /** 删掉 `<link type="image" …>` 块（上游 `MediaLinkParser.removeImageLinks`）。 */
    fun removeImageLinks(content: String): String =
        linkPattern.replace(content) { match ->
            if (match.groupValues[1].equals("image", ignoreCase = true)) "" else match.value
        }

    /** 删掉音 / 视频 / 文件链接块（上游 `MediaLinkParser.removeMediaLinks`）。 */
    fun removeMediaLinks(content: String): String =
        linkPattern.replace(content) { match ->
            val type = match.groupValues[1]
            if (type.equals("audio", ignoreCase = true) ||
                type.equals("video", ignoreCase = true) ||
                type.equals("file", ignoreCase = true)
            ) {
                ""
            } else {
                match.value
            }
        }

    /**
     * 按 provider 名删 meta 块。
     *
     * ## 为什么删成功才 `trimEnd()`
     *
     * 元数据通常写在消息**末尾**（模型先输出正文、再附签名）。删掉后会留下
     * 一个空行，复制出来就多一个尾随换行。只在真删过时 trim，避免对
     * 「本来就没有元数据」的消息做无意义的尾部裁剪。
     */
    private fun removeMetaByProvider(content: String, expectedProvider: String): String {
        var removed = false
        val result = metaTag.replace(content) { match ->
            val provider = extractMetaProvider(match.value)
            if (provider.equals(expectedProvider, ignoreCase = true)) {
                removed = true
                ""
            } else {
                match.value
            }
        }
        return if (removed) result.trimEnd() else result
    }

    private fun extractMetaProvider(metaTagContent: String): String? =
        metaProviderAttrRegex
            .find(metaTagContent.substringBefore('>'))
            ?.groupValues
            ?.getOrNull(1)
}
