package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 远端 images 端点的协议（文档 §5.4）。 */
class RemoteImageProtocolTest {

    // ── 地址推导 ──

    @Test
    fun `不带版本前缀时补 v1`() {
        assertEquals(
            "https://api.openai.com/v1/images/generations",
            RemoteImageEndpoint.generateUrl("https://api.openai.com"),
        )
    }

    @Test
    fun `已带 v1 时不重复补`() {
        // 补成 /v1/v1/... 是一个必然 404 的地址，而报错里只有 404。
        assertEquals(
            "https://api.openai.com/v1/images/generations",
            RemoteImageEndpoint.generateUrl("https://api.openai.com/v1"),
        )
    }

    @Test
    fun `尾部斜杠与空白被吸收`() {
        assertEquals(
            "https://x.example/v1/images/generations",
            RemoteImageEndpoint.generateUrl("  https://x.example/  "),
        )
    }

    @Test
    fun `空地址返回空串而不是拼出一个怪路径`() {
        assertEquals("", RemoteImageEndpoint.generateUrl(""))
        assertEquals("", RemoteImageEndpoint.generateUrl("   "))
    }

    @Test
    fun `自定义版本前缀（如 v1beta）不被改写`() {
        assertEquals(
            "https://x.example/v1beta/images/generations",
            RemoteImageEndpoint.generateUrl("https://x.example/v1beta"),
        )
    }

    // ── 请求体 ──

    @Test
    fun `请求体字段名与取值`() {
        val json = RemoteImageRequest.toJson(
            model = "gpt-image-1",
            prompt = "a cat",
            size = "1024x1024",
            n = 2,
        )
        assertTrue(json.contains("\"model\":\"gpt-image-1\""))
        assertTrue(json.contains("\"prompt\":\"a cat\""))
        assertTrue(json.contains("\"size\":\"1024x1024\""))
        assertTrue(json.contains("\"n\":2"))
        assertTrue("默认要 b64_json", json.contains("\"response_format\":\"b64_json\""))
    }

    // ── 响应 ──

    @Test
    fun `解析 base64 结果`() {
        val body = """{"created":1,"data":[{"b64_json":"QUJD"}]}"""
        val parsed = RemoteImageResponse.parse(body)
        assertEquals(1, parsed.size)
        assertEquals("QUJD", (parsed[0] as RemoteImagePayload.Base64).data)
    }

    @Test
    fun `只有 url 时也能被识别出来`() {
        val body = """{"data":[{"url":"https://x.example/a.png"}]}"""
        assertEquals(
            "https://x.example/a.png",
            (RemoteImageResponse.parse(body)[0] as RemoteImagePayload.Url).url,
        )
    }

    @Test
    fun `多张图按顺序返回`() {
        val body = """{"data":[{"b64_json":"QQ=="},{"b64_json":"Qg=="}]}"""
        assertEquals(
            listOf("QQ==", "Qg=="),
            RemoteImageResponse.parse(body).map { (it as RemoteImagePayload.Base64).data },
        )
    }

    @Test
    fun `HTTP 成功但 data 为空时返回空列表`() {
        // 与「上游报错」是两件事：前者要换模型/改提示词，后者按分类处置。
        assertTrue(RemoteImageResponse.parse("""{"created":1,"data":[]}""").isEmpty())
        assertTrue(RemoteImageResponse.parse("{\"data\":").isEmpty())
        assertTrue(RemoteImageResponse.parse("").isEmpty())
    }

    @Test
    fun `错误正文里的 message 能被读出来`() {
        val body = """{"error":{"message":"quota exceeded","type":"insufficient_quota"}}"""
        assertEquals("quota exceeded", RemoteImageResponse.errorMessage(body))
    }

    @Test
    fun `没有 error 对象时返回 null`() {
        assertNull(RemoteImageResponse.errorMessage("""{"data":[]}"""))
        assertNull(RemoteImageResponse.errorMessage("not json"))
    }
}
