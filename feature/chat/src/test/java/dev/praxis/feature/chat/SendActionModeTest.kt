package dev.praxis.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 发送按钮四态判定。
 *
 * ## 为什么这条链必须钉住
 *
 * 用户反馈的原话是「点发送它没有发出去」和「生成中点发送结果被中断」。
 * 两个抱怨来自同一个根因：**按钮显示的动作与它实际做的事不一致**。
 *
 * 判定逻辑抽成纯函数后，这四组输入就是全部可能的状态，
 * 任何一组映射错了都意味着「用户按之前以为的和按之后发生的不同」。
 */
class SendActionModeTest {

    /** 空闲 + 有内容 → 发送。 */
    @Test
    fun `空闲有内容为发送`() {
        assertEquals(
            SendActionMode.Send,
            sendActionMode(busy = false, canSend = true, hasCancel = true, hasEnqueue = true),
        )
    }

    /**
     * 生成中 + 有内容 → 入队（**不是**停止）。
     *
     * 这是本次修复的核心：想在生成期间发下一条的用户不应该被「停止」按钮
     * 误中断；而想中断的用户不会先在输入框里打字。
     */
    @Test
    fun `生成中有内容为入队`() {
        assertEquals(
            SendActionMode.Enqueue,
            sendActionMode(busy = true, canSend = true, hasCancel = true, hasEnqueue = true),
        )
    }

    /** 生成中 + 无内容 → 停止。 */
    @Test
    fun `生成中无内容为停止`() {
        assertEquals(
            SendActionMode.Cancel,
            sendActionMode(busy = true, canSend = false, hasCancel = true, hasEnqueue = true),
        )
    }

    /** 空闲 + 无内容 → 禁用。 */
    @Test
    fun `空闲无内容为禁用`() {
        assertEquals(
            SendActionMode.Disabled,
            sendActionMode(busy = false, canSend = false, hasCancel = true, hasEnqueue = true),
        )
    }

    /**
     * 没接入队回调时，生成中退化回「停止」而不是无效按钮。
     *
     * 预览/测试环境可以只传 onCancel。此时不能显示一个按下去毫无反应的加号。
     */
    @Test
    fun `无入队回调时生成中仍为停止`() {
        assertEquals(
            SendActionMode.Cancel,
            sendActionMode(busy = true, canSend = true, hasCancel = true, hasEnqueue = false),
        )
    }

    /** 生成中且既不能取消也不能入队 → 禁用（不假装能按）。 */
    @Test
    fun `生成中且无可用动作为禁用`() {
        assertEquals(
            SendActionMode.Disabled,
            sendActionMode(busy = true, canSend = true, hasCancel = false, hasEnqueue = false),
        )
    }
}
