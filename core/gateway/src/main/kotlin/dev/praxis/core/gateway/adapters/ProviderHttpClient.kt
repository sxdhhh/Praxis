package dev.praxis.core.gateway.adapters

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Agent 提供商共用的 HTTP 客户端。
 *
 * 与 AiModelCatalogFetcher 同一个思路：超时 30 秒，不用无限读 ——
 * 拉模型/签到/查积分都是短请求，不是 SSE 长连接。
 */
object ProviderHttpClient {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** GET 请求（带可选头）。 */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return client.newCall(builder.build()).execute()
    }

    /** POST JSON 请求。 */
    @Throws(IOException::class)
    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url)
            .post(okhttp3.RequestBody.create(
                "application/json; charset=utf-8".toMediaType(), json))
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return client.newCall(builder.build()).execute()
    }

    /** POST 表单请求。 */
    @Throws(IOException::class)
    fun postForm(url: String, params: Map<String, String>, headers: Map<String, String> = emptyMap()): Response {
        val body = params.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        val builder = Request.Builder().url(url)
            .post(okhttp3.RequestBody.create(
                "application/x-www-form-urlencoded".toMediaType(), body))
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return client.newCall(builder.build()).execute()
    }
}