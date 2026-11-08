package dev.praxis.core.data.gateway

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 网关账号表读写。
 *
 * ## 查询为什么带 ORDER BY
 *
 * 账号顺序就是选路优先级的一部分（文档 7.3 的 priority 升序）。让 SQL 保证
 * 顺序，而不是让每个调用点自己排 —— 否则迟早有一处忘了排。
 */
@Dao
interface GatewayAccountDao {

    @Query("SELECT * FROM gateway_account ORDER BY priority ASC, id ASC")
    suspend fun all(): List<GatewayAccountEntity>

    @Query("SELECT * FROM gateway_account WHERE id = :id")
    suspend fun byId(id: String): GatewayAccountEntity?

    @Query("SELECT id FROM gateway_account")
    suspend fun allIds(): List<String>

    /** 同 id 覆盖：导入是按网关 id 幂等覆盖（文档 8.6）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: GatewayAccountEntity)

    @Query("DELETE FROM gateway_account WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM gateway_account")
    suspend fun clear()
}

/** 自定义提供商表读写。 */
@Dao
interface GatewayProviderDao {

    @Query("SELECT * FROM gateway_provider ORDER BY id ASC")
    suspend fun all(): List<GatewayProviderEntity>

    @Query("SELECT * FROM gateway_provider WHERE id = :id")
    suspend fun byId(id: String): GatewayProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: GatewayProviderEntity)

    @Query("DELETE FROM gateway_provider WHERE id = :id")
    suspend fun delete(id: String)
}
