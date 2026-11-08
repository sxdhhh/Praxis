package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一个账号的凭证形态。
 *
 * 两种都在文档 6.1 的 credentialsCipher 注释里出现：
 * { apiKey } 与 { accessToken, refreshToken }。
 *
 * ## 为什么是密封接口而不是几个可空字段
 *
 * 「API Key 型」与「OAuth 型」的失败处理完全不同（前者 401 就是 key 坏了，
 * 后者可以续期一次）。用一个带三个可空字段的类，会写出「apiKey 为空的账号
 * 到底算哪种」这种没人答得上来的分支。
 */
sealed interface GatewayCredential {

    /** 长期 API Key。P1 只落这一种（OAuth 在导入时已被跳过）。 */
    data class ApiKey(val apiKey: String) : GatewayCredential

    /** OAuth 令牌。P2 之后才用得上，先建模为了不让存储格式改两次。 */
    data class OAuth(
        val accessToken: String,
        val refreshToken: String? = null,
    ) : GatewayCredential
}

/**
 * 凭证 JSON 的编解码。
 *
 * 这一层**只做序列化**：加密是存储层的事（KeystoreCipher）。分开之后
 * 编解码可以纯单测，加密则必须上设备。
 */
object GatewayCredentialCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(credential: GatewayCredential): String {
        val obj = buildJsonObject {
            when (credential) {
                is GatewayCredential.ApiKey -> put(KEY_API_KEY, credential.apiKey)
                is GatewayCredential.OAuth -> {
                    put(KEY_ACCESS_TOKEN, credential.accessToken)
                    credential.refreshToken?.let { put(KEY_REFRESH_TOKEN, it) }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * @return 失败时带可直接展示的原因。**不返回空凭证** —— 空凭证会被下游当成
     * 「有凭证但内容是空」，而它真正的含义是「这条数据坏了」。
     */
    fun decode(text: String): Result<GatewayCredential> = runCatching {
        val obj = json.parseToJsonElement(text) as? JsonObject
            ?: error("凭证不是 JSON 对象")

        val apiKey = obj.stringOrNull(KEY_API_KEY)
        if (!apiKey.isNullOrBlank()) return@runCatching GatewayCredential.ApiKey(apiKey)

        val accessToken = obj.stringOrNull(KEY_ACCESS_TOKEN)
        if (!accessToken.isNullOrBlank()) {
            return@runCatching GatewayCredential.OAuth(
                accessToken = accessToken,
                refreshToken = obj.stringOrNull(KEY_REFRESH_TOKEN),
            )
        }

        error("凭证里既没有 apiKey 也没有 accessToken")
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    const val KEY_API_KEY: String = "apiKey"
    const val KEY_ACCESS_TOKEN: String = "accessToken"
    const val KEY_REFRESH_TOKEN: String = "refreshToken"
}

/**
 * 账号文件 -> 凭证。
 *
 * 优先级照文档 6.1 的两种形态：有 apiKey 就是 API Key 型；否则有 accessToken
 * 就是 OAuth 型；两者都没有则这条文件本身有问题。
 *
 * 注意：P1 的导入只接受 API Key 型（OAuth 在 GatewayImporter 里已被跳过），
 * 所以这里对 OAuth 的支持是为 P2 之后准备的，不是当下就会走到的分支。
 */
fun GatewayAccountFile.toCredential(): Result<GatewayCredential> = runCatching {
    // 先取到局部变量：这三个字段是另一个模块里的可空属性，
    // 直接写 apiKey.isNotBlank() 会因为没有智能转换而编不过。
    val key = apiKey
    if (!key.isNullOrBlank()) {
        return@runCatching GatewayCredential.ApiKey(key)
    }
    val token = accessToken
    if (!token.isNullOrBlank()) {
        return@runCatching GatewayCredential.OAuth(
            accessToken = token,
            refreshToken = refreshToken,
        )
    }
    error("账号 " + id + " 没有任何凭证（apiKey 与 accessToken 都为空）")
}
