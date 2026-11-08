package dev.praxis.core.data.gateway

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 网关账号（对应网关的 account_store）。
 *
 * ## 字段为什么是这几个
 *
 * 逐字来自 docs/PORTING-GATEWAY.md 6.1。其中 id 沿用网关的 id：导出回灌时要
 * 能对上同一个账号，否则「导入再导出」会变成一堆新账号。
 *
 * ## 为什么这里没有 apiKey
 *
 * 凭证走 [credentialsCipher]（加密 JSON），与 Praxis 现有 Keystore AES/GCM
 * 同一套。明文不落库、不进日志（文档 8.7）。
 */
@Entity(tableName = "gateway_account")
data class GatewayAccountEntity(
    @PrimaryKey val id: String,
    /** 内置家 id 或 custom-xxxxxxxxxxxx。 */
    val providerId: String,
    val name: String,
    /** 账号级地址覆盖；null = 跟随提供商。 */
    val baseUrlOverride: String?,
    val enabled: Boolean,
    val priority: Int,
    /** 0 = 不限。 */
    val maxConcurrent: Int,
    /** 加密 JSON：{ apiKey } 或 { accessToken, refreshToken }。 */
    val credentialsCipher: String,
    /** 凭证到期时刻；null = 不知道。 */
    val expiringAt: Long?,
    val createdAt: Long,
)
