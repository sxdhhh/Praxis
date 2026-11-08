package dev.praxis.core.gateway

import kotlinx.serialization.Serializable

/** 网关账号文件的 format 值。 */
const val ACCOUNT_FORMAT: String = "agent2api-account"

/**
 * 网关账号文件（一个账号一个 JSON）。
 *
 * 字段名逐字对应网关导出，见 docs/PORTING-GATEWAY.md §8.2。
 * 解析用 `ignoreUnknownKeys`：网关会往里加 `desktop` / `pluginSecret` 这类
 * 与手机端无关的键，遇到未知键就报错会让导入平白失败。
 */
@Serializable
data class GatewayAccountFile(
    val format: String = "",
    val id: String = "",
    val provider: String = "",
    val name: String = "",
    /** API Key 型凭证。与 accessToken / refreshToken 三选一。 */
    val apiKey: String? = null,
    /** 账号级地址覆盖；空 = 跟随提供商。 */
    val baseUrl: String? = null,
    /** OAuth 型凭证。一期不导入（见 §8.6）。 */
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** 自定义家随账号带出。 */
    val customProviders: List<GatewayCustomProvider> = emptyList(),
)

/** 自定义提供商（网关 `custom-*` 家）。一期只解析并计数，不落库。 */
@Serializable
data class GatewayCustomProvider(
    val id: String = "",
    val name: String = "",
    val baseUrl: String? = null,
    val models: List<String> = emptyList(),
)
