package dev.praxis.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses 出站编码（文档 §5.1）。 */
class ResponsesRequestBuilderTest {

    private fun body(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor> = emptyList(),
        maxTokens: Int? = null,
    ) = Json.parseToJsonElement(
        ResponsesRequestBuilder.build(
            ChatRequest(model = "gpt-x", messages = messages, tools = tools, maxTokens = maxTokens),
        ),
    ).jsonObject

    @Test
    fun `system 走顶层 instructions`() {
        val root = body(listOf(ChatMessage(ChatRole.System, "你是助手"), ChatMessage(ChatRole.User, "你好")))
        assertEquals("你是助手", root["instructions"]?.jsonPrimitive?.content)
        assertEquals(1, root["input"]!!.jsonArray.size)
    }

    @Test
    fun `上限字段叫 max_output_tokens`() {
        // 写成 max_tokens 上游会忽略它，表现为「回复被截断但不知道为什么」。
        val root = body(listOf(ChatMessage(ChatRole.User, "hi")), maxTokens = 128)
        assertEquals(128, root["max_output_tokens"]?.jsonPrimitive?.content?.toInt())
        assertNull(root["max_tokens"])
    }

    @Test
    fun `用户消息用 input_text 块`() {
        val root = body(listOf(ChatMessage(ChatRole.User, "你好")))
        val item = root["input"]!!.jsonArray[0].jsonObject
        assertEquals("user", item["role"]?.jsonPrimitive?.content)
        val block = item["content"]!!.jsonArray[0].jsonObject
        assertEquals("input_text", block["type"]?.jsonPrimitive?.content)
        assertEquals("你好", block["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `工具调用是独立条目而不是消息字段`() {
        // 把它当成 Chat 的 tool_calls 嵌进 assistant 消息，上游会说 input[1] 不是合法 item。
        val root = body(
            listOf(
                ChatMessage(
                    role = ChatRole.Assistant,
                    text = "我看看",
                    toolCalls = listOf(ToolCall(id = "call_1", name = "read_file", argumentsJson = """{"path":"/x"}""")),
                ),
            ),
        )
        val input = root["input"]!!.jsonArray
        assertEquals(2, input.size)
        val call = input[1].jsonObject
        assertEquals("function_call", call["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", call["call_id"]?.jsonPrimitive?.content)
        // arguments 在 Responses 里是字符串（Anthropic 那边是对象）—— 别写混。
        assertEquals("""{"path":"/x"}""", call["arguments"]?.jsonPrimitive?.content)
    }

    @Test
    fun `工具结果是 function_call_output`() {
        val root = body(
            listOf(
                ChatMessage(
                    role = ChatRole.Tool,
                    toolResult = ToolResult(callId = "call_1", content = "42"),
                ),
            ),
        )
        val item = root["input"]!!.jsonArray[0].jsonObject
        assertEquals("function_call_output", item["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", item["call_id"]?.jsonPrimitive?.content)
        assertEquals("42", item["output"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assistant 正文用 output_text`() {
        val root = body(listOf(ChatMessage(ChatRole.Assistant, "回答")))
        val block = root["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("output_text", block["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `工具声明带 type=function 且按名去重`() {
        val root = body(
            messages = listOf(ChatMessage(ChatRole.User, "hi")),
            tools = listOf(
                ToolDescriptor("read_file", "读文件", """{"type":"object"}"""),
                ToolDescriptor("read_file", "重复", """{"type":"object"}"""),
            ),
        )
        val tools = root["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("function", tools[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("read_file", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `图片用 input_image 且带 data url`() {
        val root = body(
            listOf(
                ChatMessage(
                    role = ChatRole.User,
                    text = "这是什么",
                    images = listOf(ChatImage(mimeType = "image/jpeg", base64 = "AAAA")),
                ),
            ),
        )
        val blocks = root["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, blocks.size)
        assertEquals("input_image", blocks[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "data:image/jpeg;base64,AAAA",
            blocks[1].jsonObject["image_url"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `没有 system 时不写 instructions`() {
        val root = body(listOf(ChatMessage(ChatRole.User, "hi")))
        assertNull(root["instructions"])
    }

    @Test
    fun `没有工具时不写 tools`() {
        val root = body(listOf(ChatMessage(ChatRole.User, "hi")))
        assertNull(root["tools"])
    }

    @Test
    fun `坏 schema 不发出去`() {
        val root = body(
            messages = listOf(ChatMessage(ChatRole.User, "hi")),
            tools = listOf(ToolDescriptor("t", "d", "坏 JSON")),
        )
        val schema = root["tools"]!!.jsonArray[0].jsonObject["parameters"]!!.jsonObject
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `max_output_tokens 有默认值`() {
        val root = body(listOf(ChatMessage(ChatRole.User, "hi")))
        assertTrue((root["max_output_tokens"]?.jsonPrimitive?.content?.toInt() ?: 0) > 0)
    }
}
