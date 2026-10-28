package dev.praxis.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条长期记忆。
 *
 * ## 字段取舍
 *
 * 1. **tags 存单个 TEXT（逗号分隔）而不是子表**：标签只用于检索过滤，
 *    不需要按标签 JOIN 统计。单列省一次 join，且记忆条数在千级以内，
 *    全量扫描的开销可以忽略。分隔符用 `\u001F`（单元分隔符）而不是逗号 —— 
 *    标签本身可能含逗号（「工作,副业」），逗号会切错。
 * 2. **hitCount 单独存**：检索时按命中次数排标签路，每次命中 +1。
 * 3. **不和 sessions 建外键**：记忆是**跨会话**的，绑定到某个会话就失去意义。
 * 4. **lastUsedAt 与 createdAt 分开**：时间召回用 lastUsedAt，
 *    这样一条老记忆被再次引用后能重新排到前面。
 */
@Entity(
    tableName = "memories",
    indices = [
        Index("lastUsedAt"),
        Index("createdAt"),
        // 多空间后的主要查询路径：先按空间筛再按时间排。
        Index("spaceId", "lastUsedAt"),
    ],
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    /** 标签，用 [TAG_SEPARATOR] 连接。 */
    val tags: String = "",
    val createdAt: Long,
    val lastUsedAt: Long,
    val hitCount: Int = 0,

    /**
     * 所属空间 id（空串 = 默认空间）。
     *
     * 存字符串而不存空间表外键：空间是用户自建的几个名字，
     * 为它 join 一张表只为了阻止孤儿 id，而那件事一行校验就够。
     * 索引覆盖 (spaceId, lastUsedAt)：记忆列表与检索都按这两列走。
     */
    val spaceId: String = "",
) {
    companion object {
        /**
         * 单元分隔符 U+001F：用户不可能输入，不会与标签内容冲突。
         * 不能用逗号：「工作,副业」这种标签本身带逗号的会切错。
         */
        const val TAG_SEPARATOR = '\u001F'
    }
}
