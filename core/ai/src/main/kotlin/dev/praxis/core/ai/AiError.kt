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
            AiErrorKind.PROTOCOL -> false
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
