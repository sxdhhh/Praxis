package dev.praxis.core.model.character

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 酒馆（SillyTavern）角色卡 V2 格式。
 *
 * ## 为什么字段全用可空 / 带默认值
 *
 * 酒馆卡在野外流传的版本非常多：V1（`{name, description, ...}` 直接在最外层）、
 * V2（`{spec, data:{...}}`）、以及各家导出工具自己加字段的变体。
 * 任何**必填**字段都会让一批真实存在的卡导入失败。
 *
 * 所以：全部可空或带默认值，解析时 `ignoreUnknownKeys = true`。
 * 解析成功的判定不靠「必填校验」，而靠**业务规则**：
 * `data.name` 为空才算失败（见 [TavernCardImporter.toCharacterCard]）。
 *
 * ## `extensions.operit` 是往返保真的关键
 *
 * 纯酒馆字段装不下 Praxis 的全部信息（`marks`、`advancedCustomPrompt`、
 * 工具白名单、模型绑定）。若导出时丢掉它们，用户「导出→导入」一圈后
 * 卡片就被削平了 —— 这是最容易让人失去信任的行为。
 *
 * 所以导出时把这些字段塞进 `extensions.operit.character_card`，
 * 导入时优先读它；读不到才退回「纯酒馆字段 → 卡片字段」的降级映射。
 */
@Serializable
data class TavernCharacterCard(
    val spec: String = "",
    @SerialName("spec_version") val specVersion: String = "",
    val data: TavernCharacterData = TavernCharacterData(),
)

/** 酒馆卡的数据主体。字段名与酒馆规范一致（下划线命名）。 */
@Serializable
data class TavernCharacterData(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    @SerialName("first_mes") val firstMes: String = "",
    val avatar: String = "",
    @SerialName("mes_example") val mesExample: String = "",
    val scenario: String = "",
    @SerialName("creator_notes") val creatorNotes: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("post_history_instructions") val postHistoryInstructions: String = "",
    @SerialName("alternate_greetings") val alternateGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creator: String = "",
    @SerialName("character_version") val characterVersion: String = "",
    val extensions: TavernExtensions? = null,
    @SerialName("character_book") val characterBook: TavernCharacterBook? = null,
)

@Serializable
data class TavernExtensions(
    val chub: TavernChubExtension? = null,
    @SerialName("depth_prompt") val depthPrompt: TavernDepthPrompt? = null,
    /** Praxis 自己的扩展。其它工具会原样保留这个未知字段。 */
    val operit: OperitTavernExtension? = null,
)

@Serializable
data class TavernDepthPrompt(
    val role: String = "",
    val depth: Int = 0,
    val prompt: String = "",
)

/**
 * 世界书（lorebook）。
 *
 * 导入策略与 Operit 一致：把全部 entry 的正文拼成**一个**标签，
 * 而不是为每个 entry 建一个标签。理由：世界书的 entry 有几十上百条，
 * 逐个建标签会把标签列表淹没，用户找不到自己建的语气标签。
 */
@Serializable
data class TavernCharacterBook(
    val name: String = "",
    val description: String = "",
    @SerialName("scan_depth") val scanDepth: Int = 0,
    @SerialName("token_budget") val tokenBudget: Int = 0,
    @SerialName("recursive_scanning") val recursiveScanning: Boolean = false,
    val entries: List<TavernBookEntry> = emptyList(),
)

@Serializable
data class TavernBookEntry(
    val name: String = "",
    val keys: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    val insertion_order: Int = 0,
    val comment: String = "",
)

/** Praxis 扩展：兼容 Chub 的字段（只解析，不参与业务）。 */
@Serializable
data class TavernChubExtension(
    val id: Long = 0,
    val full_path: String = "",
    val expressions: String? = null,
    val background_image: String? = null,
)

/** Praxis 扩展外壳。 */
@Serializable
data class OperitTavernExtension(
    val schema: String = LEGACY_SCHEMA,
    @SerialName("character_card") val characterCard: OperitCharacterCardPayload =
        OperitCharacterCardPayload(),
) {
    companion object {
        /**
         * Operit 用的 schema 名。
         *
         * ## 为什么导出的也是这个而不是 praxis_*
         *
         * 这是**互操作标识**，不是品牌标识。写 `operit_character_card_v1`
         * 的好处是三向兼容：
         *
         * | 方向 | 结果 |
         * |---|---|
         * | Operit 导出 → Praxis 导入 | ✅ 认 |
         * | Praxis 导出 → Praxis 导入 | ✅ 认（往返保真） |
         * | Praxis 导出 → Operit 导入 | ✅ Operit 也认 |
         *
         * 若改成 `praxis_character_card_v1`，第三行立刻失效 ——
         * 我们导出的卡在 Operit 里会退化成「纯酒馆字段」的有损导入。
         * 用户在乎的是卡能用，不是 schema 字符串写着谁的名字。
         *
         * [ACCEPTED_SCHEMAS] 里额外收 `praxis_character_card_v1`，
         * 万一已有用户导出过带 praxis 前缀的卡，也能读回来。
         */
        const val LEGACY_SCHEMA: String = "operit_character_card_v1"

        /** 兼容早期写成 praxis 前缀的导出文件。 */
        const val PRAXIS_SCHEMA: String = "praxis_character_card_v1"

        /** 可接受的 schema 集合。空串也接受：少数工具会把它写成空。 */
        val ACCEPTED_SCHEMAS: Set<String> = setOf(LEGACY_SCHEMA, PRAXIS_SCHEMA, "")

        /** 判断某个 schema 值是否是我们认识的。 */
        fun accepts(schema: String?): Boolean = schema == null || schema in ACCEPTED_SCHEMAS
    }
}

/**
 * 往返保真用的完整字段载荷。
 *
 * 名字保留 `OperitCharacterCardPayload` 而不是改成 `Praxis...`：
 * 这是**写进 JSON 的 schema 名**，改了就无法读 Operit 导出的卡。
 * 类名可以改，`@SerialName` 不能 —— 这里刻意保留以维持互操作。
 */
@Serializable
data class OperitCharacterCardPayload(
    val name: String = "",
    val description: String = "",
    val characterSetting: String = "",
    val openingStatement: String = "",
    val otherContentChat: String = "",
    val otherContentVoice: String = "",
    val attachedTagIds: List<String> = emptyList(),
    val attachedTags: List<OperitAttachedTagPayload> = emptyList(),
    val advancedCustomPrompt: String = "",
    val marks: String = "",
    val chatModelBindingMode: String = "",
    val chatModelConfigId: String? = null,
    val chatModelIndex: Int = 0,
    val memoryProfileBindingMode: String = "",
    val memoryProfileId: String? = null,
    val toolAccessConfig: OperitToolAccessPayload? = null,
)

@Serializable
data class OperitAttachedTagPayload(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val promptContent: String = "",
    val tagType: String = "CUSTOM",
)

@Serializable
data class OperitToolAccessPayload(
    val enabled: Boolean = false,
    val allowedBuiltinTools: List<String> = emptyList(),
    val allowedPackages: List<String> = emptyList(),
    val allowedSkills: List<String> = emptyList(),
    val allowedMcpServers: List<String> = emptyList(),
)

/** 导入结果：一张待落库的卡 + 需要一并创建的标签。 */
data class TavernImportResult(
    val card: CharacterCard,
    /** 需要新建的标签（来源卡自带，但本地没有同内容的）。 */
    val newTags: List<PromptTag>,
    /** 世界书拼成的标签（若卡里有 character_book）。 */
    val worldBookTag: PromptTag?,
)

/**
 * 酒馆卡 ⇄ Praxis 卡 的双向转换。
 *
 * 纯函数，不碰数据库、不碰 Context —— 因此可纯 JVM 单测。
 * 标签的「查重 / 复用」由调用方（Repository）做，这里只负责**字段映射**。
 */
object TavernCardImporter {

    /**
     * 宽松解析用的 Json 配置。
     *
     * - `ignoreUnknownKeys`：酒馆卡有大量第三方字段（chub、regex 等），
     *   不认识就报错会让绝大多数真实卡片导不进来。
     * - `isLenient`：允许不带引号的字符串与宽松数字。
     * - `coerceInputValues`：`null` 出现在非空字段位置时用默认值兜底，
     *   而不是抛异常（很多导出工具会把可选字段显式写成 null）。
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /**
     * 解析酒馆 JSON。
     *
     * ## 为什么要额外判断「V1 老格式」
     *
     * V1 卡形如 `{"name":..., "description":...}`，**没有** `data` 字段。
     * 直接按 V2 解析会得到一张 `name` 为空的卡，然后被判定为「名字为空」失败。
     * 用户会以为「这个卡坏了」，其实是我们不认 V1。
     *
     * 所以：若顶层没有 `data` 但有 `name`，就把整个对象当 `data` 再解一次。
     */
    fun parse(rawJson: String): Result<TavernCharacterCard> = runCatching {
        val trimmed = rawJson.trim()
        require(trimmed.isNotEmpty()) { "内容为空" }

        val root = json.parseToJsonElement(trimmed).jsonObject
        val card = if (root.containsKey("data")) {
            json.decodeFromJsonElement(TavernCharacterCard.serializer(), root)
        } else {
            // V1：整体就是 data
            val data = json.decodeFromJsonElement(TavernCharacterData.serializer(), root)
            TavernCharacterCard(spec = "chara_card_v1", specVersion = "1.0", data = data)
        }
        card
    }

    /**
     * 酒馆卡 → Praxis 角色卡。
     *
     * ## 两种路径
     *
     * 1. **有 `extensions.operit`（schema 匹配）**：按载荷**原样**还原，
     *    这是「导出再导入」的往返保真路径。
     * 2. **没有**：降级映射。此时信息必然有损（酒馆格式没有
     *    `advancedCustomPrompt` / 工具白名单），所以要明确知道丢了什么：
     *    - `description/personality/scenario` → `characterSetting`（带中文小节标题）
     *    - `mes_example/system_prompt/post_history_instructions/alternate_greetings`
     *      → `otherContentChat`
     *    - `depth_prompt.prompt` → `advancedCustomPrompt`
     *    - `first_mes` → `openingStatement`
     *    - `creator/creator_notes/character_version` + 原始 tags → `marks`（不进提示词）
     *
     * ## 为什么降级映射要加中文小节标题
     *
     * 直接把 `description` 和 `personality` 用空行拼起来，模型分不清
     * 「这是背景」还是「这是性格」，语气会发散。加 `【角色描述】` 这类
     * 标题后，模型的遵循度明显更稳 —— 这也是 Operit 用
     * `CharacterCardBilingualData` 的原因。
     *
     * @param tagIdFactory 生成新标签 id（注入以便测试用固定 id）
     */
    fun toCharacterCard(
        tavern: TavernCharacterCard,
        cardId: String,
        now: Long = 0L,
        tagIdFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    ): Result<TavernImportResult> {
        val data = tavern.data
        if (data.name.isBlank()) {
            // 唯一硬性要求：必须有名字。没有名字的卡在列表里无法区分。
            return Result.failure(IllegalArgumentException("角色卡缺少名字"))
        }

        // 世界书 → 一个标签。
        val worldBookTag = buildWorldBookTag(data, tagIdFactory)

        val payload = data.extensions?.operit
            ?.takeIf { OperitTavernExtension.accepts(it.schema) }
            ?.characterCard

        val base = if (payload != null) {
            fromPayload(payload, cardId, now)
        } else {
            fromPlainTavern(data, cardId, now)
        }

        // 世界书标签：追加到 attachedTagIds 末尾（与 Operit 一致，用 distinct 防重复）。
        val withWorldBook = worldBookTag?.let { tag ->
            base.copy(attachedTagIds = (base.attachedTagIds + tag.id).distinct())
        } ?: base

        // payload 路径：把随卡带来的标签内容还原成 PromptTag，交给调用方去查重。
        val carriedTags = payload?.attachedTags?.map { it.toPromptTag(now) }.orEmpty()

        return Result.success(
            TavernImportResult(
                card = withWorldBook,
                newTags = carriedTags,
                worldBookTag = worldBookTag,
            ),
        )
    }

    /** 有 operit 扩展时：逐字段还原。 */
    private fun fromPayload(
        payload: OperitCharacterCardPayload,
        cardId: String,
        now: Long,
    ): CharacterCard = CharacterCard(
        id = cardId,
        name = payload.name,
        description = payload.description,
        characterSetting = payload.characterSetting,
        openingStatement = payload.openingStatement,
        otherContentChat = payload.otherContentChat,
        otherContentVoice = payload.otherContentVoice,
        attachedTagIds = payload.attachedTagIds,
        advancedCustomPrompt = payload.advancedCustomPrompt,
        marks = payload.marks,
        chatModelBindingMode = CharacterModelBindingMode.fromStorage(payload.chatModelBindingMode),
        chatModelConfigId = payload.chatModelConfigId?.takeIf { it.isNotBlank() },
        chatModelIndex = payload.chatModelIndex.coerceAtLeast(0),
        memoryProfileBindingMode =
            CharacterMemoryBindingMode.fromStorage(payload.memoryProfileBindingMode),
        memoryProfileId = payload.memoryProfileId?.takeIf { it.isNotBlank() },
        toolAccessConfig = payload.toolAccessConfig?.let {
            CharacterToolAccessConfig(
                enabled = it.enabled,
                allowedBuiltinTools = it.allowedBuiltinTools,
                allowedPackages = it.allowedPackages,
                allowedSkills = it.allowedSkills,
                allowedMcpServers = it.allowedMcpServers,
            ).normalized()
        } ?: CharacterToolAccessConfig(),
        // 导入的卡永远不是内置默认卡，否则用户能通过导入「造出」一张
        // 与默认卡同 id 的卡，删除逻辑会混乱。
        isDefault = false,
        createdAt = now,
        updatedAt = now,
    )

    /** 无 operit 扩展时：纯酒馆字段降级映射。 */
    private fun fromPlainTavern(
        data: TavernCharacterData,
        cardId: String,
        now: Long,
    ): CharacterCard {
        val characterSetting = buildString {
            if (data.description.isNotBlank()) {
                append("【角色描述】\n")
                append(data.description)
                append("\n\n")
            }
            if (data.personality.isNotBlank()) {
                append("【性格】\n")
                append(data.personality)
                append("\n\n")
            }
            if (data.scenario.isNotBlank()) {
                append("【场景】\n")
                append(data.scenario)
                append("\n\n")
            }
        }.trim()

        val otherContentChat = buildString {
            if (data.mesExample.isNotBlank()) {
                append("【对话示例】\n")
                append(data.mesExample)
                append("\n\n")
            }
            if (data.systemPrompt.isNotBlank()) {
                append("【系统提示词】\n")
                append(data.systemPrompt)
                append("\n\n")
            }
            if (data.postHistoryInstructions.isNotBlank()) {
                append("【历史后指令】\n")
                append(data.postHistoryInstructions)
                append("\n\n")
            }
            if (data.alternateGreetings.isNotEmpty()) {
                append("【备用开场白】\n")
                data.alternateGreetings.forEachIndexed { index, greeting ->
                    append("${index + 1}. $greeting\n")
                }
                append("\n")
            }
        }.trim()

        val advancedCustomPrompt = data.extensions?.depthPrompt?.prompt
            ?.takeIf { it.isNotBlank() }
            ?.let { "【深度提示词】\n$it" }
            .orEmpty()

        // 简介：只用标签（与 Operit 一致，前 5 个 + 省略标记）。
        val description = if (data.tags.isNotEmpty()) {
            val head = data.tags.take(5).joinToString(", ")
            if (data.tags.size > 5) "$head 等 ${data.tags.size} 个" else head
        } else {
            ""
        }

        // 备注：作者信息。**不**进提示词。
        val marks = buildString {
            append("来源：酒馆角色卡\n")
            if (data.creator.isNotBlank()) append("作者：${data.creator}\n")
            if (data.creatorNotes.isNotBlank()) append("作者备注：${data.creatorNotes}\n")
            if (data.characterVersion.isNotBlank()) append("版本：${data.characterVersion}\n")
        }.trim()

        return CharacterCard(
            id = cardId,
            name = data.name,
            description = description,
            characterSetting = characterSetting,
            openingStatement = data.firstMes,
            otherContentChat = otherContentChat,
            otherContentVoice = "",
            attachedTagIds = emptyList(),
            advancedCustomPrompt = advancedCustomPrompt,
            marks = marks,
            isDefault = false,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * 世界书 → 单个标签。
     *
     * 只取 `enabled` 的 entry：用户在世界书里关掉的条目，导入后不应突然生效。
     * 格式 `[条目名]\n正文` 与 Operit 一致 —— 保留条目名让模型知道
     * 「这一段是在讲什么」，否则几十段背景设定糊成一团。
     */
    private fun buildWorldBookTag(
        data: TavernCharacterData,
        tagIdFactory: () -> String,
    ): PromptTag? {
        val book = data.characterBook ?: return null
        val content = buildString {
            book.entries.forEach { entry ->
                if (entry.enabled && entry.content.isNotBlank()) {
                    append("[${entry.name}]\n")
                    append(entry.content)
                    append("\n\n")
                }
            }
        }.trim()
        if (content.isBlank()) return null

        return PromptTag(
            id = tagIdFactory(),
            name = "世界书 · ${data.name}",
            description = "从酒馆角色卡「${data.name}」导入的世界书",
            promptContent = content,
            tagType = PromptTagType.Function,
        )
    }

    private fun OperitAttachedTagPayload.toPromptTag(now: Long): PromptTag = PromptTag(
        id = id,
        name = name,
        description = description,
        promptContent = promptContent,
        tagType = PromptTagType.fromStorage(tagType),
        createdAt = now,
        updatedAt = now,
    )
}

/** 导出：Praxis 卡 → 酒馆 JSON（含 operit 扩展，保证往返无损）。 */
object TavernCardExporter {

    fun toJson(
        card: CharacterCard,
        tags: List<PromptTag>,
        pretty: Boolean = true,
    ): String {
        val json = if (pretty) {
            Json {
                prettyPrint = true
                encodeDefaults = true
            }
        } else {
            Json { encodeDefaults = true }
        }

        val payload = OperitCharacterCardPayload(
            name = card.name,
            description = card.description,
            characterSetting = card.characterSetting,
            openingStatement = card.openingStatement,
            otherContentChat = card.otherContentChat,
            otherContentVoice = card.otherContentVoice,
            attachedTagIds = card.attachedTagIds,
            // 把标签内容也写进去：否则导到另一台设备时，
            // 卡片引用了一批本地不存在的 tagId，提示词会整段丢失。
            attachedTags = tags.map {
                OperitAttachedTagPayload(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    promptContent = it.promptContent,
                    tagType = it.tagType.storage,
                )
            },
            advancedCustomPrompt = card.advancedCustomPrompt,
            marks = card.marks,
            chatModelBindingMode = card.chatModelBindingMode.storage,
            chatModelConfigId = card.chatModelConfigId,
            chatModelIndex = card.chatModelIndex,
            memoryProfileBindingMode = card.memoryProfileBindingMode.storage,
            memoryProfileId = card.memoryProfileId,
            toolAccessConfig = OperitToolAccessPayload(
                enabled = card.toolAccessConfig.enabled,
                allowedBuiltinTools = card.toolAccessConfig.allowedBuiltinTools,
                allowedPackages = card.toolAccessConfig.allowedPackages,
                allowedSkills = card.toolAccessConfig.allowedSkills,
                allowedMcpServers = card.toolAccessConfig.allowedMcpServers,
            ),
        )

        val tavernData = TavernCharacterData(
            name = card.name,
            description = card.description,
            personality = "",
            firstMes = card.openingStatement,
            mesExample = "",
            scenario = "",
            creatorNotes = "",
            systemPrompt = "",
            postHistoryInstructions = "",
            alternateGreetings = emptyList(),
            tags = emptyList(),
            creator = "Praxis",
            characterVersion = "",
            extensions = TavernExtensions(
                operit = OperitTavernExtension(
                    schema = OperitTavernExtension.LEGACY_SCHEMA,
                    characterCard = payload,
                ),
            ),
        )

        val card2 = TavernCharacterCard(
            spec = "chara_card_v2",
            specVersion = "2.0",
            data = tavernData,
        )

        return json.encodeToString(TavernCharacterCard.serializer(), card2)
    }
}