package dev.praxis.feature.chat

/** 会话列表项（与 Room 实体解耦）。 */
data class SessionItem(
    val id: String,
    val title: String,
    val updatedAt: Long,
    /**
     * 是否置顶。
     *
     * 排序**已经由 SQL 完成**（`ORDER BY pinned DESC, updatedAt DESC`），
     * 这个字段只用于在侧边栏里画图钉标记，**不要**再拿它去排序。
     */
    val pinned: Boolean = false,
)
