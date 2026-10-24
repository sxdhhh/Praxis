package dev.praxis.core.plugin

/**
 * 市场 URL 构造。
 *
 * ## 为什么单独一个对象而不是散在 HTTP 实现里
 *
 * 这些路径规则是**服务端契约**，不是 HTTP 细节。放在这里能纯 JVM 单测：
 * 「分页第 3 页是不是 `page-3.json`」「按类型列表的段顺序对不对」这类
 * 拼错一个字母就 404 的问题，不该等到联网才发现。
 */
object MarketEndpoints {

    /** 静态资源域名（预生成的列表 JSON、图标）。无需鉴权。 */
    const val STATIC_BASE: String = "https://static.operit.app"

    /** 动态接口域名（详情、下载、发布、评论）。需要 session。 */
    const val API_BASE: String = "https://api.operit.app"

    /**
     * 全量列表（静态）。
     *
     * `market/v2/lists/all/{sort}/page-{n}.json`
     *
     * 注意排序段用的是 [MarketSort.pathSegment] 的**小写原样**，
     * 不做任何本地化 —— 本地化只发生在 UI 标签上。
     */
    fun allListUrl(sort: MarketSort, page: Int): String =
        "$STATIC_BASE/market/v2/lists/all/${sort.pathSegment}/page-${page.coerceAtLeast(1)}.json"

    /** 按类型列表（静态）。`type` 会转小写，与 operit 一致。 */
    fun typeListUrl(type: String, sort: MarketSort, page: Int): String =
        "$STATIC_BASE/market/v2/lists/type/${type.lowercase()}/${sort.pathSegment}/" +
            "page-${page.coerceAtLeast(1)}.json"

    /** 按分类列表（静态）。 */
    fun categoryListUrl(categoryId: String, sort: MarketSort, page: Int): String =
        "$STATIC_BASE/market/v2/lists/category/${categoryId.lowercase()}/" +
            "${sort.pathSegment}/page-${page.coerceAtLeast(1)}.json"

    /** 条目详情（动态）。 */
    fun entryUrl(marketId: String): String =
        "$API_BASE/market/v2/entries/${encodeSegment(marketId)}"

    /** 资产下载（动态）。 */
    fun assetDownloadUrl(assetId: String): String =
        "$API_BASE/market/v2/assets/${encodeSegment(assetId)}/download"

    /**
     * 下载计数上报。
     *
     * ## 为什么与 [assetDownloadUrl] 完全一样
     *
     * 服务端把「下载」与「计数」合成一个端点：GET 这个 URL 就返回文件
     * **并且**计数 +1。operit 的 `trackDownload` 也是打同一个地址。
     *
     * 所以调用方**不要**「先 track 再 download」—— 那会下两次、计数 +2、
     * 白耗用户流量。真正的做法见 `MarketInstaller`：下载一次即完成两者。
     * 这个函数留着只是为了「已经缓存过、只想补记一次计数」的场景。
     */
    fun trackDownloadUrl(assetId: String): String = assetDownloadUrl(assetId)

    /**
     * 路径段编码。
     *
     * 只用最小的替换集：`/`、`?`、`#`、空格。不用 `URLEncoder.encode`
     * 是因为它把空格编成 `+`（路径里应为 `%20`），并且会把 `:` 之类
     * 无害字符也编掉，反而让日志难读。市场 id 是 slug（字母数字与 `-`），
     * 正常情况下一个字符都不会被改。
     */
    fun encodeSegment(value: String): String = value
        .replace("/", "%2F")
        .replace("?", "%3F")
        .replace("#", "%23")
        .replace(" ", "%20")
}

/**
 * 市场来源标记的编解码。
 *
 * ## 这是什么
 *
 * Operit 的做法：把「这个包是从市场装的、装的是哪个版本」编码后塞进
 * 包内脚本/metadata 里，用来在客户端判断来源。编码方式是一段
 * **XOR 混淆**（key `0x5a`）而不是加密 —— 目的是「随手改不掉」，
 * 不是「防住有意的伪造」。
 *
 * ## 为什么 Praxis 也做这件事，但**不作为安装前提**
 *
 * 我们需要它来做「市场更新提示」：本地要知道「当前装的这个包
 * 对应市场里的哪个条目」。但**不能**把它当安全边界：
 * 任何用户手改文件都能绕开。所以：
 *
 * - 只在**存在**时读取，缺失就退化成「本地导入」（不报错）；
 * - 校验 `market` 字段必须是 `Operit`，避免把别人家的标记当自己的；
 * - 校验 `toolpkgId` 与包 id 一致，防止把一个包的标记复制到另一个包上
 *   导致「更新时把 A 换成 B」。
 */
object MarketOriginCodec {

    /** 市场标识。固定为 `Operit`，因为这是对方市场的名字，不是我们的品牌选择。 */
    const val MARKET_NAME: String = "Operit"

    /** XOR 密钥。与 operit 一致（`0x5a`）。 */
    private const val XOR_KEY: Int = 0x5A

    private const val METADATA_PREFIX = "xor-v1:"

    /** 编码后的来源信息（可直接写进包内的 metadata）。 */
    data class Origin(
        val market: String,
        val toolpkgId: String,
        val version: String,
        val authors: List<String>,
    )

    /**
     * 编码成 `xor-v1:1,2,3,…` 形式。
     *
     * ## 为什么先转义非 ASCII
     *
     * XOR 是按**字节**做的。直接对 UTF-8 多字节字符逐字节 XOR 也能解回来，
     * 但编码结果是一串不可读字节，写进 JSON 时还要再转义一次，
     * 排错时完全看不懂。所以先把非 ASCII 转成 `\uXXXX` 再 XOR ——
     * 与 operit 的 `escapeNonAscii` 一致。
     */
    fun encode(origin: Origin): String {
        val payload = buildString {
            append("{\"market\":\"").append(escape(origin.market)).append("\",")
            append("\"toolpkgId\":\"").append(escape(origin.toolpkgId)).append("\",")
            append("\"version\":\"").append(escape(origin.version)).append("\",")
            append("\"author\":[")
            append(origin.authors.joinToString(",") { "\"" + escape(it) + "\"" })
            append("]}")
        }
        val bytes = payload.toByteArray(Charsets.US_ASCII)
        val encoded = bytes.joinToString(",") { byte -> ((byte.toInt() and 0xFF) xor XOR_KEY).toString() }
        return METADATA_PREFIX + encoded
    }

    /**
     * 解码。
     *
     * @return null 表示不存在或格式不对 —— 调用方应把它当「本地导入」处理，
     *   而不是报错。来源标记只是便利信息，缺了不该影响功能。
     */
    fun decode(encoded: String): Origin? {
        val trimmed = encoded.trim()
        if (!trimmed.startsWith(METADATA_PREFIX)) return null
        val body = trimmed.removePrefix(METADATA_PREFIX)
        if (body.isBlank()) return null

        val rawBytes = body.split(',').map { item ->
            val value = item.trim().toIntOrNull()
            if (value == null || value !in 0..255) return null
            (value xor XOR_KEY).toByte()
        }.toByteArray()

        val payload = runCatching { String(rawBytes, Charsets.US_ASCII) }.getOrNull() ?: return null

        // 手写极简 JSON 取值：这里的 payload 结构完全由 [encode] 决定，
        // 引入完整 JSON 解析器是不必要的（而且 core:plugin 想保持零序列化依赖）。
        return runCatching {
            Origin(
                market = extractString(payload, "market") ?: return null,
                toolpkgId = extractString(payload, "toolpkgId") ?: return null,
                version = extractString(payload, "version").orEmpty(),
                authors = extractStrings(payload, "author"),
            )
        }.getOrNull()
    }

    /**
     * 校验来源标记是否可用于某个包。
     *
     * @return 校验通过的标记；不通过返回 null（调用方当「未知来源」）
     */
    fun validate(origin: Origin?, packageId: String): Origin? {
        val candidate = origin ?: return null
        if (candidate.market != MARKET_NAME) return null
        if (candidate.toolpkgId != packageId.trim()) return null
        if (candidate.version.isBlank()) return null
        return candidate.copy(
            toolpkgId = candidate.toolpkgId.trim(),
            version = candidate.version.trim(),
            authors = candidate.authors.map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    /** 转成 Praxis 的 [PluginOrigin]。 */
    fun toPluginOrigin(origin: Origin, marketId: String = ""): PluginOrigin =
        PluginOrigin.Market(
            marketId = marketId.ifBlank { origin.toolpkgId },
            version = origin.version,
            installedFrom = "operit-market",
        )

    // ── 字符转义与极简 JSON 取值 ──────────────────────────────

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch.code in 0x20..0x7E -> append(ch)
                else -> {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                }
            }
        }
    }

    private fun extractString(payload: String, key: String): String? {
        val marker = "\"$key\":\""
        val start = payload.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val sb = StringBuilder()
        var i = valueStart
        while (i < payload.length) {
            val ch = payload[i]
            when {
                ch == '"' -> return sb.toString()
                ch == '\\' && i + 1 < payload.length -> {
                    when (val next = payload[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        '"' -> { sb.append('"'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        'u' -> {
                            val hex = payload.substring(i + 2, (i + 6).coerceAtMost(payload.length))
                            val code = hex.toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            i += 6
                        }
                        else -> { sb.append(next); i += 2 }
                    }
                }
                else -> { sb.append(ch); i += 1 }
            }
        }
        return null
    }

    private fun extractStrings(payload: String, key: String): List<String> {
        val marker = "\"$key\":["
        val start = payload.indexOf(marker)
        if (start < 0) return emptyList()
        val end = payload.indexOf(']', start)
        if (end < 0) return emptyList()
        val body = payload.substring(start + marker.length, end)
        return body.split(',').mapNotNull { part ->
            val trimmed = part.trim()
            if (!trimmed.startsWith("\"") || !trimmed.endsWith("\"") || trimmed.length < 2) {
                null
            } else {
                trimmed.substring(1, trimmed.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
        }.filter { it.isNotEmpty() }
    }
}