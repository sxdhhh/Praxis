package dev.praxis.core.plugin

/**
 * 在线市场的条目摘要（列表页用）。
 *
 * ## 字段为什么这么少
 *
 * 列表页只需要「能显示、能判断是否已装、能点进去」。市场返回的 JSON
 * 有 30+ 字段（评论、反应、版本历史、发布者信息、资产清单），
 * 全部建模会让列表接口变成一个巨大的模型，且大部分字段在本页用不到。
 *
 * 需要详情时单独调 [MarketApi.fetchEntry] 拿完整条目。
 *
 * ## 类型用字符串而不是枚举
 *
 * 市场的 `type` 是服务端定义的字符串（`toolpkg` / `skill` / `mcp` /
 * `sandbox` / 未来可能新增）。用枚举会在服务端加类型时**反序列化失败**，
 * 整个列表打不开。用字符串 + [kindHint] 做软映射。
 */
data class MarketEntrySummary(
    val marketId: String,
    val title: String,
    val description: String,
    /** 服务端的原始 type 字符串。 */
    val type: String,
    val version: String,
    val authors: List<String>,
    val downloads: Int,
    val logoUrl: String?,
    val updatedAt: String?,
    /** 分类名（服务端给的展示名）。 */
    val categoryId: String = "",
    /**
     * 可下载资产（2026-10-29 加）。
     *
     * ## 为什么摘要也要带 assets
     *
     * 列表 JSON 的每条本来就带 `assets[]`（含直链 `url`），而
     * `api.operit.app/market/v2/entries/{id}` 需要 GitHub session（实测 404）。
     * 也就是说：**不把 assets 放进摘要，就没有可装的下载地址**。
     *
     * 原设计把 assets 只放在 `MarketEntryDetail` 上，是假设详情接口可用。
     * 那个假设不成立，所以把字段上移 —— 摘要比详情更重要，因为它是
     * 唯一真实可用的数据源。
     */
    val assets: List<MarketAsset> = emptyList(),
) {
    /**
     * 主资产：优先 `.toolpkg`，否则第一个有直链的。
     *
     * 与 `MarketEntryDetail.primaryAsset` 同一规则，故意不抽公共函数：
     * 两处的取舍理由不同（详情是「展示哪个版本」，摘要是「装哪个文件」），
     * 抽到一起后任何一方想改都要考虑另一方。
     */
    val primaryAsset: MarketAsset?
        get() = assets.firstOrNull { it.fileName.endsWith(".toolpkg", ignoreCase = true) }
            ?: assets.firstOrNull { it.downloadUrl.isNotBlank() }
    /**
     * 猜测它属于哪类扩展。
     *
     * 猜不到时回落 [ExtensionEntry.Kind.ToolPackage] —— 市场上绝大多数是工具包，
     * 而且回落错误只影响图标与标签，不影响安装（安装按服务端 type 走）。
     */
    val kindHint: ExtensionEntry.Kind
        get() = when (type.lowercase()) {
            "skill" -> ExtensionEntry.Kind.Skill
            "mcp", "mcpserver", "mcp_server" -> ExtensionEntry.Kind.McpServer
            "sandbox", "sandboxpackage", "sandbox_package" -> ExtensionEntry.Kind.SandboxPackage
            else -> ExtensionEntry.Kind.ToolPackage
        }
}

/** 市场条目详情：比摘要多出「从哪下载」与「完整说明」。 */
data class MarketEntryDetail(
    val summary: MarketEntrySummary,
    /** 完整 Markdown 说明（详情页展示）。 */
    val detail: String,
    /** 版本历史（最新在前）。 */
    val versions: List<MarketVersion>,
    /** 可下载资产。工具包/沙盒包通常是一个 `.toolpkg` 文件。 */
    val assets: List<MarketAsset>,
) {
    /** 最新版本号；没有版本时回落摘要里的 version。 */
    val latestVersion: String
        get() = versions.firstOrNull()?.version?.takeIf { it.isNotBlank() } ?: summary.version

    /** 主资产（优先 `.toolpkg`，否则第一个可下载的）。 */
    val primaryAsset: MarketAsset?
        get() = assets.firstOrNull { it.fileName.endsWith(".toolpkg", ignoreCase = true) }
            ?: assets.firstOrNull { it.downloadUrl.isNotBlank() }
}

data class MarketVersion(
    val versionId: String,
    val version: String,
    val createdAt: String? = null,
    /** 更新说明。 */
    val changelog: String = "",
)

data class MarketAsset(
    val assetId: String,
    val fileName: String,
    val downloadUrl: String,
    /** 字节数；0 表示服务端没给。 */
    val sizeBytes: Long = 0,
    /** 服务端给的 sha256（用于下载后校验）。空表示没给。 */
    val sha256: String = "",
)

/** 市场列表的排序方式（与 operit 的 URL 段一致）。 */
enum class MarketSort(val pathSegment: String, val label: String) {
    Updated("updated", "最近更新"),
    Downloads("downloads", "下载最多"),
    Likes("likes", "点赞最多"),
    Newest("newest", "最新发布"),
}

/**
 * 在线市场接口。
 *
 * ## 为什么是接口而不是直接用 HTTP
 *
 * 三件事需要替换：单测（不起网络）、自建镜像（`api.operit.app` 不可达时
 * 用户可以填自己的镜像）、以及「市场关掉」的隐私模式（返回空列表而不是报错）。
 *
 * ## 与 Operit 的端点对应
 *
 * 逐段对齐（见 operit `MarketStatsApiService` 的 `*PathSegments`）：
 *
 * | 用途 | 路径 |
 * |---|---|
 * | 列表 | `market/v2/lists/all/{sort}/page-{n}.json`（静态） |
 * | 按类型 | `market/v2/lists/type/{type}/{sort}/page-{n}.json` |
 * | 按分类 | `market/v2/lists/category/{id}/{sort}/page-{n}.json` |
 * | 详情 | `market/v2/entries/{id}`（动态） |
 * | 下载 | `market/v2/assets/{id}/download`（动态） |
 *
 * 静态路径走 `static.operit.app`（CDN 缓存，无鉴权），
 * 动态路径走 `api.operit.app`（需要 GitHub session）。
 * 这个区分不是我们的设计而是服务端的 —— 静态列表是预生成的 JSON 文件，
 * 详情/下载是实时接口，混用会 404。
 */
interface MarketApi {

    /** 市场是否启用（关闭后所有调用返回空/失败，不抛异常）。 */
    val isEnabled: Boolean

    /** 拉列表一页。 */
    suspend fun listPage(
        sort: MarketSort = MarketSort.Updated,
        page: Int = 1,
        type: String? = null,
    ): Result<MarketPage>

    /** 拉详情。 */
    suspend fun fetchEntry(marketId: String): Result<MarketEntryDetail>

    /**
     * 下载包到本地文件。
     *
     * @param destinationPath 目标绝对路径
     * @return 下载后的字节数
     */
    suspend fun download(asset: MarketAsset, destinationPath: String): Result<Long>

    /** 上报下载次数（失败不影响安装，服务端统计用）。 */
    suspend fun trackDownload(assetId: String): Result<Unit>

    companion object {
        /**
         * 「市场关闭」的空实现。
         *
         * ## 为什么放在接口上而不是让每处调用点传 null
         *
         * `SettingsHost` 里 `pluginMarket` 是可空参数（DI 没装配时就是 null），
         * 而 UI 层不该为「市场可能不存在」写一堆判空分支 —— 那会让
         * 真正的空状态逻辑（`marketEnabled = false` 时显示「市场已关闭」）
         * 被淹没在 null 检查里。
         *
         * 这个实现让「没有市场」变成一个**正常对象**：[isEnabled] 为 false，
         * 列表返回空页、下载返回失败。UI 照常渲染、照常显示「市场已关闭」。
         */
        fun disabled(): MarketApi = DisabledMarket
    }
}

/** [MarketApi.disabled] 的单例。无状态，所以可以直接复用。 */
private object DisabledMarket : MarketApi {
    override val isEnabled: Boolean = false

    override suspend fun listPage(
        sort: MarketSort,
        page: Int,
        type: String?,
    ): Result<MarketPage> = Result.success(MarketPage(emptyList(), page, false))

    override suspend fun fetchEntry(marketId: String): Result<MarketEntryDetail> =
        Result.failure(IllegalStateException("市场未启用"))

    override suspend fun download(asset: MarketAsset, destinationPath: String): Result<Long> =
        Result.failure(IllegalStateException("市场未启用"))

    override suspend fun trackDownload(assetId: String): Result<Unit> = Result.success(Unit)
}

/** 一页市场列表。 */
data class MarketPage(
    val entries: List<MarketEntrySummary>,
    val page: Int,
    val hasMore: Boolean,
    /** 服务端生成时间（用户可见「数据更新时间」）。 */
    val generatedAt: String? = null,
)