package dev.praxis.core.plugin

/**
 * 把市场返回的 JSON 解析成 [MarketEntrySummary] / [MarketEntryDetail]。
 *
 * ## 为什么不用 `@Serializable` data class
 *
 * 市场 JSON 有三个特点让严格反序列化很危险：
 *
 * 1. **字段可能缺失或为 null**：`authors` 有时是 `[]`、有时是 `null`、
 *    有时是逗号分隔的字符串。用 `List<String>` 的非空声明会在 null 时抛。
 * 2. **字段可能改类型**：服务端把 `downloads` 从 `Int` 改成 `String` 之类的
 *    小改动，会让**整个列表打不开**。用户看到的是「市场加载失败」。
 * 3. **未知字段极多**：30+ 字段里我们只用 10 个。
 *
 * 所以用手写解析：宽容取值（缺就默认）、单条失败跳过、整体不因一条坏数据而失败。
 *
 * ## 与 Operit 的差异：不解析评论/反应/发布者详情
 *
 * 那些是详情页社交区块用的，Praxis 第一版的市场只做「浏览 + 安装 + 更新」。
 * 解析用不到的字段只是白占内存与出错概率。
 */
object MarketJsonParser {

    /**
     * 解析列表页。
     *
     * 兼容三种根结构（市场 v2 在不同端点上返回过不同外壳）：
     *
     * ```json
     * {"items":[ … ]}
     * {"entries":[ … ]}
     * [ … ]
     * ```
     *
     * @return 解析成功的条目；单个坏条目被跳过而不是整页失败
     */
    fun parseList(root: ManifestValue, page: Int): MarketPage {
        val items = when (root) {
            is ManifestValue.Items -> root.values
            is ManifestValue.Mapping -> {
                val map = root.entries
                (map["items"] ?: map["entries"] ?: map["list"])?.asList().orEmpty()
            }
            else -> emptyList()
        }

        val entries = items.mapNotNull { item ->
            parseSummary(item as? ManifestValue.Mapping ?: return@mapNotNull null)
        }

        // 分页判断：服务端没给 total/hasMore 时，用「本页是否满」推断。
        // 不满一页 = 没有下一页，这是最可靠的启发式（比猜总数安全）。
        val explicitHasMore = (root as? ManifestValue.Mapping)
            ?.entries?.get("hasMore")?.asBooleanOrNull()
        val total = (root as? ManifestValue.Mapping)
            ?.entries?.get("total")?.asIntOrNull()
        val hasMore = explicitHasMore
            ?: total?.let { it > page * DEFAULT_PAGE_SIZE }
            ?: (entries.size >= DEFAULT_PAGE_SIZE)

        val generatedAt = (root as? ManifestValue.Mapping)
            ?.entries?.get("generatedAt")?.asText()?.takeIf { it.isNotBlank() }

        return MarketPage(
            entries = entries,
            page = page,
            hasMore = hasMore,
            generatedAt = generatedAt,
        )
    }

    /** 解析单条摘要。返回 null 表示这条连 id 都没有，无法安装/去重。 */
    fun parseSummary(node: ManifestValue.Mapping): MarketEntrySummary? {
        val fields = node.entries
        val id = fields.text("id").ifBlank { fields.text("entryId") }.trim()
        // id 是唯一键：没有它就无法「已安装」比对，也无法下载。跳过这条。
        if (id.isEmpty()) return null

        val title = fields.text("title").trim().ifBlank { fields.text("name").trim() }
        return MarketEntrySummary(
            marketId = id,
            title = title.ifBlank { id },
            description = fields.text("description").trim(),
            type = fields.text("type").trim(),
            version = latestVersionOf(fields),
            authors = parseAuthors(fields["authors"] ?: fields["author"]),
            downloads = fields["downloads"]?.asIntOrNull() ?: 0,
            logoUrl = fields.text("logoUrl").trim().takeIf { it.isNotEmpty() }
                ?: fields.text("logo").trim().takeIf { it.isNotEmpty() },
            updatedAt = fields.text("updatedAt").trim().takeIf { it.isNotEmpty() },
            categoryId = fields.text("categoryId").trim(),
            // 列表条目自带 assets（含直链 url），必须解析：
            // 详情接口需要 GitHub session，摘要里的 assets 是唯一的下载来源。
            assets = parseAssets(fields),
        )
    }

    /**
     * 解析详情。
     *
     * 详情返回的外壳在不同版本里是 `{item:{…}}` 或 `{entry:{…}}` 或直接就是条目。
     * 三种都试，避免「市场改了一层包装，客户端就整页空白」。
     */
    fun parseDetail(root: ManifestValue): MarketEntryDetail? {
        val mapping = root as? ManifestValue.Mapping ?: return null
        val fields = mapping.entries
        val entryNode = (fields["item"] as? ManifestValue.Mapping)
            ?: (fields["entry"] as? ManifestValue.Mapping)
            ?: mapping
        val summary = parseSummary(entryNode) ?: return null
        val entryFields = entryNode.entries

        val versions = entryFields["versions"]?.asList().orEmpty().mapNotNull { value ->
            val v = value as? ManifestValue.Mapping ?: return@mapNotNull null
            val version = v.entries.text("version").trim()
            if (version.isEmpty()) return@mapNotNull null
            MarketVersion(
                versionId = v.entries.text("versionId").trim().ifBlank {
                    v.entries.text("id").trim()
                },
                version = version,
                createdAt = v.entries.text("createdAt").trim().takeIf { it.isNotEmpty() },
                changelog = v.entries.text("changelog").trim()
                    .ifBlank { v.entries.text("releaseNotes").trim() },
            )
        }

        val assets = parseAssets(entryFields)

        return MarketEntryDetail(
            summary = summary,
            detail = entryFields.text("detail").trim()
                .ifBlank { entryFields.text("readme").trim() },
            versions = versions,
            assets = assets,
        )
    }

    /**
     * 解析 `assets[]`。列表与详情共用。
     *
     * ## 为什么抽出来
     *
     * 两处都要读同一个结构，而且都必须处理「assetId 缺失但 url 在」这种
     * 半残条目（老数据里有过）。复制一份的后果是修了一处漏另一处，
     * 而漏的那侧正好是「有 url 但不显示下载按钮」—— 用户会以为市场坏了。
     */
    private fun parseAssets(fields: Map<String, ManifestValue>): List<MarketAsset> =
        fields["assets"]?.asList().orEmpty().mapNotNull { value ->
            val a = value as? ManifestValue.Mapping ?: return@mapNotNull null
            val assetId = a.entries.text("id").trim().ifBlank { a.entries.text("assetId").trim() }
            val url = a.entries.text("downloadUrl").trim()
                .ifBlank { a.entries.text("url").trim() }
            if (assetId.isEmpty() && url.isEmpty()) return@mapNotNull null
            MarketAsset(
                assetId = assetId,
                fileName = a.entries.text("fileName").trim()
                    .ifBlank { a.entries.text("name").trim() },
                // 没用完整 URL 时留空，由调用方用 [MarketEndpoints.assetDownloadUrl] 拼。
                downloadUrl = url,
                // ## 文件大小必须用 Long 解析
                //
                // `asIntOrNull()` 对 `size: 3000000000`（3GB，超过 Int 上限）
                // 返回 null，于是大小显示成 0 —— 用户看到「0 字节的包」不敢装。
                // 插件包确实可能上百 MB（带资源的 toolpkg），所以必须用 Long。
                sizeBytes = a.entries.long("size")
                    ?: a.entries.long("sizeBytes")
                    ?: a.entries.long("bytes")
                    ?: 0L,
                sha256 = a.entries.text("sha256").trim(),
            )
        }

    // ── 字段容错 ─────────────────────────────────────────────

    /**
     * 取版本号。
     *
     * 市场里版本信息三处都可能出现，优先顺序：
     * `latestVersion.version` → `versions[0].version` → `version` 字符串。
     * 都不是就空串（UI 上不显示版本，不影响安装）。
     */
    private fun latestVersionOf(fields: Map<String, ManifestValue>): String {
        (fields["latestVersion"] as? ManifestValue.Mapping)
            ?.entries?.text("version")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        fields["versions"]?.asList()?.firstOrNull()
            ?.let { it as? ManifestValue.Mapping }
            ?.entries?.text("version")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return fields.text("version").trim()
    }

    /**
     * 解析作者。
     *
     * 三种形态都出现过：
     * - `["a","b"]`
     * - `"a"`
     * - `"a, b"`（逗号分隔字符串）
     * - 对象数组 `[{"login":"a"}]`（发布者信息）
     */
    private fun parseAuthors(value: ManifestValue?): List<String> {
        if (value == null) return emptyList()
        return value.asList().mapNotNull { item ->
            when (item) {
                is ManifestValue.Mapping -> {
                    item.entries.text("login").trim()
                        .ifBlank { item.entries.text("name").trim() }
                        .takeIf { it.isNotEmpty() }
                }
                else -> item.asText().trim().takeIf { it.isNotEmpty() }
            }
        }.flatMap { raw ->
            // 逗号分隔字符串拆开（但别把单个名字里的空格拆坏）。
            if (',' in raw) raw.split(',').map { it.trim() }.filter { it.isNotEmpty() } else listOf(raw)
        }.distinct()
    }

    private fun Map<String, ManifestValue>.text(key: String): String =
        this[key]?.asText().orEmpty()

    /** Long 取值。用于文件大小/时间戳这类可能超过 Int 的字段。 */
    private fun Map<String, ManifestValue>.long(key: String): Long? =
        this[key]?.asLongOrNull()

    /** 市场列表的默认页大小（服务端当前就是 20，用于「不满一页即末页」推断）。 */
    const val DEFAULT_PAGE_SIZE: Int = 20
}