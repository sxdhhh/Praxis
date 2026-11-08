package dev.praxis.data.ai

import dev.praxis.core.ai.AiError
import dev.praxis.core.ai.AiErrorKind
import dev.praxis.core.ai.AiException
import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatRequest
import dev.praxis.core.ai.RequestThrottle
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.FunctionSlot
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
    /**
     * 网关出站。当前模型档案 kind = Gateway 时，整条请求转给它
     * （文档 14.3：「GatewayProvider 接在 SettingsBackedProvider 之后」）。
     *
     * 可空：没有装配网关时（例如某些测试或裁剪构建）不静默降级到单账号路径 ——
     * 那种「悄悄用别的账号发出去」的行为比直接报错危险得多。
     */
    private val gatewayProvider: AiProvider? = null,
) : AiProvider {

    override val displayName: String = "设置配置的 OpenAI 兼容服务"

    override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
        // ── 网关档案：交给路由层 ──
        //
        // 判定用**当前档案**的 kind，而不是别的开关：用户把模型档案切到「网关」
        // 就是「这条链路走网关」的唯一意思。
        val profileKind = repository.modelProfiles.first().active?.kind
            ?: dev.praxis.core.model.settings.ModelProfileKind.Single

        // ── 总开关（文档 §11 末尾的回滚闸）──
        //
        // 判定收在 core:gateway 的 GatewayGate（四种组合有单测），这里只把结果变成行为。
        val route = dev.praxis.core.gateway.GatewayGate.decide(
            profileIsGateway = profileKind == dev.praxis.core.model.settings.ModelProfileKind.Gateway,
            gatewayEnabled = repository.gatewayEnabled.first(),
        )

        if (route == dev.praxis.core.gateway.OutboundRoute.GatewayDisabled) {
            // 刻意**不**回落到单配置路径：网关档案上没有 baseUrl / apiKey（它们属于账号池），
            // 回落等于拿空地址发请求；更糟的是将来若加了「保底账号」，
            // 用户以为只是停用网关，请求却被悄悄从别的账号发了出去。
            throw AiException(
                AiError(
                    kind = AiErrorKind.INVALID_REQUEST,
                    message = "网关总开关已关闭。请在「设置 → 网关」里重新打开，或把模型档案切回单配置。",
                ),
            )
        }

        if (route == dev.praxis.core.gateway.OutboundRoute.Gateway) {
            val gateway = gatewayProvider
                ?: throw AiException(
                    AiError(
                        kind = AiErrorKind.INVALID_REQUEST,
                        message = "模型档案是「网关」，但网关没有装配（构建配置问题）",
                    ),
                )
            gateway.stream(request).collect { emit(it) }
            return@flow
        }

        val settings = repository.modelSettings.first()
        // ## CHAT 功能位的覆盖必须生效（2026-10-29 修）
        //
        // 这里原先是直接读 `settings`（全局），于是「功能模型」页里
        // 给「对话」那一项配的模型与地址**存进去了但从来没用过** ——
        // `FunctionSlot.CHAT` 在设置页会遍历展示、能编辑、能保存，
        // 但全仓没有任何地方 `resolve(CHAT)`。用户改了主对话模型没反应。
        //
        // 现在按 Operit 的 `setConfigForFunction(FunctionType.CHAT, ...)` 语义，
        // 主对话也走功能位解析：有覆盖用覆盖，没覆盖逐字段回落全局。
        //
        // 为什么是「逐字段」而不是「整块替换」：
        // `resolve()` 内部已经处理了回落（`o.model ?: global.activeModel`、
        // `o.baseUrl ?: global.baseUrl`），所以直接用它的返回值即可；
        // 但 `modelIndex` 的语义需要额外想清楚 —— 覆盖时它指向的是
        // 覆盖模型（单值，不存在池子），所以要用 resolve 后的 model。
        val chatOverride = repository.functionModelSettings.first()
            .resolve(FunctionSlot.CHAT, settings)
        // 进阶配置（自定义请求头 + 请求队列）。两件事都要在**发请求前**取到。
        val advanced = repository.modelAdvancedSettings.first()
        val apiKey = repository.apiKey()

        // 未配置时给出**可操作**的错误，而不是发一个注定 401 的请求。
        //
        // ## 判据必须看 resolve 之后的值（2026-10-29）
        //
        // `settings.isConfigured` 只看全局的 `baseUrl` + `activeModel`。
        // 若用户只给「对话」功能位填了地址与模型（全局留空），
        // 旧判据会在这里拦下来、报「模型未配置」—— 明明配好了却发不出去。
        // 所以把 resolve 的结果当作判断依据。
        if (chatOverride.baseUrl.isBlank() || chatOverride.model.isBlank()) {
            throw AiException(
                AiError(
                    kind = AiErrorKind.INVALID_REQUEST,
                    message = "模型未配置：请到 设置 → 模型 填写接口地址与模型 ID。",
                ),
            )
        }

        // ── 请求节流（对齐 Operit 的 RateLimitedAIService）───────────
        //
        // 位置很关键：必须在**建请求之前**等到名额，否则并发/限流就只是
        // 记账而不是控制。
        //
        // ## 节流器为什么是「按配置值缓存」而不是每次新建
        //
        // 限流窗口与并发闸门必须跨请求存活，否则「每分钟 10 次」就变成
        // 「每个请求各自 10 次」—— 完全失效。所以不能每次新建。
        //
        // 但也不能写成单例常量：用户把 10 改成 30 之后必须立刻生效，
        // 固定实例要等进程重启。
        //
        // 折中：按 `(rpm, concurrency)` 缓存（见 [throttleFor]）——
        // 值不变就复用同一实例（窗口连续），值一变就换新实例（立即生效）。
        val throttle = throttleFor(advanced.requestLimitPerMinute, advanced.maxConcurrentRequests)
        val permit = if (throttle.isNoOp) null else throttle.acquire()
        try {
            val delegate = OpenAiCompatProvider(
            // ## 必须用 resolve 后的 baseUrl（2026-10-29）
            //
            // 功能模型页允许给「对话」单独填地址（比如主模型走中转站、
            // 对话走官方端点）。如果这里仍读 `settings.baseUrl`，
            // 那个字段就是**存了但没用**的假控件 —— 用户改完地址毫无变化。
            //
            // `resolve` 已处理回落：未覆盖时它就是 `global.baseUrl`。
            baseUrl = chatOverride.baseUrl,
            apiKey = apiKey,
            // 自定义请求头：很多中转站靠这个标识账号/渠道，不传直接 401。
            customHeaders = advanced.customHeaders,
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
                        // 覆盖生效时用覆盖的模型（单值）；未覆盖时 resolve 已回落
                        // 到 `global.activeModel`（池子里当前选中的那个）。
                        model = chatOverride.model,
                        temperature = chatOverride.temperature,
                    ),
                ),
            )
        } finally {
            // 一定要在 finally 里还名额：emitAll 抛异常（网络错误、用户取消、
            // 上游 4xx）时若不还，并发名额就永久泄漏，几次之后彻底卡死。
            permit?.close()
        }
    }

    companion object {
        /**
         * 节流器缓存。
         *
         * ## 为什么按配置值缓存而不是单例一个
         *
         * 用户在设置页把「每分钟 10 次」改成 30，必须**立刻**生效（下次请求
         * 就用新额度）。若固定一个实例，改动要等进程重启。
         *
         * 反过来也不能每次都新建 —— 新建等于窗口清零，「限流」形同虚设。
         *
         * 按 `(rpm, concurrency)` 作为 key 缓存：值不变就复用（窗口连续），
         * 值一变就换新实例（立即生效）。旧实例被丢弃时它内部的计数随之消失，
         * 这正是「换了配置就该重新开始计」的语义。
         */
        private val throttles = java.util.concurrent.ConcurrentHashMap<String, RequestThrottle>()

        /**
         * 取当前配置对应的节流器。
         *
         * ## 为什么只保留**一份**（换配置时清掉旧的）
         *
         * 如果按 key 累积缓存，用户「10 → 30 → 10」来回改三次之后，
         * 第三次会拿到**最初那个**实例 —— 它内部的滑动窗口还停在上一次的
         * 时间戳上，实际额度比用户看到的配置更紧（或者更松）。
         *
         * 那是个极难查的 bug：用户改回 10 次/分钟，却发现自己只能发 3 次
         * 就卡住了，因为窗口里还残留着改配置之前的记录。
         *
         * 正确语义是「配置一变就重新开始计」，所以这里同时候只留一份：
         * 新配置进来时把其它配置的实例全部丢掉。
         */
        internal fun throttleFor(rpm: Int, concurrency: Int): RequestThrottle {
            if (rpm <= 0 && concurrency <= 0) return NO_OP_THROTTLE
            val key = "$rpm|$concurrency"
            // 已经只缓存这一份时直接复用（窗口连续）。
            val existing = throttles[key]
            if (existing != null && throttles.size == 1) return existing
            // 配置变了：清掉所有旧实例，只留新的 —— 等价于「重新开始计」。
            val fresh = RequestThrottle(rpm, concurrency)
            throttles.clear()
            throttles[key] = fresh
            return fresh
        }

        /** 两个限制都未配时的共享空实例（不持有任何状态）。 */
        private val NO_OP_THROTTLE = RequestThrottle(0, 0)

        /** 仅供单测：清掉缓存，避免测试间互相影响。 */
        internal fun resetThrottleCacheForTest() = throttles.clear()
    }
}
