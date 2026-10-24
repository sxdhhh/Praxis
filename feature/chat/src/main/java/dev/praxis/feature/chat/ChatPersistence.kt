package dev.praxis.feature.chat

/**
 * 聊天持久化端口。
 *
 * ## 为什么是接口而不是直接用 ChatRepository
 *
 * [ChatPersistence] 在 `feature:chat`，而 Room 实现在 `:app`。
 * 这样 feature 模块不依赖 Room / Android，测试可以传一个内存实现，
 * 也避免「UI 模块知道数据库表结构」这种越层。
 *
 * 这与 core:tool 的 ToolContext 是同一个思路：**端口在里、实现在外**。
 */
interface ChatPersistence {

    /** 建会话（已存在则刷新标题与时间）。 */
    suspend fun ensureSession(sessionId: String, title: String)

    /** 追加一条消息，返回数据库行 id。 */
    suspend fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        toolName: String? = null,
        toolArgs: String? = null,
        toolCallId: String? = null,
        isError: Boolean = false,
    ): Long

    /** 更新消息正文（流式增量）。 */
    suspend fun updateMessageContent(messageId: Long, content: String)

    /**
     * 在**指定位置**插入一条消息（不追加到末尾），返回数据库行 id。
     *
     * ## 为什么端口必须提供它（2026-10-24）
     *
     * 「插入摘要」要求摘要出现在**被长按那条之后**，而不是会话末尾。
     * 旧实现只有 [appendMessage]（取 `MAX(orderIndex)+1`），于是摘要
     * 在界面里位置正确、一重启就沉到最底下 —— 因为读回来按 `orderIndex` 排序。
     *
     * 那被写成「可接受的取舍」，但它是**不必要的取舍**：`orderIndex`
     * 本来就是排序键，腾个位就能持久正确。
     *
     * ## 默认实现为什么直接退化成 append
     *
     * 端口在本模块、实现在 `:app`。内存实现（单测）通常不需要中间插入，
     * 给个默认实现能让它们不改代码就继续编译。真实实现
     * （`RoomChatPersistence`）**必须覆写它**，否则摘要位置仍然只在内存里对。
     *
     * @param atOrderIndex 新行要占据的排序位置；该值及之后的行整体后移一位。
     */
    suspend fun insertMessageAt(
        sessionId: String,
        atOrderIndex: Int,
        role: String,
        content: String,
    ): Long = appendMessage(sessionId = sessionId, role = role, content = content)

    /**
     * 读一个会话的历史（按写入顺序）。
     *
     * 返回 [PersistedMessage] 而不是 Room 实体：feature 模块不该知道表结构，
     * 否则改一列就要改两个模块。
     */
    suspend fun loadMessages(sessionId: String): List<PersistedMessage>

    /** 列出全部会话（按最近更新倒序）。 */
    suspend fun listSessions(): List<SessionItem>

    /** 删除会话（消息由数据库外键级联清除）。 */
    suspend fun deleteSession(sessionId: String)

    /**
     * 置顶 / 取消置顶。
     *
     * 单独一个方法而不是「读 SessionItem 改字段再整条写回」：
     * 写回路径用的是 upsert（REPLACE），而 REPLACE 是删后插，
     * 会触发 messages 的 CASCADE 把整段聊天记录删掉。
     */
    suspend fun setSessionPinned(sessionId: String, pinned: Boolean)

    /**
     * 改会话标题。
     *
     * ## 为什么必须是独立方法，且底层必须是 UPDATE
     *
     * 标题有两种来源：用户手动改名、AI 总结生成。两者都不能走
     * `ensureSession` → `upsert`：`upsert` 是 `OnConflictStrategy.REPLACE`，
     * SQLite 执行它是 **DELETE + INSERT**，而 `messages` 对 `sessions`
     * 有 `ON DELETE CASCADE` —— 一次「改标题」会把整个会话的消息删光。
     *
     * 这个问题在本项目已经踩过两次（`ensureSession`、`setPinned`），
     * 所以标题也单列出来，走一条 `UPDATE sessions SET title = ?`。
     */
    suspend fun updateSessionTitle(sessionId: String, title: String)

    // ── 消息变体（2026-10-24）─────────────────────────────────

    /**
     * 把一条消息挂进变体组（首次重试时，把「原来那条回答」吸收成第 0 版）。
     *
     * 默认空实现：内存实现（单测）用不到变体，不必改代码。
     * 真实实现（`RoomChatPersistence`）必须覆写，否则变体只在 UI 内存里成立。
     */
    suspend fun attachToVariantGroup(messageId: Long, groupId: String, index: Int) {}

    /**
     * 追加一个新版本到变体组，返回数据库行 id。
     *
     * ## 为什么不能复用 [appendMessage] 再补写变体列
     *
     * 那要两次写（先插入、再 UPDATE 变体列），中间若失败会留下
     * 一条「不属于任何组」的 assistant 行 —— 重启后它就是一条独立回答，
     * 变体又不成立了。一次插入带上两个变体列才不会有这个中间态。
     *
     * 默认实现退化成 [appendMessage]（丢掉变体信息）：单测里够用。
     */
    suspend fun appendVariant(
        sessionId: String,
        groupId: String,
        index: Int,
        content: String,
    ): Long = appendMessage(sessionId = sessionId, role = "assistant", content = content)

    /**
     * 读出一个变体组的全部版本，按组内序号升序。
     *
     * 用于「切到第 N 版」时取那条的正文 —— 内存里只保留当前版，
     * 切换要回库取。
     */
    suspend fun loadVariants(sessionId: String, groupId: String): List<PersistedMessage> = emptyList()
}

/** 从数据库读回的一条消息（与 UI 气泡同构）。 */
data class PersistedMessage(
    val id: Long,
    val role: String,
    val content: String,
    val toolName: String?,
    val isError: Boolean,
    /**
     * 写入时间（毫秒）。
     *
     * ## 为什么必须带出来
     *
     * 「跳转到某条消息」以时间戳为键（见 `ChatBubble.timestamp` 的 KDoc）。
     * 恢复历史时若不带 `createdAt`，所有气泡的 timestamp 都会是默认 0，
     * 于是定位器里每一条的键都相同 —— `indexOfFirst` 永远命中第 0 条，
     * 点任何一条都跳到最顶上。
     */
    val createdAt: Long = 0L,

    /**
     * 会话内的排序位置（对应 `messages.orderIndex`）。
     *
     * ## 为什么要把它暴露给上层（2026-10-24 新增）
     *
     * 「插入摘要」需要知道**目标位置下一行**的 orderIndex，才能在它之前
     * 腾位插入。上层若只能看到 `id`，就没法把「内存里的第几条」
     * 换算成「库里该插在哪个 orderIndex」——
     *
     * ```
     * 真实库 orderIndex： [0]=用户, [3]=助手, [7]=用户, [8]=助手
     * 内存下标：            0        1        2        3
     * ```
     *
     * orderIndex **不连续**（删消息后不重排），所以「内存下标 + 1」
     * 与它毫无关系。少了这个字段，中间插入就只能靠猜，
     * 而猜错的后果是摘要插到目标**之前**去。
     */
    val orderIndex: Int = 0,

    /**
     * 工具调用的关联 id（`assistant.tool_calls[].id` ↔ `tool.tool_call_id`）。
     *
     * ## 为什么恢复时必须带出来（2026-10-24 修，「重启后无法继续聊天」）
     *
     * OpenAI 兼容协议要求：`role = "tool"` 的消息**必须**能对应到上一条
     * assistant 消息里某一个 `tool_calls[].id`，否则整轮请求被判为
     * 非法消息序列，服务端直接回 400。而 Praxis 之前的恢复链路是：
     *
     * ```
     * 库里读出 38 条 role="tool" 行（toolCallId 有值，但没往外暴露）
     *   → 恢复时一律塞进 history（text = content）
     *   → 发给上游的 tool 消息全都没有 tool_call_id
     *   → 400 请求参数有误
     * ```
     *
     * 用户的观感就是「重启后无法继续聊天」—— 而且此时无论怎么重试都失败，
     * 因为坏数据每次都在。所以这个字段不是「补充信息」，是**恢复正确性的必要条件**。
     */
    val toolCallId: String? = null,

    /**
     * 变体组 id（`messages.variantGroupId`）。`null` = 普通消息。
     *
     * 恢复历史时必须带出来，否则重启后同一组的 N 条版本会各自变回
     * 独立的助手气泡 —— 正是本轮要修的那个现象。
     */
    val variantGroupId: String? = null,

    /** 组内序号（`messages.variantIndex`）。 */
    val variantIndex: Int? = null,
)
