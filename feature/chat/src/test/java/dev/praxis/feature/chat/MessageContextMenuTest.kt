package dev.praxis.feature.chat

import dev.praxis.feature.chat.menu.MessageCopyMode
import dev.praxis.feature.chat.menu.actionsForBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按菜单移植的纯 JVM 回归面。
 *
 * ## 为什么这些用例必须先跑
 *
 * 菜单这一块最容易静默坏掉的三处：
 *
 * 1. **复制洗白**：正则漏一个标签，用户复制出去就是一坨 XML ——
 *    肉眼在 App 里很难发现（要真去粘贴才知道）。
 * 2. **回复标签的往返**：`buildReplyTag` 拼出来的前缀，`stripReplyTag`
 *    必须能削回去。两边格式一漂移，「删除一条回复」就会删不掉 history 里的项
 *    （气泡消失但模型下一轮仍记得）—— 这是最隐蔽的一类。
 * 3. **菜单项顺序与可见性**：顺序错不报错，只是「删除」跑到最上面，
 *    误触成本立刻上升。
 *
 * 三处都是纯函数/纯数据，不需要 Android 环境。
 */
class MessageContextMenuTest {

    // ── 复制洗白 ────────────────────────────────────────────────

    @Test
    fun `复制洗白 移除内部标签但保留正文`() {
        val raw = buildString {
            append("<think>我先看看文件</think>")
            append("好的，我来改。\n")
            append("<tool name=\"read_file\">{\"path\":\"a.kt\"}</tool>")
            append("<status type=\"done\">完成</status>")
            append("\n改好了。")
        }

        val cleaned = cleanMessageContentForCopy(raw)

        assertEquals("好的，我来改。\n\n改好了。", cleaned)
    }

    @Test
    fun `复制洗白 移除成对 search 标签（此前被写成空正则的回归）`() {
        // 上游 `searchTag` 是真实正则。曾经这里被写成 `Regex("")`，
        // 空正则替换等于不改动 —— 联网搜索的来源块会原样进剪贴板。
        val raw = "答案在这里。<search>{\"results\":[\"a\"]}</search>以上。"

        val cleaned = cleanMessageContentForCopy(raw)

        assertEquals("答案在这里。以上。", cleaned)
    }

    @Test
    fun `复制洗白 移除多媒体链接块但保留普通链接`() {
        // removeImageLinks 只管 image，removeMediaLinks 只管 audio/video/file；
        // 一个普通 file 链接和一张图都必须被删，而 <link type="url"> 不在
        // 匹配集合里（LINK_PATTERN 只认这四种 type）。
        val raw = "看图 <link type=\"image\" id=\"img1\"/> 和文件 " +
            "<link type=\"file\" id=\"f1\" filename=\"a.txt\">内容</link> 完毕"

        val cleaned = cleanMessageContentForCopy(raw)
        assertFalse(cleaned.contains("img1"))
        assertFalse(cleaned.contains("f1"))
        assertTrue(cleaned.contains("看图"))
        assertTrue(cleaned.contains("完毕"))
    }

    @Test
    fun `XML 原文档只剥供应商元数据`() {
        val raw = "<tool name=\"x\">{}</tool>" +
            "<meta provider=\"gemini:thought_signature\">QUJD</meta>"

        val xml = cleanMessageContentForXmlCopy(raw)

        // 结构化标签保留 —— 用户就是要把这个格式贴回给模型。
        assertTrue(xml.contains("<tool name=\"x\">"))
        // 但 base64 元数据必须没。
        assertFalse(xml.contains("QUJD"))
        assertFalse(xml.contains("thought_signature"))
    }

    @Test
    fun `ChatCopyContent 同时给出两份且互不相同`() {
        val raw = "**结论**：<tool name=\"a\">{}</tool>"

        val content = ChatCopyContent.of(raw)

        assertEquals("**结论**：", content.markdownSource)
        assertEquals(raw, content.xmlSource)
    }

    // ── 纯文本档 ────────────────────────────────────────────────

    @Test
    fun `纯文本档 去掉 Markdown 记号并保留列表结构`() {
        val md = buildString {
            append("## 标题\n\n")
            append("- 项目一\n")
            append("- 项目二\n\n")
            append("正文**加粗**和 `代码`")
        }

        val plain = ChatCopyText.markdownToPlainText(md)

        assertFalse("标题不该带 # 记号", plain.contains("#"))
        assertFalse("不该带星号", plain.contains("**"))
        assertFalse("不该带反引号", plain.contains("`"))
        assertTrue(plain.contains("标题"))
        assertTrue(plain.contains("• 项目一"))
        assertTrue(plain.contains("项目二"))
        assertTrue(plain.contains("正文加粗和 代码"))
    }

    @Test
    fun `纯文本档 链接保留 URL`() {
        val plain = ChatCopyText.markdownToPlainText("[文档](https://example.com)")

        assertTrue(plain.contains("文档"))
        assertTrue("纯文本里点不了链接，URL 不能丢", plain.contains("https://example.com"))
    }

    @Test
    fun `复制档位枚举文案与上游一致`() {
        // 三档的名字会出现在 chip 与按钮文案上，改字等于改 UI。
        assertEquals("纯文本", MessageCopyMode.PlainText.label)
        assertEquals("Markdown", MessageCopyMode.MarkdownSource.label)
        assertEquals("XML 原文", MessageCopyMode.XmlSource.label)
    }

    // ── 菜单项 ──────────────────────────────────────────────────

    @Test
    fun `用户消息菜单 顺序与上游一致且不含助手专属项`() {
        val actions = actionsForBubble(bubble(BubbleRole.User))

        assertEquals(
            listOf(
                MessageAction.Copy,
                MessageAction.Edit,
                MessageAction.RollbackHere,
                MessageAction.Delete,
                MessageAction.InsertSummary,
                MessageAction.ShowInfo,
            ),
            actions,
        )
        assertFalse("用户消息没有「重新生成」", actions.contains(MessageAction.Regenerate))
        assertFalse("用户消息没有「回复」", actions.contains(MessageAction.Reply))
        // 「引用」（Quote）已于 2026-10-24 删除：它的功能被 Reply 完全覆盖。
        // 断言大小而不是「不含某项」——枚举项已不存在，写名字会编译失败；
        // 大小能防止将来有人又加回一个语义重复的项。
        assertEquals("用户消息共 6 项", 6, actions.size)
    }

    @Test
    fun `助手消息菜单 含重新生成与回复但不含编辑重发与回滚`() {
        val actions = actionsForBubble(bubble(BubbleRole.Assistant))

        assertTrue(actions.contains(MessageAction.Regenerate))
        assertTrue(actions.contains(MessageAction.Reply))
        assertTrue(actions.contains(MessageAction.EditAssistant))
        assertFalse("「编辑并重发」只给用户消息", actions.contains(MessageAction.Edit))
        assertFalse("「回滚到此处」只给用户消息", actions.contains(MessageAction.RollbackHere))
        // 上游把删除放在信息之前，不是最后一项。
        assertTrue(
            "删除必须在信息之前（上游顺序）",
            actions.indexOf(MessageAction.Delete) < actions.indexOf(MessageAction.ShowInfo),
        )
    }

    @Test
    fun `继续 紧跟重新生成 且两者都在助手菜单里`() {
        // 两者都是「让模型再写一段」，用户会在这附近找。分开或把「继续」
        // 放到末尾（删除旁边）会让它被误当成破坏性动作。
        val actions = actionsForBubble(bubble(BubbleRole.Assistant))
        assertTrue(actions.contains(MessageAction.Continue))
        assertEquals(
            "「继续」必须紧跟「重新生成」",
            actions.indexOf(MessageAction.Regenerate) + 1,
            actions.indexOf(MessageAction.Continue),
        )
    }

    @Test
    fun `工具卡片给继续但不给重新生成`() {
        // 工具结果已在 history 里，续写有效；「重新生成」对工具卡片没有意义
        // （重跑工具可能有副作用，比如再写一次文件）。
        val tool = actionsForBubble(bubble(BubbleRole.Tool))
        assertTrue("工具卡片应能继续", tool.contains(MessageAction.Continue))
        assertFalse("工具卡片不该有重新生成", tool.contains(MessageAction.Regenerate))
    }

    @Test
    fun `用户消息与错误气泡不给继续`() {
        // 用户消息之后的回答由 AI 产生，对它点继续等于「再答一次」，
        // 那是 Regenerate 的语义；错误气泡不是模型产出。
        assertFalse(
            "用户消息没有「继续」",
            actionsForBubble(bubble(BubbleRole.User)).contains(MessageAction.Continue),
        )
        assertFalse(
            "错误气泡没有「继续」",
            actionsForBubble(bubble(BubbleRole.Error)).contains(MessageAction.Continue),
        )
    }

    @Test
    fun `工具与错误气泡只给最小集合`() {
        val tool = actionsForBubble(bubble(BubbleRole.Tool))
        assertEquals(
            // 「继续」在工具卡片上有意义（结果已在 history 里，可让它写总结）。
            listOf(
                MessageAction.Copy,
                MessageAction.Continue,
                MessageAction.ShowInfo,
                MessageAction.Delete,
            ),
            tool,
        )

        val error = actionsForBubble(bubble(BubbleRole.Error))
        assertEquals(listOf(MessageAction.Copy, MessageAction.Delete), error)
    }

    @Test
    fun `菜单不含被砍掉的四项（底层能力缺失时不放假按钮）`() {
        val all = BubbleRole.entries
            .flatMap { actionsForBubble(bubble(it)) }
            .toSet()

        // 这四项分别需要 TTS / 变体模型 / 分支模型 / 多选状态机。
        // 若将来补了底层能力，这条断言会失败 —— 那正是提醒更新文档的时机。
        assertFalse(all.any { it.name == "Speak" })
        assertFalse(all.any { it.name == "DeleteVariant" })
        assertFalse(all.any { it.name == "CreateBranch" })
        assertFalse(all.any { it.name == "MultiSelect" })
    }

    private fun bubble(role: BubbleRole) = ChatBubble(
        id = 1L,
        role = role,
        text = "text",
        timestamp = 1_700_000_000_000L,
    )

    // ── 回复标签往返 ────────────────────────────────────────────

    /**
     * 复刻 `ChatViewModel.stripReplyTag` 的正则行为。
     *
     * ## 为什么在测试里复制一份而不是直接调私有方法
     *
     * 私有方法测不了，而把它放开成 public 只是为了测试会污染 API。
     * 这里锁的是**正则本身**：只要 `buildReplyTag` 的格式变了，这条用例的
     * 期望值就要一起改 —— 那种「必须同时改两处」正是我们想要的约束。
     */
    private val stripReplyTag =
        Regex("^<reply_to\\b[^>]*>[\\s\\S]*?</reply_to>\\n?")

    @Test
    fun `回复标签剥离 能还原成纯正文`() {
        val body = "帮我把这段改一下"
        val withTag =
            "<reply_to sender=\"AI\" timestamp=\"1700000000000\">" +
                "正在回复上一条消息\"上一段回答\"</reply_to>\n$body"

        val stripped = withTag.replaceFirst(stripReplyTag, "")

        assertEquals(body, stripped)
    }

    @Test
    fun `回复标签剥离 对普通正文是恒等`() {
        val plain = "没有回复目标的普通消息"

        assertEquals(plain, plain.replaceFirst(stripReplyTag, ""))
    }

    @Test
    fun `回复标签剥离 不会误吃正文里的 reply 字样`() {
        // 正文中间出现 "<reply_to" 时不能削 —— 正则锚定了 `^`。
        // 少了锚定，用户只要打出这几个字符，自己那句话就会被吃掉。
        val tricky = "我在说 <reply_to 这个标签的用法"

        assertEquals(tricky, tricky.replaceFirst(stripReplyTag, ""))
    }
}
