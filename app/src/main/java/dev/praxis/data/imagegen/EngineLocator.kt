package dev.praxis.data.imagegen

import dev.praxis.core.imagegen.EngineAvailability
import dev.praxis.core.imagegen.EngineHealth
import dev.praxis.core.imagegen.EngineHealthCodec
import dev.praxis.core.imagegen.EngineProtocol
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 引擎探活（文档 §5.2）。
 *
 * ## 为什么用**独立**的短超时 client
 *
 * 探活的语义是「现在有没有人应答」，不是「等它应答」。用对话那个 `readTimeout(0)`
 * 会让「引擎没起」这个最常见的情况把设置页的卡片转很久。1s 连接 / 2s 读取足够。
 * 生成请求另建一个长超时的 client（见 [ImageGenClient]），两者不能共用。
 *
 * ## 为什么坏 JSON 不抛异常
 *
 * `/health` 的内容不重要，重要的是「有人应答且像个健康响应」。解析失败归为
 * [EngineAvailability.Unavailable.Reason.MalformedHealth] —— 那通常意味着 8081 上
 * 跑的是别的东西（不是我们的引擎）。抛异常会让调用方少一条可展示的原因。
 */
class EngineLocator(
    private val client: OkHttpClient = defaultClient(),
    private val port: Int = EngineProtocol.DEFAULT_PORT,
) {

    /** 探一次。永远返回结果，不抛：调用方只需要分「能用 / 不能用 + 为什么」。 */
    suspend fun probe(): EngineAvailability = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(EngineProtocol.healthUrl(port)).get().build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext EngineAvailability.Unavailable(
                        reason = EngineAvailability.Unavailable.Reason.HttpError,
                        detail = "HTTP " + response.code,
                    )
                }
                val health = EngineHealthCodec.parse(body)
                if (health.ok) {
                    EngineAvailability.Available(health = health)
                } else {
                    EngineAvailability.Unavailable(
                        reason = EngineAvailability.Unavailable.Reason.MalformedHealth,
                        detail = body.take(120),
                    )
                }
            }
        } catch (error: IOException) {
            EngineAvailability.Unavailable(
                reason = EngineAvailability.Unavailable.Reason.NotRunning,
                detail = error.message,
            )
        }
    }

    companion object {
        /** 探活 client：短超时，见类 KDoc。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }
}
