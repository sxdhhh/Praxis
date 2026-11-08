package dev.praxis.data.gateway

import dev.praxis.core.ai.AiError
import dev.praxis.core.ai.AiErrorKind
import dev.praxis.core.ai.AiException
import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatRequest
import dev.praxis.core.data.gateway.GatewayAccountStore
import dev.praxis.core.gateway.CandidateChain
import dev.praxis.core.gateway.FailoverBudget
import dev.praxis.core.gateway.FailureKind
import dev.praxis.core.gateway.GatewayCredential
import dev.praxis.core.gateway.GatewayModelRules
import dev.praxis.core.gateway.RouteState
import dev.praxis.core.gateway.RouterSession
import dev.praxis.core.model.settings.CustomHeader
import dev.praxis.data.ai.OpenAiCompatProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient

/**
 * 网关出站（文档 14.3：路由层复用现成的 OpenAiCompatProvider）。
 *
 * ## 分工
 *
 * - 选哪家、哪个账号、哪个上游模型：RouterSession（纯逻辑，可单测）
 * - 真正发请求：OpenAiCompatProvider（现成的 Chat 出站，不重复实现）
 * - 本类只做胶水：读账号、解密凭证、按决策循环、把错误翻译回 FailureKind
 *
 * ## 一条不能违反的规则：已经吐出内容之后不再换号
 *
 * 流式响应一旦有事件发出去了，用户已经看到文字。此时换号重试会造成
 * 「前一句话被另一家接上」的错乱。所以只在尚未发出任何事件时允许换号；
 * 中途失败直接把错误抛给上层，由对话层显示「中断」。
 *
 * ## 未实现（明确标注）
 *
 * AiErrorKind 目前没有「体积超限」与「内容拦截」两类，所以 413 与正文拒绝
 * 现在只能落到别的 kind 上（前者多半是 INVALID_REQUEST、后者是 PROTOCOL）。
 * 文档 10 要求给 core:ai 补 QuotaLimited / ContentBlocked / TokenExpired 三类，
 * 那是独立一轮的改动（会牵动所有既有 when 分支）。
 *
 * 另外，原生承载（某家清单里 id 等于对外名）暂时为空：模型目录要等 P2 的
 * 模型条目接上数据源。现在只能走映射。
 */
class GatewayProvider(
    private val store: GatewayAccountStore,
    /** modelRules（映射与禁用）。每次现取：用户可能刚改过配置。 */
    private val modelRules: () -> GatewayModelRules,
    /** 内置家 id 到地址的映射；自定义家的地址在 provider 表里。 */
    private val providerBaseUrl: (String) -> String?,
    private val state: RouteState,
    private val budget: FailoverBudget = FailoverBudget(),
    private val customHeaders: List<CustomHeader> = emptyList(),
    private val client: OkHttpClient = OkHttpClient(),
) : AiProvider {

    override val displayName: String = "网关"

    override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
        val rules = modelRules()

        val chain = CandidateChain.of(
            requestedModel = request.model,
            nativeModels = emptyList(),
            mappings = rules.toRoutingMappings(),
            defaultProvider = null,
        )

        if (chain.candidates.isEmpty()) {
            throw AiException(
                AiError(
                    kind = AiErrorKind.INVALID_REQUEST,
                    message = "模型 " + request.model +
                        " 没有对应任何上游（既不在各家清单里，也没有配置映射）",
                ),
            )
        }

        // 账号快照：运行期冷却走 RouteState，不依赖账号表的实时变化。
        val views = store.routingViews()

        val session = RouterSession(
            candidates = chain.candidates,
            accounts = { views },
            state = state,
            needsImages = false,
            budget = budget,
        )

        var emitted = false
        while (true) {
            val attempt = session.next() ?: break
            val view = views.firstOrNull { it.id == attempt.accountId } ?: continue
            val credential = store.credentialOf(attempt.accountId).getOrNull() ?: continue
            val apiKey = (credential as? GatewayCredential.ApiKey)?.apiKey ?: continue
            val baseUrl = view.baseUrlOverride ?: providerBaseUrl(attempt.provider) ?: continue

            val provider = OpenAiCompatProvider(
                baseUrl = baseUrl,
                apiKey = apiKey,
                customHeaders = customHeaders,
                client = client,
            )

            try {
                provider.stream(request.copy(model = attempt.wireModel)).collect { event ->
                    emitted = true
                    emit(event)
                }
                session.onSuccess(attempt)
                return@flow
            } catch (e: AiException) {
                if (e.error.kind == AiErrorKind.CANCELLED) throw e

                session.onFailure(
                    attempt = attempt,
                    kind = e.error.kind.toFailureKind(),
                    quotaResetAtMillis = e.error.retryAfterMillis,
                )

                // 已经吐过字：不再换号，直接把错误抛上去。
                if (emitted) throw e
            }
        }

        throw AiException(
            AiError(
                kind = AiErrorKind.NETWORK,
                message = "网关没有可用的账号（这次请求已经试过所有候选）",
            ),
        )
    }
}

/** AiErrorKind 到文档 7.4 失败分类的映射。 */
internal fun AiErrorKind.toFailureKind(): FailureKind = when (this) {
    AiErrorKind.RATE_LIMIT -> FailureKind.QuotaLimited
    AiErrorKind.AUTH -> FailureKind.CredentialExpired
    AiErrorKind.INVALID_REQUEST -> FailureKind.InvalidRequest
    AiErrorKind.NETWORK,
    AiErrorKind.SERVER,
    AiErrorKind.STREAM_INTERRUPTED,
    AiErrorKind.PROTOCOL,
    AiErrorKind.UNKNOWN,
    -> FailureKind.UpstreamError

    // 调用方在拿到 CANCELLED 时就原样抛出，不该走到这里。
    AiErrorKind.CANCELLED -> FailureKind.UpstreamError
}
