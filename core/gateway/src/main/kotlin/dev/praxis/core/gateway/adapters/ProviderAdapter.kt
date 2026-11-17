package dev.praxis.core.gateway.adapters

import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatRequest
import kotlinx.coroutines.flow.Flow

/** 凭证（各家不同，但存储统一）。 */
data class ProviderCredentials(
    /** 登录方式标识（api_key / web_login / device_code / sms / token_import / cookie）。 */
    val method: String,
    /** 凭证内容（API Key / access token / refresh token / cookie 字符串）。 */
    val token: String,
    /** 刷新令牌（有则填，支持续期）。 */
    val refreshToken: String? = null,
    /** 过期时刻（epoch 毫秒）；null = 不知道。 */
    val expiresAt: Long? = null,
    /** 额外字段（edition / region / uid 等各家私有数据）。 */
    val extras: Map<String, String> = emptyMap(),
)

/** 模型信息（含能力位）。 */
data class ProviderModel(
    val id: String,
    val name: String = id,
    val supportsImages: Boolean? = null,
    val supportsReasoning: Boolean? = null,
    val supportsToolCall: Boolean? = null,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
)

/** 登录方式描述（给 UI 显示）。 */
data class AdapterLoginMethod(
    val type: String,       // api_key / web_login / device_code / sms / token_import / cookie
    val label: String,      // 人话
    /** web_login 的初始 URL；其它方式为 null。 */
    val url: String? = null,
)

/**
 * Agent 插件提供商适配器。
 *
 * 每家实现这个接口，Praxis 就能像 Agent2API 一样：登录、聊天、拉模型、
 * 续期、查余额、签到。凭证由 Praxis 统一加密存储。
 *
 * ## 与 Agent2API 的关系
 *
 * 上游是 Rust（AGPL），这里是 Kotlin 重写（MIT）。逻辑 1:1 对齐，
 * 但代码是我们自己的 —— 参考实现，不是翻译。
 */
interface ProviderAdapter {
    /** 提供商 id（与 ProviderCatalog 一致，如 "workbuddy"、"trae"）。 */
    val id: String
    val label: String

    /** 可用的登录方式。 */
    fun loginMethods(): List<AdapterLoginMethod>

    /**
     * 执行登录。
     * @param method 登录方式（AdapterLoginMethod.type）
     * @param input 用户输入（API Key / 短信验证码 / Cookie 字符串等）；
     *   web_login 方式时 input 是 H5 容器回调的 Cookie 或 token
     */
    suspend fun login(method: String, input: String): Result<ProviderCredentials>

    /** 发消息（流式）。凭证由调用方传入，adapter 负责拼正确的头与请求体。 */
    fun chat(credential: ProviderCredentials, request: ChatRequest): Flow<AiStreamEvent>

    /** 拉取模型清单（在线拉取或返回静态清单）。 */
    suspend fun fetchModels(credential: ProviderCredentials): Result<List<ProviderModel>>

    /** Token 续期。null = 该家不支持续期（需要重新登录）。 */
    suspend fun refresh(credential: ProviderCredentials): Result<ProviderCredentials>? = null

    /** 余额 / 积分查询。null = 不支持。 */
    suspend fun credits(credential: ProviderCredentials): Result<String>? = null

    /** 每日签到。null = 不支持。 */
    suspend fun checkIn(credential: ProviderCredentials): Result<String>? = null

    /** 客户端伪装头（合并到默认 HTTP 头上，同名覆盖）。 */
    fun emulationHeaders(): Map<String, String> = emptyMap()
}