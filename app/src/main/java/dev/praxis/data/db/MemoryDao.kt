package dev.praxis.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** 记忆表访问。 */
@Dao
interface MemoryDao {

    /**
     * 读某个空间的全部记忆。
     *
     * [spaceId] 传 null 读跨空间全量（设置页总览）。
     * 用 `:spaceId IS NULL OR spaceId = :spaceId` 而不是写两条 SQL：
     * Room 对 `@Query` 的参数可空写法要求一致，分开写两个方法反而容易漏一个。
     */
    @Query(
        "SELECT * FROM memories WHERE (:spaceId IS NULL OR spaceId = :spaceId) " +
            "ORDER BY lastUsedAt DESC",
    )
    suspend fun all(spaceId: String?): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun find(id: Long): MemoryEntity?

    @Insert
    suspend fun insert(entity: MemoryEntity): Long

    @Update
    suspend fun update(entity: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories WHERE spaceId = :spaceId")
    suspend fun clearSpace(spaceId: String)

    @Query("DELETE FROM memories")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM memories WHERE spaceId = :spaceId")
    suspend fun countIn(spaceId: String): Int

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    /** 列出所有存在的空间 id 与条数（空间选择器用）。 */
    @Query("SELECT spaceId, COUNT(*) AS cnt FROM memories GROUP BY spaceId ORDER BY spaceId")
    suspend fun spaceCounts(): List<SpaceCount>

    /** 一个空间的条数（Room 投影用的 POJO）。 */
    data class SpaceCount(
        val spaceId: String,
        val cnt: Int,
    )
}
