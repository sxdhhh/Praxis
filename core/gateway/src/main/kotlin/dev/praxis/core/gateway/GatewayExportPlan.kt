package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 导出产物里的一个文件。
 *
 * [relativePath] 相对 `exports/` 目录，用 `/` 分隔；真实目录由 :app 侧拼上再写盘。
 *
 * 放在 core 而不是 :app：文件**叫什么**是规格（文档 8.5 给了命名形状），
 * 而「写到哪里」才是平台细节 —— 前者要能在无设备环境下断言。
 */
data class GatewayExportFile(
    val relativePath: String,
    val content: String,
)

/**
 * 一份导出计划：要写哪些文件，以及**这一轮明确没包含什么**。
 *
 * ## 为什么 notes 是计划的一部分
 *
 * 文档 8.4 给导入定了规矩：服务端专属的键必须逐条列出，不能静默丢弃。
 * 导出这侧同理 —— apiKeys 现在导不出来（Praxis 还没有网关 Key 功能），
 * 若什么都不说，用户会以为这份备份是完整的。notes 就是那句「逐条说明」。
 */
data class GatewayExportPlan(
    val files: List<GatewayExportFile>,
    val notes: List<String>,
)

/**
 * 组装导出计划（文档 8.5）。
 *
 * ## 只做编排，不碰磁盘
 *
 * 这一层是纯 JVM：文件名生成、凭证打码、配置片段合并都在这里定死，
 * 于是「到底导出了什么」可以逐条断言。写盘归 :app —— 它只负责把
 * [GatewayExportFile] 落到相对路径上。
 *
 * ## 两种粒度
 *
 * [GatewayExportPlan] 的调用方传 includeSecrets：
 * - false（默认，文档的「仅配置」）：账号文件里的凭证打码；
 * - true：写明文，且账号文件名强制带 `-secrets`。
 *
 * 配置文件两种粒度**同名**：它现在只含路由与模型映射，没有凭证，
 * 加 `-secrets` 会让用户以为里面藏着密钥。
 */
object GatewayExportPlanner {

    /** 账号文件所在子目录。 */
    const val ACCOUNTS_DIR: String = "accounts"

    /** 配置片段文件名。 */
    const val CONFIG_FILE_NAME: String = "gateway-config.json"

    /**
     * 整包文件名：网关「导出全部账号」的**真实形状**。
     *
     * 与 `accounts/<name>.json`（一账号一文件、带 format 键）并存，原因不同：
     * - `accounts/<name>.json` 是文档 §8.5 的形状，便于人看与逐条核对；
     * - 这一份是**网关认的形状**，用于「导出 → 回灌网关」这条路。
     *
     * 只写前者的话，导出在网关面板里是导不进去的 —— 那条「双向可逆」的验收
     * 就一直没成立过。
     */
    const val PACK_FILE_NAME: String = "agent2api-accounts.json"

    /** 模型映射在 config 片段里的键名（与网关 `config.json` 一致）。 */
    const val MODEL_RULES_KEY: String = "modelRules"

    /** 文件名不接受的字符（Windows 与 POSIX 的并集）。 */
    private const val ILLEGAL_CHARS: String = "\\/:*?\"<>|"

    /** 文件名长度上限：家名 + 账号名 + 日期 + 序号之后的余量。 */
    private const val NAME_LIMIT: Int = 48

    private val json = Json { prettyPrint = true }

    /**
     * @param accounts 账号池，**按用户看到的顺序**传进来（编号跟着走）
     * @param routing 路由/重试/超时配置；null 或键全空 = 不产出配置片段
     * @param modelRules 模型映射原文（Praxis 存的就是原文）；null = 不导出
     * @param date `yyyyMMdd`，注入以便测试
     */
    fun build(
        accounts: List<GatewayAccountFile>,
        routing: GatewayRoutingConfig? = null,
        modelRules: JsonElement? = null,
        includeSecrets: Boolean = false,
        date: String,
        /**
         * 是否额外产出网关能直接导入的整包（[PACK_FILE_NAME]）。
         *
         * 默认 false：老调用点与既有用例的文件清单不变。
         * :app 导出时传 true —— 那才是「回灌网关」真正需要的那一份。
         */
        emitGatewayPack: Boolean = false,
        /** 整包里带的导出时刻（毫秒），注入以便测试。 */
        exportedAt: Long = 0,
        /** 随包带出的自定义家（`custom-*` 账号的「家」）。 */
        providers: List<GatewayCustomProvider> = emptyList(),
    ): GatewayExportPlan {
        val files = mutableListOf<GatewayExportFile>()
        val notes = mutableListOf<String>()

        accounts.forEachIndexed { index, account ->
            val base = accountBaseName(account, date, index + 1)
            files += GatewayExportFile(
                relativePath = ACCOUNTS_DIR + "/" + GatewayExporter.accountFileName(base, includeSecrets),
                content = GatewayExporter.accountFile(account, includeSecrets),
            )
        }
        if (accounts.isEmpty()) {
            notes += "账号池是空的，没有账号文件可导出"
        }

        if (emitGatewayPack) {
            files += packFile(accounts, providers, includeSecrets, exportedAt)
            if (!includeSecrets) {
                notes += "整包（" + PACK_FILE_NAME + "）里的凭证已按「仅配置」粒度清空"
            }
        }

        val config = buildConfig(routing, modelRules)
        if (config != null) {
            files += GatewayExportFile(CONFIG_FILE_NAME, json.encodeToString(JsonObject.serializer(), config))
        } else {
            notes += "没有可导出的网关配置：路由与模型映射都是空的"
        }

        if (routing == null) {
            notes += "路由/重试/超时配置未包含：Praxis 还没有持久化这些设置项（状态文档 五）"
        }
        notes += "apiKeys 未包含：Praxis 还没有网关 Key 功能（文档 5.6，P6）"
        notes += "praxis-gateway-backup.json 未产出：完整档案备份还没实现"
        if (includeSecrets) {
            notes += "本次导出含明文凭证，文件名带 -secrets；不要通过聊天工具转发"
        }

        return GatewayExportPlan(files = files, notes = notes)
    }

    /**
     * 产出网关能直接导入的整包。
     *
     * 「仅配置」粒度下**清空**凭证而不是写 `***`：网关回灌时会把占位符
     * 当成真 Key 存进去，然后每一条都 401 —— 那比不带凭证更糟。
     */
    fun packFile(
        accounts: List<GatewayAccountFile>,
        providers: List<GatewayCustomProvider> = emptyList(),
        includeSecrets: Boolean = false,
        exportedAt: Long = 0,
    ): GatewayExportFile {
        val safe = if (includeSecrets) {
            accounts
        } else {
            accounts.map { it.copy(apiKey = null, accessToken = null, refreshToken = null) }
        }
        val pack = GatewayAccountsPackCodec.build(
            accounts = safe,
            providers = providers,
            exportedAt = exportedAt,
        )
        return GatewayExportFile(
            relativePath = PACK_FILE_NAME,
            content = GatewayAccountsPackCodec.encode(pack),
        )
    }

    /**
     * 合并配置片段：路由键（网关原生 camelCase）+ 模型映射原文。
     *
     * 两边都为空时返回 null —— 一个 `{}` 的空文件会让用户以为配置导出来了。
     */
    private fun buildConfig(routing: GatewayRoutingConfig?, modelRules: JsonElement?): JsonObject? {
        val routingObject: JsonObject? = routing
            ?.let { Json.parseToJsonElement(GatewayConfigExporter.encode(it)).jsonObject }
            ?.takeIf { it.isNotEmpty() }
        if (routingObject == null && modelRules == null) return null
        return buildJsonObject {
            routingObject?.forEach { (key, value) -> put(key, value) }
            modelRules?.let { put(MODEL_RULES_KEY, it) }
        }
    }

    /**
     * 账号文件名主干：`<家>-<名字>-<日期>-<序号>`（文档 8.5 的形状）。
     *
     * 名字为空时省掉那一段（`custom-3f2a91b04c7e-20261108-001`），
     * 而不是留一个空的双横线。
     */
    private fun accountBaseName(account: GatewayAccountFile, date: String, index: Int): String {
        val provider = sanitize(account.provider).ifEmpty { "account" }
        val name = sanitize(account.name)
        val serial = index.toString().padStart(3, '0')
        val namePart = if (name.isEmpty()) "" else "-" + name
        return provider + namePart + "-" + date + "-" + serial
    }

    /**
     * 净化文件名片段。
     *
     * 家名与账号名里完全可能出现 `/`（`openai/工作号`）或 `:`，
     * 直接拼进路径会写出一个打不开的文件、甚至越出 `accounts/`。
     */
    private fun sanitize(raw: String): String {
        val builder = StringBuilder()
        for (ch in raw) {
            when {
                ILLEGAL_CHARS.indexOf(ch) >= 0 -> builder.append('_')
                ch.code < 0x20 -> builder.append('_')
                else -> builder.append(ch)
            }
        }
        return builder
            .toString()
            .replace(Regex("\\s+"), "_")
            .trim()
            .trim('.')
            .take(NAME_LIMIT)
    }
}
