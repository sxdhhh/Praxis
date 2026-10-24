package dev.praxis.core.data.character

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 角色卡 DAO。 */
@Dao
interface CharacterCardDao {

    /**
     * 全部角色卡，**默认卡排最前**，其余按更新时间倒序。
     *
     * 排序写在 SQL 里而不是 UI 层：列表页、选择器、导出三处都要这个顺序，
     * 放在 UI 层意味着三份实现，迟早出现「列表顺序和选择器不一致」。
     *
     * `isDefault DESC` 让默认卡（Builtin）恒在首位。
     */
    @Query(
        """
        SELECT * FROM character_cards
        ORDER BY isDefault DESC, updatedAt DESC, name ASC
        """,
    )
    fun observeAll(): Flow<List<CharacterCardEntity>>

    @Query("SELECT * FROM character_cards WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<CharacterCardEntity?>

    @Query("SELECT * FROM character_cards WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CharacterCardEntity?

    @Query("SELECT * FROM character_cards ORDER BY isDefault DESC, updatedAt DESC, name ASC")
    suspend fun findAll(): List<CharacterCardEntity>

    @Query("SELECT * FROM character_cards WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): CharacterCardEntity?

    @Query("SELECT COUNT(*) FROM character_cards")
    suspend fun count(): Int

    /**
     * 插入或整体覆盖。
     *
     * 用 `REPLACE` 而不是「先查再 update/insert」：
     * 后者在并发（聊天页与设置页同时保存）时会出现「查到时不存在 → 插入时已存在 → 崩」。
     * REPLACE 是原子的。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CharacterCardEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CharacterCardEntity>)

    /**
     * 删除指定卡。
     *
     * `isDefault = 0` 的约束写在 SQL 里：默认卡是「用户删光后仍能继续用」的兜底，
     * 若允许删，删完应用就进入「没有任何角色卡」的状态，
     * 而 `activeCharacterCardId` 还指向那个已删的 id → 提示词为空。
     */
    @Query("DELETE FROM character_cards WHERE id = :id AND isDefault = 0")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM character_cards")
    suspend fun deleteAll()
}

/** 提示词标签 DAO。 */
@Dao
interface PromptTagDao {

    @Query("SELECT * FROM prompt_tags ORDER BY updatedAt DESC, name ASC")
    fun observeAll(): Flow<List<PromptTagEntity>>

    @Query("SELECT * FROM prompt_tags ORDER BY updatedAt DESC, name ASC")
    suspend fun findAll(): List<PromptTagEntity>

    @Query("SELECT * FROM prompt_tags WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PromptTagEntity?

    /**
     * 按内容查重。
     *
     * 导入酒馆卡时用它复用已有标签：
     * 同一个世界书被导入两次，不该产生两个内容完全相同的标签 ——
     * 否则用户会看到列表里两条一模一样的条目，删掉一个另一个还在。
     */
    @Query("SELECT * FROM prompt_tags WHERE promptContent = :content LIMIT 1")
    suspend fun findByContent(content: String): PromptTagEntity?

    @Query("SELECT * FROM prompt_tags WHERE tagType = :tagType ORDER BY name ASC")
    suspend fun findByType(tagType: String): List<PromptTagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PromptTagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<PromptTagEntity>)

    @Query("DELETE FROM prompt_tags WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM prompt_tags")
    suspend fun deleteAll()

    /**
     * 清掉所有角色卡里对某个已删标签的引用。
     *
     * ## 为什么必须在删除标签时同步做
     *
     * `attachedTagIds` 是**冗余引用**（没有外键）。删掉标签后，
     * 卡片里仍留着那个 id，`compose` 时 `tagLookup` 返回 null 会静默跳过 ——
     * 用户不会崩，但「我明明删了这个标签，为什么这张卡的提示词还是那些字」
     * 会在下一次编辑标签时再次出现（因为 id 还在，可能被复用）。
     *
     * SQLite 的 `REPLACE` 做不到「从分隔符列表里删一项」，所以这里用
     * 通配匹配粗筛（把候选行取出来），在 Kotlin 侧精确重建。
     * 数据量是「角色卡张数」，几十到几百行，不是性能热点。
     */
    @Query(
        """
        SELECT * FROM character_cards
        WHERE attachedTagIds LIKE '%' || :tagId || '%'
        """,
    )
    suspend fun findCardsReferencingTag(tagId: String): List<CharacterCardEntity>
}

// ── 关于跨表事务 ──────────────────────────────────────────────
//
// 「删标签 + 清理角色卡里的引用」需要原子完成，否则中间失败会留下
// 「标签已删、引用还在」的脏数据 —— 这种脏数据不报错，只让提示词悄悄少一段。
//
// 但**不能**在 `@Dao` 里用默认实现调另一个 DAO 实例来达成事务：
// 那个实例的方法不在当前事务的 `SupportSQLiteDatabase` 上，
// 事务边界只包住了本类自己的调用，看着原子、实际不是。
//
// 也不能声明一个没有任何 `@Query` 的 `@Dao` —— Room 会报
// 「没有可实现的抽象方法」，KSP 直接失败。
//
// 正确入口是 Repository 里的 `database.withTransaction { }`，
// 见 `CharacterRepository.deleteTag`。