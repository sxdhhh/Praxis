package dev.praxis.data.imagegen

import android.util.Base64
import dev.praxis.core.ai.AiErrorKind
import dev.praxis.core.imagegen.ImageGenParams
import dev.praxis.core.imagegen.ImageGenTarget
import dev.praxis.core.imagegen.RemoteImageEndpoint
import dev.praxis.core.imagegen.RemoteImagePayload
import dev.praxis.core.imagegen.RemoteImageRequest
import dev.praxis.core.imagegen.RemoteImageResponse
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 远端生图的结果。四档与本地同一套语义（文档 §5.4）。 */
sealed interface RemoteImageOutcome {
    data class Success(val images: List<ByteArray>) : RemoteImageOutcome
    data class Failure(val kind: AiErrorKind, val detail: String) : RemoteImageOutcome
}

/** 「让远端出一张图」这件事的端口（工具只依赖它，便于单测与换实现）。 */
interface RemoteImageGenerator {
    suspend fun generate(
        target: ImageGenTarget.Remote,
        apiKey: String?,
        params: ImageGenParams,
    ): RemoteImageOutcome
}

/**
 * OpenAI 兼容 `/v1/images/generations` 的实现（文档 §5.4）。
 *
 * ## 与本地引擎的区别
 *
 * 本地是 NDJSON 流（有进度），远端是**一次性的 JSON**（没有中间进度）。
 * 所以这条路上界面上不会动进度条 —— 这不是"没做"，是协议本身没有进度。
 *
 * ## 失败复用网关的分类器
 *
 * 额度耗尽 / 令牌过期 / 内容拦截的判定已经在 `UpstreamErrorClassifier` 里（读正文标记），
 * 远端这条路没有理由再写一份 —— 两份分类迟早对不上。
 */
class OkHttpRemoteImageGenerator(
    private val client: OkHttpClient = defaultClient(),
) : RemoteImageGenerator {

    override suspend fun generate(
        target: ImageGenTarget.Remote,
        apiKey: String?,
        params: ImageGenParams,
    ): RemoteImageOutcome = withContext(Dispatchers.IO) {
        val url = RemoteImageEndpoint.generateUrl(target.baseUrl)
        if (url.isEmpty()) {
            return@withContext RemoteImageOutcome.Failure(
                AiErrorKind.INVALID_REQUEST,
                "远端地址为空",
            )
        }
        if (apiKey.isNullOrBlank()) {
            return@withContext RemoteImageOutcome.Failure(
                AiErrorKind.AUTH,
                "还没有可用的 API Key（先去设置里配服务商）",
            )
        }

        val body = RemoteImageRequest.toJson(
            model = target.model,
            prompt = params.prompt,
            size = params.width.toString() + "x" + params.height.toString(),
            // 远端这条路一次只出一张：多张要多次请求，混在工具里会把超时与配额都说糊。
            n = 1,
        )
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .addHeader("Authorization", "Bearer " + apiKey)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            return@withContext RemoteImageOutcome.Failure(
                AiErrorKind.NETWORK,
                error.message ?: "网络失败",
            )
        }

        response.use { resp ->
            val text = runCatching { resp.body?.string() }.getOrNull().orEmpty()
            if (!resp.isSuccessful) {
                val kind = dev.praxis.core.gateway.UpstreamErrorClassifier
                    .classify(resp.code, text)
                return@withContext RemoteImageOutcome.Failure(
                    kind,
                    RemoteImageResponse.errorMessage(text) ?: ("HTTP " + resp.code),
                )
            }
            val images = RemoteImageResponse.parse(text).mapNotNull { payload ->
                when (payload) {
                    is RemoteImagePayload.Base64 ->
                        runCatching { Base64.decode(payload.data, Base64.DEFAULT) }.getOrNull()
                    // url 形式暂不支持：还要再发一次 GET，且链接会过期。
                    is RemoteImagePayload.Url -> null
                }
            }
            if (images.isEmpty()) {
                return@withContext RemoteImageOutcome.Failure(
                    AiErrorKind.PROTOCOL,
                    "上游返回成功但没有可用的图（可能是 url 形式，暂不支持）",
                )
            }
            RemoteImageOutcome.Success(images)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** 远端出图可能要几十秒：读超时给足，连接超时短一点。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
