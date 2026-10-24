package dev.praxis.data

import dev.praxis.data.db.MessageDao
import dev.praxis.data.db.MessageEntity
import dev.praxis.data.db.PraxisDatabase
import dev.praxis.data.db.SessionDao
import dev.praxis.data.db.SessionEntity
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 会话与消息的持久化入口。
 *
 * 只暴露领域最需要的操作，不把 DAO 直接透传出去 —— 否则调用方会绕过
 * 「orderIndex 维护」「updatedAt 刷新」这些必须成对做的事。
 */
class ChatRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    /**
     * 数据库实例，仅用于 `withTransaction { }`（中间插入需要原子性）。
     *
     * ## 为什么可空
     *
     * 单测里可以只造两个 DAO 的 fake 来验证「append 的 orderIndex 递增」这类
     * 纯逻辑，不必起一个真实的 Room。为 null 时 [insertMessageAt] 退化为
     * 「后移 + 插入」两次独立调用 —— 少了事务保护，但功能仍正确，
     * 且调用点能看到这个降级（见该方法 KDoc）。
     */
    private val db: PraxisDatabase? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * 串行化「读 MAX + 插入」这一步。
     *
     * ## 为什么必须有（2026-10-24 修，真机数据为证）
     *
     * `appendMessage` 的实现是「先 `MAX(orderIndex)+1`，再 insert」。
     * 这两步之间**不是原子的** —— 而 `:app` 侧真正调用它的
     * `RoomChatPersistence` 是被 `ChatViewModel.launchPersist` 并发调用的：
     * 一轮回复里可能同时有「assistant 占位行」和多个「tool 结果行」在写。
     *
     * 真机库里抓到的重复即由此而来（同一个会话内）：
     *
     * ```
     * (orderIndex=5,  id=6,  tool) (5, 7, tool)
     * (orderIndex=21, id=26, tool) (21, 27, tool) (21, 28, tool)
     * ```
     *
     * 两条协程同时读到 `MAX=4`，就都插入 `5`。
     *
     * ## 为什么是 `Mutex` 而不是把整个方法包进事务
     *
     * `MAX + INSERT` 放进 `withTransaction` 只能防「其它数据库连接」，
     * 防不住同一进程内的两个协程 —— Room 的事务在同一时间只允许一个，
     * 第二个会等第一个提交，但**它的 `MAX` 查询发生在拿到事务之后**，
     * 所以那种写法其实也能修（等价于串行）。
     *
     * 用 `Mutex` 的理由是成本：`db` 是**可空**的（单测路径不需要真库），
     * 拿不到 `db` 时无法开事务，但依然需要串行化。`Mutex` 两条路径都能覆盖，
     * 且不依赖 `db` 是否存在。
     *
     * ## 为什么不改成 `SELECT MAX` 的原子 SQL
     *
     * 可以写成 `INSERT ... SELECT COALESCE(MAX(orderIndex),-1)+1 FROM messages`，
     * 一条语句原子完成。但那会把 `orderIndex` 分配逻辑搬进 SQL 字符串，
     * 单测就无法在不启 Room 的情况下验证「递增」这条不变式
     * （现有测试正是这么测的）。`Mutex` 保住了可测性。
     */
    private val appendLock = Mutex()

    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()

    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
        messageDao.observeSession(sessionId)

    /**
     * 建会话；已存在则**什么都不做**。
     *
     * ## 为什么不能是 REPLACE 语义（2026-10-22 修，第 9 项根因）
     *
     * 旧实现是 `sessionDao.upsert(...)`，而 `upsert` 标注的是
     * `@Insert(onConflict = OnConflictStrategy.REPLACE)`。
     * SQLite 的 `REPLACE` 不是「更新」，而是**先 DELETE 再 INSERT**。
     *
     * 而 `messages` 表对 `sessions` 声明了 `ON DELETE CASCADE`。
     * 两者相乘的后果是：
     *
     * ```
     * 发送一条新消息
     *   → ensureSession(sessionId, ...)   // 每轮都会调
     *   → REPLACE → DELETE 掉 sessions 那一行
     *   → CASCADE 删掉该会话的**全部** messages
     *   → 然后才 appendMessage 写入本轮两条
     * ```
     *
     * 也就是说：**每发一条消息，之前所有聊天记录就被清空一次**。
     * 用户看到的是「对话记录退出后怎么就没了」，实际比这更糟 ——
     * 不用退出，发下一条就没了。
     *
     * 这个坑在 `SessionDao.setPinned` 的注释里已经记过一次（所以它用 UPDATE），
     * 但 `ensureSession` 这条路径漏了。修法：先查存在性，只在不存在时插入。
     *
     * ## 为什么保留「已存在就不更新标题」
     *
     * 旧行为里意图是「用户改名要能落库」。但那个意图从未真正成立：
     * 调用点传的标题是 `text.take(24)`（首条消息前 24 字），
     * 不是用户设定的名字。允许它覆盖，等于每轮都把标题改成最后一句话的开头。
     * 真正的改名应该走一个显式的 `renameSession()`，而不是借 `ensureSession`。
     */
    suspend fun ensureSession(sessionId: String, title: String) {
        // 这一步是必需的，且必须走 DAO 的查询而不是「先 upsert 再修」——
        // 任何以 REPLACE 开头的写法都会先触发那一次 DELETE。
        if (sessionDao.find(sessionId) != null) return
        val ts = now()
        sessionDao.upsert(
            SessionEntity(
                id = sessionId,
                title = title,
                createdAt = ts,
                updatedAt = ts,
            ),
        )
    }

    /**
     * 追加一条消息，自动分配 orderIndex，并刷新会话的 updatedAt。
     *
     * 两步必须一起做：只写消息不刷 updatedAt，会话列表的「最近」排序就永远不动，
     * 用户会觉得新对话沉底了。
     */
    suspend fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        toolName: String? = null,
        toolArgs: String? = null,
        toolCallId: String? = null,
        isError: Boolean = false,
    ): Long {
        // 整个「读 MAX → 插入」必须在锁内：并发时两条协程会读到同一个 MAX。
        // 详见 [appendLock] 的 KDoc。
        val id = appendLock.withLock {
            val nextIndex = messageDao.maxOrderIndex(sessionId) + 1
            messageDao.insert(
                MessageEntity(
                    sessionId = sessionId,
                    orderIndex = nextIndex,
                    role = role,
                    content = content,
                    toolName = toolName,
                    toolArgs = toolArgs,
                    toolCallId = toolCallId,
                    isError = isError,
                    createdAt = now(),
                ),
            )
        }
        // touchSession 放在锁外：它只更新会话时间，与 orderIndex 分配无关，
        // 占着锁做一次多余 UPDATE 会拉长临界区（工具连续返回时是热点路径）。
        touchSession(sessionId)
        return id
    }

    /** 流式期间的增量写入（只改 content，不动其他列）。 */
    suspend fun updateMessageContent(messageId: Long, content: String) {
        messageDao.updateContent(messageId, content)
    }

    /**
     * 把一条消息挂进变体组（首次重试时把原回答吸收成第 0 版）。
     *
     * 不做 `touchSession`：改的是「这条消息属于哪个组」，
     * 会话的最近活跃时间没有变化。
     */
    suspend fun attachToVariantGroup(messageId: Long, groupId: String, index: Int) {
        messageDao.attachToVariantGroup(messageId, groupId, index)
    }

    /**
     * 追加一个变体版本（一次写入就带上变体列）。
     *
     * ## 为什么必须走 [appendLock]
     *
     * 与 [appendMessage] 同一个理由：`maxOrderIndex` + `insert` 之间若被
     * 另一条协程插入，两条消息会拿到同一个 `orderIndex`。而变体场景下
     * 这**更容易发生** —— 重试与「工具结果回写」是两条独立协程，
     * 用户连点重试时它们会同时走到这里。
     */
    suspend fun appendVariant(
        sessionId: String,
        groupId: String,
        index: Int,
        content: String,
    ): Long {
        val id = appendLock.withLock {
            val nextIndex = messageDao.maxOrderIndex(sessionId) + 1
            messageDao.insert(
                MessageEntity(
                    sessionId = sessionId,
                    orderIndex = nextIndex,
                    role = "assistant",
                    content = content,
                    createdAt = now(),
                    variantGroupId = groupId,
                    variantIndex = index,
                ),
            )
        }
        touchSession(sessionId)
        return id
    }

    /** 读出一个变体组的全部版本（按组内序号升序）。 */
    suspend fun loadVariants(sessionId: String, groupId: String): List<MessageEntity> =
        messageDao.variantsOf(sessionId, groupId)

    /** 组内当前最大序号；组为空时返回 null。 */
    suspend fun maxVariantIndex(sessionId: String, groupId: String): Int? =
        messageDao.maxVariantIndex(sessionId, groupId)

    /**
     * 在**指定位置**插入一条消息（中间插入，不追加到末尾）。
     *
     * ## 为什么需要它（2026-10-24，插入摘要位置修复）
     *
     * `appendMessage` 只能取 `MAX(orderIndex)+1`。用它写摘要的后果是
     * 「本次会话内位置对（内存里 `list.add(index+1, …)`），重启后摘要跑到最底下」——
     * 因为读回来是按 `orderIndex` 排序的，而它在库里是最后一行。
     *
     * 那个行为以前在 KDoc 里被写成「可接受的取舍」，但它其实是**不需要的取舍**：
     * `orderIndex` 早就存在、也正是 `observeSession` 的排序键。
     * 只要把 `[atOrderIndex, ∞)` 整体后移一位，再插入新行，位置就持久正确。
     *
     * ## 为什么必须放在 `withTransaction` 里
     *
     * 「后移」与「插入」之间若失败（磁盘满 / 进程被杀），会留下一个空洞：
     * 索引不连续但数据没丢。反复失败会让索引一直膨胀，而且下一次插入
     * 仍会占用那个空洞位置，最终顺序会错。事务保证两者要么都成、要么都不成。
     *
     * ## 关于 orderIndex 的语义
     *
     * 它只是**排序键**，不是「第几条」的严格计数。允许不连续是有意的：
     * 删除消息后不重排（重排要改整表，代价远大于收益），
     * 所以这里也允许插入时产生空洞 —— 只要相对顺序正确即可。
     *
     * @param atOrderIndex 新行要占据的 orderIndex；该值及之后的行整体后移一位。
     * @return 新插入行的 id。
     */
    suspend fun insertMessageAt(
        sessionId: String,
        atOrderIndex: Int,
        role: String,
        content: String,
        toolName: String? = null,
        toolArgs: String? = null,
        toolCallId: String? = null,
        isError: Boolean = false,
    ): Long {
        val id = if (db != null) {
            db.withTransaction {
                // 与 appendMessage 抢同一把锁：事务只保证「后移 + 插入」是原子的，
                // 防不住另一条协程在事务之前刚读完 MAX 又插到同一位置。
                appendLock.withLock {
                    // 先腾位，再插入。顺序不能反：先插会与目标位置那一行并列，
                    // 之后才后移会把新行也一起推开（它已经满足 `>= fromIndex`）。
                    messageDao.shiftOrderIndexFrom(
                        sessionId = sessionId,
                        fromIndex = atOrderIndex,
                        delta = 1,
                    )
                    messageDao.insert(
                        MessageEntity(
                            sessionId = sessionId,
                            orderIndex = atOrderIndex,
                            role = role,
                            content = content,
                            toolName = toolName,
                            toolArgs = toolArgs,
                            toolCallId = toolCallId,
                            isError = isError,
                            createdAt = now(),
                        ),
                    )
                }
            }
        } else {
            // 无事务的降级路径（单测）。顺序同上。
            appendLock.withLock {
                messageDao.shiftOrderIndexFrom(
                    sessionId = sessionId,
                    fromIndex = atOrderIndex,
                    delta = 1,
                )
                messageDao.insert(
                    MessageEntity(
                        sessionId = sessionId,
                        orderIndex = atOrderIndex,
                        role = role,
                        content = content,
                        toolName = toolName,
                        toolArgs = toolArgs,
                        toolCallId = toolCallId,
                        isError = isError,
                        createdAt = now(),
                    ),
                )
            }
        }
        touchSession(sessionId)
        return id
    }

    /** 一次性列出会话（按 updatedAt 倒序）。 */
    suspend fun loadSessionsOnce(): List<SessionEntity> = sessionDao.observeAll().first()

    /** 一次性读全部消息（启动恢复用；实时观察走 observeMessages）。 */
    suspend fun loadMessagesOnce(sessionId: String): List<MessageEntity> =
        messageDao.observeSession(sessionId).first()

    suspend fun deleteSession(sessionId: String) {
        // 消息由外键 CASCADE 自动清除，这里不手动删 —— 手动删容易漏。
        sessionDao.delete(sessionId)
    }

    /**
     * 置顶 / 取消置顶。
     *
     * 走 DAO 的 UPDATE，**不碰** upsert：
     * upsert 是 `OnConflictStrategy.REPLACE`，语义是 delete + insert，
     * 会连带触发 messages 的 CASCADE。改一个布尔值却把聊天记录删了，
     * 这是本项目里最容易踩且最难察的坑之一。
     */
    suspend fun setSessionPinned(sessionId: String, pinned: Boolean) {
        sessionDao.setPinned(sessionId, pinned)
    }

    /**
     * 改会话标题（AI 总结 / 用户改名）。
     *
     * 走 SessionDao.setTitle（一条 UPDATE），不用 upsert ——
     * 理由见该 DAO 方法的 KDoc：REPLACE 会 CASCADE 删光消息。
     */
    suspend fun updateSessionTitle(sessionId: String, title: String) {
        sessionDao.setTitle(sessionId, title)
    }

    /**
     * 刷新会话的 `updatedAt`（供侧边栏按最近活跃排序）。
     *
     * ## 为什么不能用 `upsert`（2026-10-22 修，第二个数据丢失点）
     *
     * 旧实现是：
     * ```
     * val existing = sessionDao.find(sessionId) ?: return
     * sessionDao.upsert(existing.copy(updatedAt = now()))
     * ```
     * 读起来完全正确 ——「读出来、改个时间、写回去」。
     *
     * 但 `upsert` 是 `@Insert(onConflict = REPLACE)`，而 SQLite 的 REPLACE
     * 等价于 **DELETE + INSERT**。`messages` 表又对 `sessions` 有
     * `ON DELETE CASCADE`。于是：
     *
     * ```
     * appendMessage(…)
     *   → messageDao.insert(新消息)           // 刚写进去
     *   → touchSession(sessionId)
     *   → REPLACE → DELETE sessions 行
     *   → CASCADE 删掉**该会话全部消息**      // 刚写的这条也被删了
     * ```
     *
     * 结果是「发完消息，记录就没了」—— 用户报的第 9 项
     * 「对话记录退出后怎么就没了」的真正原因在这里：
     * 不是退出才没，是**每次写完就删**。
     *
     * 修法：用一条 `UPDATE` 只改时间列。`SessionDao` 已经有 `setPinned`
     * 用同样的思路（它的注释里也记过这个坑），这里补一个 `touch`。
     */
    private suspend fun touchSession(sessionId: String) {
        // 不先 find 判空：UPDATE 命中 0 行是无害的，
        // 而多一次 SELECT 在这里没有意义（这是每条消息都会走的路径）。
        sessionDao.touch(sessionId, now())
    }
}
