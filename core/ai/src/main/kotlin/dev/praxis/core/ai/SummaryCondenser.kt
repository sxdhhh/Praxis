package dev.praxis.core.ai

/**
 * 摘要瘦身用的最小正则集（`ChatMarkupRegex` 在 `:feature:chat`，`core:ai` 不能反向依赖）。
 *
 * ## 为什么单独一份，而不是把 `ChatMarkupRegex` 提到 `core:ai`
 *
 * `ChatMarkupRegex` 里还有「复制洗白」用的十几个正则（status / think /
 * search / emotion / attachment / meta 剥离 …），那些只服务 UI 层，
 * 提上来会把 UI 的关注点塞进 AI 抽象层。
 *
 * 而摘要瘦身真正需要的只有这四条：**分块、抽属性、读 content/error、去标签**。
 * 分开维护的代价是「两处可能漂移」，所以这里的每一条都在注释里标了
 * 对应的上游来源，并且行为与 `:feature:chat` 那份一致 —— 有测试锁住。
 *
 * ## 为什么不干脆在 `core:ai` 里定义一份完整 `ChatMarkupRegex`
 *
 * 那会出现**两个同名 object**（不同包），调用点 import 错一个就静默用错实现。
 * 只有一个 21 行的窄接口，反而更难出错。
 */
internal object MarkupPatterns {

    /** 工具标签名：`tool` / `tool_xxx`，排除 `tool_result*`。 */
    private const val TOOL_TAG_NAME = "tool(?:_(?!result(?:_|\\b))[A-Za-z0-9_]+)?"

    /** 工具结果标签名：`tool_result` / `tool_result_xxx`。 */
    private const val TOOL_RESULT_TAG_NAME = "tool_result(?:_[A-Za-z0-9_]+)?"

    /**
     * 同时匹配 `tool` 与 `tool_result` 的**成对**块。
     *
     * `[\\s\\S]*?` 是惰性的，配合 `\\1` 反向引用保证「开标签名 == 闭标签名」，
     * 否则 `<tool_a>…</tool_b>` 这种会跨块错配。
     */
    val toolOrToolResultBlock = Regex(
        """<($TOOL_RESULT_TAG_NAME|$TOOL_TAG_NAME)\b[\s\S]*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 带属性的 `tool_result` 成对块：group2 = 属性串，group3 = 内文。 */
    val toolResultTagWithAttrs = Regex(
        """<($TOOL_RESULT_TAG_NAME)\b([^>]*)>([\s\S]*?)</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** `<param name="x">值</param>`。 */
    val toolParam = Regex("<param\\s+name=\"([^\"]+)\">([\\s\\S]*?)</param>")

    /** `name="…"` 属性。 */
    val nameAttr = Regex("name\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    /** `status="…"` 属性。 */
    val statusAttr = Regex("status\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    /** `<content>…</content>`：工具**成功**结果的内文。 */
    val contentTag = Regex(
        "<content>([\\s\\S]*?)</content>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** `<error>…</error>`：工具**失败**结果的内文。 */
    val errorTag = Regex(
        "<error>([\\s\\S]*?)</error>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** `<workspace_attachment>…</workspace_attachment>`。 */
    val workspaceAttachment = Regex(
        "<workspace_attachment\\b[\\s\\S]*?</workspace_attachment>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** `<attachment>…</attachment>`（自闭合形态由 [anyXmlTag] 兜底）。 */
    val attachment = Regex(
        "<attachment\\b[\\s\\S]*?</attachment>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** `<reply_to …>…</reply_to>`。内文用 `[^<]*`：引用内容里不会再嵌标签。 */
    val replyTo = Regex(
        """<reply_to\s+sender="([^"]+)"\s+timestamp="([^"]+)">([^<]*)</reply_to>""",
    )

    /** `<think>` / `<thinking>` 成对标签。 */
    val thinkTag = Regex(
        "<think(?:ing)?\\b[\\s\\S]*?</think(?:ing)?>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    val thinkSelfClosingTag = Regex("<think(?:ing)?\\b[^>]*/>", RegexOption.IGNORE_CASE)

    val searchTag = Regex(
        "<search\\b[\\s\\S]*?</search>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    val searchSelfClosingTag = Regex("<search\\b[^>]*/>", RegexOption.IGNORE_CASE)

    /** 任意 XML 标签（含自闭合与结束标签）。 */
    val anyXmlTag = Regex("<[^>]*>")

    /** 取开标签名。用「字母开头 + 字母数字下划线」精确匹配，容忍属性里出现 `>`。 */
    private val openingTagName = Regex("<([A-Za-z][A-Za-z0-9_]*)")

    fun extractOpeningTagName(xml: String): String? =
        openingTagName.find(xml.trim())?.groupValues?.getOrNull(1)

    private val toolTagNameRegex = Regex("^$TOOL_TAG_NAME$", RegexOption.IGNORE_CASE)

    private val toolResultTagNameRegex =
        Regex("^$TOOL_RESULT_TAG_NAME$", RegexOption.IGNORE_CASE)

    /**
     * 把 `tool_abc` / `tool_result_abc` 归一成 `tool` / `tool_result`。
     *
     * 模型可以生成带随机后缀的标签名（上游 `generateRandomToolTagName`）
     * 来避免与自己正文里的示例冲突。归一之后，「这是调用还是结果」
     * 的判断就与后缀无关。
     */
    fun normalizeToolLikeTagName(tagName: String?): String? = when {
        tagName == null -> null
        toolTagNameRegex.matches(tagName) -> "tool"
        toolResultTagNameRegex.matches(tagName) -> "tool_result"
        else -> tagName
    }
}

/**
 * 摘要输入瘦身（1:1 移植 Operit `AIMessageManager.summarizeMemory` 内的
 * `condense*` 系列，`core/chat/AIMessageManager.kt:684~1010`）。
 *
 * ## 为什么摘要之前必须先瘦身
 *
 * 这一步看起来「只是截断」，实际是压缩链路能不能成立的关键：
 *
 * ```
 * 一次压缩的输入 = 要被折叠的那一段对话
 * 而这段对话之所以被折叠，正是因为它太长了
 * ```
 *
 * 如果把原始消息（含工具调用参数、工具结果、附件、思考块）整段喂给摘要模型：
 *
 * - **成本**：压缩本身变成一次超大请求，可能自己就超限；
 * - **质量**：工具结果是几十 KB 的 JSON / 日志，摘要模型会把注意力
 *   全放在那上面，反而忽略用户到底要什么；
 * - **确定性**：附件里的 base64 会让每次摘要结果差异极大。
 *
 * 所以上游按角色用不同策略：
 *
 * | 角色 | 策略 |
 * |---|---|
 * | 用户 | 剥附件 / prompt 块 → 工具结果换成「[结果: name status] + 预览」→ 头 240 + 尾 96 |
 * | 助手 | 剥思考块 → 按 tool / tool_result 分块 → 文本块头 120/尾 48（**最后一段** 280/120）→ 工具块压成一行 → 超 25 块时头 12 + 尾 10 |
 *
 * 这些数字都照抄上游，代表「用户说的要完整读、助手中间过程可以粗、
 * 但结论要完整读」这个判断。
 */
object SummaryCondenser {

    // ── 预算常量（全部取自上游 summarizeMemory）──────────────────────

    /** 用户消息：头 240 字 + 尾 96 字。 */
    private const val USER_HEAD = 240
    private const val USER_TAIL = 96

    /** 助手普通文本块：头 120 + 尾 48。 */
    private const val ASSISTANT_HEAD = 120
    private const val ASSISTANT_TAIL = 48

    /** 助手**最后一段**文本：头 280 + 尾 120。 */
    private const val ASSISTANT_LAST_HEAD = 280
    private const val ASSISTANT_LAST_TAIL = 120

    /** 工具参数：头 72 + 尾 32；最多列 8 个参数。 */
    private const val TOOL_PARAM_HEAD = 72
    private const val TOOL_PARAM_TAIL = 32
    private const val TOOL_PARAM_MAX = 8

    /** 工具调用正文预览：头 120 + 尾 48。 */
    private const val TOOL_BODY_HEAD = 120
    private const val TOOL_BODY_TAIL = 48

    /** 工具结果预览：头 140 + 尾 56。 */
    private const val TOOL_RESULT_HEAD = 140
    private const val TOOL_RESULT_TAIL = 56

    /** 助手消息的片段数上限：超过就头 12 + 尾 10 + 省略提示。 */
    private const val MAX_SEGMENTS = 25
    private const val HEAD_SEGMENTS = 12
    private const val TAIL_SEGMENTS = 10

    // ── 文案（与上游 strings.xml 逐字一致）──────────────────────────

    private const val TOOL_START_PREFIX = "[工具: "
    private const val RESULT_START_PREFIX = "[结果: "
    private const val RESULT_SUFFIX = "]"
    private const val SUCCESS = "成功"
    private const val FAILURE = "失败"
    private const val EMPTY = "[空]"
    private const val WORKSPACE_OMITTED = "[已附着的开发工作区已省略]"
    private const val ATTACHMENT_OMITTED = "[附件已省略]"
    private const val REPLY_OMITTED = "[回复引用已省略]"
    private const val TOOL_RESULT_OMITTED_SHORT = "[工具结果已省略]"

    /**
     * 瘦身一条**用户**消息。
     *
     * 顺序有讲究：
     *
     *  1. 先剥大块附件（`workspace_attachment` / `attachment` / `reply_to`）——
     *     它们可能占几百 KB，先剥掉能让后面的正则扫得快；
     *  2. 再把 `tool_result` **成对块**换成「[结果: name status] + 预览」，
     *     而不是直接删。直接删会让摘要丢掉「工具跑过什么」这个事实；
     *  3. 最后整体按头尾截断。
     */
    fun condenseUser(raw: String): String {
        val pruned = pruneUserLargeBlocks(raw)
        return headTail(pruned, USER_HEAD, USER_TAIL)
    }

    /**
     * 瘦身一条**助手**消息。
     *
     * ## 为什么要分块而不是整体截断
     *
     * 助手消息的结构是「文本 → 工具调用 → 工具结果 → 文本 → …」。
     * 整体截断只会留下开头一小段 + 结尾一小段，中间的工具调用链
     * 会被整段削平 —— 而「它查了什么、拿到什么、据此得出什么」
     * 恰恰是摘要最该保留的因果关系。
     *
     * 分块后每块按自己的规则压：文本块保留头尾，工具块压成一行元信息，
     * 于是 20 次工具调用的消息能被压成 20 行可读的轨迹。
     */
    fun condenseAssistant(raw: String): String {
        val withoutThinking = stripThinking(raw)
        val normalized = normalizeWhitespace(withoutThinking)
        if (normalized.isBlank()) return EMPTY

        val segments = splitSegments(normalized)
        if (segments.isEmpty()) return EMPTY

        // 超长时头 12 + 尾 10 + 省略一句。照抄上游的 12/10 而不是对半 12/12：
        // 尾部比头部更常包含「本轮结论」，但头部定义了任务是什么，
        // 上游的比例是「任务优先、结论随后」。
        val kept = if (segments.size > MAX_SEGMENTS) {
            val head = segments.take(HEAD_SEGMENTS)
            val tail = segments.takeLast(TAIL_SEGMENTS)
            val omitted = segments.size - head.size - tail.size
            head + Segment.Text(raw = "[中间省略 $omitted 段]") + tail
        } else {
            segments
        }

        // 找最后一段文本：它给更大的预算。
        val lastTextIndex = kept.indexOfLast { it is Segment.Text }

        val parts = kept.mapIndexedNotNull { index, segment ->
            when (segment) {
                is Segment.Text -> {
                    val head = if (index == lastTextIndex) ASSISTANT_LAST_HEAD else ASSISTANT_HEAD
                    val tail = if (index == lastTextIndex) ASSISTANT_LAST_TAIL else ASSISTANT_TAIL
                    headTail(segment.raw, head, tail).takeIf { it.isNotBlank() }
                }

                is Segment.Tool -> {
                    val params = condenseToolParams(segment.raw)
                    val body = condenseToolBody(segment.raw)
                    buildString {
                        append(TOOL_START_PREFIX)
                        append(segment.name ?: "tool")
                        append(RESULT_SUFFIX)
                        if (params.isNotBlank()) {
                            append(" ")
                            append(params)
                        }
                        if (body.isNotBlank()) {
                            append(if (params.isNotBlank()) " | " else " ")
                            append(body)
                        }
                    }.trim().ifBlank { null }
                }

                is Segment.ToolResult -> {
                    val status = when (segment.status?.lowercase()) {
                        null -> ""
                        "success" -> SUCCESS
                        "error" -> FAILURE
                        else -> segment.status
                    }
                    val preview = condenseToolResult(segment.raw)
                    buildString {
                        append(RESULT_START_PREFIX)
                        append(segment.name ?: "tool")
                        if (status.isNotBlank()) {
                            append(" ")
                            append(status)
                        }
                        append(RESULT_SUFFIX)
                        if (preview.isNotBlank()) {
                            append(" ")
                            append(preview)
                        }
                    }
                }
            }
        }

        val combined = parts.joinToString(" ").trim()
        return combined.ifBlank { EMPTY }
    }

    // ── 内部：分块 ──────────────────────────────────────────────────
internal sealed interface Segment {
        /** 该片段的原始文本（已去标签 / 或整段工具块原文）。 */
        val raw: String

        data class Text(override val raw: String) : Segment

        data class Tool(
            override val raw: String,
            val name: String?,
        ) : Segment

        data class ToolResult(
            override val raw: String,
            val name: String?,
            val status: String?,
        ) : Segment
    }

    /**
     * 把消息切成交替的「文本 / 工具 / 工具结果」片段。
     *
     * ## 为什么用 `toolOrToolResultBlock` 一次扫完
     *
     * `tool` 与 `tool_result` 在文本里是**混排**的，且 `tool_result_x` 也以
     * `tool_` 开头。用一条正则同时匹配两者、再用
     * `normalizeToolLikeTagName` 归一化名字，比跑两遍正则简单且不会错位
     * （两遍会让 `tool_result` 被 `tool` 的规则先吃掉一半）。
     */
    internal fun splitSegments(text: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var lastEnd = 0
        for (match in MarkupPatterns.toolOrToolResultBlock.findAll(text)) {
            val start = match.range.first
            if (start > lastEnd) {
                val stripped = stripXmlTags(text.substring(lastEnd, start))
                if (stripped.isNotBlank()) out += Segment.Text(stripped)
            }
            val block = match.value
            val rawName = MarkupPatterns.extractOpeningTagName(block)
            val normalized = MarkupPatterns.normalizeToolLikeTagName(rawName)
            val name = MarkupPatterns.nameAttr.find(block)
                ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            if (normalized == "tool") {
                out += Segment.Tool(raw = block, name = name)
            } else {
                val status = MarkupPatterns.statusAttr.find(block)
                    ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                out += Segment.ToolResult(raw = block, name = name, status = status)
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) {
            val stripped = stripXmlTags(text.substring(lastEnd))
            if (stripped.isNotBlank()) out += Segment.Text(stripped)
        }
        return out
    }

    // ── 内部：各块压缩 ──────────────────────────────────────────────

    /** 参数列表压成 `name=预览; name2=预览; ...+N`。 */
    internal fun condenseToolParams(block: String): String {
        val params = MarkupPatterns.toolParam.findAll(block).mapNotNull { match ->
            val name = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val rawValue = stripXmlTags(match.groupValues.getOrNull(2).orEmpty())
            val preview = headTail(rawValue, TOOL_PARAM_HEAD, TOOL_PARAM_TAIL)
            if (name.isBlank() || preview.isBlank()) null else "$name=$preview"
        }.toList()
        if (params.isEmpty()) return ""
        val visible = params.take(TOOL_PARAM_MAX)
        val omitted = params.size - visible.size
        return buildString {
            append(visible.joinToString("; "))
            if (omitted > 0) {
                append("; ...+")
                append(omitted)
            }
        }
    }

    /** 工具调用的正文（去掉参数后的部分）压成预览。 */
    internal fun condenseToolBody(block: String): String {
        val withoutParams = MarkupPatterns.toolParam.replace(xmlBody(block), " ")
        return headTail(stripXmlTags(withoutParams), TOOL_BODY_HEAD, TOOL_BODY_TAIL)
    }

    /**
     * 工具结果压成预览。
     *
     * 取值优先级：`<content>` → `<error>` → 整个标签内文。
     * 上游就是这个顺序 —— 成功的工具结果在 `content` 里，
     * 失败的在 `error` 里，两者都没有再用内文兜底。
     */
    internal fun condenseToolResult(block: String): String {
        val body = MarkupPatterns.contentTag.find(block)?.groupValues?.getOrNull(1)
            ?: MarkupPatterns.errorTag.find(block)?.groupValues?.getOrNull(1)
            ?: xmlBody(block)
        return headTail(stripXmlTags(body), TOOL_RESULT_HEAD, TOOL_RESULT_TAIL)
    }

    // ── 内部：文本工具函数 ──────────────────────────────────────────

    /**
     * 把大块附件换成人话占位。
     *
     * ## 为什么用「占位」而不是删掉
     *
     * 附件本身不该进摘要（体积 + 无意义），但「**这条消息带过附件**」
     * 是个重要事实：用户说「看这个文件」而摘要里没有附件痕迹，
     * 下一轮模型会不知道自己漏掉了什么。
     */
    internal fun pruneUserLargeBlocks(text: String): String {
        val replaced = text
            .replace(MarkupPatterns.workspaceAttachment, WORKSPACE_OMITTED)
            .replace(MarkupPatterns.attachment, ATTACHMENT_OMITTED)
            .replace(MarkupPatterns.replyTo, REPLY_OMITTED)

        return MarkupPatterns.toolResultTagWithAttrs.replace(replaced) { match ->
            val attrs = match.groupValues.getOrNull(2).orEmpty()
            val name = MarkupPatterns.nameAttr.find(attrs)
                ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            val status = MarkupPatterns.statusAttr.find(attrs)
                ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            val statusSuffix = status?.let { " $it" }.orEmpty()
            val preview = condenseToolResult(match.value)
            buildString {
                if (name != null) {
                    append(RESULT_START_PREFIX)
                    append(name)
                    append(statusSuffix)
                    append(RESULT_SUFFIX)
                } else {
                    append(TOOL_RESULT_OMITTED_SHORT)
                }
                if (preview.isNotBlank()) {
                    append(" ")
                    append(preview)
                }
            }
        }
    }

    /**
     * 剥掉思考块与搜索块（对齐上游 `ChatUtils.removeThinkingContent`）。
     *
     * 思考内容对摘要**只有害处**：它是模型的自言自语，会把摘要的
     * 「事实密度」稀释掉，且往往比结论长好几倍。
     */
    internal fun stripThinking(text: String): String {
        if (!text.contains("<think", ignoreCase = true) &&
            !text.contains("<search", ignoreCase = true)
        ) {
            return text.trim()
        }
        return text
            .replace(MarkupPatterns.thinkTag, "")
            .replace(MarkupPatterns.thinkSelfClosingTag, "")
            .replace(MarkupPatterns.searchTag, "")
            .replace(MarkupPatterns.searchSelfClosingTag, "")
            .trim()
    }

    /** 把所有 XML 标签换成空格（不是删掉：避免两个词黏成一个）。 */
    internal fun stripXmlTags(text: String): String =
        MarkupPatterns.anyXmlTag.replace(text, " ").trim()

    /** 折叠所有空白成单空格（上游 `normalizeForReview`）。 */
    internal fun normalizeWhitespace(text: String): String =
        text.replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** 取标签内文（`<x …>内文</x>` 的中间部分）。 */
    internal fun xmlBody(block: String): String {
        val openEnd = block.indexOf('>')
        val closeStart = block.lastIndexOf("</")
        if (openEnd < 0 || closeStart <= openEnd) return ""
        return block.substring(openEnd + 1, closeStart).trim()
    }

    /**
     * 头尾保留截断（上游 `condenseHeadTail`）。
     *
     * ## 三个边界照抄上游
     *
     * - 长度不足「头 + 尾 + 3」时**原样返回**：留 3 字余量，避免
     *   「截完只剩 `...`」这种比原文还长的退化；
     * - 头尾都为 0 时返回 `"..."`；
     * - 只有头或只有尾时，省略号放在缺的那一侧。
     */
    internal fun headTail(text: String, headChars: Int, tailChars: Int): String {
        val normalized = normalizeWhitespace(text)
        val head = headChars.coerceAtLeast(0)
        val tail = tailChars.coerceAtLeast(0)
        if (normalized.length <= head + tail + 3) return normalized
        if (head == 0 && tail == 0) return "..."
        if (head == 0) return "..." + normalized.takeLast(tail)
        if (tail == 0) return normalized.take(head) + "..."
        return normalized.take(head) + "..." + normalized.takeLast(tail)
    }
}
