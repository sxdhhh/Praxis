package dev.praxis.core.gateway

import kotlinx.serialization.json.Json

/**
 * 网关配置导出：领域模型 -> 网关原生 JSON。
 *
 * ## 为什么必须与导入配对实现（文档 8.5）
 *
 * 验收写的是「双向可逆」：Praxis 导出的要被 Agent2API 原样导入，反之亦然。
 * 只做导入不做导出，这条永远验不了；只做导出不做往返测试，字段改错也发现不了。
 *
 * ## 两种粒度
 *
 * - 仅配置（默认）：没有凭证，apiKey 写成占位；
 * - 含凭证：明文 apiKey，文件名强制带 -secrets。
 *
 * 文档只说了 apiKey 要打码。这里把 accessToken / refreshToken 也在「仅配置」
 * 粒度下清空 —— 那一档的语义是「无凭证」，留着 OAuth 令牌与「无凭证」自相矛盾，
 * 也和「含凭证要二次确认」的保护不一致。
 */
object GatewayExporter {

    /** 「仅配置」粒度下 apiKey 的占位值。 */
    const val REDACTED: String = "***"

    private val json = Json {
        prettyPrint = true
        // 默认值不写出：null 与空集合自然消失，导出的文件干净且与网关一致。
        encodeDefaults = false
    }

    /**
     * 导出一个账号文件。
     *
     * @param includeSecrets true 时写明文凭证；false 时 apiKey 打码、OAuth 令牌清空
     */
    fun accountFile(account: GatewayAccountFile, includeSecrets: Boolean): String {
        val safe = if (includeSecrets) {
            account
        } else {
            account.copy(
                apiKey = REDACTED,
                accessToken = null,
                refreshToken = null,
            )
        }
        return json.encodeToString(GatewayAccountFile.serializer(), safe)
    }

    /**
     * 账号文件名。
     *
     * 含凭证时强制加 -secrets（文档 8.5）：名字本身就是最后一道提醒，
     * 用户把这个文件发出去之前至少会看见那三个字。
     *
     * @param base 形如 openai-工作号-20261107-001（不含扩展名）
     */
    fun accountFileName(base: String, includeSecrets: Boolean): String {
        val trimmed = base.trim().ifEmpty { "account" }
        val suffix = if (includeSecrets) "-secrets" else ""
        return trimmed + suffix + ".json"
    }
}
