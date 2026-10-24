package dev.praxis.core.data.character

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.praxis.core.model.character.CharacterCard
import dev.praxis.core.model.character.CharacterPromptComposer
import dev.praxis.core.model.character.PromptFunctionType
import dev.praxis.core.model.character.PromptTag
import dev.praxis.core.model.character.PromptTagType
import dev.praxis.core.model.character.TavernCardImporter
import dev.praxis.core.model.character.TavernImportResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 角色卡与提示词标签的仓储。
 *
 * ## 为什么活跃角色卡 id 放 DataStore 而不是 Room
 *
 * 它是**单个全局标量**（当前用哪张卡），不是实体集合：
 * 放 Room 就要建一张只有一行的表，或者给每张卡加 `active` 列
 * （那样「只有一张活跃」得靠事务保证，容易写错）。
 * DataStore 存一个 key 天然满足「全局唯一标量」。
 *
 * 卡片与标签本身是实体集合，放 Room —— 两类数据分开，各用合适的存储。
 */
class CharacterRepository(
    private val database: CharacterDatabase,
    private val dataStore: DataStore<Preferences>,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    private val cardDao get() = database.characterCardDao()
    private val tagDao get() = database.promptTagDao()

    // ── 角色卡 ────────────────────────────────────────────────

    /** 全部角色卡（默认卡在前）。 */
    val cards: Flow<List<CharacterCard>> =
        cardDao.observeAll().map { list -> list.map { it.toModel() } }

    /** 全部标签。 */
    val tags: Flow<List<PromptTag>> =
        tagDao.observeAll().map { list -> list.map { it.toModel() } }

    /**
     * 当前活跃角色卡 id。
     *
     * 读不到时回落默认卡 id —— 不返回 null：
     * 调用方（聊天页取提示词）不该为「可能没有活跃卡」写分支，
     * 而「没有活跃卡」本身也不该是一种可长期存在的状态。
     */
    val activeCardId: Flow<String> =
        dataStore.data.map { it[KEY_ACTIVE_CARD] ?: CharacterCard.DEFAULT_ID }

    /** 当前活跃角色卡。库为空时返回内存里的默认卡（不写库）。 */
    val activeCard: Flow<CharacterCard> = activeCardId.map { id ->
        cardDao.findById(id)?.toModel() ?: fallbackDefault()
    }

    /**
     * 确保默认角色卡存在。
     *
     * ## 幂等，且必须容忍「用户删光了所有卡」
     *
     * 首次启动、用户删光所有非默认卡（默认卡删不掉，见 DAO 的 SQL 约束）、
     * 从备份恢复失败 —— 这些情况下都可能没有默认卡。
     * 每次进设置页都会调一次，重复调用不能产生第二张默认卡（按固定 id upsert）。
     *
     * @return 默认卡的 id（恒为 [CharacterCard.DEFAULT_ID]）
     */
    suspend fun ensureDefaultCard(): String {
        val existing = cardDao.findById(CharacterCard.DEFAULT_ID)
        if (existing == null) {
            val timestamp = now()
            cardDao.upsert(
                CharacterCardEntity.fromModel(
                    CharacterCard(
                        id = CharacterCard.DEFAULT_ID,
                        name = CharacterCard.DEFAULT_NAME,
                        description = DEFAULT_DESCRIPTION,
                        characterSetting = DEFAULT_CHARACTER_SETTING,
                        otherContentChat = DEFAULT_OTHER_CONTENT_CHAT,
                        otherContentVoice = DEFAULT_OTHER_CONTENT_VOICE,
                        isDefault = true,
                        createdAt = timestamp,
                        updatedAt = timestamp,
                    ),
                ),
            )
        }
        // 活跃 id 指向一张不存在的卡时，纠正回默认卡。
        // 场景：用户删掉了当前活跃卡（或从旧备份恢复后 id 对不上）。
        // 不纠正的话，聊天页会拿到「空提示词」，而界面上卡片是选中的 ——
        // 用户完全看不出问题在哪。
        val activeId = dataStore.data.first()[KEY_ACTIVE_CARD]
        if (activeId != null && cardDao.findById(activeId) == null) {
            dataStore.edit { it[KEY_ACTIVE_CARD] = CharacterCard.DEFAULT_ID }
        }
        return CharacterCard.DEFAULT_ID
    }

    /** 新建一张卡（id 由 [idFactory] 生成）。返回新 id。 */
    suspend fun create(
        name: String,
        description: String = "",
        characterSetting: String = "",
    ): String {
        val id = idFactory()
        val timestamp = now()
        cardDao.upsert(
            CharacterCardEntity.fromModel(
                CharacterCard(
                    id = id,
                    name = name.trim().ifEmpty { "未命名角色" },
                    description = description,
                    characterSetting = characterSetting,
                    isDefault = false,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            ),
        )
        return id
    }

    /**
     * 保存整张卡。
     *
     * ## 三道保护，缺一都会造成用户可感知的坏状态
     *
     * 1. **id 被规范化**：不接受空白 id（否则主键为空字符串，
     *    与默认卡的 id 不冲突但会让 `findById("")` 命中一张幽灵卡）。
     * 2. **`isDefault` 不可篡改**：默认卡身份只由 id 决定。
     *    若允许调用方把 `isDefault` 改成 false，用户就能删掉默认卡，
     *    之后 `ensureDefaultCard` 会重建一张内容全默认的卡 ——
     *    用户之前对默认卡的修改会「莫名回滚」。
     * 3. **`updatedAt` 由仓储写**：调用方给的可能是旧值，
     *    而列表按 `updatedAt` 倒序，用旧值会让「刚编辑过的卡沉到底部」。
     */
    suspend fun save(card: CharacterCard): CharacterCard {
        require(card.id.isNotBlank()) { "角色卡 id 不能为空" }
        val existing = cardDao.findById(card.id)?.toModel()
        val isDefault = existing?.isBuiltinDefault ?: card.isBuiltinDefault
        val stamped = card.copy(
            name = card.name.trim().ifEmpty { "未命名角色" },
            attachedTagIds = card.attachedTagIds.distinct(),
            isDefault = isDefault,
            createdAt = existing?.createdAt ?: card.createdAt.takeIf { it > 0 } ?: now(),
            updatedAt = now(),
        )
        cardDao.upsert(CharacterCardEntity.fromModel(stamped))
        return stamped
    }

    /**
     * 删除一张卡。
     *
     * @return true 表示确实删了；false 表示它是默认卡（受保护）
     *
     * 删除当前活跃卡后**必须**把活跃 id 切回默认卡，
     * 否则聊天页拿到的是「不存在的卡 → 空提示词」。
     */
    suspend fun delete(cardId: String): Boolean {
        val deleted = cardDao.deleteById(cardId) > 0
        if (deleted) {
            val activeId = dataStore.data.first()[KEY_ACTIVE_CARD]
            if (activeId == cardId) {
                dataStore.edit { it[KEY_ACTIVE_CARD] = CharacterCard.DEFAULT_ID }
            }
        }
        return deleted
    }

    /** 切换活跃卡。id 不存在时不做任何事（返回 false）。 */
    suspend fun setActive(cardId: String): Boolean {
        if (cardDao.findById(cardId) == null) return false
        dataStore.edit { it[KEY_ACTIVE_CARD] = cardId }
        return true
    }

    // ── 提示词 ────────────────────────────────────────────────

    /**
     * 取当前活跃卡的最终提示词（供 ChatViewModel 的 `systemPromptProvider` 用）。
     *
     * 这是整套角色系统的**唯一出口**：聊天每轮都会调它，
     * 所以它必须自己保证「不抛异常」。任何 IO 失败都退化成空串 ——
     * 提示词读不出来最多是回答不够贴合人设，
     * 而抛异常会让用户根本发不出消息。
     */
    suspend fun activePrompt(functionType: PromptFunctionType = PromptFunctionType.Chat): String =
        runCatching {
            val id = dataStore.data.first()[KEY_ACTIVE_CARD] ?: CharacterCard.DEFAULT_ID
            val card = cardDao.findById(id)?.toModel() ?: return@runCatching ""
            val tagIndex = tagDao.findAll().associate { it.id to it.toModel() }
            CharacterPromptComposer.compose(
                card = card,
                tagLookup = { tagIndex[it] },
                functionType = functionType,
            )
        }.getOrDefault("")

    // ── 标签 ──────────────────────────────────────────────────

    /** 新建或复用标签：内容相同则复用已有 id（避免重复条目）。 */
    suspend fun createOrReuseTag(
        name: String,
        promptContent: String,
        description: String = "",
        tagType: PromptTagType = PromptTagType.Custom,
    ): String {
        if (promptContent.isBlank()) {
            // 空内容标签没有意义：它不会影响提示词，只会在列表里占一行。
            return ""
        }
        tagDao.findByContent(promptContent)?.let { return it.id }
        val id = idFactory()
        val timestamp = now()
        tagDao.upsert(
            PromptTagEntity.fromModel(
                PromptTag(
                    id = id,
                    name = name.trim().ifEmpty { "未命名标签" },
                    description = description,
                    promptContent = promptContent,
                    tagType = tagType,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            ),
        )
        return id
    }

    suspend fun saveTag(tag: PromptTag): PromptTag {
        require(tag.id.isNotBlank()) { "标签 id 不能为空" }
        val existing = tagDao.findById(tag.id)?.toModel()
        val stamped = tag.copy(
            name = tag.name.trim().ifEmpty { "未命名标签" },
            createdAt = existing?.createdAt ?: tag.createdAt.takeIf { it > 0 } ?: now(),
            updatedAt = now(),
        )
        tagDao.upsert(PromptTagEntity.fromModel(stamped))
        return stamped
    }

    /**
     * 删标签，并在**同一事务**里清掉所有卡片对它的引用。
     *
     * 用 `withTransaction` 而不是分两次调用：中间失败会留下
     * 「标签没了、引用还在」的脏数据，不报错，只让提示词悄悄少一段。
     */
    suspend fun deleteTag(tagId: String) {
        database.withCharacterTransaction {
            val affected = tagDao.findCardsReferencingTag(tagId)
            affected.forEach { entity ->
                val ids = SplitCodec.decodeList(entity.attachedTagIdsRaw)
                val filtered = ids.filterNot { it == tagId }
                if (filtered.size != ids.size) {
                    cardDao.upsert(
                        entity.copy(attachedTagIdsRaw = SplitCodec.encodeList(filtered)),
                    )
                }
            }
            tagDao.deleteById(tagId)
        }
    }

    // ── 酒馆导入 / 导出 ────────────────────────────────────────

    /**
     * 从酒馆 JSON 导入。
     *
     * ## 标签的「复用」在这里做，不在转换器里
     *
     * [TavernCardImporter] 是纯函数，不知道数据库里已有什么。
     * 由这里对每个随卡带来的标签先按内容查重：
     * 命中就复用已有 id，并把卡里的引用改指向新 id。
     *
     * ## 为什么必须重映射 id
     *
     * 卡片自带的 `attachedTagIds` 是**导出方设备上的** id。
     * 本机很可能没有这些 id。若不重映射，导入后卡片引用的全是
     * 不存在的标签 → 提示词静默少一大段，而界面上标签显示为已附着。
     */
    suspend fun importFromTavernJson(rawJson: String): Result<String> = runCatching {
        val parsed = TavernCardImporter.parse(rawJson).getOrThrow()
        val id = idFactory()
        val result = TavernCardImporter.toCharacterCard(
            tavern = parsed,
            cardId = id,
            now = now(),
            tagIdFactory = idFactory,
        ).getOrThrow()

        val remapped = applyTagRemap(result)

        cardDao.upsert(CharacterCardEntity.fromModel(remapped))
        remapped.id
    }

    /**
     * 把随卡带来的标签落库，并把卡里的引用改成本机实际 id。
     *
     * 返回的卡：`attachedTagIds` 已全部换成本机 id。
     */
    private suspend fun applyTagRemap(result: TavernImportResult): CharacterCard {
        val timestamp = now()
        // 旧 id → 本机 id
        val idMap = mutableMapOf<String, String>()

        result.newTags.forEach { tag ->
            if (tag.promptContent.isBlank()) return@forEach
            val localId = tagDao.findByContent(tag.promptContent)?.id ?: run {
                tagDao.upsert(PromptTagEntity.fromModel(tag.copy(createdAt = timestamp, updatedAt = timestamp)))
                tag.id
            }
            if (tag.id.isNotBlank()) idMap[tag.id] = localId
        }

        result.worldBookTag?.let { tag ->
            if (tag.promptContent.isBlank()) return@let
            val localId = tagDao.findByContent(tag.promptContent)?.id ?: run {
                tagDao.upsert(PromptTagEntity.fromModel(tag.copy(createdAt = timestamp, updatedAt = timestamp)))
                tag.id
            }
            idMap[tag.id] = localId
        }

        // 逐个替换；映射不到的 id（导出方有、本机没有）直接丢掉 ——
        // 留着只会在每次 compose 时产生一次无效查找。
        val remappedIds = result.card.attachedTagIds.mapNotNull { oldId -> idMap[oldId] }.distinct()

        return result.card.copy(attachedTagIds = remappedIds)
    }

    /** 导出单张卡为酒馆 JSON（含 operit 扩展，往返保真）。 */
    suspend fun exportCardAsTavernJson(cardId: String): Result<String> = runCatching {
        val card = cardDao.findById(cardId)?.toModel() ?: error("角色卡不存在")
        val allTags = tagDao.findAll().map { it.toModel() }
        val byId = allTags.associateBy { it.id }
        val attached = card.attachedTagIds.mapNotNull { byId[it] }
        dev.praxis.core.model.character.TavernCardExporter.toJson(card, attached)
    }

    private fun fallbackDefault(): CharacterCard {
        val timestamp = now()
        return CharacterCard(
            id = CharacterCard.DEFAULT_ID,
            name = CharacterCard.DEFAULT_NAME,
            description = DEFAULT_DESCRIPTION,
            characterSetting = DEFAULT_CHARACTER_SETTING,
            otherContentChat = DEFAULT_OTHER_CONTENT_CHAT,
            otherContentVoice = DEFAULT_OTHER_CONTENT_VOICE,
            isDefault = true,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
    }

    companion object {
        /**
         * 活跃角色卡 id 的 DataStore 键。
         *
         * 与 Operit 的键名一致（`active_character_card_id`），
         * 便于将来做数据迁移时对照。
         */
        private val KEY_ACTIVE_CARD = stringPreferencesKey("active_character_card_id")

        /**
         * 默认角色卡的默认文案。
         *
         * ## 为什么默认卡必须有非空设定
         *
         * 若默认卡三个字段全空，`compose` 返回空串，
         * `SystemPromptBuilder` 就不会插入角色层 —— 用户看到的现象是
         * 「装完就能用」（因为走内置人格），但一旦他建了第二张卡再切回默认卡，
         * 就会觉得「默认卡没生效」。给一段明确的默认设定，行为才一致。
         */
        const val DEFAULT_DESCRIPTION: String =
            "Praxis 的内置助手人格。适合日常问答与工具调用。"

        const val DEFAULT_CHARACTER_SETTING: String =
            "你是 Praxis，运行在 Android 上的 AI 智能体工作台。" +
                "回答要准确、具体、有可执行性；不确定时明确说不确定，" +
                "不要编造事实、链接或文件路径。"

        const val DEFAULT_OTHER_CONTENT_CHAT: String =
            "回答用中文，除非用户使用其它语言。" +
                "涉及命令与代码时给出可直接执行的完整片段，不要省略关键参数。"

        const val DEFAULT_OTHER_CONTENT_VOICE: String =
            "输出是语音合成用的：不要使用 Markdown、列表符号或代码块，" +
                "用短句口语化表达，避免括号注释与长串符号。"
    }
}