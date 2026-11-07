package dev.praxis.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成中的发送按钮语义（2026-11-07 用户反馈）。
 *
 * ## 为什么单测 sendActionMode 还不够
 *
 * 那个纯函数一直是正确的：生成中 + 有内容 + 有入队回调 → 加号。
 * 但生产里 canSend 带着 !isStreaming 这个条件，而 busy 必然意味着
 * isStreaming —— 于是「生成中且有内容」这个组合永远不成立，
 * 加号一次都没出现过。用户的反馈就是「排队发送的功能怎么没看到」。
 *
 * 所以这条链要在**状态层**钉住：生成中只要输入框有内容，canSend 就必须为真，
 * 按钮才可能变成加号。
 */
class ChatStateCanSendTest {

    @Test
    fun 生成中有内容时仍可处理() {
        val state = ChatState(input = "下一条", isStreaming = true)

        assertTrue("生成中也要能入队，否则加号永远出不来", state.canSend)
        assertEquals(
            "生成中 + 有内容 → 加号入队",
            SendActionMode.Enqueue,
            sendActionMode(
                busy = state.isBusy,
                canSend = state.canSend,
                hasCancel = true,
                hasEnqueue = true,
            ),
        )
    }

    @Test
    fun 生成中空输入是停止() {
        val state = ChatState(isStreaming = true)

        assertFalse(state.canSend)
        assertEquals(
            SendActionMode.Cancel,
            sendActionMode(
                busy = state.isBusy,
                canSend = state.canSend,
                hasCancel = true,
                hasEnqueue = true,
            ),
        )
    }

    @Test
    fun 空闲有内容是发送() {
        val state = ChatState(input = "你好")

        assertTrue(state.canSend)
        assertEquals(
            SendActionMode.Send,
            sendActionMode(
                busy = state.isBusy,
                canSend = state.canSend,
                hasCancel = true,
                hasEnqueue = true,
            ),
        )
    }
}