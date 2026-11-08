package dev.praxis.core.data.gateway

import dev.praxis.core.gateway.AccountView

/**
 * 账号实体 -> 选路视图。
 *
 * ## 为什么要单独一步映射，而不是让 RoutePlanner 直接吃实体
 *
 * 实体的几个字段是**存储形态**（credentialsCipher 是密文），而选路需要的是
 * **判断形态**（hasCredentials 是布尔）。把解密与运行期计数放在这一层之外，
 * RoutePlanner 就能保持纯函数、不碰 Room、不碰 Keystore —— 这也是文档 10
 * 「core:gateway 不碰 Room」那条的落地方式。
 *
 * @param hasCredentials 该账号的凭证能否解出（由调用方解密后告知）
 * @param supportsImages 能力位；null = 缺键，按文档 7.2 放行
 */
fun GatewayAccountEntity.toAccountView(
    hasCredentials: Boolean,
    supportsImages: Boolean? = null,
    runningConcurrent: Int = 0,
    requestsThisMinute: Int = 0,
    requestLimitPerMinute: Int = 0,
): AccountView = AccountView(
    id = id,
    providerId = providerId,
    enabled = enabled,
    priority = priority,
    maxConcurrent = maxConcurrent,
    hasCredentials = hasCredentials,
    baseUrlOverride = baseUrlOverride,
    supportsImages = supportsImages,
    runningConcurrent = runningConcurrent,
    requestsThisMinute = requestsThisMinute,
    requestLimitPerMinute = requestLimitPerMinute,
)
