package dev.praxis.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** 记忆表访问。 */
@Dao
interface MemoryDao {

    /** 全量读取。候选集规模在千级以内，一次读全后再在内存里融合比写复杂 SQL 更可控。 */
    @Query("SELECT * FROM memories ORDER BY lastUsedAt DESC")
    suspend fun all(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun find(id: Long): MemoryEntity?

    @Insert
    suspend fun insert(entity: MemoryEntity): Long

    @Update
    suspend fun update(entity: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int
}
