package dev.praxis.data.ai

import dev.praxis.core.ai.AiError
import dev.praxis.core.ai.AiErrorKind
import dev.praxis.core.ai.AiException
import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatMessage
import dev.praxis.core.ai.ChatRequest
import dev.praxis.core.ai.ChatRole
import dev.praxis.core.ai.OpenAiStreamAssembler
import dev.praxis.core.ai.SseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 * 1. **只用流式**：非流式 = 消费完流再拼接。两套实现必然行为不一致。
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

    private fun endpoint(): String {
        val trimmed = baseUrl.trimEnd('/')
        // 允许用户填 https://api.x.com 或 https://api.x.com/v1
        return if (trimmed.endsWith("/v1")) "$trimmed/chat/completions"
        else "$trimmed/v1/chat/completions"
    }

    private fun buildRequestBody(request: ChatRequest) = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        put("temperature", request.temperature)
        request.maxTokens?.let { put("max_tokens", it) }

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
                                // parametersJson 已是合法 JSON，作为原始元素嵌入
                                put("parameters", json.parseToJsonElement(tool.parametersJson))
                            }
                        },
                    )
                }
            }
        }
    }

    private fun messageToJson(msg: ChatMessage) = buildJsonObject {
        put("role", msg.role.wireName())
        if (msg.text.isNotEmpty()) put("content", msg.text)

        if (msg.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                msg.toolCalls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.argumentsJson)
                            }
                        },
                    )
                }
            }
        }

        msg.toolResult?.let { result ->
            put("tool_call_id", result.callId)
        }
    }

    private fun ChatRole.wireName(): String = when (this) {
        ChatRole.System -> "system"
        ChatRole.User -> "user"
        ChatRole.Assistant -> "assistant"
        ChatRole.Tool -> "tool"
    }

    companion object {
        private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

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
