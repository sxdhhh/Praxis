package dev.praxis.core.gateway

/** 模型用途。 */
enum class ModelKind {
    Chat,
    Image,
}

/** 条目从哪来。 */
enum class ModelSource {
    /** 上游清单里本来就有。 */
    Native,

    /** 用户手填的。 */
    Manual,

    /** 由 models.dev 补全的元数据。 */
    ModelsDev,
}

/**
 * 一条结构化的模型条目（文档 6.3，对应网关 catalog 的一行）。
 *
 * ## 能力位为什么是三态
 *
 * null = 未声明，按文档 7.2 的「缺键放行」处理。用 Boolean 默认 false 会把
 * 「不知道」变成「不支持」—— 老账号一个能力位都没声明过，那样会让所有带图
 * 请求无号可用。
 */
data class ModelEntry(
    val provider: String,
    val id: String,
    val name: String = id,
    val kind: ModelKind = ModelKind.Chat,
    val source: ModelSource,
    val enabled: Boolean = true,
    val credits: Double? = null,
    val supportsToolCall: Boolean? = null,
    val supportsImages: Boolean? = null,
    val supportsVideo: Boolean? = null,
    val supportsReasoning: Boolean? = null,
    val supportsImageGeneration: Boolean? = null,
    val supportsImageEditing: Boolean? = null,
    val inputModalities: List<String> = emptyList(),
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val reasoningEfforts: List<String> = emptyList(),
    val reasoningDefault: String? = null,
    val reasoningFixed: String? = null,
    val reasoningSource: String? = null,
)

/** 迁移结果。 */
data class ModelEntryMigrationResult(
    /** 与老列表等长同序：下标语义由此保住。 */
    val entries: List<ModelEntry>,
    /**
     * 老列表里空段的下标。
     *
     * 空段被保留成条目（否则后面的下标全部前移，档案里的当前模型下标就指错了），
     * 位置单独报出来，让调用方在迁移之后再决定要不要清理 —— 那是用户的决定，
     * 不是迁移该替他做的。
     */
    val blankIndices: List<Int>,
)

/**
 * 老模型池 -> 结构化条目（文档 6.3 的迁移段）。
 *
 * ## 三条不能违反的约束
 *
 * 1. 等长同序：文档写明「modelIndex 语义保留（指向新列表的下标）」。
 *    少一条、换一次序，用户升级后看到的模型就变了 —— 那比不迁移更糟。
 * 2. 不合并重复项：同样是为了下标。两条同名模型在列表里就是两个位置。
 * 3. 不 trim：trim 会改名，而这批条目的 name 就是用户当初填的名字。
 *
 * 所以这一层只做「包一层」：给每个位置补上 provider / source / enabled 与
 * 三态能力位（全 null = 未声明），别的一概不动。
 */
object ModelEntryMigration {

    /**
     * @param pool 老配置里的模型池（调用方已按逗号切好，保持原样传进来）
     * @param provider 该套配置的服务商；老池里没有这个信息，只能由档案提供
     */
    fun migrate(pool: List<String>, provider: String): ModelEntryMigrationResult {
        val blank = mutableListOf<Int>()
        val entries = pool.mapIndexed { index, raw ->
            if (raw.isBlank()) blank += index
            ModelEntry(
                provider = provider,
                id = raw,
                name = raw,
                kind = ModelKind.Chat,
                // 老池里的名字是用户手填的，来源就是 Manual。
                source = ModelSource.Manual,
                enabled = true,
                // 能力位与额度全部留 null：老配置里没有这些信息，
                // 填 false 或 0 会把「不知道」说成「不支持」。
                credits = null,
            )
        }
        return ModelEntryMigrationResult(entries = entries, blankIndices = blank)
    }
}
