package dev.praxis.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次会话。Phase 1 只落最小字段，后续移植 Operit 的 chat 域时按需扩展，
 * 不做「先建大表再废弃」的返工。
 */
@Entity(
    tableName = "sessions",
    /**
     * 与 `MIGRATION_5_6` 里手写的索引**必须逐字一致**（列、顺序、名字）。
     *
     * Room 在启动时会拿「实体注解推导出的 schema」与「实际库」比对：
     *  - SQL 里建了索引但注解没写 → Room 认为多了个东西（版本不一致告警）；
     *  - 注解写了但 SQL 没建 → 报 `Migration didn't properly handle`，应用起不来。
     *
     * 名字用 Room 的自动命名规则（`index_<表>_<列下划线连接>`），
     * 所以这里不写 `name`，让它自己生成 —— 与迁移里手写的那个名字相同。
     */
    indices = [Index(value = ["pinned", "displayOrder"])],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * 是否置顶。
     *
     * ## 为什么加在实体上而不是单独的置顶表
     *
     * 置顶是**会话自身的一个属性**，不是一对多关系。单独建表要多一次 JOIN，
     * 而排序 `ORDER BY pinned DESC, updatedAt DESC` 需要它在同一张表里才高效
     * （SQLite 能用上 `pinned` 前缀索引）。
     *
     * 默认 false：老数据迁移后全部不置顶，与原行为一致。
     */
    val pinned: Boolean = false,

    /**
     * 列表排序键 —— **1:1 对齐 Operit 的 `ChatEntity.displayOrder`**。
     *
     * ## 为什么需要它（旧实现是真实缺陷）
     *
     * 旧排序是 `ORDER BY pinned DESC, updatedAt DESC`。而 `updatedAt` 是
     * **每次发消息都会刷新**的字段（`touchUpdatedAt`）。后果：
     *
     * ```
     * 用户在跟 A 聊天 → A 跳到第一
     * 用户切到 B 说一句 → B 跳到第一，A 掉下去
     * ```
     *
     * 也就是「列表顺序随说话而变」—— 用户找不到会话，因为它的位置在动。
     *
     * Operit 的做法（`ChatDao.kt:17`）是把**排序键与活跃时间分开**：
     *
     * ```sql
     * ORDER BY pinned DESC, displayOrder ASC
     * ```
     *
     * 其中 `displayOrder = -createdAt`（`ChatEntity.kt:20` 的默认值）。
     * 发消息只改 `updatedAt`（用于「最近活跃」展示/同步），**不动排序键**。
     * 于是列表顺序稳定：新会话在前面，老会话老位置，除非用户手动拖动。
     *
     * ## 为什么默认值是 `-createdAt` 而不是 `createdAt`
     *
     * 因为排序方向是 `ASC`，而我们要「新的在前」。取负数之后，
     * `createdAt` 越大 → `displayOrder` 越小 → `ASC` 排得越前。
     *
     * 用负数而不是「`createdAt DESC`」的好处：以后支持**手动拖拽排序**时，
     * 直接改单个会话的 `displayOrder` 即可，不必改 SQL 的排序方向。
     *
     * ## 迁移
     *
     * `MIGRATION_5_6` 加列后立刻 `UPDATE sessions SET displayOrder = -createdAt`，
     * 这样老用户的列表顺序迁移前后**完全一致**（都是按创建时间倒序）。
     */
    val displayOrder: Long = -createdAt,
)
