package dev.praxis.data.imagegen

import dev.praxis.core.ai.AiErrorKind
import dev.praxis.core.data.gateway.GatewayAccountStore
import dev.praxis.core.gateway.GatewayCredential
import dev.praxis.core.gateway.GatewayRoutingPolicy
import dev.praxis.core.gateway.ModelCandidate
import dev.praxis.core.gateway.RoutePlanner
import dev.praxis.core.gateway.RouteState
import dev.praxis.core.gateway.toFailureKind

/**
 * [ImageGatewayAccounts] 的实现：复用文本请求那套选号与运行态。
 *
 * ## 为什么不用 RouterSession
 *
 * `RouterSession` 是**带换号循环**的状态机（它管「试下一个」）。
 * 生图这条路只需要「选一个」，换号交给上层决定（现在不换：一次画图失败就报错，
 * 因为远端生图可能已经扣了额度）。所以这里直接用 [RoutePlanner] ——
 * 它是纯函数，不持有任何状态，选完就走。
 */
class GatewayImageAccountPool(
    private val store: GatewayAccountStore,
    private val state: RouteState,
    /** 每次现取：用户在设置页改了策略，下一张图就该按新策略选号。 */
    private val policy: () -> GatewayRoutingPolicy = { GatewayRoutingPolicy() },
    /** 内置家/自定义家的地址（与 GatewayProvider 同一套解析顺序）。 */
    private val providerBaseUrl: (String) -> String? = { null },
) : ImageGatewayAccounts {

    override suspend fun pick(provider: String, model: String): ImageGatewayAccount? {
        val views = store.routingViews()
        if (views.isEmpty()) return null
        val plan = RoutePlanner.plan(
            candidates = listOf(ModelCandidate(provider = provider, wireModel = model)),
            accounts = views,
            state = state,
            // 生图不带图（没有「输入图」这个概念），所以能力位不参与过滤。
            needsImages = false,
            stickyAccountId = null,
            policy = policy(),
        )
        val attempt = plan.attempts.firstOrNull() ?: return null
        val view = views.firstOrNull { it.id == attempt.accountId } ?: return null
        val credential = store.credentialOf(attempt.accountId).getOrNull()
            as? GatewayCredential.ApiKey ?: return null
        // 地址优先级与文本请求一致：账号级覆盖 > 家表 > 内置/自定义家表。
        val baseUrl = view.baseUrlOverride
            ?: store.providers().firstOrNull { it.id == provider }?.baseUrl
            ?: providerBaseUrl(provider)
            ?: return null
        return ImageGatewayAccount(
            accountId = attempt.accountId,
            baseUrl = baseUrl,
            apiKey = credential.apiKey,
        )
    }

    override suspend fun report(accountId: String, success: Boolean, kind: AiErrorKind?) {
        if (success) {
            state.onSuccess(accountId)
            return
        }
        state.onFailure(accountId, (kind ?: AiErrorKind.NETWORK).toFailureKind())
    }
}
