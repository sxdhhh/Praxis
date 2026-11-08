package dev.praxis.core.data.gateway

import dev.praxis.core.gateway.AccountView
import dev.praxis.core.gateway.NativeModel
import dev.praxis.core.gateway.GatewayAccountRow
import dev.praxis.core.gateway.GatewayAccountDraft
import dev.praxis.core.gateway.GatewayAccountDraftRules
import dev.praxis.core.gateway.GatewayCredential
import dev.praxis.core.gateway.GatewayCredentialCodec
import dev.praxis.core.gateway.userMessage
import dev.praxis.core.gateway.GatewayCustomProvider
import dev.praxis.core.gateway.GatewayImportPlan
import dev.praxis.core.gateway.RouteState
import dev.praxis.core.gateway.toCredential

/** 一次导入实际写进去的东西。 */
data class StoredImport(
    val accountIds: List<String>,
    val providerIds: List<String>,
    /**
     * 因为**没有地址**而没落库的自定义家。
     *
     * 网关账号文件里 baseUrl 是可空的，而 provider 表的地址列非空。把空地址
     * 写进去，只会在真正发请求时以更远的方式炸掉（「为什么这个家一直失败」）。
     * 所以这里不写，并列出来让调用方提示用户补地址。
     */
    val providersWithoutBaseUrl: List<String>,
)

/**
 * 账号池的落库层：导入计划 -> 两张表。
 *
 * ## 加密函数为什么是注入进来的
 *
 * KeystoreCipher 是 Android API，直接用会让这一层只能上设备测。把它做成
 * (String) -> String? 之后，落库的全部规则（全有或全无、保留用户的选择、
 * 顺序）都能在纯 JVM 上验。
 *
 * ## 一处对既有约定的**有意偏离**（文档 8.7 第 1 条）
 *
 * KeystoreCipher 的既有约定是「加密失败就存明文，宁可退回可用状态」。
 * 网关凭证不能这么办：那等于用户以为密钥加密了、实际明文躺在库里。
 * 这里加密失败会让**整次导入失败**，一条都不写，并在错误里点名是哪个账号。
 *
 * ## 重复导入不覆盖用户的手动设置
 *
 * 文档 8.6 说同 id 存在就覆盖（updated）。但「覆盖」指的是网关文件带来的字段
 * （名称、地址、凭证），不包括用户在 Praxis 里做过的选择：
 *
 * - enabled：用户手动停用过的账号，导入后不该被重新启用；
 * - priority：用户拖拽调过的顺序，导入后不该被重置；
 * - maxConcurrent：同上；
 * - createdAt：这是「什么时候加的」，不是被导入覆盖的内容。
 */
class GatewayAccountStore(
    private val accountDao: GatewayAccountDao,
    private val providerDao: GatewayProviderDao,
    /** 加密；返回 null 表示加密失败。 */
    private val encrypt: (String) -> String?,
    /** 解密；返回 null 表示解不出（密钥换了等）。 */
    private val decrypt: (String) -> String?,
    private val clock: () -> Long,
) {

    /**
     * 应用一次导入。
     *
     * 分两段：先全部校验与加密，再统一写。这样加密半途失败时不会留下
     * 「写进去一半账号」的仓库 —— 那种状态用户没法收拾。
     */
    suspend fun applyImport(plan: GatewayImportPlan): Result<StoredImport> {
        val prepared = mutableListOf<GatewayAccountEntity>()

        plan.accepted.forEach { file ->
            val credential = file.toCredential().getOrElse { return Result.failure(it) }
            val plain = GatewayCredentialCodec.encode(credential)
            val cipherText = encrypt(plain) ?: return Result.failure(
                IllegalStateException(
                    "账号 " + file.id + " 的凭证加密失败，已中止导入（不会写明文）",
                ),
            )

            val existing = accountDao.byId(file.id)
            prepared += GatewayAccountEntity(
                id = file.id,
                providerId = file.provider,
                name = file.name,
                baseUrlOverride = file.baseUrl?.takeIf { it.isNotBlank() },
                enabled = existing?.enabled ?: true,
                priority = existing?.priority ?: prepared.size,
                maxConcurrent = existing?.maxConcurrent ?: 0,
                credentialsCipher = cipherText,
                expiringAt = existing?.expiringAt,
                createdAt = existing?.createdAt ?: clock(),
            )
        }

        prepared.forEach { accountDao.upsert(it) }

        val now = clock()
        val providersWithoutBaseUrl = mutableListOf<String>()
        val providerIds = mutableListOf<String>()
        plan.providers.forEach { provider ->
            val previous = providerDao.byId(provider.id)
            val entity = provider.toEntity(
                now = now,
                previousCreatedAt = previous?.createdAt,
            )
            if (entity == null) {
                providersWithoutBaseUrl += provider.id
            } else {
                providerDao.upsert(entity)
                providerIds += provider.id
            }
        }

        return Result.success(
            StoredImport(
                accountIds = prepared.map { it.id },
                providerIds = providerIds,
                providersWithoutBaseUrl = providersWithoutBaseUrl,
            ),
        )
    }

    /**
     * 手动添加一个账号（文档 §9.3 的三级流程终点）。
     *
     * ## 与 [applyImport] 同一套纪律
     *
     * 先校验、再加密、最后才写；加密失败**一个字节都不写** ——
     * 半写进去的账号用户没法收拾（删都不知道删哪条）。
     *
     * ## id 为什么用 custom- 前缀
     *
     * 与网关的命名习惯一致：内置家有固定 id，用户自己加的用 `custom-<12 位>`。
     * 两批账号放在同一个池子里参与选路，前缀只是让人一眼看出出处。
     */
    suspend fun addAccount(draft: GatewayAccountDraft): Result<GatewayAccountEntity> {
        val error = GatewayAccountDraftRules.validate(draft)
        if (error != null) {
            return Result.failure(IllegalArgumentException(error.userMessage()))
        }
        val normalized = GatewayAccountDraftRules.normalize(draft)
        val cipher = encrypt(
            GatewayCredentialCodec.encode(GatewayCredential.ApiKey(normalized.apiKey)),
        ) ?: return Result.failure(
            IllegalStateException("凭证加密失败，已中止（不会写明文）"),
        )
        val existing = accountDao.all()
        val entity = GatewayAccountEntity(
            id = newAccountId(),
            providerId = normalized.providerId,
            name = normalized.name,
            baseUrlOverride = normalized.baseUrl.takeIf { it.isNotBlank() },
            enabled = true,
            priority = (existing.maxOfOrNull { row -> row.priority } ?: -1) + 1,
            maxConcurrent = 0,
            credentialsCipher = cipher,
            expiringAt = null,
            createdAt = clock(),
        )
        accountDao.upsert(entity)
        return Result.success(entity)
    }

    /** `custom-<12 位小写十六进制>`，与网关的 id 形状一致。 */
    private fun newAccountId(): String =
        "custom-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    /** 读出某账号的凭证。解不出时返回失败，不返回空凭证。 */
    suspend fun credentialOf(id: String): Result<GatewayCredential> {
        val entity = accountDao.byId(id)
            ?: return Result.failure(IllegalStateException("没有这个账号：" + id))

        val plain = decrypt(entity.credentialsCipher)
            ?: return Result.failure(
                IllegalStateException("账号 " + id + " 的凭证解不开（密钥可能已更换），需要重新填写"),
            )

        return GatewayCredentialCodec.decode(plain)
    }

    suspend fun all(): List<GatewayAccountEntity> = accountDao.all()

    /**
     * 选路要的账号视图。
     *
     * hasCredentials 用「能不能解密」来判定：解不开就等同于没有凭证
     * （文档 12：Keystore 重置时不要整池崩，只让该账号不可用）。
     */
    /** 自定义提供商（含各家声明的模型清单）。 */
    suspend fun providers(): List<GatewayProviderEntity> = providerDao.all()

    /**
     * 各家声明的模型清单 -> 候选链的「原生承载」（文档 7.1）。
     *
     * ## 清单从哪来
     *
     * provider 表的 modelsJson：它是**账号文件里带的 models 数组**（8.2），
     * 也就是「这家有哪些模型」的第一手信息。没有它，原生承载只能是空的，
     * 请求就只能靠 modelRules 的映射 —— 那对大多数用户等于「一条都发不出去」。
     *
     * 空行与本该是数字的东西不会被当成模型名：清单里只保留非空行。
     */
    suspend fun nativeModels(): List<NativeModel> = providerDao.all().flatMap { provider ->
        provider.modelsJson
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { NativeModel(provider = provider.id, id = it) }
    }

    suspend fun routingViews(): List<AccountView> = accountDao.all().map { entity ->
        entity.toAccountView(hasCredentials = decrypt(entity.credentialsCipher) != null)
    }

    /**
     * 组装界面要的行模型。
     *
     * 家名与「该账号承载哪些上游模型」都由调用方给：前者要查内置家清单
     * （在 :app 用插件/常量表翻译），后者来自模型条目表。这里不猜、不兜底 ——
     * 传空列表就等于「还不知道它承载什么」，状态推导会据此不判定限额。
     */
    suspend fun accountRows(
        state: RouteState,
        providerLabelOf: (String) -> String,
        wireModelsOf: (String) -> List<String>,
    ): List<GatewayAccountRow> = accountDao.all().map { entity ->
        GatewayAccountRow.from(
            id = entity.id,
            name = entity.name,
            providerLabel = providerLabelOf(entity.providerId),
            enabled = entity.enabled,
            wireModels = wireModelsOf(entity.id),
            state = state,
        )
    }
}

/**
 * 账号文件里的自定义提供商 -> 表行。
 *
 * @return null 表示这家没有地址，不该落库（见 StoredImport.providersWithoutBaseUrl）
 *
 * modelsJson 用换行分隔的模型清单（账号文件里带着 models 数组）；mappingsJson
 * 留空：映射在 config.json 的 modelRules 里，不在账号文件里，这一步不伪造。
 *
 * @param previousCreatedAt 已存在时沿用它：这是「什么时候加的」，不是被导入覆盖的内容。
 */
fun GatewayCustomProvider.toEntity(now: Long, previousCreatedAt: Long? = null): GatewayProviderEntity? {
    val address = baseUrl?.takeIf { it.isNotBlank() } ?: return null
    return GatewayProviderEntity(
        id = id,
        name = name,
        baseUrl = address,
        modelsJson = models.joinToString("\n"),
        mappingsJson = "",
        createdAt = previousCreatedAt ?: now,
    )
}
