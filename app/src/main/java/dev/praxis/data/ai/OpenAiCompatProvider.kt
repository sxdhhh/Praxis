package dev.praxis.data.ai

import dev.praxis.core.ai.AiError
import dev.praxis.core.ai.AiErrorKind
import dev.praxis.core.ai.AiException
import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatEndpoint
import dev.praxis.core.ai.ChatMessage
import dev.praxis.core.ai.ChatRequest
import dev.praxis.core.ai.ChatRole
import dev.praxis.core.ai.OpenAiStreamAssembler
import dev.praxis.core.ai.ThinkingEffort
import dev.praxis.core.ai.SseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容协议的 Provider。
 *
 * 一个实现覆盖绝大多数服务商（OpenAI / DeepSeek / Moonshot / 各类中转），
 * 因为它们都遵循同一套 `/chat/completions` + SSE 协议。
 * 协议不一致（Anthropic、Gemini）再单独写实现，不在这里堆 if 分支 ——
 * 那会让这个类变成「谁都不敢改」的巨石。
 *
 * ## 关键设计
 *
 * 1. **流式为主，非流式为辅**：默认走 SSE；`ChatRequest.stream = false`
 *    时走一次性解析（两种响应体形状不同，见 `emitNonStreaming`）。
 *    仍不提供第二套 provider 接口 —— 上层始终消费同一个 `Flow<AiStreamEvent>`。
 * 2. **HTTP 错误用 [AiError] 归类**：不让 OkHttp 的异常类型泄漏到上层。
 * 3. **读流在 IO 调度器**：`flowOn(Dispatchers.IO)` 保证网络读不阻塞主线程。
 */
class OpenAiCompatProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : AiProvider {

    override val displayName: String = "OpenAI 兼容"

    override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
        // buildRequestBody 返回 JsonObject；toRequestBody 只接受 String/ByteArray，
        // 这里显式 toString() 一次，避免依赖隐式转换。
        val body = buildRequestBody(request).toString()

        val httpRequest = Request.Builder()
            .url(endpoint())
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .apply {
                if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
            }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response: Response = try {
            client.newCall(httpRequest).execute()
        } catch (io: IOException) {
            throw AiException(
                AiError(
                    kind = AiErrorKind.NETWORK,
                    message = io.message ?: "network failure",
                    cause = io,
                ),
            )
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val errText = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                throw AiException(
                    AiError.fromHttpCode(
                        code = resp.code,
                        message = "HTTP ${resp.code}: ${errText.take(300)}",
                        retryAfterMillis = resp.header("Retry-After")?.toLongOrNull()?.times(1000),
                    ),
                )
            }
            val source = resp.body?.source()
                ?: throw AiException(AiError(AiErrorKind.STREAM_INTERRUPTED, "empty response body"))
            // ── 非流式分支（2026-10-24 新增）──────────────────────────
            //
            // ## 为什么必须有这一支，而不是只把 `stream` 改成 false
            //
            // 非流式响应体是**一整段 JSON**，没有 `data:` 前缀；
            // 而下面的 SSE 解析器只认 `data: {...}` 行。若只改请求参数
            // 不补这一支，用户打开「关闭流式输出」后会得到**空回复**：
            // 请求成功、没有内容 —— 比不开这个开关更糟。
            //
            // ## 两种协议的形状差异
            //
            // ```
            // 流式:   {"choices":[{"delta":{"content":"你"}}]}
            // 非流式: {"choices":[{"message":{"content":"你好",
            //                     "tool_calls":[{"id":"call_a",
            //                       "function":{"name":"read_file",
            //                                   "arguments":"{\"path\":\"/a\"}"}}]}}]}
            // ```
            //
            // 关键差别：`delta` → `message`，且 `arguments` 是**完整 JSON**
            // （不像流式那样分片），所以不需要累加桶。
            if (!request.stream) {
                val raw = withContext(Dispatchers.IO) { source.readUtf8() }
                emitNonStreaming(raw, json).forEach { emit(it) }
                return@use
            }

            val sse = SseParser()
            val assembler = OpenAiStreamAssembler(json)

            while (true) {
                val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                // SSE 事件以空行分隔；readUtf8Line 逐行读，这里补回分隔符
                sse.feed(line + "\n").forEach { payload ->
                    assembler.feed(payload).forEach { emit(it) }
                }
                if (line.isEmpty()) {
                    // 空行 = 一个事件结束
                }
            }

            // 多行拼接后可能还剩一条未以空行结尾的事件
            sse.finish().forEach { payload ->
                assembler.feed(payload).forEach { emit(it) }
            }

            assembler.finish().forEach { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 拼出真正的请求端点。
     *
     * 逻辑已抽到 [ChatEndpoint]（`core:ai`，纯 JVM 可单测）—— 那段规则有三个真实的
     * 失败史，必须被单测逐条钉住，放在本类里（Android 模块 + OkHttp）就只能靠真机试。
     *
     * 这里只留一行委托，避免出现「上游改了规则、这一份没改」的第二份实现。
     */
    private fun endpoint(): String = ChatEndpoint.resolve(baseUrl)

    private fun buildRequestBody(request: ChatRequest) = buildJsonObject {
        put("model", request.model)
        // 用请求里的开关，不再写死 true（见 `ChatRequest.stream` 的 KDoc）。
        //
        // ## 为什么这里不改别的行为
        //
        // 非流式时服务端返回的是一整段 JSON（没有 `data:` 前缀的行）。
        // 下面的 SSE 解析器已经能容忍「没有 data 行」的情况吗？
        // **不能** —— 所以真正关闭流式需要 provider 走另一条解析分支。
        // 本轮先把字段与写入点打通（让开关有实际效果：不再请求 stream），
        // 响应体解析的兼容分支留给下一步（见记忆里的未完成项）。
        put("stream", request.stream)
        put("temperature", request.temperature)
        request.maxTokens?.let { put("max_tokens", it) }

        // ── 思考强度 ────────────────────────────────────────────────
        //
        // ## 为什么用 `reasoning_effort`
        //
        // 这是 OpenAI 兼容层里**最常见**的字段名（o 系列、以及多数兼容网关
        // 都接受）。各家真正的参数名并不统一（Anthropic 是 `thinking`、
        // 部分国产模型是 `enable_thinking` / `thinking_budget`），
        // 但那几个都不是 OpenAI 兼容协议的一部分，写进来会让
        // 「本来是兼容的网关」直接 400。
        //
        // ## 为什么关闭时干脆不写这个字段
        //
        // 不写 = 用服务端默认。写 `"none"` 反而可能被不认识该值的服务端
        // 判为非法枚举。**不认识该字段**的服务端会直接忽略它（JSON 宽松解析），
        // 所以「支持就生效、不支持就忽略」这个目标是靠「只在开启时写」实现的。
        when (request.thinkingEffort) {
            ThinkingEffort.None -> Unit
            ThinkingEffort.Low -> put("reasoning_effort", "low")
            ThinkingEffort.Medium -> put("reasoning_effort", "medium")
            ThinkingEffort.High -> put("reasoning_effort", "high")
        }

        putJsonArray("messages") {
            request.messages.forEach { msg -> add(messageToJson(msg)) }
        }

        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", parseParametersSchema(tool))
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * 把 `parametersJson` 变成可嵌入的 JSON 元素。
     *
     * ## 为什么要容错，而不是直接 `parseToJsonElement`
     *
     * `parametersJson` 来自各工具手写的字符串。只要**任何一个**工具写错
     * （少个逗号、多了个注释、空的 `""`），`parseToJsonElement` 就抛异常，
     * 而这次抛是发生在**拼请求体时**——整个请求直接失败，用户看到的是
     * 「发不出去」，完全不知道是某个工具的 schema 有问题。
     *
     * 一个工具的参数写错，正确的后果应该是「那个工具用不了」，
     * 而不是「所有对话都发不出去」。所以：解析失败 → 退回
     * `{"type":"object","properties":{}}`（合法的空 schema）。
     * 模型仍然能看到这个工具的存在，只是不知道参数 —— 调用时会报参数错误，
     * 由 `ToolExecutor` 转成可读结果回传，链路仍然是通的。
     */
    private fun parseParametersSchema(tool: dev.praxis.core.ai.ToolDescriptor) =
        runCatching {
            val raw = tool.parametersJson.trim()
            if (raw.isEmpty()) {
                json.parseToJsonElement(EMPTY_OBJECT_SCHEMA)
            } else {
                json.parseToJsonElement(raw)
            }
        }.getOrElse {
            json.parseToJsonElement(EMPTY_OBJECT_SCHEMA)
        }

    private fun messageToJson(msg: ChatMessage) = buildJsonObject {
        put("role", msg.role.wireName())

        // ── content 的三种情形 ─────────────────────────────────
        //
        // | role | 有 toolResult | content 写什么 |
        // |---|---|---|
        // | tool | 是 | **toolResult.content**（见下） |
        // | 其它 | — | text（非空才写） |
        //
        // ⚠️ 2026-10-22 修的真缺陷：旧实现只写 `msg.text`，而 tool 消息是通过
        // `toolResult` 字段携带内容的（见 ChatViewModel 的构造），`text` 恒为空。
        // 于是发给上游的 tool 消息长这样：
        //
        // ```json
        // {"role":"tool","tool_call_id":"call_a"}     ← 没有 content！
        // ```
        //
        // OpenAI 对 tool 消息的 `content` 是**必填**。少了它的后果分两种：
        //  - 严格的服务端 → 直接 400 `content is required`；
        //  - 宽松的服务端 → 当成空结果，于是模型永远「看不到工具返回了什么」，
        //    表现为「它一直重复调用同一个工具，像没执行过一样」。
        //
        // 第二种更糟：不报错，所以用户只会觉得「AI 很笨」，而根因在序列化层。
        val content: String = if (msg.role == ChatRole.Tool) {
            msg.toolResult?.content.orEmpty()
        } else {
            msg.text
        }
        // ## 带图片时 `content` 必须变成**数组**（2026-10-24，多模态）
        //
        // OpenAI 兼容协议的两种形态：
        //
        // ```json
        // 纯文本： "content": "这是什么"
        // 带图片： "content": [
        //            {"type":"text","text":"这是什么"},
        //            {"type":"image_url","image_url":{"url":"data:image/png;base64,…"}}
        //          ]
        // ```
        //
        // **不能**把 base64 拼进字符串（`"content": "这是什么 ![img](data:…)"`）：
        // 模型不会把它当图片读，只会当成一长串乱码字符 —— 既浪费 token，
        // 也让模型对「图里有什么」完全无感。这是多模态接入最容易踩的坑。
        //
        // 图片只对 user 消息有意义，其它角色若误带图（工具结果里夹了图），
        // 忽略即可 —— 上游对 `role=tool` 的数组 content 支持极不一致。
        val images = if (msg.role == ChatRole.User) msg.images else emptyList()
        if (images.isNotEmpty()) {
            putJsonArray("content") {
                // 文本部分先放。空文本不占位：只发图片时不该出现空 text 元素
                // （部分网关会对空 text 元素报 400）。
                if (content.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", content)
                        },
                    )
                }
                images.forEach { image ->
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", image.toDataUrl())
                            }
                        },
                    )
                }
            }
        } else if (content.isNotEmpty() || msg.role == ChatRole.Tool) {
            // tool 消息即使内容为空也必须写 `content: ""` —— 缺字段与空串在上游
            // 是两种校验结果（缺 → 报错，空 → 合法的空结果）。
            put("content", content)
        }

        if (msg.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                msg.toolCalls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                // ## 空 arguments 必须补成 `{}`
                                //
                                // 无参工具（如 `linux_status`）在流式里收到的
                                // `arguments` 是空串（见 OpenAiStreamAssembler 的
                                // 增量拼接）。原样回传会得到：
                                // `"arguments": ""` —— 不是合法 JSON，
                                // 上游 400 报 `arguments must be a JSON object`。
                                //
                                // 这也解释了「第一次调工具成功、第二次就 400」
                                // 这类时序相关的怪问题：只有无参工具会触发。
                                val args = call.argumentsJson.trim()
                                put(
                                    "arguments",
                                    if (args.isEmpty() || args == "null") "{}" else args,
                                )
                            }
                        },
                    )
                }
            }
        }

        msg.toolResult?.let { result ->
            put("tool_call_id", result.callId)
            // 名字非空才写：OpenAI 官方规范里 tool 消息的 `name` 是可选的，
            // 但不少兼容网关会校验它并与 tool_calls[].function.name 比对，
            // 缺了报 `tool message must have a name`。
            // 空串表示「不知道名字」（历史消息/单测），此时不写字段。
            if (result.name.isNotEmpty()) put("name", result.name)
        }
    }

    private fun ChatRole.wireName(): String = when (this) {
        ChatRole.System -> "system"
        ChatRole.User -> "user"
        ChatRole.Assistant -> "assistant"
        ChatRole.Tool -> "tool"
    }

    companion object {
        // CHAT_COMPLETIONS_PATH 已移到 core:ai 的 ChatEndpoint（那里可纯 JVM 单测），
        // 这里不再保留第二份 —— 两份常量迟早会改漏一处。
        private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

        /**
         * 解析**非流式**响应体（`stream = false` 时服务端返回的整段 JSON）。
         *
         * ## 为什么要单独一个函数
         *
         * 非流式与流式的 JSON 形状不同（`message` vs `delta`），且
         * `arguments` 在非流式里是**完整 JSON 字符串**、不是分片。
         * 若复用流式的累加桶，会把完整串当成「第一片」再追加后续片 ——
         * 而后续片根本不存在，所以结果碰巧对；但它同时把「分片语义」
         * 带进来，将来有人加 `content` 分片就会出错。分开更安全。
         *
         * ## 为什么返回 List 而不是直接 emit
         *
         * 保持纯函数：只依赖入参与 [Json]，可在 JVM 单测里直接验证
         * 三种情况（纯文本 / 带工具调用 / 空 choices），不必起 HTTP。
         *
         * ## 解析失败时抛异常而不是返回空
         *
         * 返回空列表会让上层表现为「模型没说话」，用户反复重试都同样结果，
         * 且没有任何线索。抛 [AiException] 才能让 UI 显示真实原因。
         */
        internal fun emitNonStreaming(raw: String, json: Json): List<AiStreamEvent> {
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: throw AiException(
                    AiError(
                        kind = AiErrorKind.PROTOCOL,
                        message = "非流式响应不是合法 JSON: ${raw.take(200)}",
                    ),
                )
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw AiException(
                    AiError(
                        kind = AiErrorKind.PROTOCOL,
                        message = "非流式响应缺少 choices: ${raw.take(200)}",
                    ),
                )
            val message = choice["message"]?.jsonObject ?: return emptyList()
            val events = mutableListOf<AiStreamEvent>()

            // 正文。可能为空串（纯工具调用那一轮），此时不 emit Delta ——
            // 空 Delta 会让 UI 的流式槽经历一次「有值但为空」的闪烁。
            message["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                events += AiStreamEvent.Delta(it)
            }

            // 工具调用。非流式下 arguments 已是完整串，**不做任何拼接**。
            val calls = message["tool_calls"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val fn = obj["function"]?.jsonObject ?: return@mapNotNull null
                val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                dev.praxis.core.ai.ToolCall(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = name,
                    argumentsJson = fn["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }.orEmpty()
            if (calls.isNotEmpty()) {
                events += AiStreamEvent.ToolCalls(calls)
            }

            // 结束事件。非流式一定补一次 Done，否则上层状态机停在 Connecting。
            events += AiStreamEvent.Done(
                choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
            )
            return events
        }

        /**
         * 合法的空参数 schema。
         *
         * 用在两处：工具自己没写 `parametersJson`、或写了但解析失败。
         * OpenAI 对无参函数的规范写法就是这个对象，不能省略 `parameters`
         * （缺字段会被部分网关判为「函数声明不完整」）。
         */
        private const val EMPTY_OBJECT_SCHEMA = """{"type":"object","properties":{}}"""

        /**
         * 流式必须禁用 readTimeout：模型思考几十秒很正常，
         * 设成 120s 会在长推理上误杀。用 callTimeout 兜底防永久挂死。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}
