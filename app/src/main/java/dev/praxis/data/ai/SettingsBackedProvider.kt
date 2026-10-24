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
        // temperature 必须在这里覆盖：`ChatRequest` 的默认值是写死的 0.7，
        // 而 `ChatViewModel` 构造 `ChatRequest` 时不带 temperature。
        // 之前只 copy 了 model，导致设置页存下的温度**从来没有进过请求体** ——
        // 表现为「改了温度没任何变化」。model 与 temperature 都取设置里的值。
        emitAll(
            delegate.stream(
                request.copy(
                    // ## 必须用 `activeModel` 而不是 `settings.model`（2026-10-24 修）
                    //
                    // `settings.model` 现在是**模型池**（逗号分隔多个），
                    // 直接塞进请求体会把 `"a,b,c"` 整串发给服务端 ——
                    // 那不是一个存在的模型名，必然 400（model not found）。
                    //
                    // `activeModel` 按 `modelIndex` 从池里取**单个**，
                    // 越界时回落第 0 个。这是 1:1 对齐 Operit
                    // `MultiServiceManager.createServiceFromConfig` 的做法：
                    // 那里也是 `getValidModelIndex` + `getModelByIndex`
                    // 先算出 `selectedModelName`，再 `copy(modelName = ...)`。
                    model = settings.activeModel,
                    temperature = settings.temperature,
                ),
            ),
        )
    }
}
