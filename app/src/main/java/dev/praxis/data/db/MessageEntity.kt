package dev.praxis.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条消息。
 *
 * ## 三个设计约束
 *
 * 1. **外键级联删除**：删会话必须连带删消息。靠应用层手动删迟早漏，
 *    然后用「删不干净的历史」撑爆数据库。
 * 2. **`orderIndex` 而不是纯时间戳排序**：同一毫秒内可能写入多条
 *    （用户消息 + 助手占位气泡），时间戳会并列，排序不稳定。
 * 3. **工具调用元数据存三列而不是 JSON**：`toolName` / `toolArgs` / `toolCallId`，
 *    因为后续要按工具名做统计（哪些工具用得最多）。存整块 JSON 就没法查。
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index(value = ["sessionId", "orderIndex"])],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    /** 写入顺序（0 起），同一会话内唯一。 */
    val orderIndex: Int,
    /** 角色：user / assistant / tool / error。存字符串不存 ordinal。 */
    val role: String,
    val content: String,
    /** 工具名（仅 tool 角色有值）。 */
    val toolName: String? = null,
    /** 工具参数原文（仅 tool 角色有值）。 */
    val toolArgs: String? = null,
    /** 工具调用 ID（用于与模型返回的 tool_calls 对齐）。 */
    val toolCallId: String? = null,
    /** 工具结果是否失败。 */
    val isError: Boolean = false,
    val createdAt: Long,
)
