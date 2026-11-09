package dev.praxis.data.imagegen

import dev.praxis.core.imagegen.EngineProtocol
import dev.praxis.core.imagegen.ImageGenEvent
import dev.praxis.core.imagegen.ImageGenParams
import dev.praxis.core.imagegen.ImageGenRequest
import dev.praxis.core.imagegen.NdjsonReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `/generate` 的流式客户端（文档 §3.3/§3.4、§5.2）。
 *
 * ## 为什么是 NDJSON 而不是 SSE
 *
 * 引擎每行吐一个独立 JSON（`progress` / `complete`），没有 `data:` 前缀、没有事件名。
 * 套用 Praxis 现成的 SSE 解析器会一行都解析不出来 —— 而那种失败很难查，
 * 因为「没有事件」看起来和「引擎没响应」一样。
 *
 * ## 为什么逐行 emit，不攒完再解析
 *
 * 一次生成 20~60 秒。攒完再解析等于把进度条和低分辨率预览全丢掉，
 * 用户看到的是一个 60 秒不动的圈。
 *
 * ## 取消必须真的关连接
 *
 * 上游取消 Flow（用户退出对话、换了一张图）时 `finally` 里 `call.cancel()`。
 * 不关的话引擎会**继续把这一张算完** —— 那是几十秒满负载与发热，
 * 而且用户完全看不到进度，只会觉得手机发烫。
 */
class ImageGenClient(
    private val client: OkHttpClient = defaultClient(),
    private val port: Int = EngineProtocol.DEFAULT_PORT,
) : ImageGenRunner {

    /**
     * 发起一次生成。
     *
     * 事件顺序约定：若干 [ImageGenEvent.Progress]，最后一条 [ImageGenEvent.Complete]。
     * 引擎报错（HTTP 非 2xx）时抛 [IOException]，由调用方转成工具的分类错误。
     */
    override fun generate(params: ImageGenParams): Flow<ImageGenEvent> = flow {
        val request = Request.Builder()
            .url(EngineProtocol.generateUrl(port))
            .post(ImageGenRequest.toJson(params).toRequestBody(JSON))
            .build()
        val call = client.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    // **正文必须带上**：引擎把失败原因写在正文里（OOM 的措辞就在那儿），
                    // 只报状态码会让上层无法分类 —— 而「OOM 自动降一档重试」
                    // （文档 §12）完全依赖这个分类。
                    val detail = runCatching { response.body?.string() }
                        .getOrNull()
                        .orEmpty()
                        .trim()
                        .take(300)
                    throw IOException(
                        "生成请求失败：HTTP " + response.code +
                            if (detail.isBlank()) "" else "：" + detail,
                    )
                }
                val source = response.body?.source()
                    ?: throw IOException("生成响应没有 body")
                while (currentCoroutineContext().isActive) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    // 未知 type / 坏行返回 null：跳过，不中断整条流。
                    // 引擎升级后多一种消息不该让这一张图作废。
                    val event = NdjsonReader.parseLine(line) ?: continue
                    emit(event)
                }
            }
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private val JSON = "application/json".toMediaType()

        /**
         * 生成用 client：**读超时无限**。
         *
         * 引擎在 CPU 上跑 512×512/20 步可能就是几分钟，任何有限读超时都会在
         * 计算中途把连接掐断（表现为「生成到一半失败」，而引擎其实还在算）。
         * 真正的上限由用户取消与 [ImageGenClient] 的取消语义保证。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
