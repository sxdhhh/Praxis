package dev.praxis.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    /**
     * 全部会话，**置顶在前**，各自组内按 [SessionEntity.displayOrder] 升序。
     *
     * ## 为什么排序放在 SQL 而不是 Kotlin
     *
     * 侧边栏要按这个顺序渲染，而列表可能上百条。在 Kotlin 里再排一次意味着
     * 每次 Flow 发射都要重排整个列表；放在 SQL 里则由 SQLite 用索引完成。
     *
     * ## `pinned DESC` 而不是 `pinned ASC`
     *
     * SQLite 里 `false = 0`、`true = 1`，所以 DESC 让 1（置顶）排前面。
     * 这一点很容易写反，导致「置顶的被排到最后」。
     *
     * ## 为什么第二排序键从 `updatedAt DESC` 改成 `displayOrder ASC`
     * （2026-10-24，对齐 Operit `ChatDao.kt:17`）
     *
     * 旧排序是 `updatedAt DESC`，而 `updatedAt` 每次发消息都会刷新 ——
     * 于是「列表顺序会随用户说话而变」，用户刚说完的会话跃到第一、
     * 上一句待过的会话被挤下去。找会话时位置一直在动，这是真实烦恼。
     *
     * `displayOrder` 只由**创建时间**决定（`-createdAt`，见实体 KDoc），
     * 发消息不碰它。所以列表稳定：新建的在前、老的留在原位，
     * 只有置顶与将来的手动拖拽会改变顺序。
     *
     * SQL 与 `ChatDao.kt:17` 逐字一致：`ORDER BY pinned DESC, displayOrder ASC`。
     */
    @Query("SELECT * FROM sessions ORDER BY pinned DESC, displayOrder ASC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT COUNT(*) FROM sessions")
    fun count(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun find(id: String): SessionEntity?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * 置顶 / 取消置顶。
     *
     * 用一条 UPDATE 而不是「读出来改字段再 upsert」：
     * upsert 是 REPLACE，它会**先删后插**，而 messages 表对 sessions 有
     * `ON DELETE CASCADE` 外键 —— 那条会话的消息会被一起删掉。
     * 这不是理论风险，是 REPLACE 的既定行为。
     */
    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    /**
     * 只刷新 `updatedAt`。
     *
     * ## 为什么必须是 `UPDATE`（2026-10-22，与 [setPinned] 同一个坑）
     *
     * 不能写成「读出来 → `copy(updatedAt = ...)` → `upsert`」。
     * `upsert` 是 `REPLACE`，SQLite 执行它是 **DELETE + INSERT**，
     * 而 `messages` 对 `sessions` 有 `ON DELETE CASCADE` ——
     * 一次「刷新时间」会把该会话的**全部消息删掉**。
     *
     * 这条路径被 `appendMessage` 每条消息都调用一次，
     * 所以旧实现的效果是「写一条消息 → 立刻清空整个会话」。
     * 用户报的第 9 项（对话记录没了）根因就在这里。
     */
    @Query("UPDATE sessions SET updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: String, ts: Long)

    /**
     * 只改标题。
     *
     * ## 为什么是 UPDATE 而不是 upsert（第三次强调，同一个坑）
     *
     * `upsert` 是 `REPLACE` = DELETE + INSERT，而 `messages` 对 `sessions`
     * 声明了 `ON DELETE CASCADE`。用 upsert 改标题会把该会话的**全部消息删光**。
     * AI 自动生成标题是「第一句话之后立刻发生」的操作，用错就等于
     * 「一发消息记录就没了」。
     */
    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String)
}