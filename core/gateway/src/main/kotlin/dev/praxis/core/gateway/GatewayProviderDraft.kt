package dev.praxis.core.gateway

/**
 * 新建 / 修改一个自定义提供商（文档 §5.3）。
 *
 * ## 与账号的关系
 *
 * 「提供商」是**地址与模型清单**（这家在哪、有什么模型），
 * 「账号」是**凭证**（用哪把 key 访问它）。两者分开：同一家可以有多个账号，
 * 而换地址不该动凭证。
 */
data class GatewayProviderDraft(
    /** 家的标识。它是账号表的 `providerId`，也是协议分派的依据（见下）。 */
    val id: String = "",
    /** 显示名；留空则用 [id]。 */
    val name: String = "",
    val baseUrl: String = "",
    /** 这家声明的模型名（每行一个）。 */
    val models: List<String> = emptyList(),
)

/***/
enum class GatewayProviderDraftError {
    MissingId,
    IdWithWhitespace,
    IdCollidesWithBuiltin,
    MissingBaseUrl,
    BadBaseUrlScheme,
}

/** 自定义提供商的校验与归一化（纯函数）。 */
object GatewayProviderDraftRules {

    /** 与内置家重名的 id。 */
    private val BUILTIN_IDS = setOf("anthropic", "openai")

    fun validate(draft: GatewayProviderDraft): GatewayProviderDraftError? {
        val normalized = normalize(draft)
        if (normalized.id.isEmpty()) return GatewayProviderDraftError.MissingId
        if (normalized.id.any { it.isWhitespace() }) {
            return GatewayProviderDraftError.IdWithWhitespace
        }
        // 内置地址优先（见 ProviderProtocols 与 BuiltinProviders），所以与内置家重名的
        // 自定义家**永远不会被用到**。这种情况必须当场拒绝：否则用户配完发现不生效，
        // 而界面上一片正常。
        if (normalized.id.lowercase() in BUILTIN_IDS) {
            return GatewayProviderDraftError.IdCollidesWithBuiltin
        }
        if (normalized.baseUrl.isEmpty()) return GatewayProviderDraftError.MissingBaseUrl
        if (!GatewayAccountDraftRules.isHttpUrl(normalized.baseUrl)) {
            return GatewayProviderDraftError.BadBaseUrlScheme
        }
        return null
    }

    /** 去空白、去地址尾部斜杠、名字留空用 id、模型名去空行。 */
    fun normalize(draft: GatewayProviderDraft): GatewayProviderDraft {
        val id = draft.id.trim()
        var baseUrl = draft.baseUrl.trim()
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.dropLast(1)
        return GatewayProviderDraft(
            id = id,
            name = draft.name.trim().ifEmpty { id },
            baseUrl = baseUrl,
            // 空行、纯空白不算模型名（与 GatewayAccountStore.nativeModels 同口径，
            // 否则清单里会出现一个「名为空串的模型」，选中它必然是 404）。
            models = draft.models.map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
}

/** 校验失败的人话。 */
fun GatewayProviderDraftError.userMessage(): String = when (this) {
    GatewayProviderDraftError.MissingId -> "要给这家起个标识（账号会引用它）"
    GatewayProviderDraftError.IdWithWhitespace -> "标识里不能有空格"
    GatewayProviderDraftError.IdCollidesWithBuiltin ->
        "这个标识与内置家重名：内置地址优先，你的自定义不会被用到 —— 换个名字"
    GatewayProviderDraftError.MissingBaseUrl -> "要填地址"
    GatewayProviderDraftError.BadBaseUrlScheme -> "地址要以 http:// 或 https:// 开头"
}
