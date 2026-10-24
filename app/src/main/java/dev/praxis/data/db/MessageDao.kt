package dev.praxis.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * 按写入顺序读整个会话。
     *
     * ## 为什么必须是 `orderIndex, id` 双键排序（2026-10-24 修）
     *
     * 旧实现只写 `ORDER BY orderIndex ASC`。而 `orderIndex` **允许重复**：
     * 索引是 `Index(value = ["sessionId", "orderIndex"])`，**不是 UNIQUE**。
     *
     * 真机数据库里实测到大量重复（同一个会话内）：
     *
     * ```
     * (5, 6, tool), (5, 7, tool)          ← 两个工具结果并列
     * (13, 15), (13, 16)
     * (21, 26), (21, 27), (21, 28)        ← 三个并列
     * ```
     *
     * 重复的来源是 `appendMessage` 的「读 MAX +1 再插入」在**并发**下不原子：
     * 工具调用连续返回时，多个 `launchPersist` 协程同时读到同一个 MAX，
     * 于是各自都插入 MAX+1。（并发问题的根修在
     * `ChatRepository.appendMessage` 的互斥锁，这里只做读取侧的兜底。）
     *
     * 单键排序在重复值上**顺序未定义** —— SQLite 不保证任何稳定次序，
     * 同一条查询在不同时刻可能给出不同顺序。表现就是用户报的
     * 「重启后会话内容全部显示不正常」：气泡时间顺序错乱、工具卡片与回答交织。
     *
     * 加 `id ASC` 作为第二键是**正确的兜底**，不是随便凑一个键：
     * `id` 是 `AUTOINCREMENT`，它的递增顺序就是真实插入顺序。
     * 并发写虽然让 `orderIndex` 撞车，但 `id` 仍然严格反映谁先谁后。
     * 所以「同 orderIndex 内按 id 排」能精确复原真实顺序。
     *
     * 这也让历史脏数据（已经撞车的行）无需迁移即可正确显示。
     */
    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId " +
            "ORDER BY orderIndex ASC, id ASC",
    )
    fun observeSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM messages WHERE sessionId = :sessionId")
    suspend fun maxOrderIndex(sessionId: String): Int

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    /**
     * 更新正文。
     *
     * 流式输出期间会**高频调用**（每个 delta 一次），所以只更新 content 一列，
     * 不整行 UPDATE —— 整行写会连带覆盖 toolName 等字段，而且写入量更大。
     */
    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countInSession(sessionId: String): Int

    /**
     * 把 `fromIndex` 及其之后的所有行整体后移 [delta] 位。
     *
     * ## 为什么需要它（2026-10-24，插入摘要位置修复）
     *
     * 摘要必须插在**被长按那条之后**。旧实现只调 `appendMessage`（取
     * `MAX(orderIndex)+1`），于是摘要落到了会话末尾 —— 界面本次会话内
     * 看着对，一重启就读到最底下。这是「位置只在内存里成立」的假实现。
     *
     * `orderIndex` 本来就是 UI 的排序键（`observeSession` 按它 ASC），
     * 所以只要腾出一个空位、把新行插进去，重启后位置就是对的。
     *
     * ## 为什么一条 UPDATE 就够（不需要倒着逐行）
     *
     * SQLite 的 `UPDATE ... SET orderIndex = orderIndex + 1 WHERE orderIndex >= :fromIndex`
     * 是**基于行集合**执行的：SQLite 会先把命中的 rowid 收集起来再逐行改，
     * 不会因为「改过的行又落进 WHERE 条件」而重复累加。
     * 所以一条语句就等价于「整段后移一位」，并且天然原子。
     *
     * 曾经考虑过「按 orderIndex DESC 逐行 +1」的写法，那是从
     * `UNIQUE` 索引冲突的角度考虑的；本表的
     * `Index(value = ["sessionId", "orderIndex"])` **不是 UNIQUE**，
     * 中间态不可能报错，逐行反而更慢、还要多读一次。
     *
     * ## 调用方必须放在事务里
     *
     * 「后移 + 插入」必须原子。否则后移完成、插入失败时会留下一个**空洞**：
     * 数据没丢（只是索引不连续），但反复失败会让索引一直膨胀。
     * 见 `ChatRepository.insertMessageAt`。
     */
    @Query(
        "UPDATE messages SET orderIndex = orderIndex + :delta " +
            "WHERE sessionId = :sessionId AND orderIndex >= :fromIndex",
    )
    suspend fun shiftOrderIndexFrom(sessionId: String, fromIndex: Int, delta: Int)

    /** 在指定 orderIndex 上查一行（调试与「摘要已存在」判断用）。 */
    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND orderIndex = :orderIndex LIMIT 1",
    )
    suspend fun atOrderIndex(sessionId: String, orderIndex: Int): MessageEntity?

    // ── 消息变体（对齐 Operit 的「重新生成」语义）───────────────

    /**
     * 取出同一变体组的全部版本，按组内序号升序。
     *
     * 走 `(sessionId, variantGroupId)` 复合索引。
     */
    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND variantGroupId = :groupId " +
            "ORDER BY variantIndex ASC",
    )
    suspend fun variantsOf(sessionId: String, groupId: String): List<MessageEntity>

    /**
     * 组内当前最大序号；组里一条都没有时返回 null。
     *
     * 为什么单独查而不是 `variantsOf(...).maxOfOrNull { it.variantIndex }`：
     * 追加版本是高频路径（每次重试一次），没必要把整组正文都读上来 ——
     * 一个版本可能几千字，读十条就是几十 KB。
     */
    @Query(
        "SELECT MAX(variantIndex) FROM messages WHERE sessionId = :sessionId " +
            "AND variantGroupId = :groupId",
    )
    suspend fun maxVariantIndex(sessionId: String, groupId: String): Int?

    /**
     * 把某条消息挂进变体组（用于「首次重试」时把原回答吸收成第 0 版）。
     *
     * 只更新两列：不整行写，避免覆盖正在流式更新的 `content`。
     */
    @Query(
        "UPDATE messages SET variantGroupId = :groupId, variantIndex = :index WHERE id = :id",
    )
    suspend fun attachToVariantGroup(id: Long, groupId: String, index: Int)
}
