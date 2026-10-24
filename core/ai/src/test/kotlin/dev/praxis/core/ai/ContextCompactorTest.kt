package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文压缩与应用。
 *
 * 核心要钉住的是**幂等性**：同一次历史被压缩两次，摘要不能叠成两份。
 * 叠了就会「越压越长」，压缩本身反而撑爆上下文。
 */
class ContextCompactorTest {

    private fun msg(role: ChatRole, text: String) = ChatMessage(role = role, text = text)

    @Test
    fun `压缩请求包含 Operit 原始提示词的四节`() {
        val request = ContextCompactor.buildRequest(
            previousSummary = null,
            messages = listOf(msg(ChatRole.User, "这是中文内容，用来触发中文提示词")),
            model = "m",
            useEnglish = false,
        )
        val system = request.messages.first { it.role == ChatRole.System }.text
        listOf("核心任务状态", "互动情节与设定", "对话历程与概要", "关键信息与上下文")
            .forEach { assertTrue("缺少小节：$it", system.contains(it)) }
        assertTrue(system.contains("时序重点"))
    }

    @Test
    fun `英文提示词用方括号小节与更长的分隔线`() {
        val request = ContextCompactor.buildRequest(
            previousSummary = null,
            messages = listOf(msg(ChatRole.User, "hello")),
            model = "m",
            useEnglish = true,
        )
        val system = request.messages.first { it.role == ChatRole.System }.text
        listOf("Core Task Status", "Interaction & Scenario",
            "Conversation Progress & Overview", "Key Information & Context")
            .forEach { assertTrue("missing section: $it", system.contains(it)) }
        // 英文版分隔线是 39 个 =，中文是 28 个。两者不能混。
        assertTrue(system.contains("=".repeat(39)))
        assertFalse(system.contains("【"))
    }

    @Test
    fun `自动按中文内容选中文提示词`() {
        val chinese = listOf(msg(ChatRole.User, "请帮我把这段代码改成 Kotlin 并且补上单元测试"))
        assertFalse(ContextCompactor.detectUseEnglish(chinese))
        val english = listOf(msg(ChatRole.User, "please refactor this to Kotlin"))
        assertTrue(ContextCompactor.detectUseEnglish(english))
    }

    @Test
    fun `零星汉字不误判为中文语境`() {
        // 英文技术讨论里夹一个中文词：应仍判英文。
        val mixed = listOf(msg(ChatRole.User, "fix the bug in build.gradle 请"))
        assertTrue(ContextCompactor.detectUseEnglish(mixed))
    }

    @Test
    fun `首次压缩不追加旧摘要`() {
        val system = ContextCompactor.buildRequest(null, listOf(msg(ChatRole.User, "x")), "m")
            .messages.first().text
        assertFalse(system.contains("用于继承上下文"))
    }

    @Test
    fun `有上次摘要时拼进 system 而不是 user`() {
        val request = ContextCompactor.buildRequest(
            previousSummary = "旧摘要内容",
            messages = listOf(msg(ChatRole.User, "x")),
            model = "m",
            useEnglish = false,
        )
        // 上游把旧摘要拼在 system 尾部（`appendPreviousSummary`）。
        val system = request.messages.first { it.role == ChatRole.System }.text
        assertTrue(system.contains("旧摘要内容"))
        assertTrue(system.contains("用于继承上下文"))
        // user 消息里不应再出现旧摘要（旧实现放在这里，导致模型把它当本轮输入）。
        val user = request.messages.last().text
        assertFalse(user.contains("旧摘要内容"))
    }

    @Test
    fun `全局规则追加在提示词末尾`() {
        val system = ContextPromptsHelper.systemWithRules()
        assertTrue(system.contains("务必保留所有文件路径"))
        // 规则在提示词之后（最后一段）。
        assertTrue(system.lastIndexOf("务必保留所有文件路径") > system.indexOf("时序重点"))
    }

    @Test
    fun `关掉一个小节后提示词不再包含它`() {
        val prompt = SummaryPrompts.applySectionOverrides(
            overrides = listOf(SummarySectionOverride(id = "interaction", enabled = false)),
            useEnglish = false,
        )
        assertFalse(prompt.contains("【互动情节与设定】"))
        // 其余三节还在。
        assertTrue(prompt.contains("【核心任务状态】"))
        assertTrue(prompt.contains("【对话历程与概要】"))
        assertTrue(prompt.contains("【关键信息与上下文】"))
    }

    @Test
    fun `改小节标题与指令生效`() {
        val prompt = SummaryPrompts.applySectionOverrides(
            overrides = listOf(
                SummarySectionOverride(
                    id = "core_task",
                    title = "当前目标",
                    instruction = "只写一句话。",
                ),
            ),
            useEnglish = false,
        )
        assertTrue(prompt.contains("【当前目标】"))
        assertTrue(prompt.contains("只写一句话。"))
        assertFalse(prompt.contains("【核心任务状态】"))
    }

    @Test
    fun `默认小节列表与提示词小节一一对应`() {
        val sections = SummaryPrompts.defaultSections(useEnglish = false)
        assertEquals(SummaryPrompts.SECTION_IDS.size, sections.size)
        assertEquals(
            SummaryPrompts.SECTION_IDS,
            sections.map { it.id },
        )
        assertTrue(sections.all { it.instruction.isNotBlank() })
    }

    @Test
    fun `工具调用与结果都进摘要输入`() {
        val request = ContextCompactor.buildRequest(
            previousSummary = null,
            messages = listOf(
                ChatMessage(
                    role = ChatRole.Assistant,
                    text = "",
                    toolCalls = listOf(ToolCall("1", "read_file", "参数内容")),
                ),
                ChatMessage(
                    role = ChatRole.Tool,
                    toolResult = ToolResult(callId = "1", content = "文件内容"),
                ),
            ),
            model = "m",
        )
        val user = request.messages.last().text
        assertTrue(user.contains("read_file"))
        assertTrue(user.contains("文件内容"))
    }

    @Test
    fun `压缩条数保留最近若干条`() {
        val keep = ContextCompactor.KEEP_RECENT_MESSAGES
        assertEquals(0, ContextCompactor.compactCount(keep))
        assertEquals(0, ContextCompactor.compactCount(keep + 1))
        assertEquals(2, ContextCompactor.compactCount(keep + 2))
        assertEquals(6, ContextCompactor.compactCount(keep + 6))
    }

    @Test
    fun `应用摘要会丢掉被折叠的消息且插在 system 之后`() {
        val messages = listOf(
            msg(ChatRole.System, "系统"),
            msg(ChatRole.User, "旧问题"),
            msg(ChatRole.Assistant, "旧回答"),
            msg(ChatRole.User, "新问题"),
        )
        val result = CompactionApplier.apply(
            messages,
            CompactedContext(summary = "摘要正文", coveredCount = 2),
        )
        assertEquals(3, result.size)
        assertEquals(ChatRole.System, result[0].role)
        assertTrue(result[0].text.contains("系统"))
        assertTrue(result[1].text.contains("摘要正文"))
        assertEquals("新问题", result[2].text)
        assertFalse(result.any { it.text == "旧问题" })
        assertFalse(result.any { it.text == "旧回答" })
    }

    @Test
    fun `无摘要时原样返回`() {
        val messages = listOf(msg(ChatRole.User, "a"), msg(ChatRole.Assistant, "b"))
        assertEquals(messages, CompactionApplier.apply(messages, CompactedContext.None))
    }

    @Test
    fun `覆盖数超过实际长度时不越界`() {
        val messages = listOf(msg(ChatRole.User, "a"))
        val result = CompactionApplier.apply(
            messages,
            CompactedContext(summary = "s", coveredCount = 99),
        )
        assertEquals(1, result.size)
        assertEquals(ChatRole.System, result[0].role)
    }

    @Test
    fun `有 system 时覆盖数超长保留 system 与摘要`() {
        val messages = listOf(
            msg(ChatRole.System, "系统"),
            msg(ChatRole.User, "a"),
        )
        val result = CompactionApplier.apply(
            messages,
            CompactedContext(summary = "摘要", coveredCount = 99),
        )
        assertEquals(2, result.size)
        assertEquals("系统", result[0].text)
        assertTrue(result[1].text.contains("摘要"))
    }

    // ── 双阈值判定（上游 shouldGenerateSummary）──────────────────────

    @Test
    fun `token 达阈值就压缩`() {
        assertTrue(
            ContextCompactor.shouldCompact(
                currentTokens = 800, maxTokens = 1000, tokenUsageThreshold = 0.75f,
                userMessagesSinceLastSummary = 0,
            ),
        )
    }

    @Test
    fun `上限未知时靠条数阈值也能压缩`() {
        // 这是旧实现最致命的缺陷：`maxTokens > 0` 恒假 -> 永不压缩。
        assertTrue(
            ContextCompactor.shouldCompact(
                currentTokens = 999_999, maxTokens = 0, tokenUsageThreshold = 0.75f,
                userMessagesSinceLastSummary = ContextCompactor.DEFAULT_MESSAGE_COUNT_THRESHOLD,
            ),
        )
    }

    @Test
    fun `两个阈值都没到就不压缩`() {
        assertFalse(
            ContextCompactor.shouldCompact(
                currentTokens = 100, maxTokens = 1000, tokenUsageThreshold = 0.75f,
                userMessagesSinceLastSummary = 3,
            ),
        )
    }

    @Test
    fun `严格模式只认 token`() {
        assertFalse(
            ContextCompactor.shouldCompact(
                currentTokens = 100, maxTokens = 1000, tokenUsageThreshold = 0.75f,
                userMessagesSinceLastSummary = 999, strictThreshold = true,
            ),
        )
    }

    @Test
    fun `条数阈值为 0 时关闭该条件`() {
        assertFalse(
            ContextCompactor.shouldCompact(
                currentTokens = 100, maxTokens = 1000, tokenUsageThreshold = 0.75f,
                userMessagesSinceLastSummary = 999, messageCountThreshold = 0,
            ),
        )
    }
}

/** 便于在测试里拼带全局规则的 system。 */
private object ContextPromptsHelper {
    fun systemWithRules(): String = SummaryPrompts.buildSummarySystemPrompt(
        previousSummary = null,
        useEnglish = false,
        globalRules = "务必保留所有文件路径",
    )
}

/**
token 估算（1:1 对齐 Operit `ChatUtils.estimateTokenCount`）。
 */
class TokenEstimatorTest {

    @Test
    fun `空串为 0`() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun `中文系数是每个汉字 1_5 token`() {
        // 这是本轮修的最大缺陷：旧实现按 cjk / 1.5 算（÷ 而非 ×）。
        // 200 个汉字 = 300 token（上游值）；旧实现会给 133，差 2.25 倍。
        assertEquals(300, TokenEstimator.estimate("汉".repeat(200)))
    }

    @Test
    fun `拉丁字符按 4 字符 1 token`() {
        // 400 个字母 = 100 token。
        assertEquals(100, TokenEstimator.estimate("a".repeat(400)))
    }

    @Test
    fun `中文比英文更贵`() {
        assertTrue(TokenEstimator.estimate("汉".repeat(8)) > TokenEstimator.estimate("a".repeat(8)))
    }

    @Test
    fun `每条消息有固定结构开销`() {
        // 空文本的消息也占 4 token（角色字段 + JSON 结构）。
        assertEquals(4, TokenEstimator.estimate(listOf(ChatMessage(ChatRole.User, ""))))
        assertEquals(8, TokenEstimator.estimate(listOf(
            ChatMessage(ChatRole.User, ""),
            ChatMessage(ChatRole.User, ""),
        )))
    }

    @Test
    fun `工具参数与结果计入估算`() {
        val plain = TokenEstimator.estimate(listOf(ChatMessage(ChatRole.Assistant, "")))
        val withTool = TokenEstimator.estimate(
            listOf(
                ChatMessage(
                    role = ChatRole.Assistant,
                    text = "",
                    toolCalls = listOf(ToolCall("1", "nmap", "target-10-0-0-1")),
                ),
            ),
        )
        assertTrue(withTool > plain)
    }

    @Test
    fun `格式化 k 与 M`() {
        assertEquals("999", TokenEstimator.format(999))
        assertEquals("1.0k", TokenEstimator.format(1000))
        assertEquals("12.3k", TokenEstimator.format(12_345))
        assertEquals("1.5M", TokenEstimator.format(1_500_000))
    }
}

/**
增量 token 计算（1:1 对齐 Operit `TokenCacheManager`）。

三个必须验证的性质：**结果与全量算一致**、**追加时只算增量**、
**截断后必须 reset**。
 */
class TokenCacheTest {

    private fun msgs(vararg texts: String) =
        texts.map { ChatMessage(ChatRole.User, it) }

    @Test
    fun `首次计算与全量估算一致`() {
        val cache = TokenCache()
        val list = msgs("aaa", "bbb")
        assertEquals(TokenEstimator.estimate(list), cache.count(list))
    }

    @Test
    fun `追加一条时只算增量`() {
        val cache = TokenCache()
        val base = msgs("aaa", "bbb")
        cache.count(base)
        val longer = base + ChatMessage(ChatRole.User, "ccc")
        assertEquals(TokenEstimator.estimate(longer), cache.count(longer))
        // 前缀部分被复用（等于旧总数），增量就是新那条。
        assertEquals(TokenEstimator.estimate(base), cache.cachedPrefixTokens)
        assertEquals(
            TokenEstimator.estimate(listOf(ChatMessage(ChatRole.User, "ccc"))),
            cache.incrementalTokens,
        )
    }

    @Test
    fun `中间一条被改时重算前缀但仍正确`() {
        val cache = TokenCache()
        cache.count(msgs("aaa", "bbb", "ccc"))
        // 改中间那条：公共前缀只有 1 条（"aaa"）。
        val edited = msgs("aaa", "ZZZZZZ", "ccc")
        assertEquals(TokenEstimator.estimate(edited), cache.count(edited))
        // 增量 = 后两条（被改的那条 + 它后面的），等于全量减前缀。
        val prefixOnly = msgs("aaa")
        assertEquals(
            TokenEstimator.estimate(edited) - TokenEstimator.estimate(prefixOnly),
            cache.incrementalTokens,
        )
    }

    @Test
    fun `reset 后全量重算`() {
        val cache = TokenCache()
        cache.count(msgs("aaa"))
        cache.reset()
        assertEquals(0, cache.cachedPrefixTokens)
        val list = msgs("aaa", "bbb")
        assertEquals(TokenEstimator.estimate(list), cache.count(list))
    }

    @Test
    fun `截断后必须 reset 否则会算出旧值`() {
        val cache = TokenCache()
        val full = msgs("aaa", "bbb", "ccc")
        val fullTotal = cache.count(full)
        // 截断成前缀（模拟 truncateFrom）。
        val truncated = full.take(2)
        // 不 reset：会返回旧总数（错误）。
        assertEquals(fullTotal, cache.count(truncated))
        // reset 之后：得到截断后的真实值。
        cache.reset()
        assertEquals(TokenEstimator.estimate(truncated), cache.count(truncated))
    }
}
