package dev.praxis.data.ai

import dev.praxis.core.ai.AiError
import dev.praxis.core.ai.AiErrorKind
import dev.praxis.core.ai.AiException
import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatRequest
import dev.praxis.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first

/**
 * 每次请求都从设置读配置的 Provider 包装。
 *
 * ## 为什么不能构造时就固定 baseUrl/apiKey
 *
 * 用户在设置页改完立刻回到聊天页发消息，期望新配置生效。
 * 如果 provider 是启动时用 BuildConfig 构造的，改了也白改 —— 这是
 * 「设置页看着能改、实际没用」的典型缺陷。
 *
 * ## 为什么每轮重建而不是监听变化
 *
 * OkHttpClient 是重对象（连接池、线程池）。这里复用同一个 client，
 * 只重建轻量的「地址 + 密钥」组合，代价可忽略，但语义最直白。
 */
class SettingsBackedProvider(
    private val repository: SettingsRepository,
) : AiProvider {

    override val displayName: String = "设置配置的 OpenAI 兼容服务"

    override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
        val settings = repository.modelSettings.first()
        val apiKey = repository.apiKey()

        // 未配置时给出**可操作**的错误，而不是发一个注定 401 的请求。
        if (!settings.isConfigured) {
            throw AiException(
                AiError(
                    kind = AiErrorKind.INVALID_REQUEST,
                    message = "模型未配置：请到 设置 → 模型 填写接口地址与模型 ID。",
                ),
            )
        }

        val delegate = OpenAiCompatProvider(
            baseUrl = settings.baseUrl,
            apiKey = apiKey,
        )
        emitAll(delegate.stream(request.copy(model = settings.model)))
    }
}
