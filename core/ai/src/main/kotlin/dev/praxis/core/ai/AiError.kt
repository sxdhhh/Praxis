package dev.praxis.core.ai

/**
 * 统一错误模型。
 *
 * Phase 1 只有会话列表，还没有真实请求；这里先把「上游错误如何归类」这事儿定死，
 * 避免后续各 provider 各写一套 catch。判定顺序按「重试价值」排：
 * RATE_LIMIT / SERVER / NETWORK 可重试，AUTH / INVALID_REQUEST 只能改配置。
 *
 * [retryAfterMillis] 只在服务端明确给了 Retry-After 或配额重置时间时才有值，
 * 不要用「指数退避的估算值」冒充它。
 */
enum class AiErrorKind {
    /** 连接失败、DNS 失败、超时。 */
    NETWORK,

    /** 429 或明确的配额超限。 */
    RATE_LIMIT,

    /** 5xx。 */
    SERVER,

    /** 流在结束标记之前断了（半截 JSON、连接被对端关闭）。 */
    STREAM_INTERRUPTED,

    /** 401 / 403，密钥错误或权限不足。 */
    AUTH,
    /** 400，通常是消息体不合法（例如被截断的 argumentsJson）。 */
    INVALID_REQUEST,

    /**
     * 响应体本身不符合协议（不是合法 JSON、缺少 `choices`、流里既无
     * `delta` 也无 `message`）。
     *
     * ## 为什么与 [INVALID_REQUEST] 分开
     *
     * [INVALID_REQUEST] 是「**我发出去的东西**被服务端判为不合法」（HTTP 400），
     * 用户改配置就能解决；`PROTOCOL` 是「**服务端回来的东西**不合协议」——
     * 请求本身没错（HTTP 200），是网关/代理改写了响应体，或者对面根本不是
     * 一个 OpenAI 兼容端点。两者的处置方式完全不同：前者让用户去改模型配置，
     * 后者要用户去查 baseUrl / 代理。混成一个 kind 会把排查方向指错。
     */
    PROTOCOL,


    /**
     * 额度用尽（429 且正文表明配额耗尽，或正文带配额标记）。
     *
     * ## 与 [RATE_LIMIT] 分开的理由
     *
     * 两者都是 429，但用户该做的事完全不同：速率限制等一下就好；额度用尽要等
     * 上游重置（或换号）。混成一类，网关就会把「等十秒」和「等一天」当成同一件事。
     *
     * ## 谁产出它（当前未实现）
     *
     * 需要看响应正文里的配额标记。这个判定点还没写，所以现在不会出现这个 kind；
     * 加它进来是为了让判定落地时路由层的行为已经是对的（见 7.4 第 1 行）。
     */
    QUOTA_LIMITED,

    /**
     * 令牌过期（401 且正文说明令牌失效，而不是密钥写错）。
     *
     * 与 [AUTH] 分开：令牌过期可以**尝试续期一次**再换号，密钥错只能让用户重填。
     *
     * 同样：正文判定点尚未实现，现在不会产出。
     */
    TOKEN_EXPIRED,

    /**
     * 内容拦截：HTTP 200，但正文是拒绝标记，或 finish_reason 命中。
     *
     * 这类失败**不罚账号**——换哪个号都会被拦（文档 7.4 第 3 行）。
     *
     * 同样：判定点尚未实现（需要检查正文与 finish_reason），现在不会产出。
     */
    CONTENT_BLOCKED,

    /** 用户主动取消（协程被 cancel）。 */
    CANCELLED,

    UNKNOWN,
}

/**
 * 一次 AI 调用的失败描述。
 *
 * [message] 面向日志与开发者，不要直接当用户可见文案抛出去；
 * UI 层按 [kind] 决定展示哪一句固定文案。
 */
data class AiError(
    val kind: AiErrorKind,
    val message: String,
    val retryAfterMillis: Long? = null,
    val cause: Throwable? = null,
) {
    /** 这一条是否值得自动重试。 */
    val retryable: Boolean
        get() = when (kind) {
            AiErrorKind.NETWORK, AiErrorKind.SERVER,
            AiErrorKind.RATE_LIMIT, AiErrorKind.STREAM_INTERRUPTED -> true

            AiErrorKind.AUTH, AiErrorKind.INVALID_REQUEST,
            AiErrorKind.CANCELLED, AiErrorKind.UNKNOWN,
            // 服务端回来的响应体形态不对，原样重试只会拿到同样的形态。
            AiErrorKind.PROTOCOL,
            // 额度用尽要等上游重置，原样重试只会再撞一次 429。
            AiErrorKind.QUOTA_LIMITED,
            // 令牌过期要续期（或由用户重填），重试没有意义。
            AiErrorKind.TOKEN_EXPIRED,
            // 内容拦截换谁都会被拦，重试同样没用。
            AiErrorKind.CONTENT_BLOCKED -> false
        }

    companion object {

        /** 按 HTTP 状态码归类。4xx 里只有 429 值得重试。 */
        fun fromHttpCode(code: Int, message: String, retryAfterMillis: Long? = null): AiError {
            val kind = when {
                code == 401 || code == 403 -> AiErrorKind.AUTH
                code == 429 -> AiErrorKind.RATE_LIMIT
                code == 408 -> AiErrorKind.NETWORK
                code in 500..599 -> AiErrorKind.SERVER
                code in 400..499 -> AiErrorKind.INVALID_REQUEST
                else -> AiErrorKind.UNKNOWN
            }
            return AiError(kind = kind, message = message, retryAfterMillis = retryAfterMillis)
        }
    }
}
