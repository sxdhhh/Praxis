package dev.praxis.core.model.settings

/**
 * 模型服务配置。
 *
 * ## 为什么 baseUrl / apiKey 要放进设置而不是 BuildConfig
 *
 * 写死在 BuildConfig 意味着「换个服务商就要重新打包」，等于普通用户用不了。
 * 这是产品级缺陷，不是便利性问题。
 *
 * ## 为何 [apiKey] 不在这里
 *
 * 这个 data class 会被日志、crash report、状态快照带出去。
 * 密钥单独存取（见 SettingsRepository），避免它跟着整个配置对象到处流。
 */
data class ModelSettings(
    /** 服务商接口地址，可填 `https://api.x.com` 或带 `/v1` 后缀。 */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 模型 ID，例如 `gpt-4o-mini` / `deepseek-chat`。 */
    val model: String = DEFAULT_MODEL,
    /** 采样温度。0 更确定，1 更发散。 */
    val temperature: Double = 0.7,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        /** 留空而不是塞一个真实地址：用户必须自己确认填哪个服务商。 */
        const val DEFAULT_BASE_URL: String = ""
        const val DEFAULT_MODEL: String = ""
    }
}
