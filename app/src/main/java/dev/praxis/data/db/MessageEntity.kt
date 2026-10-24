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
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "orderIndex"]),
        // 变体组查询：`WHERE sessionId = ? AND variantGroupId = ?`。
        // 列顺序不能反（复合索引只对前缀生效）；索引名必须合 Room 的
        // `index_<表>_<列...>` 命名，否则启动时 schema 校验会报
        // `Migration didn't properly handle`，应用直接起不来。
        Index(value = ["sessionId", "variantGroupId"]),
    ],
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
    /**
     * 变体组 id（对齐 Operit 的「消息变体」）。
     *
     * ## 它解决什么问题
     *
     * 现在点「重新生成」会**再插入一条 assistant 消息**。用户重试三次，
     * 列表里就有四条并排的助手回答，只有最后一条算数 —— 前面三条是垃圾，
     * 而且把上下文撑长了（它们都在 `observeSession` 的结果里，
     * 构造请求时会被带上）。
     *
     * Operit 的做法是：同一个「位置」的多次回答属于同一个变体组，
     * 用 `selectedVariantIndex` 指向当前生效的那一条，其余折叠。
     *
     * ## 取值语义
     *
     *  - `null`：普通消息，不属于任何变体组（绝大多数消息都是这个）。
     *  - 非 null：同组内所有消息是「同一句话的不同版本」。
     *
     * ## 为什么用字符串而不是自增 Long
     *
     * 组 id 要在**插入前**就确定（同一轮重试的几条共享它），
     * 用 `System.nanoTime()` 的字符串形式最省事，不需要先查库拿序列。
     * 它不对用户可见、不需要有序，只要唯一。
     */
    val variantGroupId: String? = null,

    /**
     * 组内序号（0 起），决定「当前是第几个版本」的显示。
     *
     * `null` 与 [variantGroupId] 同为 null 或同非 null —— 两者要么都有、要么都没有。
     * 拆成两个可空列而不是一个复合对象：SQLite 没有结构体，
     * 拆列才能对「组内最大序号」做 `MAX()` 查询。
     */
    val variantIndex: Int? = null,
)
