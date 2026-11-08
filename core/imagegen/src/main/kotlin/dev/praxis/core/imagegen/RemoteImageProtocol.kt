package dev.praxis.core.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI 兼容 images 端点的请求体（文档 §5.4）。
 *
 * ## 为什么固定 response_format = b64_json
 *
 * 另一种是 url：服务端托管一张图，我们还得再发一次 GET 拿字节，而且链接会过期 ——
 * 两跳、两个失败点。直接要 base64 一次性拿到手。
 */
object RemoteImageRequest {

    fun toJson(
        model: String,
        prompt: String,
        size: String,
        n: Int,
        responseFormat: String = "b64_json",
    ): String = buildJsonObject {
        put("model", model)
        put("prompt", prompt)
        put("size", size)
        put("n", n)
        put("response_format", responseFormat)
    }.toString()
}

/**
 * images 端点地址的推导。
 *
 * ## 为什么要处理 baseUrl 的两种形状
 *
 * Praxis 里 baseUrl 存的是「用户原始填的地址」，两种都常见：
 * `https://api.openai.com`（要补 /v1）与 `https://api.openai.com/v1`（已经带了）。
 * 不判断就会拼出 /v1/v1/images/generations —— 一个必然 404 的地址，
 * 而报错里只有 HTTP 404，用户根本想不到是这里。
 */
object RemoteImageEndpoint {

    private const val PATH = "/images/generations"

    fun generateUrl(baseUrl: String): String {
        var trimmed = baseUrl.trim()
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.dropLast(1)
        }
        if (trimmed.isEmpty()) return ""
        // 已经带版本前缀的不再补（/v1、/v1beta 之类）。
        val hasVersion = trimmed.substringAfterLast("/").matches(Regex("v[0-9].*"))
        return if (hasVersion) trimmed + PATH else trimmed + "/v1" + PATH
    }
}

/** 远端返回的一张图：base64 或 URL（我们只要 base64，URL 只用于给出可读原因）。 */
sealed interface RemoteImagePayload {
    data class Base64(val data: String) : RemoteImagePayload
    data class Url(val url: String) : RemoteImagePayload
}

/**
 * images 端点的响应解析。
 *
 * ## 为什么错误与成功分开两个函数
 *
 * 失败分类要用到 status 与正文里的 error 字段（额度、内容拦截…），而成功只要图。
 * 合在一起会让「解析成功但 data 为空」与「上游报错」混成同一个空列表 ——
 * 那两种情况给用户的处置完全不同（重试 vs 换模型）。
 */
object RemoteImageResponse {

    /** @return 图列表；空列表表示「HTTP 成功但一张图都没有」 */
    fun parse(body: String): List<RemoteImagePayload> {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val data = root["data"]
            ?.let { element -> runCatching { element.jsonArray }.getOrNull() }
            ?: return emptyList()
        return data.mapNotNull { element ->
            val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val base64 = item["b64_json"]?.jsonPrimitive?.contentOrNull
            if (!base64.isNullOrBlank()) {
                RemoteImagePayload.Base64(base64)
            } else {
                item["url"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { RemoteImagePayload.Url(it) }
            }
        }
    }

    /** 上游在正文里给的可读原因（error.message）；没有就 null。 */
    fun errorMessage(body: String): String? {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null
        val error = root["error"] ?: return null
        return runCatching { error.jsonObject["message"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
