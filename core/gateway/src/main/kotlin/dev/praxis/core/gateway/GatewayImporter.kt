package dev.praxis.core.gateway

import kotlinx.serialization.json.Json

/**
 * 网关配置导入：解析 + 出报告。
 *
 * ## 本期只出报告（P0）
 *
 * 文档 §11 的 P0 产出写的是「GatewayImporter/Exporter（只出报告）」。
 * 也就是：**先证明能读懂网关的导出文件，再谈落库**。落库要等 P1 的账号表。
 * 这样做的价值是：导入格式错了会在这一层立刻暴露，而不是等表建好才发现
 * 字段对不上。
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
     * 生成导入报告（不落库）。
     *
     * @param files 文件名 → JSON 正文。用文件名是因为失败项要能指向**哪个文件**。
     * @param existingIds 已存在的账号 id（同 id 记为「更新」，文档 §8.6）
     * @param knownProviders Praxis 认识的 provider id；`custom-*` 一律放行
     */
    fun report(
        files: Map<String, String>,
        existingIds: Set<String>,
        knownProviders: Set<String>,
    ): GatewayImportReport {
        val added = mutableListOf<GatewayImportReport.Item>()
        val updated = mutableListOf<GatewayImportReport.Item>()
        val skipped = mutableListOf<GatewayImportReport.Item>()
        val failed = mutableListOf<GatewayImportReport.Item>()
        // 同 provider + baseUrl 的账号出现第二次时提示「可能是重复账号」。
        val seenEndpoints = mutableSetOf<String>()

        files.forEach { (fileName, text) ->
            val parsed = parse(text)
            val file = parsed.getOrNull()
            if (file == null) {
                failed += GatewayImportReport.Item(
                    id = fileName,
                    name = fileName,
                    reason = parsed.exceptionOrNull()?.message,
                )
                return@forEach
            }

            val display = file.name.ifBlank { file.id }
            if (file.apiKey.isNullOrBlank()) {
                val isOauth = !file.accessToken.isNullOrBlank() ||
                    !file.refreshToken.isNullOrBlank()
                skipped += GatewayImportReport.Item(
                    id = file.id,
                    name = display,
                    reason = if (isOauth) {
                        "一期仅支持 API Key 型；OAuth 账号需要续期实现，暂不导入"
                    } else {
                        "文件里没有可用凭证（apiKey / accessToken / refreshToken 都为空）"
                    },
                )
                return@forEach
            }

            val isCustom = file.provider.startsWith("custom-")
            if (!isCustom && file.provider !in knownProviders) {
                skipped += GatewayImportReport.Item(
                    id = file.id,
                    name = display,
                    reason = "Praxis 不认识这家提供商：" + file.provider,
                )
                return@forEach
            }

            val endpointKey = file.provider + "|" + file.baseUrl.orEmpty()
            val duplicate = !seenEndpoints.add(endpointKey)
            val item = GatewayImportReport.Item(
                id = file.id,
                name = display,
                reason = if (duplicate) "可能是重复账号（同提供商与同地址）" else null,
            )
            if (file.id in existingIds) updated += item else added += item
        }

        return GatewayImportReport(
            added = added,
            updated = updated,
            skipped = skipped,
            failed = failed,
        )
    }
}
