package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话粘性键（文档 7.3）。
 *
 * 这一层只有两条规则，但错一条就会让「同一会话固定走同一个账号」悄悄失效：
 * 表现是同一段对话时好时坏，看不出是键的问题。
 */
class ConversationKeyTest {

    @Test
    fun 显式头优先() {
        val key = ConversationKey.of(
            headers = mapOf(ConversationKey.THREAD_ID_HEADER to "thread-1"),
            contentSeed = "随便什么内容",
        )

        assertEquals("h:thread-1", key)
    }

    @Test
    fun 头名大小写不敏感() {
        val key = ConversationKey.of(
            headers = mapOf("ah-thread-id" to "thread-2"),
            contentSeed = null,
        )

        assertEquals("h:thread-2", key)
    }

    @Test
    fun 空头值当作没有() {
        assertNull(ConversationKey.of(mapOf(ConversationKey.THREAD_ID_HEADER to "   "), null))
    }

    @Test
    fun 没有显式头时用内容哈希() {
        val a = ConversationKey.of(emptyMap(), "同一段内容")
        val b = ConversationKey.of(emptyMap(), "同一段内容")

        assertEquals("相同内容必须得到同一个键", a, b)
        assertNotEquals(null, a)
    }

    @Test
    fun 不同内容得到不同键() {
        assertNotEquals(
            ConversationKey.of(emptyMap(), "内容 A"),
            ConversationKey.of(emptyMap(), "内容 B"),
        )
    }

    @Test
    fun 两者都没有时返回_null() {
        // 不编兜底键：常量会把所有无头请求钉在同一个账号上，
        // 随机值等于没有粘性却还在维护表。
        assertNull(ConversationKey.of(emptyMap(), null))
        assertNull(ConversationKey.of(emptyMap(), "   "))
    }

    @Test
    fun 显式头与内容哈希的键不会互相撞() {
        val header = ConversationKey.of(mapOf(ConversationKey.THREAD_ID_HEADER to "x"), "x")
        val content = ConversationKey.of(emptyMap(), "x")

        assertNotEquals(header, content)
    }
}
