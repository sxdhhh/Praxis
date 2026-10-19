package dev.praxis.feature.chat

/** 会话列表项（与 Room 实体解耦）。 */
data class SessionItem(
    val id: String,
    val title: String,
    val updatedAt: Long,
)
