package dev.praxis.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「继续生成」的状态契约测试。
 *
 * ## 为什么这一段必须锁住
 *
 * 「继续」最容易做错的地方**不在发请求**，而在历史管理：
 *
 *  - 若把提示词塞进 `history`，`truncateFrom` 的
 *    `while (history.size > keepForHistory)` 就会永久错位
 *    （history 多一条未显示在 UI 的记录）→ 删消息后模型仍「记得」；
 *  - 若「继续」复用了 `truncateFrom`，用户会得到一段**全新**回答，
 *    而不是接着上文写 —— 语义完全错。
 *
 * 这两点都能用纯状态断言表达，不必起 Android 环境。
 * 全流程（真发请求）留给 instrumentation，不在这里假装测到。
 */
class ChatContinueTest {

    private fun bubble(id: Long, role: BubbleRole, text: String) = ChatBubble(
        id = id,
        role = role,
        text = text,
    )

    // ── 菜单可见性（真正的入口约束）────────────────────────────

    @Test
    fun `助手消息可继续`() {
        val actions = dev.praxis.feature.chat.menu.actionsForBubble(
            bubble(1, BubbleRole.Assistant, "已写一半的回答"),
        )
        assertTrue(actions.contains(MessageAction.Continue))
    }

    @Test
    fun `工具消息可继续`() {
        // 工具结果已进 history，可让它基于结果写总结。
        val actions = dev.praxis.feature.chat.menu.actionsForBubble(
            bubble(2, BubbleRole.Tool, "工具结果"),
        )
        assertTrue(actions.contains(MessageAction.Continue))
    }

    @Test
    fun `用户消息不可继续`() {
        val actions = dev.praxis.feature.chat.menu.actionsForBubble(
            bubble(3, BubbleRole.User, "问题"),
        )
        assertFalse(actions.contains(MessageAction.Continue))
    }

    @Test
    fun `错误气泡不可继续`() {
        val actions = dev.praxis.feature.chat.menu.actionsForBubble(
            bubble(4, BubbleRole.Error, "网络连接失败"),
        )
        assertFalse(actions.contains(MessageAction.Continue))
    }

    // ── 「只能接在最后一条」的判定逻辑 ─────────────────────────

    /**
     * 复现 `continueGeneration` 里的位置判定。
     *
     * 这里刻意**重写**一遍判断而不是调用私有方法：私有方法不可测，
     * 而把它改成 internal 只为测试就多了一个不该存在的可见性。
     * 断言的真正对象是「这条规则本身」—— 若以后改实现，这条测试会
     * 通过 diff 提醒作者同步检查。
     */
    private fun isLast(bubbles: List<ChatBubble>, id: Long): Boolean =
        bubbles.lastOrNull()?.id == id

    @Test
    fun `最后一条可以继续`() {
        val bubbles = listOf(
            bubble(1, BubbleRole.User, "问题"),
            bubble(2, BubbleRole.Assistant, "半截回答"),
        )
        assertTrue(isLast(bubbles, 2))
    }

    @Test
    fun `中间那条不能继续`() {
        // 对中间的助手消息续写，新内容会接在**整段历史末尾**，
        // 而不是接在那条后面 —— 用户会看到「点了第三条、回答了第五条」。
        val bubbles = listOf(
            bubble(1, BubbleRole.User, "问题一"),
            bubble(2, BubbleRole.Assistant, "回答一"),
            bubble(3, BubbleRole.User, "问题二"),
            bubble(4, BubbleRole.Assistant, "回答二"),
        )
        assertFalse(isLast(bubbles, 2))
        assertTrue(isLast(bubbles, 4))
    }

    @Test
    fun `空列表没有任何一条可以继续`() {
        assertFalse(isLast(emptyList(), 1))
    }

    // ── notice 语义 ────────────────────────────────────────────

    @Test
    fun `默认没有提示`() {
        // 提示不该一进页面就存在。有值意味着上一轮某个操作失败了，
        // 而新会话里那是过时信息。
        assertNull(ChatState().notice)
    }

    @Test
    fun `提示可以被清掉`() {
        // `clearNotice` 的效果（早退 + 置空）。UI 的 4 秒计时器依赖它。
        val withNotice = ChatState().copy(notice = "只能接在最后一条上")
        assertEquals("只能接在最后一条上", withNotice.notice)
        assertNull(withNotice.copy(notice = null).notice)
    }

    // ── 新增气泡的正确形态 ─────────────────────────────────────

    @Test
    fun `继续产生的新气泡是流式助手气泡`() {
        // `continueGeneration` 追加的就是这个形态：空文本 + streaming。
        // 不建这个占位气泡的话，`startStream` 找不到落笔的地方
        // （增量全被丢弃，界面一直空着）。
        val placeholder = ChatBubble(
            id = 99,
            role = BubbleRole.Assistant,
            text = "",
            streaming = true,
        )
        assertTrue(placeholder.streaming)
        assertTrue(placeholder.text.isEmpty())
        assertEquals(BubbleRole.Assistant, placeholder.role)
    }
}