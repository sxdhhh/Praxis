package dev.praxis.core.data.gateway

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Insert

/**
 * 一条网关请求记录（文档 6.1 的 `gateway_request`）。
 *
 * ## 为什么字段这么多而这一轮没人写
 *
 * 它是**报表与 token 预算的数据源**（P3）。表结构与字段一次定死，
 * 后面接写入时不用再改 schema（改一次就是一次迁移）。
 *
 * ## `isTest` 为什么会存在
 *
 * 模型连通性测试会**真打上游**。如果它进报表，人工样本会把趋势读歪 ——
 * 用户会看到「今天用量突然翻倍」，其实是他自己点了两次测试。
 */
@Entity(tableName = "gateway_request")
data class GatewayRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 请求时刻（毫秒）。 */
    val ts: Long,
    /** 对外模型名。 */
    val model: String,
    /** 真正发给上游的名字；映射过的才有值。 */
    val upstreamModel: String? = null,
    val providerId: String? = null,
    val accountId: String? = null,
    /** HTTP 状态；0 = 没拿到响应（网络层失败）。 */
    val status: Int,
    val error: String? = null,
    /** 试了几个组合（失败换号会累加）。 */
    val attempts: Int = 1,
    /**
     * 上游给的 token 用量。
     *
     * **失败请求一律 0**（文档 §5.7：上游错误时 usage 常缺失，留着半截会让
     * 「失败暴增」看起来像「用量暴增」）。
     */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val credit: Double? = null,
    val durationMs: Long = 0,
    val firstResponseMs: Long? = null,
    val client: String? = null,
    /** 测试请求不入报表。 */
    val isTest: Boolean = false,
)

/**
 * 日聚合（文档 6.1 的 `gateway_daily`）。
 *
 * ## 为什么要它
 *
 * 报表要「今日 / 近 7 天 / 近 30 天」。每次扫全表在手机上代价太高，
 * 所以留一张按天预聚合的表。
 *
 * ## `day` 必须是**本地时区**的 yyyy-MM-dd
 *
 * 网关 #329 的真实事故：容器没设 TZ，UTC+8 用户的「今天」实际是昨天 08:00 到今天 08:00。
 * 所以聚合键由 `GatewayDailyKey.of(...)` 生成（有单测），不在这里拼字符串。
 */
@Entity(tableName = "gateway_daily", primaryKeys = ["day", "model", "providerId"])
data class GatewayDailyEntity(
    /** 本地时区的 yyyy-MM-dd。 */
    val day: String,
    val model: String,
    /** 空串表示未知来源（Room 的主键不能为 null）。 */
    val providerId: String,
    val requests: Int,
    val failures: Int,
    val tokens: Long,
    val cacheRead: Long,
)

/** 请求记录与日聚合的读写。 */
@Dao
interface GatewayUsageDao {

    @Insert
    suspend fun insertRequest(entity: GatewayRequestEntity): Long

    @Query("SELECT COUNT(*) FROM gateway_request WHERE ts >= :sinceMillis AND isTest = 0")
    suspend fun countSince(sinceMillis: Long): Int

    @Query("SELECT SUM(promptTokens + completionTokens) FROM gateway_request WHERE ts >= :sinceMillis AND isTest = 0")
    suspend fun tokensSince(sinceMillis: Long): Long?

    @Query("DELETE FROM gateway_request WHERE ts < :beforeMillis")
    suspend fun purgeRequestsBefore(beforeMillis: Long): Int

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertDaily(entity: GatewayDailyEntity)

    @Query("SELECT * FROM gateway_daily WHERE day >= :sinceDay ORDER BY day DESC")
    suspend fun dailySince(sinceDay: String): List<GatewayDailyEntity>

    @Query("DELETE FROM gateway_daily WHERE day < :beforeDay")
    suspend fun purgeDailyBefore(beforeDay: String): Int

    // ── 报表聚合（文档 §9.6）──
    //
    // 直接查明细聚合，先不写日聚合表：手机上一周的量级（几千行）扫一遍是毫秒级，
    // 而日聚合表要保证「写入与聚合同时更新」才算对 —— 那是等真有性能问题再上的复杂度。

    /** 总请求数 / 失败数 / token 合计。[isTest] 一律排除（文档 §5.7 的测试请求不入报表）。 */
    @Query(
        "SELECT COUNT(*) AS requests, " +
            "SUM(CASE WHEN status != 200 THEN 1 ELSE 0 END) AS failures, " +
            "SUM(promptTokens + completionTokens) AS tokens " +
            "FROM gateway_request WHERE ts >= :sinceMillis AND isTest = 0",
    )
    suspend fun summarySince(sinceMillis: Long): GatewayUsageSummaryRow?

    /** 按模型分组（报表的「哪个模型用得最多」）。 */
    @Query(
        "SELECT model AS name, COUNT(*) AS requests, " +
            "SUM(CASE WHEN status != 200 THEN 1 ELSE 0 END) AS failures, " +
            "SUM(promptTokens + completionTokens) AS tokens " +
            "FROM gateway_request WHERE ts >= :sinceMillis AND isTest = 0 " +
            "GROUP BY model ORDER BY requests DESC",
    )
    suspend fun byModelSince(sinceMillis: Long): List<GatewayUsageGroupRow>

    /**
     * 按账号聚合窗口内的 token（token 预算用）。
     *
     * 排除 accountId 为空的记录：那是「没能定到账号」的失败，算到谁头上都不对，
     * 而算错了会**静默地**让某个账号提前被限流。
     */
    @Query(
        "SELECT accountId AS accountId, SUM(promptTokens + completionTokens) AS tokens " +
            "FROM gateway_request WHERE ts >= :sinceMillis AND isTest = 0 " +
            "AND accountId IS NOT NULL GROUP BY accountId",
    )
    suspend fun tokensByAccountSince(sinceMillis: Long): List<GatewayAccountTokensRow>

    /**
     * 最近 [limit] 条请求（日志页的「请求记录」视图，文档 §5.8）。
     *
     * **包含** `isTest`：这是排障视图，用户点了「测试连接」之后就该能看到那条请求。
     * 报表才排除它（那里会污染趋势）。同一个库、两个视图、口径不同 —— 是有意的。
     */
    @Query("SELECT * FROM gateway_request ORDER BY ts DESC LIMIT :limit")
    suspend fun recentRequests(limit: Int): List<GatewayRequestEntity>
}

/** 汇总一行（SQL 别名对应字段）。 */
data class GatewayUsageSummaryRow(
    val requests: Int,
    val failures: Int,
    val tokens: Long,
)

/** 按账号的 token 合计（预算判定用）。 */
data class GatewayAccountTokensRow(
    val accountId: String,
    val tokens: Long,
)

/** 分组一行（按模型 / 按家）。 */
data class GatewayUsageGroupRow(
    val name: String,
    val requests: Int,
    val failures: Int,
    val tokens: Long,
)

