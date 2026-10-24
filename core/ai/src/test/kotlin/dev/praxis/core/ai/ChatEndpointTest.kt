package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端点拼接的回归护栏。
 *
 * 每一条失败用例都对应一次真实故障（用户填了看似合理的地址却得到 404，
 * 而错误信息是服务端的英文 JSON，看不出是地址问题）。
 */
class ChatEndpointTest {

    // ── 四种基本形态 ────────────────────────────────────────────────

    @Test
    fun `裸域名补 v1 与端点`() {
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            ChatEndpoint.resolve("https://api.x.com"),
        )
    }

    @Test
    fun `以 v1 结尾只补端点`() {
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            ChatEndpoint.resolve("https://api.x.com/v1"),
        )
    }

    @Test
    fun `完整端点原样使用`() {
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            ChatEndpoint.resolve("https://api.x.com/v1/chat/completions"),
        )
    }

    @Test
    fun `尾斜杠被去掉`() {
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            ChatEndpoint.resolve("https://api.x.com/v1/"),
        )
    }

    // ── 真实故障：完整端点被再拼一遍 ────────────────────────────────
    //
    // 代理日志实证：`POST /v1/chat/completions/v1/chat/completions → 404`。

    @Test
    fun `完整端点带尾斜杠也不重复拼接`() {
        val result = ChatEndpoint.resolve("http://8.163.55.49:8788/v1/chat/completions/")
        assertEquals("http://8.163.55.49:8788/v1/chat/completions", result)
    }

    @Test
    fun `拼错的子路径不被静默修好`() {
        // `.../chat/completions/foo` 不以 CHAT_COMPLETIONS_PATH 结尾，
        // 也不以版本段结尾 → 会补 /v1。这是有意为之：让它 404，
        // 用户看到的是服务端明确拒绝，比静默改写成另一个地址更好排查。
        val result = ChatEndpoint.resolve("https://api.x.com/v1/chat/completions/foo")
        assertEquals("https://api.x.com/v1/chat/completions/foo/v1/chat/completions", result)
    }

    // ── 真实故障：服务商预设地址被插了一层 /v1 ──────────────────────

    @Test
    fun `智谱 v4 地址不被插 v1`() {
        // 旧实现只认「正好以 /v1 结尾」，会把这条拼成 `.../v4/v1/chat/completions` → 404。
        // 这是「选了预设反而不能用」的最坏情况。
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            ChatEndpoint.resolve("https://open.bigmodel.cn/api/paas/v4"),
        )
    }

    @Test
    fun `阿里云百炼 compatible-mode 地址不被插 v1`() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            ChatEndpoint.resolve("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
    }

    @Test
    fun `硅基流动与月之暗面等标准 v1 地址`() {
        assertEquals(
            "https://api.siliconflow.cn/v1/chat/completions",
            ChatEndpoint.resolve("https://api.siliconflow.cn/v1"),
        )
        assertEquals(
            "https://api.moonshot.cn/v1/chat/completions",
            ChatEndpoint.resolve("https://api.moonshot.cn/v1"),
        )
    }

    @Test
    fun `本地服务的端口地址`() {
        assertEquals(
            "http://127.0.0.1:11434/v1/chat/completions",
            ChatEndpoint.resolve("http://127.0.0.1:11434/v1"),
        )
        // 不带 /v1 的本地裸地址也要能用。
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            ChatEndpoint.resolve("http://127.0.0.1:8080"),
        )
    }

    // ── 空串与空白 ──────────────────────────────────────────────────

    @Test
    fun `空串与纯空白返回空串`() {
        // 返回空串而不是 "/v1/chat/completions"：
        // 后者会让调用方误以为是相对路径问题，而真正的原因是没配置。
        assertEquals("", ChatEndpoint.resolve(""))
        assertEquals("", ChatEndpoint.resolve("   "))
        assertEquals("", ChatEndpoint.resolve("///"))
    }

    @Test
    fun `首尾空白被裁掉`() {
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            ChatEndpoint.resolve("  https://api.x.com/v1  "),
        )
    }

    // ── 版本段判定 ──────────────────────────────────────────────────

    @Test
    fun `版本段判定覆盖各形态`() {
        assertTrue(ChatEndpoint.endsWithVersionSegment("https://a.com/v1"))
        assertTrue(ChatEndpoint.endsWithVersionSegment("https://a.com/v2"))
        assertTrue(ChatEndpoint.endsWithVersionSegment("https://a.com/v1beta"))
        assertTrue(ChatEndpoint.endsWithVersionSegment("https://a.com/api/v3"))
        assertTrue(ChatEndpoint.endsWithVersionSegment("https://a.com/api/paas/v4"))
        assertTrue(ChatEndpoint.endsWithVersionSegment("https://a.com/compatible-mode/v1"))
    }

    @Test
    fun `没有版本号的路径不算版本段`() {
        assertFalse(ChatEndpoint.endsWithVersionSegment("https://a.com"))
        assertFalse(ChatEndpoint.endsWithVersionSegment("https://a.com/api"))
        assertFalse(ChatEndpoint.endsWithVersionSegment("https://a.com/api/paas"))
        // `version` 以 v 开头但没有紧跟数字 → 不是版本段。
        assertFalse(ChatEndpoint.endsWithVersionSegment("https://a.com/version"))
    }
}