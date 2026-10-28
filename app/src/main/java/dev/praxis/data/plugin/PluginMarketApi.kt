package dev.praxis.data.plugin

import dev.praxis.core.plugin.ManifestParser
import dev.praxis.core.plugin.MarketApi
import dev.praxis.core.plugin.MarketAsset
import dev.praxis.core.plugin.MarketEndpoints
import dev.praxis.core.plugin.MarketEntryDetail
import dev.praxis.core.plugin.MarketJsonParser
import dev.praxis.core.plugin.MarketPage
import dev.praxis.core.plugin.MarketSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 基于 OkHttp 的 [MarketApi] 实现。
 *
 * ## 只用「静态列表」与「直链下载」两个端点
 *
 * 市场有两个域名：
 *
 * | 用途 | 域名 | 鉴权 |
 * |---|---|---|
 * | 列表（预生成 JSON） | `static.operit.app` | 无 |
 * | 详情 / 资产下载 | `api.operit.app` | 需要 GitHub session |
 *
 * 实测（2026-10-29）：`static.operit.app/market/v2/lists/all/updated/page-1.json`
 * 返回 200 / 645KB / 1540 条；而 `api.operit.app` 的所有路径返回 404。
 *
 * 所以这里不调 [MarketApi.fetchEntry]（会失败），也不走
 * `assets/{id}/download` 中转 —— **列表条目里已经带了 `assets[].url`**，
 * 那是作者的 GitHub Release 直链，直接 GET 即可。
 *
 * 代价：拿不到「完整 Markdown 说明 + 版本历史」。换来的是「市场真的能用」。
 * 宁可少一个详情页，也不做一个点进去永远转圈的详情页。
 *
 * ## 为什么不做分页缓存
 *
 * 一页 100 条、645KB。用户在列表页滚动加载十几页是正常行为，但
 * 每次回列表页都重拉第一页纯属浪费。`OkHttpClient` 自带 HTTP 缓存，
 * 服务端给了 ETag / Cache-Control 就已经有缓存了 —— 自己在内存里再存一层
 * 只会引入「缓存什么时候失效」的问题。
 */
class PluginMarketApi(
    private val client: OkHttpClient = defaultClient(),
    /** 关掉后所有调用返回空/失败（隐私模式）。 */
    override val isEnabled: Boolean = true,
) : MarketApi {

    override suspend fun listPage(
        sort: MarketSort,
        page: Int,
        type: String?,
    ): Result<MarketPage> = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext Result.success(MarketPage(emptyList(), page, false))

        val url = if (type.isNullOrBlank()) {
            MarketEndpoints.allListUrl(sort, page)
        } else {
            MarketEndpoints.typeListUrl(type, sort, page)
        }
        runCatching {
            val body = getText(url)
            // 复用 ManifestParser 而不是引一个 JSON 库：`core:plugin` 已经有
            // 一个能解析 JSON 与 HJSON 的解析器（为清单文件写的），
            // 市场返回的就是普通 JSON，是它的子集。
            val root = ManifestParser.parse(body, url).getOrThrow()
            MarketJsonParser.parseList(root, page)
        }
    }

    /**
     * 拉详情。
     *
     * ## 这个方法会失败，而且是有意为之
     *
     * `api.operit.app` 需要 GitHub session，本项目没有也不该有。
     * 保留实现而不是抛「不支持」：万一将来服务端开放了匿名访问，
     * 这里不需要改。调用方必须准备好拿到失败 —— 界面不依赖它。
     */
    override suspend fun fetchEntry(marketId: String): Result<MarketEntryDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = getText(MarketEndpoints.entryUrl(marketId))
                val root = ManifestParser.parse(body, marketId).getOrThrow()
                MarketJsonParser.parseDetail(root) ?: error("详情响应里没有条目")
            }
        }

    override suspend fun download(
        asset: MarketAsset,
        destinationPath: String,
    ): Result<Long> = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext Result.failure(IllegalStateException("市场已关闭"))
        runCatching {
            // 直链下载，不走 assets/{id}/download：后者需要 session。
            val url = asset.downloadUrl.ifBlank {
                error("条目没有给出下载地址")
            }
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("下载失败：HTTP ${response.code}")
                }
                val body = response.body ?: error("下载响应没有正文")
                val target = File(destinationPath)
                target.parentFile?.mkdirs()
                // 先写 .part 再改名：中途失败/取消不会留下半个包被误当成完整文件。
                val part = File(destinationPath + ".part")
                part.outputStream().use { out -> body.byteStream().copyTo(out) }
                if (!part.renameTo(target)) {
                    part.delete()
                    error("无法写入 $destinationPath")
                }
                target.length()
            }
        }
    }

    /**
     * 上报下载次数。
     *
     * 与 [download] 打的是同一个地址（服务端把两者合并）。所以「下载成功」
     * 已经计过数了，这里**不重复请求** —— 回成功即可。
     * 详见 [MarketEndpoints.trackDownloadUrl] 的说明。
     */
    override suspend fun trackDownload(assetId: String): Result<Unit> = Result.success(Unit)

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            // 服务端对无 UA 的请求返回 403（nginx 默认策略）。
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}：$url")
            return response.body?.string() ?: error("响应没有正文：$url")
        }
    }

    companion object {
        private const val USER_AGENT = "Praxis-Android/1.0 (plugin-market)"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 列表文件 645KB，移动网络下 10 秒不够。
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // 市场是公开 CDN，不需要 cookie；关掉可以让请求少一层状态。
            .build()
    }
}
