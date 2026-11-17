package dev.praxis.core.gateway.adapters

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Agent2API 网关的连接设置。
 *
 * ## 为什么必须可配置（这是真机上的硬阻塞）
 *
 * `127.0.0.1` 在**手机上指手机自己**，而网关通常跑在另一台机器上（PC 或服务器）。
 * 硬编码地址的结果是「永远连不上」，而且用户从界面上看不出原因。
 *
 * ## 三个字段各自的作用
 *
 * - `baseUrl`：网关根部地址。手机连 PC 用 `http://192.168.x.x:3065`，
 *   连远端用 `https://域名/a2a`（若经 nginx 反代到 `/a2a/v1`，则填到 `/a2a`）。
 * - `apiKey`：网关 Key，用于 `/v1 下的全部` 调用（对话、模型）。留空 = fail-closed 时会被拒。
 * - `adminUser` / `adminPass`：面板管理员凭据，用于管理 API（登录助手家、签到、积分）。
 *   这两项是可选的：只聊天的话不需要它们。
 */
data class GatewayConnectionSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val adminUser: String = "",
    val adminPass: String = "",
) {
    /** 能不能连（地址是 http(s)）。 */
    val isConfigured: Boolean get() = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")

    /** 能不能用管理 API（面板登录）。 */
    val hasAdminCredentials: Boolean get() = adminUser.isNotBlank() && adminPass.isNotBlank()
}

/** 连接设置的 JSON 编解码（字段名写死，改类名不影响已存的配置）。 */
object GatewayConnectionCodec {

    private const val KEY_URL = "url"
    private const val KEY_API_KEY = "apiKey"
    private const val KEY_ADMIN_USER = "adminUser"
    private const val KEY_ADMIN_PASS = "adminPass"

    fun encode(settings: GatewayConnectionSettings): String = buildJsonObject {
        put(KEY_URL, settings.baseUrl.trim())
        if (settings.apiKey.isNotBlank()) put(KEY_API_KEY, settings.apiKey)
        if (settings.adminUser.isNotBlank()) put(KEY_ADMIN_USER, settings.adminUser)
        if (settings.adminPass.isNotBlank()) put(KEY_ADMIN_PASS, settings.adminPass)
    }.toString()

    /**
     * 解码。null = 从没配过；failure = 配过但坏了（两者必须分开 ——
     * 与 ImageGenTargetCodec 同一口径：坏配置不能让用户以为「没配过」）。
     */
    fun decode(text: String?): Result<GatewayConnectionSettings>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            GatewayConnectionSettings(
                baseUrl = root.str(KEY_URL).orEmpty(),
                apiKey = root.str(KEY_API_KEY).orEmpty(),
                adminUser = root.str(KEY_ADMIN_USER).orEmpty(),
                adminPass = root.str(KEY_ADMIN_PASS).orEmpty(),
            )
        }
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
}

/**
 * 常见地址的规范化与提示。
 *
 * 用户在手机上填地址最容易犯的两个错：写 `localhost`/`127.0.0.1`（指手机自己）、
 * 或者漏了 `http://`。这里不静默改写（那会让用户以为填对了），而是给出具体提示。
 */
object GatewayAddressRules {

    /** @return null = 可用；非 null = 给用户看的原因。 */
    fun check(baseUrl: String): String? {
        val url = baseUrl.trim()
        if (url.isEmpty()) return "先填网关地址"
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "地址要以 http:// 或 https:// 开头"
        }
        val host = url.removePrefix("http://").removePrefix("https://")
            .substringBefore(':').substringBefore('/')
        if (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) {
            return "127.0.0.1 / localhost 在手机上指手机自己；请填 PC 的局域网地址（如 192.168.1.80）或公网地址"
        }
        return null
    }

    /** 去掉末尾斜杠（拼路径时不再出现双斜杠）。 */
    fun normalize(baseUrl: String): String = baseUrl.trim().trimEnd('/')
}