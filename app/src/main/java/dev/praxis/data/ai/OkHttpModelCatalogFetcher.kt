package dev.praxis.data.ai

import dev.praxis.core.ai.AiModelOption
import dev.praxis.core.ai.ModelCatalog
import dev.praxis.core.ai.ModelCatalogFetcher
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * [ModelCatalogFetcher] 的 OkHttp 实现（1:1 移植 Operit `ModelListFetcher.getModelsList`
 * 的传输层）。
 *
 * ## 只做传输，不做解析
 *
 * URL 派生与 JSON 解析都在 `:core:ai` 的 [ModelCatalog]（纯函数、可单测）。
 * 这里只负责：加认证头 → 发 GET → 把 body 交给 [ModelCatalog.parse]。
 * 这样「解析写错了」和「请求发错了」是两类可区分的失败。
 *
 * ## 三处直接从上游搬过来的行为
 *
 * 1. **超时 30s / 30s / 30s**：上游注释写的是「使用更长的超时时间」。
 *    拉列表不像对话要长连接，30s 足够覆盖慢代理；用对话那个
 *    `readTimeout(0)`（无限）会让「拉不到」永远转圈。
 * 2. **`/v1/models` 失败后回退 `/models`**：上游只对 OpenAI 系这么干。
 *    这里对所有 Bearer 类都试 —— 因为 Praxis 的「自定义 / 中转站」这一项
 *    本身就是任意 OpenAI 兼容实现，无法预先分类。
 * 3. **不加 `Accept` 限制**：上游只加 `Content-Type: application/json`。
 *    有的中转站会按 `Accept` 内容协商返回 HTML 错误页。
 *
 * ## 为什么单独建一个 client 而不是复用 [OpenAiCompatProvider.defaultClient]
 *
 * 那个 client 的 `readTimeout` 是 0（无限），为 SSE 长连接准备的。
 * 拉模型列表是一次普通短请求，用无限超时会让失败永久挂起 ——
 * 用户看到的是「转圈转到放弃」。
 */
class OkHttpModelCatalogFetcher(
    private val client: OkHttpClient = defaultClient(),
) : ModelCatalogFetcher {

    override suspend fun fetch(
        baseUrl: String,
        apiKey: String,
        providerKind: ModelCatalogFetcher.ProviderKind,
    ): Result<List<AiModelOption>> = withContext(Dispatchers.IO) {
        val firstUrl = ModelCatalog.modelsUrl(baseUrl)
        if (firstUrl.isEmpty()) {
            return@withContext Result.failure(
                ModelCatalog.ParseFailure(ModelCatalog.Failure.EmptyBaseUrl),
            )
        }

        // ① 主路径。
        when (val primary = requestOnce(firstUrl, apiKey, providerKind)) {
            is FetchOutcome.Success -> return@withContext ModelCatalog.parse(primary.body)
            is FetchOutcome.HttpError -> {
                // ② 回退：`/v1/models` → `/models`。
                //
                // 只在「主路径是 404/400/405」时试。401/403 说明是鉴权问题，
                // 换个路径也一样 —— 多打一次请求只是让用户多等一轮。
                val fallbackUrl = fallbackUrlFor(firstUrl)
                if (fallbackUrl != null && primary.code in FALLBACK_CODES) {
                    when (val fallback = requestOnce(fallbackUrl, apiKey, providerKind)) {
                        is FetchOutcome.Success -> return@withContext ModelCatalog.parse(fallback.body)
                        is FetchOutcome.HttpError -> return@withContext Result.failure(
                            ModelCatalog.ParseFailure(
                                ModelCatalog.Failure.Http(fallback.code, fallback.body),
                            ),
                        )
                        is FetchOutcome.NetworkError -> return@withContext Result.failure(
                            ModelCatalog.ParseFailure(
                                ModelCatalog.Failure.Network(fallback.detail),
                            ),
                        )
                    }
                }
                return@withContext Result.failure(
                    ModelCatalog.ParseFailure(ModelCatalog.Failure.Http(primary.code, primary.body)),
                )
            }
            is FetchOutcome.NetworkError -> return@withContext Result.failure(
                ModelCatalog.ParseFailure(ModelCatalog.Failure.Network(primary.detail)),
            )
        }
    }

    /** 一次 GET。所有分支都显式关 body，避免连接池泄漏。 */
    private fun requestOnce(
        url: String,
        apiKey: String,
        providerKind: ModelCatalogFetcher.ProviderKind,
    ): FetchOutcome {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .get()

        when (providerKind) {
            ModelCatalogFetcher.ProviderKind.Bearer -> {
                if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
            }
            // 上游对 OpenRouter 强制要求这两个头，否则返回 403 而不是模型列表。
            ModelCatalogFetcher.ProviderKind.OpenRouter -> {
                if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
                builder.addHeader("HTTP-Referer", "dev.praxis")
                builder.addHeader("X-Title", "Praxis")
            }
            // 本地推理：不带任何认证头。带了反而可能被某些实现拒绝。
            ModelCatalogFetcher.ProviderKind.NoAuth -> Unit
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    FetchOutcome.Success(body)
                } else {
                    FetchOutcome.HttpError(response.code, body)
                }
            }
        } catch (e: IOException) {
            FetchOutcome.NetworkError(e.message)
        } catch (e: Exception) {
            // URL 非法（`MalformedURLException` 是 IOException 的子类，
            // 但 `IllegalArgumentException` 不是）等：统一归到网络类失败，
            // 让上层只面对一种错误表达。
            FetchOutcome.NetworkError(e.message)
        }
    }

    private sealed interface FetchOutcome {
        data class Success(val body: String) : FetchOutcome
        data class HttpError(val code: Int, val body: String) : FetchOutcome
        data class NetworkError(val detail: String?) : FetchOutcome
    }

    private companion object {
        /**
         * 值得换路径重试的状态码。
         *
         * `404`：路径不存在（最常见 —— 服务商只提供 `/models`）。
         * `400`：有的网关把「未知路径」判成参数错误。
         * `405`：只允许 POST 的实现。
         *
         * **不含 401/403**：那是密钥问题，换路径不会变好。
         */
        val FALLBACK_CODES = setOf(400, 404, 405)

        /** `/v1/models` → `/models`；不含 `/v1/` 的地址返回 null（没有可回退的）。 */
        fun fallbackUrlFor(url: String): String? =
            if (url.endsWith("/v1/models")) {
                url.removeSuffix("/v1/models") + "/models"
            } else {
                null
            }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}