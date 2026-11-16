package dev.praxis.core.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 网关「导出全部账号」的**真实形状**（整包一份）。
 *
 * ## 为什么必须单独有这个类型
 *
 * 实测一份真实导出（2026-11，95 个账号）的顶层键只有四个：
 * `version` / `exportedAt` / `accounts` / `customProviders`。
 * `GatewayAccountFile` 是「一个账号一个文件」的旧形状（还带 `format: agent2api-account`），
 * 网关那边不认它 —— 也就是说在此之前，Praxis 的导出**回灌不进网关**，
 * 而导入虽然能读整包，却把大部分账号当成「没有凭证」跳过。
 *
 * 现在两边都用这一份形状：读出什么形状、就按什么形状写回去。
 */
@Serializable
data class GatewayAccountsPack(
    val version: Int = 1,
    /** 导出时刻（毫秒）。网关用它做过期比较，缺了会让对方判成很久以前。 */
    val exportedAt: Long = 0,
    val accounts: List<PackAccount> = emptyList(),
    val customProviders: List<PackProvider> = emptyList(),
)

/**
 * 整包里的一个账号。
 *
 * 字段名逐字对应真实导出；只列 Praxis 会读会写的那部分，
 * 其余键（`credits` / `pluginLedger` / `checkinAt` …）由 `ignoreUnknownKeys` 放行，
 * 回写时**不凭空造**（造出来的余额只会误导）。
 */
@Serializable
data class PackAccount(
    val id: String = "",
    val provider: String = "",
    val name: String = "",
    val nickname: String = "",
    /** API Key 型凭证。 */
    val apiKey: String? = null,
    /** OAuth 型凭证（网关侧会自己续期；Praxis 目前只能原样保存/回写）。 */
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** 真实导出里账号级地址叫 endpoint；也有家把它写在 baseUrl。 */
    val endpoint: String? = null,
    val baseUrl: String? = null,
    /** 网关的排序位；Praxis 的路由优先级另有一套，导入后按顺序落。 */
    val priority: Int? = null,
    val enabled: Boolean? = null,
    /** 国内版 / 国际版等分站标记。 */
    val edition: String? = null,
    /** 账号级出口（代理 id）。Praxis 的代理绑定是另一套，导入时只记录。 */
    val proxy: String? = null,
    val addedAt: Long? = null,
    val updatedAt: Long? = null,
    /** 令牌过期时刻；只用于展示与「该重新登录了」的提示。 */
    val expiresAt: Long? = null,
) {
    /** 账号级地址：endpoint 优先（真实导出用的键）。 */
    val resolvedBaseUrl: String? get() = endpoint?.takeIf { it.isNotBlank() }
        ?: baseUrl?.takeIf { it.isNotBlank() }

    /** 凭证是否为空（三个都空 = 这条账号没有可用凭据）。 */
    val hasCredential: Boolean
        get() = !apiKey.isNullOrBlank() || !accessToken.isNullOrBlank() ||
            !refreshToken.isNullOrBlank()

    /** 是否 OAuth / 会话型（不是 API Key）。 */
    val isOauth: Boolean get() = apiKey.isNullOrBlank() && hasCredential
}

/** 整包里的自定义家。 */
@Serializable
data class PackProvider(
    val id: String = "",
    val name: String = "",
    val baseUrl: String? = null,
    /** 真实形状是对象数组：`{enabled,id,reasoning}`。 */
    val models: List<PackModel> = emptyList(),
)

/** 自定义家里的一个模型条目。 */
@Serializable
data class PackModel(
    val id: String = "",
    /** 关掉的模型不导入（与网关的「逐模型启停」同一语义）。 */
    val enabled: Boolean = true,
)

/**
 * 整包的读写。
 *
 * 编解码放在 core：形状是契约，能不能往返必须能在无设备环境下断言。
 */
object GatewayAccountsPackCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = false
    }

    /** 解析整包。失败时带可直接展示的原因。 */
    fun decode(text: String): Result<GatewayAccountsPack> = runCatching {
        val pack = json.decodeFromString(GatewayAccountsPack.serializer(), text.trim())
        require(pack.accounts.isNotEmpty()) { "这份文件里没有账号（accounts 为空）" }
        pack
    }

    /**
     * 写整包。
     *
     * 会剔掉完全没有凭据、也没有自定义家归属的空壳行 —— 网关回灌时会把它当坏账号。
     */
    fun encode(pack: GatewayAccountsPack): String {
        val cleaned = pack.copy(
            accounts = pack.accounts.filter { it.id.isNotBlank() && it.provider.isNotBlank() },
            customProviders = pack.customProviders
                .filter { it.id.isNotBlank() }
                .map { provider ->
                    provider.copy(models = provider.models.filter { it.id.isNotBlank() })
                },
        )
        return json.encodeToString(GatewayAccountsPack.serializer(), cleaned)
    }

    /** 由 Praxis 的账号行 + 自定义家组装整包（导出用）。 */
    fun build(
        accounts: List<GatewayAccountFile>,
        providers: List<GatewayCustomProvider> = emptyList(),
        exportedAt: Long,
        version: Int = 1,
    ): GatewayAccountsPack = GatewayAccountsPack(
        version = version,
        exportedAt = exportedAt,
        accounts = accounts.map { account ->
            PackAccount(
                id = account.id,
                provider = account.provider,
                name = account.name,
                apiKey = account.apiKey,
                accessToken = account.accessToken,
                refreshToken = account.refreshToken,
                endpoint = account.baseUrl,
                priority = null,
                enabled = true,
            )
        },
        customProviders = providers.map { provider ->
            PackProvider(
                id = provider.id,
                name = provider.name,
                baseUrl = provider.baseUrl,
                models = provider.models.map { PackModel(id = it, enabled = true) },
            )
        },
    )
}