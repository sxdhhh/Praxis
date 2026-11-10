package dev.praxis.core.gateway

import kotlinx.serialization.json.Json

/**
 * 一份可执行的导入计划：既能落库的东西，也是给用户看的报告。
 *
 * ## 为什么要「计划」而不只是「报告」
 *
 * P0 只要报告（先证明读得懂）。等 P1 有账号表之后，调用方真正需要的是
 * 「哪些账号该写进去」——如果那时再重新解析一遍，两处规则就会各自演化。
 * 所以现在就把 accepted 和 report 一起交出去，规则只有一份。
 */
data class GatewayImportPlan(
    /** 通过全部检查的账号：新增与更新都在里面（是否覆盖由 id 是否已存在决定）。 */
    val accepted: List<GatewayAccountFile>,
    /** 账号文件里带出来的自定义提供商（按 id 去重，保持首次出现的顺序）。 */
    val providers: List<GatewayCustomProvider>,
    val report: GatewayImportReport,
)

/**
 * 网关账号文件导入：解析 + 过滤 + 出计划。
 *
 * ## 两个入口，一份规则
 *
 * - [prepare]：交出「哪些账号该写进去」+ 报告（P1 落库用）
 * - [report]：只要报告（P0 的用法）
 *
 * report 是 prepare 的一层薄委托。规则只写一遍，否则两处会各自演化 ——
 * 那种分歧的表现是「报告说导入了 5 个，实际只写进去 3 个」。
 *
 * ## 为什么先只出报告，再落库
 *
 * 文档 §11 的 P0 产出就是「只出报告」：先证明能读懂网关的导出文件，再谈写库。
 * 导入格式错了会在这一层立刻暴露，而不是等表建好才发现字段对不上。
 */
object GatewayImporter {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析一个账号文件。
     *
     * @return 失败时带**可直接展示给用户**的原因（缺 format / 缺 id …）
     */
    fun parse(text: String): Result<GatewayAccountFile> = runCatching {
        val file = json.decodeFromString(GatewayAccountFile.serializer(), text)
        require(file.format == ACCOUNT_FORMAT) {
            "不是网关账号文件（format=" + file.format.ifBlank { "缺失" } + "）"
        }
        require(file.id.isNotBlank()) { "缺少 id" }
        require(file.provider.isNotBlank()) { "缺少 provider" }
        file
    }

    /**
     * 批量导出形状（网关「导出全部账号」的产物）：`{"accounts":[...]}`。
     *
     * ## 为什么必须支持它
     *
     * 用户从网关面板导出的是**一整份**（顶层 accounts 数组），不是一个个单账号文件。
     * 只认单文件形状的话，他下载下来的东西在 Praxis 里会整份被判「不是账号文件」——
     * 而那是最容易让人放弃的一步。
     *
     * 字段名逐字对应真实导出（2026-11 实测的那份）：凭证是 accessToken/refreshToken
     * （OAuth 型）、地址在 `endpoint`、家是 `provider`。
     */
    @kotlinx.serialization.Serializable
    private data class BulkAccountFile(val accounts: List<BulkAccountEntry> = emptyList())

    @kotlinx.serialization.Serializable
    private data class BulkAccountEntry(
        val id: String = "",
        val provider: String = "",
        val name: String = "",
        val nickname: String = "",
        val apiKey: String? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        /** 真实导出里地址叫 endpoint。 */
        val endpoint: String? = null,
        val baseUrl: String? = null,
    )

    /**
     * 解析一份文本，得到其中的**全部**账号。
     *
     * 先按单账号文件试（网关面板「导出这个账号」的产物），再按批量导出试。
     * 两种形状共用后面同一套逐条规则 —— 报告因此能精确到**哪一条**、为什么。
     */
    fun parseAll(text: String): Result<List<GatewayAccountFile>> = runCatching {
        parse(text).getOrNull()?.let { single -> return@runCatching listOf(single) }
        val bulk = runCatching {
            json.decodeFromString(BulkAccountFile.serializer(), text)
        }.getOrNull()
        val entries = bulk?.accounts
        require(!entries.isNullOrEmpty()) {
            "既不是网关账号文件（缺 format），也不是批量导出（缺 accounts 数组）"
        }
        entries.mapIndexed { index, entry ->
            require(entry.id.isNotBlank()) { "批量导出第 " + (index + 1) + " 条缺 id" }
            require(entry.provider.isNotBlank()) {
                "账号 " + entry.id + " 缺 provider"
            }
            GatewayAccountFile(
                format = ACCOUNT_FORMAT,
                id = entry.id,
                provider = entry.provider,
                name = entry.name.ifBlank { entry.nickname },
                apiKey = entry.apiKey,
                baseUrl = entry.endpoint ?: entry.baseUrl,
                accessToken = entry.accessToken,
                refreshToken = entry.refreshToken,
            )
        }
    }

    /**
     * 生成导入报告（不落库）。
     *
     * @param files 文件名 → JSON 正文。用文件名是因为失败项要能指向**哪个文件**。
     * @param existingIds 已存在的账号 id（同 id 记为「更新」，文档 §8.6）
     * @param knownProviders Praxis 认识的 provider id；`custom-*` 一律放行
     */
    fun prepare(
        files: Map<String, String>,
        existingIds: Set<String>,
        knownProviders: Set<String>,
    ): GatewayImportPlan {
        val accepted = mutableListOf<GatewayAccountFile>()
        val providers = mutableListOf<GatewayCustomProvider>()
        val added = mutableListOf<GatewayImportReport.Item>()
        val updated = mutableListOf<GatewayImportReport.Item>()
        val skipped = mutableListOf<GatewayImportReport.Item>()
        val failed = mutableListOf<GatewayImportReport.Item>()
        // 同 provider + baseUrl 的账号出现第二次时提示「可能是重复账号」。
        val seenEndpoints = mutableSetOf<String>()

        files.forEach { (fileName, text) ->
            // 一份文本可能是**多个**账号（批量导出），所以是 parseAll。
            val parsed = parseAll(text)
            val accounts = parsed.getOrNull()
            if (accounts == null) {
                failed += GatewayImportReport.Item(
                    id = fileName,
                    name = fileName,
                    reason = parsed.exceptionOrNull()?.message,
                )
                return@forEach
            }

            accounts.forEach accountLoop@ { file ->
            val display = file.name.ifBlank { file.id }
            if (file.apiKey.isNullOrBlank()) {
                val isOauth = !file.accessToken.isNullOrBlank() ||
                    !file.refreshToken.isNullOrBlank()
                // 两个原因同时成立时**都说出来**：只说一个，用户按那一条补完
                // 之后还会再撞上第二个 —— 报告的价值就在于「一次说清」。
                val providerUnknown = !file.provider.startsWith("custom-") &&
                    file.provider !in knownProviders
                val credentialReason = if (isOauth) {
                    "一期仅支持 API Key 型；OAuth 账号需要续期实现，暂不导入"
                } else {
                    "文件里没有可用凭证（apiKey / accessToken / refreshToken 都为空）"
                }
                skipped += GatewayImportReport.Item(
                    id = file.id,
                    name = display,
                    reason = if (providerUnknown) {
                        credentialReason + "；另外 Praxis 也还不认识这家提供商：" +
                            file.provider
                    } else {
                        credentialReason
                    },
                )
                return@accountLoop
            }

            val isCustom = file.provider.startsWith("custom-")
            if (!isCustom && file.provider !in knownProviders) {
                skipped += GatewayImportReport.Item(
                    id = file.id,
                    name = display,
                    reason = "Praxis 不认识这家提供商：" + file.provider,
                )
                return@accountLoop
            }

            val endpointKey = file.provider + "|" + file.baseUrl.orEmpty()
            val duplicate = !seenEndpoints.add(endpointKey)
            val item = GatewayImportReport.Item(
                id = file.id,
                name = display,
                reason = if (duplicate) "可能是重复账号（同提供商与同地址）" else null,
            )
            accepted += file
            file.customProviders.forEach { provider ->
                if (providers.none { it.id == provider.id }) providers += provider
            }
            if (file.id in existingIds) updated += item else added += item
            }
        }

        return GatewayImportPlan(
            accepted = accepted,
            providers = providers,
            report = GatewayImportReport(
                added = added,
                updated = updated,
                skipped = skipped,
                failed = failed,
            ),
        )
    }

    /** 只要报告时的便捷入口（P0 的用法）。规则与 [prepare] 同一份。 */
    fun report(
        files: Map<String, String>,
        existingIds: Set<String>,
        knownProviders: Set<String>,
    ): GatewayImportReport = prepare(files, existingIds, knownProviders).report
}
