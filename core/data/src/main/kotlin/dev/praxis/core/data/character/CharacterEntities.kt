package dev.praxis.core.data.character

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.praxis.core.model.character.CharacterCard
import dev.praxis.core.model.character.CharacterMemoryBindingMode
import dev.praxis.core.model.character.CharacterModelBindingMode
import dev.praxis.core.model.character.CharacterToolAccessConfig
import dev.praxis.core.model.character.PromptTag
import dev.praxis.core.model.character.PromptTagType

/**
 * 角色卡表。
 *
 * ## 为什么用 Room 而不是 DataStore 分键
 *
 * Operit 用的是 DataStore + 每字段一个 key（`character_card_<id>_name` …）。
 * 那套做法的问题在**列表查询**：取全部卡要「先读 id 集合，再对每个 id
 * 读 18 个 key」，一张卡 18 次读取，20 张卡就是 360 次 map 查找，
 * 且排序、搜索、按 `isDefault` 过滤都得在内存里做。
 *
 * Praxis 已经用 Room（`PraxisDatabase` 有 sessions/messages/memories），
 * 角色卡是标准的「一对多 + 需要列表/排序」实体，用 Room 才对：
 * 一次 `SELECT * ORDER BY ...` 拿到全部，且迁移有 schema 校验。
 *
 * ## 为什么把 List 字段存成单个 TEXT 列
 *
 * Room 不直接支持 `List<String>`（除非写 TypeConverter 或再建关联表）。
 * 这里选 TEXT + 单元分隔符（`\u001F`），而不是：
 *
 * - JSON：要多一层序列化，排序/容量都无收益，且用户手写的标签名里
 *   出现引号时容易出问题；
 * - 关联表：`attachedTagIds` 只是「引用」，不需要级联查询，
 *   建表会让「保存一张卡」变成多表事务。
 *
 * `\u001F`（Unit Separator）是 ASCII 控制符，不可能出现在用户输入里 ——
 * 用它而不用逗号/换行，是因为标签名可能含逗号（「语气：冷淡, 简洁」）。
 */
@Entity(
    tableName = "character_cards",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["isDefault"]),
    ],
)
data class CharacterCardEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val characterSetting: String,
    val openingStatement: String,
    val otherContentChat: String,
    val otherContentVoice: String,
    /** 单元分隔符连接的标签 id。 */
    @ColumnInfo(name = "attachedTagIds") val attachedTagIdsRaw: String,
    val advancedCustomPrompt: String,
    val marks: String,
    /** 存 [CharacterModelBindingMode.storage]。 */
    val chatModelBindingMode: String,
    val chatModelConfigId: String?,
    val chatModelIndex: Int,
    /** 存 [CharacterMemoryBindingMode.storage]。 */
    val memoryProfileBindingMode: String,
    val memoryProfileId: String?,
    /** 存 `CharacterToolAccessConfig` 的紧凑编码，见 [ToolAccessCodec]。 */
    val toolAccessConfigRaw: String,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 转成领域模型。 */
    fun toModel(): CharacterCard = CharacterCard(
        id = id,
        name = name,
        description = description,
        characterSetting = characterSetting,
        openingStatement = openingStatement,
        otherContentChat = otherContentChat,
        otherContentVoice = otherContentVoice,
        attachedTagIds = SplitCodec.decodeList(attachedTagIdsRaw),
        advancedCustomPrompt = advancedCustomPrompt,
        marks = marks,
        chatModelBindingMode = CharacterModelBindingMode.fromStorage(chatModelBindingMode),
        chatModelConfigId = chatModelConfigId,
        chatModelIndex = chatModelIndex,
        memoryProfileBindingMode = CharacterMemoryBindingMode.fromStorage(memoryProfileBindingMode),
        memoryProfileId = memoryProfileId,
        toolAccessConfig = ToolAccessCodec.decode(toolAccessConfigRaw),
        isDefault = isDefault,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromModel(card: CharacterCard): CharacterCardEntity = CharacterCardEntity(
            id = card.id,
            name = card.name,
            description = card.description,
            characterSetting = card.characterSetting,
            openingStatement = card.openingStatement,
            otherContentChat = card.otherContentChat,
            otherContentVoice = card.otherContentVoice,
            attachedTagIdsRaw = SplitCodec.encodeList(card.attachedTagIds),
            advancedCustomPrompt = card.advancedCustomPrompt,
            marks = card.marks,
            chatModelBindingMode = card.chatModelBindingMode.storage,
            chatModelConfigId = card.chatModelConfigId,
            chatModelIndex = card.chatModelIndex,
            memoryProfileBindingMode = card.memoryProfileBindingMode.storage,
            memoryProfileId = card.memoryProfileId,
            toolAccessConfigRaw = ToolAccessCodec.encode(card.toolAccessConfig),
            isDefault = card.isDefault,
            createdAt = card.createdAt,
            updatedAt = card.updatedAt,
        )
    }
}

/** 提示词标签表。 */
@Entity(
    tableName = "prompt_tags",
    indices = [Index(value = ["tagType"]), Index(value = ["updatedAt"])],
)
data class PromptTagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val promptContent: String,
    /** 存 [PromptTagType.storage]。 */
    val tagType: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toModel(): PromptTag = PromptTag(
        id = id,
        name = name,
        description = description,
        promptContent = promptContent,
        tagType = PromptTagType.fromStorage(tagType),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromModel(tag: PromptTag): PromptTagEntity = PromptTagEntity(
            id = tag.id,
            name = tag.name,
            description = tag.description,
            promptContent = tag.promptContent,
            tagType = tag.tagType.storage,
            createdAt = tag.createdAt,
            updatedAt = tag.updatedAt,
        )
    }
}

/** 列表 → 单列字符串的编码。 */
internal object SplitCodec {
    /**
     * 单元分隔符。
     *
     * 不用逗号/换行/竖线的理由：标签名与工具名都可能含这些字符
     * （「语气：冷淡, 简洁」），分裂后会静默增多条目。
     * `\u001F` 是 ASCII 的 Unit Separator，用户输入法打不出来。
     */
    private const val SEPARATOR = '\u001F'

    fun encodeList(values: List<String>): String =
        values.filter { it.isNotEmpty() }.joinToString(SEPARATOR.toString())

    /**
     * 解码。
     *
     * 空串 → 空列表（不是 `listOf("")`）。
     * 这个分支必须显式写：`"".split("\u001F")` 在 Kotlin 里返回 `[""]`，
     * 于是「没有标签」的卡会变成「有一个空 id 的标签」。
     */
    fun decodeList(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotEmpty() }
    }

    /**
     * 四个 List 用 `\u001E`（Record Separator）分段，段内用 `\u001F`。
     *
     * 一行存下整个工具白名单，避免为它单独建一张关联表 ——
     * 白名单只是「角色卡的附属数据」，从不单独查询。
     */
    private const val RECORD_SEPARATOR = '\u001E'

    fun encodeGroups(groups: List<List<String>>): String =
        groups.joinToString(RECORD_SEPARATOR.toString()) { encodeList(it) }

    fun decodeGroups(raw: String, expectedGroups: Int): List<List<String>> {
        if (raw.isEmpty()) return List(expectedGroups) { emptyList() }
        val parts = raw.split(RECORD_SEPARATOR)
        return List(expectedGroups) { index ->
            decodeList(parts.getOrElse(index) { "" })
        }
    }
}

/** [CharacterToolAccessConfig] 的紧凑编码。 */
internal object ToolAccessCodec {
    fun encode(config: CharacterToolAccessConfig): String {
        // 默认配置（未启用且四个白名单全空）编码成空串：
        // 绝大多数卡的这一列都是空串，数据库体积与可读性都更好。
        //
        // 注意判断必须**逐个**列出四个列表，不能只查 enabled：
        // 用户可能先勾了工具、后来把 enabled 关掉 —— 此时白名单内容还在，
        // 若因 enabled=false 就编码成空串，用户再打开开关时白名单已丢。
        val isDefault = !config.enabled &&
            config.allowedBuiltinTools.isEmpty() &&
            config.allowedPackages.isEmpty() &&
            config.allowedSkills.isEmpty() &&
            config.allowedMcpServers.isEmpty()
        if (isDefault) return ""

        return SplitCodec.encodeGroups(
            listOf(
                // 第一段存一个标记。"1"/"0" 而不是空/非空：
                // 空列表与「未写」在 decodeGroups 里都退化成 emptyList，
                // 无法区分，所以 enabled 必须有自己的显式标记。
                listOf(if (config.enabled) "1" else "0"),
                config.allowedBuiltinTools,
                config.allowedPackages,
                config.allowedSkills,
                config.allowedMcpServers,
            ),
        )
    }

    fun decode(raw: String): CharacterToolAccessConfig {
        if (raw.isEmpty()) return CharacterToolAccessConfig()
        val groups = SplitCodec.decodeGroups(raw, expectedGroups = 5)
        return CharacterToolAccessConfig(
            enabled = groups[0].firstOrNull() == "1",
            allowedBuiltinTools = groups[1],
            allowedPackages = groups[2],
            allowedSkills = groups[3],
            allowedMcpServers = groups[4],
        )
    }
}