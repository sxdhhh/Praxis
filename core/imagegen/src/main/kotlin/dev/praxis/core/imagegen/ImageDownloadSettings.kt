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
) {
    companion object {
        /** GitHub 的模型发布页（当前内置清单都指到这里）。 */
        const val DEFAULT_BASE_URL: String = "https://github.com/"
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
            val settings = ImageDownloadSettings(baseUrl = baseUrl, wifiOnly = wifiOnly)
            require(ImageDownloadSettingsRules.isValidBaseUrl(settings.baseUrl)) {
                "下载源不是 http/https 地址：" + settings.baseUrl
            }
            ImageDownloadSettings(
                baseUrl = ImageDownloadSettingsRules.normalizeBaseUrl(settings.baseUrl),
                wifiOnly = settings.wifiOnly,
            )
        }
    }
}
