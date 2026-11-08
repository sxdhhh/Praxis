package dev.praxis.core.data.gateway

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 结构化模型条目的读写。
 *
 * ## 为什么单列一个 enabled 查询
 *
 * 选路只关心启用的条目（文档 5.4 第 2 条「逐模型启停」）。让 SQL 过滤，
 * 而不是让每个调用点自己 filter —— 否则迟早有一处漏了，把停用的模型也发出去。
 */
@Dao
interface GatewayModelDao {

    @Query("SELECT * FROM gateway_model ORDER BY provider ASC, id ASC")
    suspend fun all(): List<GatewayModelEntity>

    @Query("SELECT * FROM gateway_model WHERE enabled = 1 ORDER BY provider ASC, id ASC")
    suspend fun enabled(): List<GatewayModelEntity>

    @Query("SELECT * FROM gateway_model WHERE provider = :provider AND id = :id")
    suspend fun byId(provider: String, id: String): GatewayModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GatewayModelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<GatewayModelEntity>)

    @Query("DELETE FROM gateway_model WHERE provider = :provider AND id = :id")
    suspend fun delete(provider: String, id: String)

    @Query("DELETE FROM gateway_model")
    suspend fun clear()
}
