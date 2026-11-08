package dev.praxis.core.gateway

/**
 * 「手动添加一个账号」的草稿（文档 §9.3 的三级流程）。
 *
 * ## 为什么单独一个类型，而不是直接传一堆字符串
 *
 * 校验、归一化、落库三步都要同一份字段；散着传迟早出现「UI 校验的是 trim 后的值、
 * 落库写的是原始值」这种不一致 —— 用户看到的是「明明填对了却说格式不对」。
 */
data class GatewayAccountDraft(
    /** 内置家 id 或 custom-xxxxxxxx。 */
    val providerId: String,
    /** 显示名；留空时用 providerId 顶上。 */
    val name: String = "",
    /** 账号级地址覆盖；空 = 跟随提供商。 */
    val baseUrl: String = "",
    val apiKey: String = "",
)

/** 草稿不合法时的原因。每一条都对应一句能指导用户的话。 */
enum class GatewayAccountDraftError {
    /** 没选渠道。 */
    MissingProvider,
    /** 自定义渠道必须给地址（内置家有默认地址）。 */
    MissingBaseUrl,
    /** 地址不是 http/https —— 只填了域名是常见的少写。 */
    BadBaseUrlScheme,
    /** 没填 Key。 */
    MissingApiKey,
}

/**
 * 草稿的校验与归一化（纯函数，文档 §9.3）。
 *
 * ## 校验放在 core 而不是页面
 *
 * 页面只该负责「把错误显示出来」。把规则写在组合函数里，就没法单测
 * （`:feature:*` 在本机编不了），只能靠手点 —— 而「地址少写 http://」这种
 * 恰恰是最容易漏的输入。
 */
object GatewayAccountDraftRules {

    /** 内置家有没有默认地址：现在没有（要等 P5 的内置家清单）。 */
    fun hasDefaultBaseUrl(providerId: String): Boolean = false

    fun validate(draft: GatewayAccountDraft): GatewayAccountDraftError? {
        val normalized = normalize(draft)
        if (normalized.providerId.isEmpty()) return GatewayAccountDraftError.MissingProvider
        if (normalized.baseUrl.isEmpty()) return GatewayAccountDraftError.MissingBaseUrl
        if (!isHttpUrl(normalized.baseUrl)) return GatewayAccountDraftError.BadBaseUrlScheme
        if (normalized.apiKey.isEmpty()) return GatewayAccountDraftError.MissingApiKey
        return null
    }

    /**
     * 去掉首尾空白、去掉地址尾部斜杠、名字留空时用 providerId。
     *
     * 去尾部斜杠是必须的：`https://x.com/` 与 `https://x.com` 拼出来的端点不同
     * （前者会变成 `//v1/models`），而服务器对这两种的处理并不一致。
     */
    fun normalize(draft: GatewayAccountDraft): GatewayAccountDraft {
        val provider = draft.providerId.trim()
        var baseUrl = draft.baseUrl.trim()
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.dropLast(1)
        }
        val name = draft.name.trim().ifEmpty { provider }
        return GatewayAccountDraft(
            providerId = provider,
            name = name,
            baseUrl = baseUrl,
            apiKey = draft.apiKey.trim(),
        )
    }

    /** 只接受 http/https：ws:// 之类在这里就是错的。 */
    fun isHttpUrl(value: String): Boolean {
        val lower = value.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        // 协议之后必须还有内容（"https://" 单独一个不算地址）。
        return lower.removePrefix("https://").removePrefix("http://").isNotEmpty()
    }
}

/** 错误码 -> 给用户看的一句话（UI 与存储层共用，避免两处措辞不一致）。 */
fun GatewayAccountDraftError.userMessage(): String = when (this) {
    GatewayAccountDraftError.MissingProvider -> "先选一个渠道"
    GatewayAccountDraftError.MissingBaseUrl -> "填上服务地址，例如 https://api.openai.com/v1"
    GatewayAccountDraftError.BadBaseUrlScheme -> "地址要以 http:// 或 https:// 开头"
    GatewayAccountDraftError.MissingApiKey -> "填上 API Key"
}
