package dev.praxis.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anthropic Messages 的出站编码（文档 §5.1）。 */
class AnthropicRequestBuilderTest {

    private fun body(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor> = emptyList(),
        maxTokens: Int? = null,
        stream: Boolean = true,
    ) = Json.parseToJsonElement(
        AnthropicRequestBuilder.build(
            ChatRequest(
                model = "claude-x",
                messages = messages,
                tools = tools,
                maxTokens = maxTokens,
                stream = stream,
            ),
        ),
    ).jsonObject

    @Test
    fun `system 是顶层字段而不是一条消息`() {
        // Anthropic 只认一个顶层 system；写成 messages 里的 role=system 会被拒。
        val root = body(listOf(ChatMessage(ChatRole.System, "你是助手"), ChatMessage(ChatRole.User, "你好")))
        assertEquals("你是助手", root["system"]?.jsonPrimitive?.content)
        val messages = root["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `正文永远是数组形态`() {
        val root = body(listOf(ChatMessage(ChatRole.User, "你好")))
        val content = root["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("你好", content[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `工具结果搬进 user 消息的 tool_result 块`() {
        // 这是与 OpenAI 兼容最实质的差异：那边是独立的 role=tool 消息。
        val root = body(
            listOf(
                ChatMessage(
                    role = ChatRole.Tool,
                    toolResult = ToolResult(callId = "call_1", content = "42", isError = false),
                ),
            ),
        )
        val message = root["messages"]!!.jsonArray[0].jsonObject
        assertEquals("user", message["role"]?.jsonPrimitive?.content)
        val block = message["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", block["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", block["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals("42", block["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `失败的工具结果带 is_error`() {
        val root = body(
            listOf(
                ChatMessage(
                    role = ChatRole.Tool,
                    toolResult = ToolResult(callId = "c", content = "炸了", isError = true),
                ),
            ),
        )
        val block = root["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals(true, block["is_error"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `assistant 的工具调用编成 tool_use`() {
        val root = body(
            listOf(
                ChatMessage(
                    role = ChatRole.Assistant,
                    toolCalls = listOf(ToolCall(id = "c1", name = "read_file", argumentsJson = """{"path":"/x"}""")),
                ),
            ),
        )
        val block = root["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", block["type"]?.jsonPrimitive?.content)
        assertEquals("read_file", block["name"]?.jsonPrimitive?.content)
        // input 必须是被解析后的对象，不能是字符串。
        assertEquals("/x", block["input"]!!.jsonObject["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun `工具声明按名去重`() {
        // 重名会让上游 400（网关 #513）。
        val root = body(
            messages = listOf(ChatMessage(ChatRole.User, "hi")),
            tools = listOf(
                ToolDescriptor("read_file", "读文件", """{"type":"object"}"""),
                ToolDescriptor("read_file", "读文件（重复）", """{"type":"object"}"""),
                ToolDescriptor("write_file", "写文件", """{"type":"object"}"""),
            ),
        )
        val tools = root["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `坏 schema 不发出去`() {
        val root = body(
            messages = listOf(ChatMessage(ChatRole.User, "hi")),
            tools = listOf(ToolDescriptor("t", "d", "这不是 JSON")),
        )
        val schema = root["tools"]!!.jsonArray[0].jsonObject["input_schema"]!!.jsonObject
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `max_tokens 有默认值`() {
        // Anthropic 不给我们「不写这个字段」的机会。
        val root = body(listOf(ChatMessage(ChatRole.User, "hi")))
        assertEquals(4096, root["max_tokens"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `显式 max_tokens 优先`() {
        val root = body(listOf(ChatMessage(ChatRole.User, "hi")), maxTokens = 128)
        assertEquals(128, root["max_tokens"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `图片走 image 块`() {
        val root = body(
            listOf(
                ChatMessage(
                    role = ChatRole.User,
                    text = "这是什么",
                    images = listOf(ChatImage(mimeType = "image/jpeg", base64 = "AAAA")),
                ),
            ),
        )
        val content = root["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        val image = content[0].jsonObject
        assertEquals("image", image["type"]?.jsonPrimitive?.content)
        val source = image["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("image/jpeg", source["media_type"]?.jsonPrimitive?.content)
        assertEquals("AAAA", source["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `没有工具时不写 tools 字段`() {
        val root = body(listOf(ChatMessage(ChatRole.User, "hi")))
        assertNull(root["tools"])
    }

    @Test
    fun `流式开关透传`() {
        assertTrue(body(listOf(ChatMessage(ChatRole.User, "hi")), stream = true)["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, body(listOf(ChatMessage(ChatRole.User, "hi")), stream = false)["stream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `版本头是约定的那个`() {
        assertEquals("2023-06-01", AnthropicRequestBuilder.API_VERSION)
    }
}
