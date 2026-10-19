package dev.praxis.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /** 按写入顺序读整个会话。 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
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
}
