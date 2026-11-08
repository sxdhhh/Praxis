package dev.praxis.core.data.gateway

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 自定义提供商（对应网关的 customProviders）。
 *
 * 内置 21 家不走这张表：它们是代码里的常量。只有用户自己加的（custom-*）才落库。
 */
@Entity(tableName = "gateway_provider")
data class GatewayProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    /** 模型清单（JSON 数组文本，原样保存）。 */
    val modelsJson: String,
    /** 模型映射（JSON 数组文本，原样保存）。 */
    val mappingsJson: String,
    val createdAt: Long,
)
