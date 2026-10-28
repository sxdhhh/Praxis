package dev.praxis.data.plugin

import dev.praxis.core.plugin.PluginOrigin

/**
 * [PluginOrigin] 与存储字符串之间的编解码。
 *
 * ## 格式
 *
 * ```
 * builtin
 * local
 * market:<marketId>|<version>|<installedFrom>
 * dev:<note>
 * ```
 *
 * ## 为什么不用 JSON
 *
 * 只有四种形态、字段不超过三个。序列化成 JSON 要额外处理转义，而
 * `marketId` / `installedFrom` 里可能出现 `|`（服务端 id 是 base64url
 * 风格的串，理论上不含，但 `installedFrom` 是 URL）。
 *
 * 所以：`|` 是分隔符，值里若出现 `|` 就转义成 `%7C`（百分号编码只做
 * 这一个字符，不是通用 URL 编码 —— 通用编码会把 `https://` 也改掉，
 * 人读存储内容时就认不出来了）。
 *
 * ## 为什么未知前缀返回 null 而不是回落到 Local
 *
 * 回落到 Local 会让「从市场装的包」在降级安装后显示成「本地导入」，
 * 用户会以为市场来源丢了。返回 null 让 `PluginRepository` 用它的默认值，
 * 至少那是显式的、可解释的。
 */
object MarketOriginCodec {

    private const val SEP = '|'
    private const val ESCAPED = "%7C"

    fun encode(origin: PluginOrigin): String = when (origin) {
        PluginOrigin.BuiltIn -> "builtin"
        PluginOrigin.Local -> "local"
        is PluginOrigin.Market -> "market:" + listOf(
            origin.marketId,
            origin.version,
            origin.installedFrom,
        ).joinToString(SEP.toString()) { escape(it) }
        is PluginOrigin.Development -> "dev:" + escape(origin.note)
    }

    fun decode(raw: String): PluginOrigin? {
        if (raw.isBlank()) return null
        val prefix = raw.substringBefore(':', missingDelimiterValue = raw)
        val rest = raw.substringAfter(':', missingDelimiterValue = "")
        return when (prefix) {
            "builtin" -> PluginOrigin.BuiltIn
            "local" -> PluginOrigin.Local
            "market" -> {
                val parts = rest.split(SEP).map { unescape(it) }
                val marketId = parts.getOrNull(0).orEmpty()
                if (marketId.isBlank()) return null
                PluginOrigin.Market(
                    marketId = marketId,
                    version = parts.getOrNull(1).orEmpty(),
                    installedFrom = parts.getOrNull(2).orEmpty(),
                )
            }
            "dev" -> PluginOrigin.Development(note = unescape(rest))
            else -> null
        }
    }

    private fun escape(value: String): String = value.replace(SEP.toString(), ESCAPED)

    private fun unescape(value: String): String = value.replace(ESCAPED, SEP.toString())
}
