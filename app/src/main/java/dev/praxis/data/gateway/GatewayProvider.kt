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
import dev.praxis.core.gateway.PlannedAttempt
import dev.praxis.core.gateway.RouteState
import dev.praxis.core.gateway.RouterSession
import dev.praxis.core.gateway.toFailureKind
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
 * 原生承载来自 provider 表里各家声明的模型清单（账号文件里的 models 数组），
 * 不是空的；但「模型目录」（含能力位、上下文长度）仍未接入，那属于 P2。
 */
class GatewayProvider(
    private val store: GatewayAccountStore,
    /** modelRules（映射与禁用）。每次现取：用户可能刚改过配置。 */
    private val modelRules: () -> GatewayModelRules,
    /** 内置家 id 到地址的映射；自定义家的地址在 provider 表里（优先查表）。 */
    private val providerBaseUrl: (String) -> String? = { null },
    private val state: RouteState,
    /**
     * 选路策略（文档 §9.4）。**每次现取**：用户在设置页改了策略，
     * 下一句就该按新策略走，而不是重启 App 才生效。
     */
    private val policy: () -> dev.praxis.core.gateway.GatewayRoutingPolicy =
        { dev.praxis.core.gateway.GatewayRoutingPolicy() },
    private val budget: FailoverBudget = FailoverBudget(),
    /**
     * 请求记录落地（报表与 token 预算的数据源）。
     *
     * 默认空实现：没有装配报表时不影响请求本身 —— 但那是**显式**的空，
     * 不是「悄悄丢记录」（装配点见 AppModule/PraxisApp）。
     */
    private val recordUsage: suspend (dev.praxis.core.gateway.GatewayUsageDraft) -> Unit = {},
    /**
     * 窗口内各账号的 token 用量（token 预算判定，文档 §7.2 第 6 条）。
     *
     * 默认空 map：没装配报表时预算恒为「不限」—— 但那是**显式**的空，
     * 装配点在 AppModule / PraxisApp。
     */
    private val accountSpend: suspend (sinceMillis: Long) -> Map<String, Long> = { emptyMap() },
    private val customHeaders: List<CustomHeader> = emptyList(),
    private val client: OkHttpClient = OkHttpClient(),
) : AiProvider {

    override val displayName: String = "网关"

    override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
        val rules = modelRules()

        val chain = CandidateChain.of(
            requestedModel = request.model,
            // 原生承载来自各家声明的模型清单（账号文件里的 models）。
            nativeModels = store.nativeModels(),
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

        // 账号与家清单快照：运行期冷却走 RouteState，不依赖账号表的实时变化。
        val views = store.routingViews()
        val providers = store.providers()

        val session = RouterSession(
            candidates = chain.candidates,
            accounts = { views },
            state = state,
            // 带图请求要先按能力位过滤（文档 13 的验收项，也是 #408 一次白撞 9 次的原因）。
            needsImages = request.messages.any { it.images.isNotEmpty() },
            budget = budget,
            policy = policy(),
        )

        // token 预算：每次请求前取一次窗口内的用量推给会话。
        //
        // 放在这里而不是构造时：用量每轮都在变，缓存的数字会让预算「晚一步」生效。
        val tokenBudget = policy().toTokenBudget()
        if (tokenBudget.enabled) {
            val windowStart = tokenBudget.windowStartMillis(System.currentTimeMillis())
            session.setBudget(tokenBudget, accountSpend(windowStart))
        }

        val startedAt = System.currentTimeMillis()
        var attempts = 0
        var lastUsage: AiStreamEvent.Usage? = null
        var lastAttempt: PlannedAttempt? = null

        // 成功/失败都记一条；失败按文档 §5.7 清零 token（规则在 core 的 GatewayUsageRules）。
        suspend fun record(success: Boolean, status: Int, error: String?) {
            val draft = dev.praxis.core.gateway.GatewayUsageDraft(
                model = request.model,
                upstreamModel = lastAttempt?.wireModel,
                providerId = lastAttempt?.provider,
                accountId = lastAttempt?.accountId,
                status = status,
                error = error,
                attempts = attempts,
                promptTokens = lastUsage?.promptTokens ?: 0,
                completionTokens = lastUsage?.completionTokens ?: 0,
                cacheReadTokens = lastUsage?.cacheReadTokens ?: 0,
                durationMs = System.currentTimeMillis() - startedAt,
            )
            recordUsage(dev.praxis.core.gateway.GatewayUsageRules.build(draft, success))
        }

        var emitted = false
        try {
        while (true) {
            val attempt = session.next() ?: break
            lastAttempt = attempt
            attempts += 1
            val view = views.firstOrNull { it.id == attempt.accountId } ?: continue
            val credential = store.credentialOf(attempt.accountId).getOrNull() ?: continue
            val apiKey = (credential as? GatewayCredential.ApiKey)?.apiKey ?: continue
            // 地址优先级：账号级覆盖 > 自定义家表 > 内置家清单。
            val baseUrl = view.baseUrlOverride
                ?: providers.firstOrNull { it.id == attempt.provider }?.baseUrl
                ?: providerBaseUrl(attempt.provider)
                ?: continue

            // 按**这一家**的协议选 provider（文档 §5.1 的四类入口）。
            //
            // 在换号的循环里判定：一次请求的候选可能跨协议（比如同时配了
            // 官方 Anthropic 与某个 OpenAI 兼容中转），所以不能在外面算一次。
            val protocol = dev.praxis.core.gateway.ProviderProtocols.of(attempt.provider)
            val provider: dev.praxis.core.ai.AiProvider = when (protocol) {
                dev.praxis.core.gateway.ProviderProtocol.AnthropicMessages ->
                    dev.praxis.data.ai.AnthropicProvider(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        customHeaders = customHeaders,
                        client = client,
                    )
                dev.praxis.core.gateway.ProviderProtocol.Responses ->
                    dev.praxis.data.ai.ResponsesProvider(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        customHeaders = customHeaders,
                        client = client,
                    )
                dev.praxis.core.gateway.ProviderProtocol.OpenAiChat ->
                    OpenAiCompatProvider(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        customHeaders = customHeaders,
                        client = client,
                    )
            }

            try {
                provider.stream(request.copy(model = attempt.wireModel)).collect { event ->
                    emitted = true
                    // 用量事件记下来（结尾一起落库），同时照常下发给上层。
                    if (event is AiStreamEvent.Usage) lastUsage = event
                    emit(event)
                }
                session.onSuccess(attempt)
                record(success = true, status = dev.praxis.core.gateway.GatewayUsageRules.STATUS_OK, error = null)
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
        } catch (e: AiException) {
            // 用户主动取消不算失败，不入记录（否则报表里会出现一堆「失败」，
            // 而用户只是换了句话）。其余的失败都记一条，token 由规则清零。
            if (e.error.kind != AiErrorKind.CANCELLED) {
                record(
                    success = false,
                    status = dev.praxis.core.gateway.GatewayUsageRules.STATUS_NO_RESPONSE,
                    error = e.error.message,
                )
            }
            throw e
        }
    }
}
