package dev.praxis.data.ai

import dev.praxis.core.ai.AiError
import dev.praxis.core.ai.AiErrorKind
import dev.praxis.core.ai.AiException
import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.AnthropicRequestBuilder
import dev.praxis.core.ai.AnthropicStreamAssembler
import dev.praxis.core.ai.ChatRequest
import dev.praxis.core.ai.SseParser
import dev.praxis.core.gateway.ProviderProtocol
import dev.praxis.core.gateway.ProviderProtocols
import dev.praxis.core.model.settings.CustomHeader
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Anthropic Messages 协议的 provider（文档 §5.1）。
 *
 * ## 与 [OpenAiCompatProvider] 的关系
 *
 * 两者实现同一个 [AiProvider]，差异全在协议层：
 * 编码用 [AnthropicRequestBuilder]、组装用 [AnthropicStreamAssembler]、
 * 鉴权头用 `x-api-key` + 版本头（不是 `Bearer`）。
 * **错误分类是共享的**（[dev.praxis.core.gateway.UpstreamErrorClassifier]）——
 * 429/401/413 的判定与协议无关，重复写一遍必然漂移。
 */
class AnthropicProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val customHeaders: List<CustomHeader> = emptyList(),
    private val client: OkHttpClient = defaultClient(),
) : AiProvider {

    override val displayName: String = "Anthropic Messages"

    override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
        val body = AnthropicRequestBuilder.build(request)

        val httpRequest = Request.Builder()
            .url(endpoint())
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .apply {
                // 鉴权头按协议来：Anthropic 用 x-api-key + 版本头，Bearer 会被拒。
                ProviderProtocols
                    .authHeaders(ProviderProtocol.AnthropicMessages, apiKey)
                    .forEach { (name, value) -> addHeader(name, value) }
                customHeaders.forEach { (name, value) ->
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) {
                        addHeader(trimmed, value.replace("\r", "").replace("\n", "").trim())
                    }
                }
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
                    AiError(
                        kind = dev.praxis.core.gateway.UpstreamErrorClassifier
                            .classify(resp.code, errText),
                        message = "HTTP " + resp.code + ": " + errText.take(300),
                        retryAfterMillis = resp.header("Retry-After")
                            ?.toLongOrNull()?.times(1000),
                    ),
                )
            }
            val source = resp.body?.source()
                ?: throw AiException(AiError(AiErrorKind.PROTOCOL, "响应没有正文"))

            val sse = SseParser()
            val assembler = AnthropicStreamAssembler()
            while (true) {
                val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
                sse.feed(line + "\n").forEach { payload ->
                    assembler.feed(payload).forEach { emit(it) }
                }
            }
            // 未以空行结尾的最后一件事也要吃进去。
            sse.finish().forEach { payload ->
                assembler.feed(payload).forEach { emit(it) }
            }

            // 上游在流里报的 error：抛出去让上层按失败处理（而不是当成正常结束）。
            assembler.errorMessage()?.let { message ->
                throw AiException(AiError(AiErrorKind.SERVER, message))
            }

            assembler.finish().forEach { emit(it) }
        }
    }

    private fun endpoint(): String {
        val base = baseUrl.trimEnd('/')
        return base + ProviderProtocols.path(ProviderProtocol.AnthropicMessages)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
