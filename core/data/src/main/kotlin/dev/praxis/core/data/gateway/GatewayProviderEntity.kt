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
    /**
     * 额外请求头（JSON 对象文本，如 `{"Anthropic-Beta":"claude-code-20250219"}`）。
     *
     * 来自上游预设的 `quirks.headers`（preset-providers.js）：对某些家是**硬门槛**
     * （Cline 的 `X-CLIENT-TYPE` 缺了一律 403；Anthropic/GLM 的 `Anthropic-Beta`
     * 决定 beta 特性可用性）。空串 = 没有。
     */
    val extraHeadersJson: String = "",
    /**
     * 出站 URL 的查询串后缀（上游 `quirks.urlSuffix`）。
     *
     * GLM / MiniMax 的 Claude 兼容端点要求 `?beta=true`，缺了部分 beta 特性不可用。
     * 空串 = 不加。
     */
    val urlSuffix: String = "",
    /**
     * 上次**成功**拉到模型清单的时间；0 = 从没成功过。
     *
     * 文档 §5.4.1 第 17 条的「快照 + 缓存」：`modelsJson` 是快照本身，
     * 这一列让它带上了「什么时候的快照」—— 缓存新鲜度与界面上的
     * 「上次刷新：3 小时前」都靠它。
     */
    val modelsFetchedAt: Long = 0L,
)
