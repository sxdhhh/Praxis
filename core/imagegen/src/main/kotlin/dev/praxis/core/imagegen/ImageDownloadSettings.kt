package dev.praxis.core.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 模型下载的设置（docs/PORTING-IMAGEGEN.md §7.2）。
 *
 * ## 只有两个字段，因为只有这两个能真正生效
 *
 * 文档那一页还画了「并发下载数」与「代理」：
 *
 * - **并发数**：下载器现在是逐个文件顺序下的（大包一次只下一个文件，天然限住峰值磁盘写）。
 *   改成真并发要先解决「多个 .part 同时写 + 进度合并」，不是加个字段的事 ——
 *   放一个读不到的并发数，用户改了以为变快，其实没变。
 * - **代理**：Praxis 还没有全局代理设置，这条要等它先存在。
 *
 * 所以页面会**明说**这两项没做，而不是摆两个假开关。
 */
data class ImageDownloadSettings(
    /** 下载源根地址；模型目录里的 fileUri 拼在它后面。 */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 仅 Wi-Fi 下载（默认开：几 GB 的包走流量是能让人肉疼的失误）。 */
    val wifiOnly: Boolean = true,
    /**
     * 同时下载几个文件（1~4，默认 2）。
     *
     * ## 为什么默认 2 而不是 1
     *
     * 多文件模型（.mnn + tokenizer + embeddings）每个文件都不大且互不依赖，
     * 2 路并发能把等待时间压掉近一半。再往上收益迅速变小：
     * 手机上的瓶颈通常是带宽与热点，而不是连接数。
     */
    val concurrency: Int = DEFAULT_CONCURRENCY,
) {
    companion object {
        /** GitHub 的模型发布页（当前内置清单都指到这里）。 */
        const val DEFAULT_BASE_URL: String = "https://github.com/"

        /** 并发上限。再高对移动网络没有意义，只会让每个连接都变慢。 */
        const val MAX_CONCURRENCY: Int = 4
        const val DEFAULT_CONCURRENCY: Int = 2
    }
}
/** 设置里地址的归一化与校验（纯函数）。 */
object ImageDownloadSettingsRules {

    /**
     * 归一化：去空白、保证尾部有斜杠。
     *
     * 尾部斜杠是必须的：fileUri 形如 `xororz/sd-qnn/resolve/main/x.zip`，
     * 拼在 `https://github.com` 后面会变成 `https://github.comxororz/...`。
     */
    fun normalizeBaseUrl(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ImageDownloadSettings.DEFAULT_BASE_URL
        while (value.endsWith("/")) {
            value = value.dropLast(1)
        }
        return value + "/"
    }

    /**
     * 并发数归一化：超出 1..[ImageDownloadSettings.MAX_CONCURRENCY] 就夹到边界。
     *
     * 这里**夹而不是拒**：并发数不是用户界面上的精确输入（是 1~4 的选择），
     * 而设置文件可能被旧版本或手改写过。与「画布 2000 必须拒绝」不同 ——
     * 那个是用户明确填的数字，静默改掉会让他以为生效了。
     */
    fun normalizeConcurrency(raw: Int): Int = raw.coerceIn(1, ImageDownloadSettings.MAX_CONCURRENCY)

    /** 只接受 http/https，且协议后必须有主机。 */
    fun isValidBaseUrl(raw: String): Boolean {
        val lower = raw.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val host = lower.removePrefix("https://").removePrefix("http://")
        return host.isNotEmpty() && !host.startsWith("/")
    }
}

/** `imagegen_download` 的编解码。 */
object ImageDownloadSettingsCodec {

    fun encode(settings: ImageDownloadSettings): String = buildJsonObject {
        put("baseUrl", settings.baseUrl)
        put("wifiOnly", settings.wifiOnly)
        put("concurrency", settings.concurrency)
    }.toString()

    /** @param text null/空白 = 没配过（返回 null）；坏内容 = failure。 */
    fun decode(text: String?): Result<ImageDownloadSettings>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root: JsonObject = Json.parseToJsonElement(text).jsonObject
            val baseUrl = root["baseUrl"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: ImageDownloadSettings.DEFAULT_BASE_URL
            val wifiOnly = root["wifiOnly"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull()
                ?: error("wifiOnly 不是布尔值")
            val resolved = ImageDownloadSettings(
                baseUrl = ImageDownloadSettingsRules.normalizeBaseUrl(baseUrl),
                wifiOnly = wifiOnly,
                // 缺字段用默认 2（schema 演进）；有值则夹到 1~4。
                concurrency = ImageDownloadSettingsRules.normalizeConcurrency(
                    root.intOr("concurrency", ImageDownloadSettings.DEFAULT_CONCURRENCY),
                ),
            )
            require(ImageDownloadSettingsRules.isValidBaseUrl(resolved.baseUrl)) {
                "下载源不是 http/https 地址：" + resolved.baseUrl
            }
            resolved
        }
    }

    private fun JsonObject.intOr(key: String, fallback: Int): Int {
        val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return fallback
        return raw.toIntOrNull() ?: error(key + " 不是整数")
    }
}
