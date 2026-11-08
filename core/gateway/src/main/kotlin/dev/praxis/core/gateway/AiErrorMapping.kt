package dev.praxis.core.gateway

import dev.praxis.core.ai.AiErrorKind

/**
 * AI 层的错误分类 -> 文档 7.4 的失败分类。
 *
 * ## 为什么放在这里而不是 :app
 *
 * 这张表决定「换不换号、罚不罚号」，是最该被单测钉住的东西。放在 :app 里
 * 就只能靠整包编译来验证；放在领域层可以逐行验（文档 10 也允许 core:gateway
 * 依赖 core:ai）。
 */
fun AiErrorKind.toFailureKind(): FailureKind = when (this) {
    // 429 且正文表明额度耗尽：要等上游重置。
    AiErrorKind.QUOTA_LIMITED -> FailureKind.QuotaLimited
    // 429 但只是速率限制：同样归到额度受限（冷却时长由上游给的 Retry-After 决定）。
    AiErrorKind.RATE_LIMIT -> FailureKind.QuotaLimited

    // 令牌过期可以续期一次；密钥错则只能让用户重填 —— 但两者在 7.4 里都走
    // 「先续期、仍失败再换号」这条路，所以映射到同一个动作。
    AiErrorKind.TOKEN_EXPIRED -> FailureKind.CredentialExpired
    AiErrorKind.AUTH -> FailureKind.CredentialExpired

    // 内容拦截不罚账号，换家。
    AiErrorKind.CONTENT_BLOCKED -> FailureKind.ContentBlocked

    // 请求本身不合法（400）：不换号、不重试。
    AiErrorKind.INVALID_REQUEST -> FailureKind.InvalidRequest

    // 其余（网络、5xx、流中断、协议形态不对、未知）都按上游错误处理：
    // 退避 + 换号。协议形态不对多半是对面不是 OpenAI 兼容端点，换一家仍是
    // 同样的错，但这一步的处置与上游错误一致，区别在于理由文案。
    AiErrorKind.NETWORK,
    AiErrorKind.SERVER,
    AiErrorKind.STREAM_INTERRUPTED,
    AiErrorKind.PROTOCOL,
    AiErrorKind.UNKNOWN,
    -> FailureKind.UpstreamError

    // 调用方在拿到 CANCELLED 时应当原样抛出，不该走到这里。
    AiErrorKind.CANCELLED -> FailureKind.UpstreamError
}
