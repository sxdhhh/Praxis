package dev.praxis.data.ai

import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatMessage
import dev.praxis.core.ai.ChatRequest
import dev.praxis.core.ai.ChatRole
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.FunctionSlot
import dev.praxis.feature.chat.ChatTitleGenerator
import dev.praxis.feature.chat.SessionTitle
import kotlinx.coroutines.flow.first

/**
 * 用模型生成会话标题（1:1 对齐 Operit `ConversationService.generateConversationTitle`）。
 *
 * ## 为什么要单独一个 provider 实例而不是复用聊天那个
 *
 * 聊天用的是「主模型 + 用户的 temperature / thinking」。标题生成需要：
 *  - **另一个模型**（`FunctionSlot.TITLE`，通常配便宜的小模型）；
 *  - **不同参数**（不需要思考、不需要工具）；
 *  - **非流式语义**（只要最后那一句标题）。
 *
 * 复用同一个 provider 意味着要么污染聊天参数，要么在这里再 copy 一次请求 ——
 * 后者其实也行，但「按功能位选模型」这件事本来就在 `:app` 层，
 * 所以直接构造一条独立链路更清楚。
 *
 * ## 为什么用 `stream()` 拼字符串
 *
 * `AiProvider` 只暴露 [AiProvider.stream]（见该接口的 KDoc：
 * 「非流式调用 = 消费流并拼接，不需要第二套接口」）。这里遵循同一约定，
 * 不引入第二条调用路径 —— 两套实现必然会出现行为不一致。
 */
class SettingsBackedTitleGenerator(
    private val repository: SettingsRepository,
    private val provider: AiProvider,
) : ChatTitleGenerator {

    override suspend fun generate(userText: String): String {
        val global = repository.modelSettings.first()
        val functionSettings = repository.functionModelSettings.first()
        // 功能位解析：用户没单独配 TITLE 时回落到主模型（`resolve` 的既定语义）。
        val model = functionSettings.resolve(FunctionSlot.TITLE, global).model

        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = ChatRole.System, text = SessionTitle.SYSTEM_PROMPT),
                ChatMessage(role = ChatRole.User, text = SessionTitle.userPrompt(userText)),
            ),
            // 标题生成不该带工具：带了以后模型可能先去调工具而不输出标题。
            tools = emptyList(),
            // ## 为什么不在这里设 temperature
            //
            // `SettingsBackedProvider.stream` 会用 `settings.temperature` **覆盖**
            // 请求体里的温度（它的 KDoc 写明了这一点：旧实现漏了覆盖导致
            // 「设置页改了温度没生效」）。所以这里传 0.3 也会被冲掉，
            // 写了只是让人误以为标题生成用的是低温度。
            // 想真正让标题稳定，应给 TITLE 功能位单独配低温度 —— 那是设置层的事。
            //
            // 40 字标题足够，限制上限能挡住「模型写了一段解释」的情况。
            maxTokens = 64,
        )

        val builder = StringBuilder()
        provider.stream(request).collect { event ->
            when (event) {
                is AiStreamEvent.Delta -> builder.append(event.text)
                // 标题生成不期望工具调用；真出现了也不执行（这里不持有执行器），
                // 直接忽略，最终用已收到的文本作为标题。
                is AiStreamEvent.ToolCalls -> Unit
                is AiStreamEvent.Done -> Unit
            }
        }
        return builder.toString()
    }
}
