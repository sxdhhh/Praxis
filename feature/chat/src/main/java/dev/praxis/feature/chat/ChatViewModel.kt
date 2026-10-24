package dev.praxis.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.praxis.core.ai.AiError
import dev.praxis.core.ai.AiErrorKind
import dev.praxis.core.ai.AiException
import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatMessage
import dev.praxis.core.ai.ChatRequest
import dev.praxis.core.ai.ChatRole
import dev.praxis.core.ai.ToolResult
import dev.praxis.core.ai.CompactedContext
import dev.praxis.core.ai.CompactionApplier
import dev.praxis.core.ai.CompactionSettings
import dev.praxis.core.ai.CompactionTrigger
import dev.praxis.core.ai.MemoryPressure
import dev.praxis.core.ai.MemoryPressurePolicy
import dev.praxis.core.ai.TokenCache
import dev.praxis.core.ai.ContextAssembler
import dev.praxis.core.ai.ContextCompactor
import dev.praxis.core.ai.SystemPromptBuilder
import dev.praxis.core.ai.SystemPromptSettings
import dev.praxis.core.ai.ThinkingEffort
import dev.praxis.core.tool.descriptorsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 聊天页状态机。
 *
 * ## 三个必须遵守的点
 *
 * 1. **流式增量直接改同一个气泡**：不给每个 delta 新建对象，否则 LazyColumn 会
 *    每帧重新布局整条消息，长回复时肉眼可见卡顿。
 * 2. **失败不丢上下文**：出错时保留已收到的部分文本，错误作为独立气泡追加，
 *    用户能看到「说到哪断的」。
 * 3. **发送中的协程要能取消**：切页面或重新发送时必须 cancel，否则旧流会继续
 *    往新状态里写，出现「回复串台」。
 */
class ChatViewModel(
    private val provider: AiProvider,
    private val model: String,
    private val toolRegistry: dev.praxis.core.tool.ToolRegistry? = null,
    private val toolExecutor: dev.praxis.core.tool.ToolExecutor? = null,
    private val toolContext: dev.praxis.core.tool.ToolContext? = null,
    /**
     * 每轮发送时**现取**权限快照，而不是构造时缓存。
     *
     * 缓存会导致「设置页改了档位，但聊天还在用旧档位」—— 用户会以为设置没生效。
     */
    private val permissionSnapshotProvider: (() -> dev.praxis.core.tool.ToolPermissionSnapshot)? = null,
    /**
     * 持久化仓储。null 表示不落盘（单测与纯 UI 预览用）。
     *
     * 用可空而不是强制注入：ViewModel 的单测不应该被迫起一个 Room 实例。
     */
    private val chatRepository: ChatPersistence? = null,
    private val sessionId: String = "default",
    /**
     * 长期记忆检索端口。null 表示不注入记忆（单测与纯 UI 预览用）。
     *
     * 用可空而不是强制注入的理由与 [chatRepository] 一致：
     * ViewModel 的单测不应该被迫起一个 Room 实例。
     */
    private val memoryPort: MemoryPort? = null,
    /**
     * 已启用技能拼成的提示词片段（可为空）。
     *
     * 用 `suspend () -> String` 而不是 String：技能列表可能在设置页被改，
     * 每轮现取才能保证「刚关掉的技能下一句就不生效」。
     * 空串表示不插 system。
     */
    private val skillsPromptProvider: (suspend () -> String)? = null,

    /**
     * 用户自定义系统提示词（每轮现取）。
     *
     * 现取而不是构造时缓存：用户在设置页改完提示词，下一句就该生效。
     * 缓存会导致「改完没反应，要重启」，那是最容易被当成 bug 的体验。
     */
    private val systemPromptProvider: (suspend () -> String)? = null,

    /**
     * 上下文运行配置（上限 / 压缩阈值 / 思考强度，每轮现取）。
     *
     * 打包成一个函数而不是三个参数：它们总是一起变（用户在模型页一改就是一整组），
     * 拆成三个会让构造签名迅速变长，且调用点要记住三个都得传。
     */
    private val contextConfigProvider: (suspend () -> ContextConfig)? = null,

    /**
     * 压缩用的模型 ID（来自「摘要」功能位配置）。
     *
     * 与主模型分开：摘要这类任务用便宜模型就够，
     * 用户在主模型上花的是「回答质量」的钱，不该被摘要分摊。
     */
    private val summaryModelProvider: (suspend () -> String)? = null,

    /**
     * 会话标题生成器（AI 总结）。
     *
     * ## 为什么必须有，且是可选
     *
     * 旧实现把标题固定成 `text.take(24)`（首条消息前 24 字），侧边栏里
     * 多个会话常常长得一样，且截断后是半句话。Operit 的做法是**单独调一次
     * 模型**总结用户真实目的（`ConversationService.generateConversationTitle`）。
     *
     * 这里照此实现，但保持可选：单测 / 预览不注入时退化为「兜底标题」，
     * 不会因为缺少网络依赖而让发送失败。
     */
    private val titleGenerator: ChatTitleGenerator? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var nextId = 0L
    private var streamingJob: Job? = null

    /**
     * 「插入摘要」那次模型调用的任务句柄。
     *
     * 单独一个字段而不是复用 [streamingJob]：两者可以同时存在
     * （用户在等摘要返回时可以继续发消息），共用句柄会让其中一个
     * 把另一个取消掉。只用于「连点两次插入摘要时取消上一次」。
     */
    private var summaryJob: Job? = null

    /**
     * 气泡时间戳的单调发生器。
     *
     * ## 为什么不能直接用 `System.currentTimeMillis()`
     *
     * 「跳转到某条消息」以时间戳为键（`indexOfFirst { it.timestamp == ts }`）。
     * 而一次发送会在同一次调用里创建两个气泡（用户消息 + 助手占位），
     * 中间没有 IO，两次 `currentTimeMillis()` 极可能返回**同一毫秒**。
     * 时间戳一撞，定位器里点任何一条都命中前一条 —— 表现是「点第 10 条跳到第 9 条」。
     *
     * 库里已经有 `orderIndex` 解决排序，但它是行级概念，UI 气泡没有这个字段。
     * 这里用「严格递增且贴近真实时间」的生成器解决：正常情况下就是真实时间，
     * 只有同一毫秒内连续创建时才 +1。
     */
    private var lastBubbleTimestamp = 0L

    private fun nextTimestamp(): Long {
        val now = System.currentTimeMillis()
        lastBubbleTimestamp = if (now > lastBubbleTimestamp) now else lastBubbleTimestamp + 1
        return lastBubbleTimestamp
    }

    /** 对话历史（送给模型的原文，不含 UI 的 Error 气泡）。 */
    private val history = mutableListOf<ChatMessage>()

    /**
     * 本次请求要追加的**瞬时**指令（不进 [history]、不落库）。
     *
     * ## 为什么不能直接改 `history`
     *
     * `truncateFrom` / `removeBubble` 靠「`history` 与 `bubbles` 按条数对齐」
     * 来裁剪（前者 `while (history.size > keepForHistory) removeAt(lastIndex)`）。
     * 若「继续」塞一条假用户消息进 `history`，而 UI 里没有对应气泡，
     * 这条不变量当场被破坏：之后每删一条消息，`history` 都会多留一条，
     * 用户看到的是「删了消息、AI 却还记得」。
     *
     * 所以这里放一个**一次性**字段：`startStream` 取用后立即清空
     * （见那里的 `finally` 与取用点）。
     *
     * ## 为什么是 String 而不是 Boolean
     *
     * 文案要写清「接着上一段继续」的边界（不要重复已写内容、不要重新开头），
     * 否则模型看到半句话会倾向于重写一遍完整回答 —— 那与「继续」的意图相反。
     * 具体措辞集中在 [CONTINUE_HINT] 一处，便于以后微调。
     */
    private var pendingContinueHint: String? = null

    /** UI 气泡 id → 数据库行 id（流式增量要按行更新）。 */
    private val assistantRowIds = mutableMapOf<Long, Long>()

    /**
     * 当前已生效的压缩结果。
     *
     * 只在内存里：它是一份「派生产物」，原始消息仍在 [history] 与数据库里。
     * 落盘它反而会带来不一致风险（摘要说覆盖了 20 条，而库里被删了几条）。
     */
    private var compacted: CompactedContext = CompactedContext.None

    /**
     * 增量 token 计算缓存（1:1 移植 Operit `TokenCacheManager`）。
     *
     * ## 为什么需要它
     *
     * `publishUsage` 每次都要算整段历史的 token，而它在流式期间的开销路径上。
     * 有了公共前缀缓存，「追加一轮对话」只需算新增的一两条。
     *
     * ## 两个必须 `reset()` 的时机
     *
     *  1. **压缩生效后**：历史头部被换成摘要，公共前缀必然断裂；
     *  2. **历史被截断 / 回滚后**：`truncateFrom` 会把尾部砍掉，
     *     而缓存里还留着「更长的旧历史」，下一次 `count` 会拿它当公共前缀 ——
     *     于是算出一个偏大的值。
     */
    private val tokenCache = TokenCache()

    /**
     * 压缩相关的可配项（来自设置页；未注入时用 [CompactionSettings] 的默认值）。
     *
     * 与 [RuntimeConfig] 分开：这几项是「用户偏好」（条数阈值、全局规则），
     * 不随每轮请求的上下文预算变化，也不必每轮 await 一次。
     */
    private val compactionSettings: CompactionSettings = CompactionSettings()

    /**
     * 正在执行的内存驱动压缩（防止重复进入）。
     *
     * 由 [startCompaction] 的 `finally` 释放 —— 所有提前返回路径都覆盖到。
     */
    private var memoryCompactInFlight = false

    /**
     * 上一次因内存压力压缩时的档位。
     *
     * `null` = 还没因内存压过。同一档位不重复压，只有**升级**才再压一次
     * （见 [compactForMemoryPressure]）。
     *
     * ## 什么时候清零（这一步很关键，弄反会让自动压缩永久失效）
     *
     * 只有内存**回落到 [MemoryPressure.Normal]** 时才清零。
     *
     * 反过来想就清楚了：如果长期不清零，那么「压过一次 → 内存降下来 →
     * 过一会儿又涨回 High」这条最常见的循环里，第二次涨到 High 会被
     * `level <= last` 挡掉 —— 用户从此再也不会被自动压缩，而那正是
     * 他最需要的时候。
     *
     * 但也不能在每次 `onMemoryPressureChanged` 里无脑清零：
     * 那样「持续停在 High」的每一次轮询（15 秒一轮）都会压一次，
     * 变成每 15 秒烧一次模型调用。所以只在**真正回到 Normal** 时清。
     */
    private var lastMemoryCompactLevel: MemoryPressure? = null

    /**
     * 内存压力变化入口（由 UI 层把 `MemoryMonitor.pressure` 转写进来）。
     *
     * ## 为什么不在 ViewModel 里直接注入 `MemoryMonitor`
     *
     * `feature:chat` 是 Android library 但不该知道 `app` 层的数据源实现
     * （那样单测要造一个 `ActivityManager`）。所以沿用本项目既有的
     * 「端口在里、实现在外」口径：ViewModel 只暴露一个接收档位的方法，
     * 由 `ChatTab` 侧的 `LaunchedEffect` 把 Flow 转发进来。
     *
     * ## 回落时要做的事
     *
     * 内存回到 [MemoryPressure.Normal] 时清掉 [lastMemoryCompactLevel]，
     * 否则「压过一次 → 内存降下来 → 又涨上去」这条循环里，
     * 第二次涨上去会被「同档位已压过」挡掉，永远不再自动压缩。
     */
    fun onMemoryPressureChanged(level: MemoryPressure) {
        if (level <= MemoryPressure.Normal) {
            // 已经回落到正常：允许下一轮同档位再触发。
            lastMemoryCompactLevel = null
            return
        }
        if (!MemoryPressurePolicy.shouldCompact(level)) return
        compactForMemoryPressure(level)
    }

    /** 队列条目的自增 id（与气泡 id 分开，避免两套 id 撞在一起）。 */
    private var nextQueueId = 0L

    /**
     * 从队列取出、正在发送的那一条。
     *
     * 失败时要 `restore` 回队首，否则这条消息在「输入框」和「队列」里都不存在。
     * 用普通字段而不是状态：它不参与渲染，进状态只会多一次重组。
     */
    private var inFlightQueueMessage: QueuedMessage? = null

    /** 每轮的运行配置缓存（避免同一次发送里多次 await provider）。 */
    private data class RuntimeConfig(
        val systemPrompt: String,
        val contextLimit: Int,
        val compactThreshold: Float,
        val thinkingEffort: ThinkingEffort,
        val summaryModel: String,
        /** 是否用流式请求（来自底栏「行为」分区）。 */
        val stream: Boolean,
    )

    /** 落盘用协程作用域（与 viewModelScope 分开，可单独取消）。 */
    private val persistScope = kotlinx.coroutines.CoroutineScope(
        viewModelScope.coroutineContext + kotlinx.coroutines.Dispatchers.IO,
    )

    /** 快捷落盘：repo 为空时直接跳过，保证「不落盘也能用」。 */
    private fun launchPersist(block: suspend ChatPersistence.() -> Unit) {
        val repo = chatRepository ?: return
        persistScope.launch { repo.block() }
    }

    private var restored = false

    /**
     * 是否正在恢复历史。
     *
     * ## 为什么必须有这个标志（「消息突然不显示」的直接原因之一）
     *
     * 恢复走 `persistScope.launch`（IO），而 UI 是可交互的。旧实现里
     * `restoreIfNeeded()` 一进来就把 `restored = true`，然后：
     *
     * ```
     * restoreIfNeeded()  → restored = true → launch 读库（几十毫秒）
     * 用户此时点「发送」     → send() → 气泡 = [新消息]
     * 读库回来             → 用「历史 + fresh」重算 → ...
     * ```
     *
     * 这条路径现在看起来是对的（前置拼接），但下面两点仍然会让消息**看不见**：
     *
     * 1. **重复调用被永久挡掉**：`restored` 一旦为 true，第二次进入同一会话
     *    （比如转屏重建、从设置页返回）不会再读库。若第一次读库恰好失败
     *    （`runCatching` 吞掉的异常），这个会话就**永远空白**。
     * 2. **空库也吃掉机会**：首条消息还没落盘时读库得到空列表，
     *    之后 `restored = true` 挡住重试。用户看到「历史消息全没了」。
     *
     * 所以现在 `restored` 只在**成功读到数据**时置位，并提供
     * [reloadHistory] 供外部（切回会话 / 恢复失败）强制重试。
     */
    private var restoreInFlight = false

    /**
     * 强制重读历史（跳过「已恢复」判断）。
     *
     * ## 为什么需要它
     *
     * `LaunchedEffect(viewModel)` 在换实例时会重跑，但同一个 ViewModel
     * 的「重建视图」场景（转屏、从二级页返回）不会。那些场景里如果
     * 第一次恢复失败过，界面就再也回不来 —— 除非有一个显式重试入口。
     */
    fun reloadHistory() {
        restored = false
        restoreIfNeeded()
    }

    /**
     * 从数据库恢复历史（每个 ViewModel 只跑一次）。
     *
     * 为什么必须做：不做的话「杀进程 → 重开」聊天页是空的，
     * 用户会认为消息全丢了 —— 这是最伤信任的一类缺陷。
     *
     * 恢复时**重建 history**（送给模型的原文），否则模型不知道之前聊过什么，
     * 表现为「AI 失忆」。
     */
    fun restoreIfNeeded() {
        // 三个条件的顺序有意义：
        //  - restored：已经成功恢复过，直接返回；
        //  - restoreInFlight：正在读库，避免并发两次恢复互相覆盖（第二次读到的
        //    结果会把第一次刚拼好的气泡再拼一遍，出现重复消息）；
        //  - chatRepository == null：不落盘模式（单测/预览），不需要恢复。
        if (restored || restoreInFlight) return
        val repo = chatRepository ?: return
        restoreInFlight = true

        persistScope.launch {
            try {
                val allRows = runCatching { repo.loadMessages(sessionId) }.getOrNull()
                if (allRows == null) {
                    // 读库失败（权限、迁移、磁盘满）。**不置 restored**：
                    // 置了的话这个会话本进程内永远空白，用户只能杀进程。
                    return@launch
                }
                if (allRows.isEmpty()) {
                    // 空库是「还没写过」的正常状态，同样不置 restored：
                    // 首条消息落盘后需要一次恢复才能把那条读回来。
                    return@launch
                }

            // 只恢复最近 [ChatRenderBudget.HISTORY_WINDOW] 条。
            //
            // ## 为什么必须有上限（真机缺陷，2026-10-22 修）
            //
            // 几百轮的旧会话会一次性把几百条消息 map 成气泡并整体下发，
            // 首帧要测量几百个条目 —— 表现是「点进旧会话白屏几秒」，
            // 而且越老的会话越慢。这个成本随会话年龄线性增长，
            // 属于「用久了必然遇到」的那类问题。
            //
            // ## 为什么不影响「AI 记得之前聊过」
            //
            // [history]（发给模型的原文）与 UI 气泡是两回事。这里**两者都只
            // 取最近 N 条**，因为超出的部分会被 `ContextAssembler` 的字符预算
            // 裁掉 —— 与其发出去再丢，不如一开始就不载入。
            // 真正需要「很久以前的事实」时走长期记忆（`memoryPort`），
            // 那条链路本来就在按相关性检索。
            val rows = if (allRows.size > ChatRenderBudget.HISTORY_WINDOW) {
                allRows.takeLast(ChatRenderBudget.HISTORY_WINDOW)
            } else {
                allRows
            }

            val bubbles = rows.mapNotNull { row ->
                // ## 先丢弃「空 assistant 占位行」（2026-10-24 修，重启后脏气泡）
                //
                // `send()` 为了拿行 id 会先插一条 `role="assistant", content=""`
                // 的占位行，等流式内容回来再 `updateContent` 回填。
                // 只要这一轮回合没走完（进程被杀、用户直接退出、模型 400、
                // 工具链中途断掉），库里就永久留下一条**空正文**的 assistant 行。
                //
                // 真机库 45 条里有 3 条这样的行（含最后一条 id=45），
                // 恢复后它们渲染成「只有一个『回复』标签、下面一片空白」的
                // 残缺气泡 —— 正是用户报的「会话内容全部显示不正常」。
                //
                // 为什么在**恢复时**丢弃而不是在写库时避免：写库那一刻无法知道
                // 这一轮会不会失败，只能先占位。而恢复是「已经定局」的时刻，
                // 空正文的 assistant 行没有任何信息量，丢弃是正确处置。
                //
                // 注意只丢 assistant：`user` / `tool` / `summary` 的空正文另有
                // 含义（用户可能真发了个空串的历史脏数据），不作处理。
                if (row.role == "assistant" && row.content.isBlank()) return@mapNotNull null

                val bubbleId = nextId++
                // 记住行 id，后续流式增量才能定位到正确的那一行。
                assistantRowIds[bubbleId] = row.id
                val role = when (row.role) {
                    "user" -> BubbleRole.User
                    "assistant" -> BubbleRole.Assistant
                    "tool" -> BubbleRole.Tool
                    // 摘要从数据库恢复：与 Operit 的 `sender = "summary"` 同名。
                    // 必须能区分，否则重启后摘要会被当成普通助手消息，
                    // 长按菜单会多出「重新生成 / 回复」那些不该有的项。
                    "summary" -> BubbleRole.Summary
                    else -> BubbleRole.Error
                }
                // 同时重建送给模型的历史。
                //
                // ## 2026-10-24 修：「重启后无法继续聊天」的真正根因
                //
                // 旧实现把每条非 error 行都写成 `ChatMessage(role, text = content)`。
                // 真机库里 45 条有 **38 条是 `role = "tool"`**，于是恢复后的
                // history 里有 38 条裸 tool 消息 → 上游回
                // `400 请求参数有误` → 用户看到截图里那句
                // 「请求参数有误，请检查模型配置」，且**每次重试都失败**
                // （坏数据一直在，不是偶发）。
                //
                // ## 为什么不能「还原成合法 tool 消息」了事
                //
                // 我一度想用 `ToolResult(callId=…, name=…)` 把 tool 回放合规。
                // 但核对序列化侧后发现这条路走不通：协议要求的是**成对结构** ——
                //
                // ```json
                // {"role":"assistant","tool_calls":[{"id":"call_x", …}]}
                // {"role":"tool","tool_call_id":"call_x","content":"…"}
                // ```
                //
                // 而库里的 assistant 行**没有** `toolCalls` 列
                // （`MessageEntity` 只存 toolName / toolArgs / toolCallId，
                // 都是给 tool 行用的；assistant 的 tool_calls 从未落盘）。
                // 于是回放出来的 tool 消息找不到「声明过它的 assistant」，
                // 上游依然判非法 —— 只是错误位置从「缺 tool_call_id」
                // 变成「tool_call_id 无对应声明」。
                //
                // ## 采用的方案：把工具结果折叠成 assistant 文本
                //
                // 既然无法重建合法配对，就不要发送 `role = "tool"`。改为发一条
                // **assistant 消息**，正文里带工具名与结果：
                //
                // ```
                // 〔工具 list_dir〕file .keep 1
                // ```
                //
                // 这样做的好处：
                //  - 协议上完全合法（纯 assistant 文本，不触发任何配对校验）；
                //  - 模型仍能看到「之前调过哪些工具、拿到什么结果」，
                //    不会失忆 —— 它损失的是「结构化 tool_calls 语义」，
                //    而那个语义在历史回放里本就不是必需的（模型是在续写，
                //    不是在重放调用）；
                //  - 无需数据库迁移（不动表结构）。
                //
                // `summary` 同理映射成 assistant：它是一段历史压缩，
                // 以用户口吻喂回去会让模型以为「用户刚说了这一大段」。
                val replayText: String? = when (role) {
                    BubbleRole.Error -> null
                    BubbleRole.Tool -> {
                        val name = row.toolName?.takeIf { it.isNotBlank() } ?: "工具"
                        // 结果正文放在第二行，避免工具名很长时挤在一起。
                        "〔工具 $name〕\n" + row.content
                    }
                    else -> row.content
                }
                if (replayText != null) {
                    history += ChatMessage(
                        role = if (role == BubbleRole.User) ChatRole.User else ChatRole.Assistant,
                        text = replayText,
                    )
                }
                ChatBubble(
                    id = bubbleId,
                    role = role,
                    text = row.content,
                    // 带上库里的写入时间：跳转链路以时间戳为键（见
                    // `ChatBubble.timestamp` 的 KDoc）。
                    timestamp = row.createdAt,
                    variantGroupId = row.variantGroupId,
                    variantIndex = row.variantIndex,
                    // 组的版本总数在这一步还不知道（要等所有行都映射完）。
                    // 先置 1，下面的折叠步骤会回填真实值。
                    variantCount = 1,
                )
            }

            // ── 折叠变体组：同组只留 index 最大的那条 ──────────────
            //
            // ## 为什么必须在恢复阶段折叠，而不是 UI 渲染时过滤
            //
            // 库里同一变体组的 N 条版本是 N 行真实数据（各自需要 orderIndex
            // 才能被读到）。若不在恢复时折叠，重启后用户就会看到 N 条并排的
            // 助手回答 —— 正是「重新生成叠气泡」那个 bug 在重启后的形态。
            //
            // 放这里而不是渲染层：`bubbles` 同时也是「当前会话有哪些消息」
            // 的唯一真相，下游（定位器、跳转、滚动锚定）都读它。
            // 在渲染层过滤的话，那些下游仍会看到 N 条。
            //
            // ## 为什么留 index 最大而不是留第一条
            //
            // 与 `ChatBubble.variantIndex` 的约定一致：**index 最大的就是
            // 当前版本**（最后一次重新生成的结果）。这样重启后的显示
            // 与重启前一致，不需要再存一列 selected 状态。
            //
            // ## 为什么只传 bubbles、不传 rows
            //
            // `bubbles` 是 `mapNotNull` 的结果（空正文的 assistant 行被丢掉了），
            // 它与 `rows` 的下标**已经错位**。想按下标配对就会读错行。
            // 而每条气泡自己带着 `variantGroupId` / `variantIndex`，够用了。
            val folded = foldVariantGroups(bubbles)

            // ## 为什么是「前置」而不是「覆盖」（2026-10-22 修，第 9 项）
            //
            // 旧写法是 `_state.update { it.copy(bubbles = bubbles) }` —— 无条件整体替换。
            //
            // 这里有真实的竞态窗口：恢复走 `persistScope.launch`，读库是 IO，
            // 从点开会话到数据回来通常有几十毫秒。这段时间界面是**可交互**的
            // （`restoring` 只是一个布尔，没有挡住发送），用户完全可能已经
            // 发出一条消息。此时：
            //
            //   send()  → bubbles = [新用户气泡, 新助手占位]
            //   恢复回来 → bubbles = [历史…]          ← 新气泡被整表覆盖掉
            //
            // 用户看到的是「刚发的消息消失」。而库里其实有 —— 下一次进来
            // 又能看到。这种「时有时无」最容易被当成「记录丢了」。
            //
            // 正确做法：历史**前面接上**，保留恢复期间新产生的气泡。
            // 去重按文本 + 角色：恢复出来的最后一条很可能就是用户刚发的那条
            // （它已落库），不能重复显示。
            _state.update { current ->
                val existing = current.bubbles
                if (existing.isEmpty()) {
                    current.copy(bubbles = folded)
                } else {
                    // 找出「历史里没有、但当前列表里有」的那些尾部气泡。
                    // 用 `(role, text)` 判断而不是 id：恢复生成的是新 id，
                    // id 与数据库行 id 无对应关系，比不了。
                    val restoredKeys = folded.map { it.role to it.text }.toSet()
                    val fresh = existing.filter { (it.role to it.text) !in restoredKeys }
                    current.copy(bubbles = folded + fresh)
                }
            }
            // 只有走到这里才算「真的恢复过」。放在 try 的末尾而不是开头，
            // 是因为上面几个提前 return 的分支（读库失败 / 空库）都还没恢复成功。
            restored = true
            } finally {
                // 无论成功失败都要清：否则一次失败会把后续 attempt 全挡住。
                restoreInFlight = false
            }
        }
    }

    /**
     * 折叠变体组：同一组只保留 `variantIndex` 最大的那条，并回填 `variantCount`。
     *
     * ## 输入是「已映射好的气泡列表」而不是数据库行
     *
     * 恢复链路里 `bubbles` 已由 `mapNotNull` 产出（空正文的 assistant 行被丢弃），
     * 它与 `rows` 的下标**已经错位**。所以按下标配对行会读错数据。
     * 每条气泡自己带 `variantGroupId` / `variantIndex`，足够折叠。
     *
     * ## 为什么要保持原始顺序
     *
     * 变体组的成员在库里是**连续插入**的（同一次重试会追加到末尾），
     * 但一个会话里可能有多个组。折叠后必须让「组的第一条出现的那个位置」
     * 承载整组 —— 否则消息顺序会乱。
     *
     * 做法：按原顺序遍历，遇到组的第一个成员就记录它「代表整组」，
     * 后续同组成员跳过；代表位置最终使用组内 index 最大的那条内容。
     *
     * ## `variantCount` 为什么在这里回填
     *
     * 映射阶段每条气泡只有自己，不知道组里一共几个。折叠是**唯一**
     * 能看到全组成员的地方 —— 顺手把总数写回去，气泡渲染时
     * 不需要再去查（那会让 LazyColumn 滚动每帧做一次查询）。
     */
    private fun foldVariantGroups(bubbles: List<ChatBubble>): List<ChatBubble> {
        // 1. 统计每组的版本数，并找出 index 最大的那条（= 当前版本）。
        val counts = HashMap<String, Int>()
        val latestByGroup = HashMap<String, ChatBubble>()
        bubbles.forEach { b ->
            val g = b.variantGroupId ?: return@forEach
            counts[g] = (counts[g] ?: 0) + 1
            val cur = latestByGroup[g]
            val curIdx = cur?.variantIndex ?: -1
            val thisIdx = b.variantIndex ?: -1
            if (thisIdx > curIdx) latestByGroup[g] = b
        }
        if (counts.isEmpty()) return bubbles

        // 2. 按原顺序重建：每个组只在**首次出现的位置**输出一条，
        //    内容/时间戳取组内 index 最大的那条（保持「重启后看到最新版」）。
        val emitted = HashSet<String>()
        return bubbles.mapNotNull { b ->
            val g = b.variantGroupId ?: return@mapNotNull b
            if (!emitted.add(g)) return@mapNotNull null // 该组已输出过，跳过重复成员
            val latest = latestByGroup[g] ?: b
            // 用 latest 的内容，但**用首次出现位置**的 id —— id 是列表 key，
            // 取 latest 的 id 也可以，但保持「位置稳定」更利于 LazyColumn 复用。
            //
            // ## ⚠️ 必须同步搬迁 `assistantRowIds` 映射（2026-10-24）
            //
            // 折叠后保留的是**首次出现那条的 id**（即旧版本），而内容取自
            // latest（最新版）。但 `assistantRowIds` 里，这个 id 映射的是
            // **旧版本的数据库行**。
            //
            // 不改的话后果很具体：用户对这条被折叠的回答点「继续」或
            // 「重新生成」，`continueGeneration` / `regenerate` 会按
            // `assistantRowIds[bubble.id]` 去写那一行 —— 于是**旧版本被覆盖**，
            // 而最新版保持不变。用户看到的是「我让它继续，结果它改的是
            // 上一个版本的内容」。
            //
            // 这里把映射改成指向 latest 的行，让「这条气泡 ↔ 库里最新那行」
            // 始终一致。旧版本的行仍然完整留在库里（切换时还要读它）。
            val latestBubbleId = latest.id
            if (latestBubbleId != b.id) {
                val latestRow = assistantRowIds[latestBubbleId]
                if (latestRow != null) {
                    assistantRowIds[b.id] = latestRow
                }
            }
            b.copy(
                text = latest.text,
                timestamp = latest.timestamp,
                variantIndex = latest.variantIndex,
                variantCount = counts[g] ?: 1,
            )
        }
    }

    /**
     * 流式正文节流器：把「每个 delta 写一次库」压到约 1/10。
     *
     * 不节流的后果是真实的：3000 字回复会产生数百次 SQLite UPDATE，
     * 回复越长越卡。终态内容靠 [flushContent] 强制写入，不会丢尾。
     */
    private val contentWriter = ThrottledContentWriter { rowId, content ->
        launchPersist { updateMessageContent(rowId, content) }
    }

    /**
     * 兜底落盘（2026-10-22 修，第 9 项核心之一）。
     *
     * ## 之前完全没有 onCleared —— 这就是「退出后记录就没了」
     *
     * 整条落盘链路都跑在 [persistScope] 上，而它的 context 是
     * `viewModelScope.coroutineContext + Dispatchers.IO`。
     *
     * `viewModelScope` 的语义是：**ViewModel 被清除时取消所有子协程**。
     * 于是用户切会话 / 退出聊天页 / 进程被回收时：
     *
     * ```
     * ViewModel.onCleared()
     *   → viewModelScope 取消
     *   → persistScope 一并取消
     *   → contentWriter 里还在 100ms 节流窗口中的正文彻底丢失
     * ```
     *
     * 典型现象：AI 正在流式输出，用户按返回键 → 回来一看，
     * 这条回复只剩前几段，最后一段（往往是最重要的结论）没落盘。
     * 而屏幕上刚才明明显示过 —— 用户自然认为「记录丢了」。
     *
     * ## 为什么不能直接用 persistScope
     *
     * `onCleared()` 被调用时 scope 已经在取消中，`launch` 进去的协程
     * 会立刻收到 `CancellationException`，等于没写。
     * 所以这里用一个**独立**的一次性作用域，并用 `NonCancellable`
     * 保证「已经开始写的那一条」不被中途打断。
     *
     * 不泄漏：`SupervisorJob()` 的 Job 在两次写完成后没有任何引用，
     * 随协程结束被回收，不需要手动 cancel。
     */
    override fun onCleared() {
        super.onCleared()
        val repo = chatRepository ?: return
        // 取出所有还在节流窗口里的正文（可能不止一条：工具并发气泡）。
        val pendingWrites = contentWriter.drainPending()
        if (pendingWrites.isEmpty()) return

        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        ).launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                for ((rowId, content) in pendingWrites) {
                    runCatching { repo.updateMessageContent(rowId, content) }
                }
            }
        }
    }

    /** 流式期间提交正文（可能被节流丢弃，留待 flush）。 */
    private fun persistContent(bubbleId: Long, content: String) {
        val rowId = assistantRowIds[bubbleId] ?: return
        contentWriter.submit(rowId, content)
    }

    /** 终态强制写入：流结束 / 失败 / 取消时调用，保证不丢最后一段。 */
    private fun flushContent(bubbleId: Long, content: String) {
        val rowId = assistantRowIds[bubbleId] ?: return
        // 先把最新内容放进暂存区，再 flush —— 提交可能因节流窗口被丢弃。
        contentWriter.submit(rowId, content)
        contentWriter.flush(rowId)
        contentWriter.forget(rowId)
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text) }
    }

    /**
     * 添加一张待发图片。
     *
     * ## 为什么会超过一张
     *
     * 视觉模型普遍支持单条消息多图（对比、看前后两帧）。限制成一张会让
     * 「这两张图有什么区别」这种问题问不出来。上限取 [MAX_ATTACHMENTS]：
     * 再多会让请求体过大、且模型对图片数量也有隐式上限。
     *
     * ## 为什么超限时给提示而不是静默丢弃
     *
     * 用户点第五张图时什么都不发生，会以为选图功能坏了。
     * 用 `notice` 说明原因（复用输入栏已有的 4 秒提示机制，不新增 UI）。
     */
    fun addAttachment(image: dev.praxis.core.ai.ChatImage) {
        _state.update { current ->
            if (current.attachments.size >= MAX_ATTACHMENTS) {
                current.copy(notice = "最多附 $MAX_ATTACHMENTS 张图片")
            } else {
                current.copy(
                    attachments = current.attachments + image,
                    // 清掉上一次的「读不了这张图」提示：现在成功了，
                    // 旧提示挂着会让用户以为这次也失败。
                    notice = null,
                )
            }
        }
    }

    /** 移除第 [index] 张待发图片（送错图时用）。 */
    fun removeAttachment(index: Int) {
        _state.update { current ->
            if (index !in current.attachments.indices) {
                current
            } else {
                current.copy(
                    attachments = current.attachments.filterIndexed { i, _ -> i != index },
                )
            }
        }
    }

    /** 读图失败时提示用户（由 UI 在 `ChatImagePreparer` 返回 null 时调用）。 */
    fun notifyImageUnreadable() {
        _state.update { it.copy(notice = "这张图片读不了，换一张试试") }
    }

    /**
     * 清掉一次性提示（由输入栏的 4 秒计时器调用）。
     *
     * 单独一个方法而不是让 UI 直接 `copy(notice = null)`：UI 不该持有
     * 「怎么写状态」的知识；而且这里要保证「已经清过就早退」——
     * 计时器与 `send()` 可能同时清，早退能少一次无意义的重组。
     */
    fun clearNotice() {
        if (_state.value.notice == null) return
        _state.update { it.copy(notice = null) }
    }

    fun send() {
        val text = _state.value.input.trim()
        // ## 判据与 `canSend` 保持一致：有文字**或**有图片
        //
        // 只用 `text.isEmpty()` 早退会让「只发图不写字」发不出去 ——
        // 而 `canSend` 已经允许那种情况，两处判据必须同步，否则会出现
        // 「发送键亮着但按下去没反应」。
        val attachments = _state.value.attachments
        if ((text.isEmpty() && attachments.isEmpty()) || _state.value.isStreaming) return

        // ## 必须在 `_state.update` **之前**判定「是不是本会话第一条」
        //
        // 判定放在 update 之后会永远得到 false：`send()` 刚把用户气泡 push
        // 进去，此时 `count { User }` 已经是 1（首条）或 2（第二条）。
        // 用 `<= 1` 尚能命中首条，但「恢复出来的历史里已有用户消息」这种
        // 情况就分不清了。在 push 之前数，语义最直白：还没有用户气泡 = 首条。
        val isFirstUserMessage = _state.value.bubbles.none { it.role == BubbleRole.User }

        // 用户气泡 + 空的助手气泡（先建好，之后往里灌增量）
        val userBubble = ChatBubble(
            id = nextId++,
            role = BubbleRole.User,
            text = text,
            timestamp = nextTimestamp(),
            // 图片随气泡一起进状态：发送后用户仍能回看自己发了哪张
            // （见 `ChatBubble.images` 的 KDoc）。
            images = attachments,
        )
        val assistantId = nextId++
        val assistantBubble = ChatBubble(
            id = assistantId,
            role = BubbleRole.Assistant,
            text = "",
            streaming = true,
            timestamp = nextTimestamp(),
        )

        // ## 回复标签在**这里**注入（1:1 对齐上游 `buildUserMessageContent`）
        //
        // 上游把 `<reply_to …>` 拼进「发给模型的正文」，而 UI 气泡里显示的
        // 是用户敲的纯文本。两边因此不一致 —— 这不是 bug，是有意的：
        // 用户不该在自己的气泡里看到一坨 XML。
        //
        // 这里照此实现：`history`（= 送给模型的原文）带标签，`ChatBubble.text`
        // 只放纯文本。由此带来一个必须一起处理的连带问题：`removeBubble` 与
        // `saveEditingMessage` 都是「按角色 + 文本相等」在 history 里定位，
        // 带标签的 history 条目就不再等于纯文本气泡了。所以那两处改成
        // 先用 [stripReplyTag] 削掉前缀再比较。
        val replyTag = buildReplyTag()
        // 正文在前、标签在后（顺序见 `composeUserContent` 的 KDoc）。
        //
        // ## 图片挂在**同一条** user 消息上（2026-10-24，多模态）
        //
        // 协议要求文本与图片在同一个 `content` 数组里，所以不能拆成
        // 「先发一条图、再发一条字」。`OpenAiCompatProvider.messageToJson`
        // 见到 `images` 非空就会把 content 写成数组。
        history += ChatMessage(
            role = ChatRole.User,
            text = composeUserContent(text, replyTag),
            images = attachments,
        )
        if (replyTag.isNotEmpty()) {
            // 标签只对「这一条」生效：发出去之后引用关系就结束了，
            // 留着会让下一条也带着上一轮的回复标签。
            _state.update { it.copy(replyToBubbleId = null) }
        }

        _state.update {
            it.copy(
                bubbles = it.bubbles + userBubble + assistantBubble,
                input = "",
                // 图片已进 history，必须清空待发列表 —— 否则下一轮会
                // 把同一批图再发一次（重复计费，且模型会看到两张一样的图）。
                attachments = emptyList(),
                isStreaming = true,
                error = null,
                // 清了上一次的操作提示（比如「继续只能接在最后一条」）。
                // 不清的话旧提示会挂在新一轮对话上面 —— 用户会以为
                // 这次发送也出了问题。
                notice = null,
                // 从「已发出」开始计阶段：用户按下发送后到第一个 token
                // 之间可能有好几秒，这段时间不给反馈就是一个黑洞。
                stage = ChatStage.Connecting,
            )
        }

        // 用户消息先落盘，再做助手占位落盘，顺序与 UI 一致。
        launchPersist {
            // ## 标题：先写兜底标题，再异步让 AI 总结（对齐 Operit）
            //
            // 旧实现是 `ensureSession(sessionId, text.take(24))` ——
            // 直接把首句前 24 字当会话名。两个后果：
            //  - 侧边栏里多条会话名一模一样（都以「帮我看看这个」开头）；
            //  - 标题是半句话（24 字常常截在词中间）。
            //
            // Operit 的 `addVisibleUserMessageToCurrentChat` 是**两段式**：
            //   1. 第一条消息时先把兜底标题写进去（`new_conversation`），
            //      这样侧边栏立刻有名字，不用等模型；
            //   2. 异步 `generateConversationTitle` 成功后，**只在标题仍是兜底值
            //      时**才覆盖 —— 用户若在这期间手动改名，不能被 AI 盖掉。
            //
            // 这里 1:1 照此实现：第 1 步写入兜底标题，第 2 步用
            // `generateTitleIfPossible` 在「标题仍是兜底值」时才覆盖。
            val fallbackTitle = SessionTitle.fallback(text)
            ensureSession(sessionId, fallbackTitle)
            appendMessage(sessionId, role = "user", content = text)
            val assistantRowId = appendMessage(
                sessionId,
                role = "assistant",
                content = "",
            )
            assistantRowIds[assistantId] = assistantRowId
            // 只有「本会话第一条消息」才触发生成：标题描述的是会话主题，
            // 后面每轮都重新总结会让标题不停变（用户会觉得列表在乱跳）。
            if (isFirstUserMessage) {
                generateTitleIfPossible(fallbackTitle, text)
            }
        }

        startStream(assistantId)
    }

    /**
     * AI 总结标题（1:1 对齐 Operit `generateConversationTitle`）。
     *
     * ## 三条必须的防御
     *
     * 1. **失败静默**：标题是锦上添花，网络断 / 余额不足 / 模型没配都会失败。
     *    这里 catch 后什么都不做，用户已经有兜底标题可用。
     * 2. **只在标题仍是兜底值时才覆盖**：用户可能在生成期间手动改了名，
     *    或者另一个实例已经写了更好的标题。用「当前是否仍等于兜底值」判断，
     *    与上游 `currentTitle == titleToPreserve` 同一个思路。
     * 3. **空结果不覆盖**：模型偶尔返回空串（被安全过滤 / 只输出了思考块）。
     *    覆盖成空标题会让侧边栏出现一条没有名字的会话。
     */
    private suspend fun ChatPersistence.generateTitleIfPossible(
        fallbackTitle: String,
        userText: String,
    ) {
        val generator = titleGenerator ?: return
        val generated = try {
            generator.generate(userText)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 刻意不写进 state.error：标题失败不该在聊天页弹一条错误，
            // 那会让用户以为「这轮消息没发出去」。
            return
        }
        val cleaned = SessionTitle.sanitize(SessionTitle.stripThinking(generated))
        if (cleaned.isBlank()) return
        // 只有当前标题仍是兜底值时覆盖（防止盖掉用户手动改名）。
        val current = runCatching {
            listSessions().firstOrNull { it.id == sessionId }?.title
        }.getOrNull()
        if (current != null && current != fallbackTitle) return
        runCatching { updateSessionTitle(sessionId, cleaned) }
    }

    /**
     * 掐断当前这一轮（对齐 Operit 的取消动作）。
     *
     * ## 为什么必须有，且不能只 cancel 协程
     *
     * `startStream` 开头就 `streamingJob?.cancel()`，但那条路径是「开新流」，
     * 它不负责收尾旧流。如果用户点停止只调 `streamingJob.cancel()`：
     *  - `isStreaming` 永远停在 true → 发送键永久变成「停」，再也发不出去；
     *  - 已收到的半截内容不会被 flush → 重启后半截消失，与屏幕不一致。
     *
     * 所以这里三件事都做：取消协程、把半截内容落盘、把状态归位。
     *
     * ## 为什么不追加一条「已取消」气泡
     *
     * 取消是用户自己按的，他刚看到了结果。再追加一条系统消息等于解释了
     * 一件他刚亲手做过的事。真正的失败（网络断）才需要一条 `Error` 气泡 ——
     * 因为那件事**不是**用户做的。
     */
    fun cancel() {
        if (!_state.value.isBusy) return

        streamingJob?.cancel()
        streamingJob = null

        // 正在流式的那个气泡要把已经吐出来的字留下。
        //
        // ## 为什么文本要从 `streamingText` 取而不是 `bubble.text`（2026-10-24）
        //
        // 文本现在存在独立的流式槽里，`bubbles[i].text` 在流式期间**保持不变**
        // （这正是消除卡顿的关键，见 `applyStreamText`）。所以这里读
        // `bubble.text` 会拿到**开始输出之前**的值（通常是空串或占位符），
        // 于是「点停止后已吐出的内容全丢」。
        //
        // 正确顺序：先从槽里取最新文本 → seal 定稿进 bubbles 并清槽 →
        // 用那份文本落盘。
        val streamingBubble = _state.value.bubbles.lastOrNull { it.streaming }
        if (streamingBubble != null) {
            val latest = _state.value.streamingText[streamingBubble.id] ?: streamingBubble.text
            sealStreamText(streamingBubble.id, latest)
            flushContent(streamingBubble.id, latest)
        }

        _state.update {
            it.copy(
                isStreaming = false,
                stage = ChatStage.Idle,
                // 取消不是错误：上一轮的 error 不该在这里被清掉（它仍然是事实），
                // 但也不该新增一条。
                //
                // 但**必须吃掉下一次自动出队**：用户点「停止」的意图是
                // 「这一整套先停下」，而不是「立刻发队列里的下一条」。
                // 不抑制的话，「阻塞→解除」的跳变沿会马上把队首发出去 ——
                // 他按停止，界面却立刻开始新的请求，看起来像停止失效。
                suppressNextAutoDequeue = it.queue.isNotEmpty,
            )
        }
    }

    /** 重试：把最后一条用户消息重发（不重复添加用户气泡）。 */
    /**
     * 重试上一轮失败的回答。
     *
     * 语义：**不重发用户消息**（它已经在 history 与库里），只重新生成助手回复。
     * 失败气泡留在原处不动 —— 它是事实记录，重试成功也不该抹掉。
     */
    fun retry() {
        if (_state.value.isStreaming) return
        val assistantId = nextId++

        // ## 变体：找到「要重试的那条回答」并决定组 id（2026-10-24）
        //
        // 目标 = 最后一条 assistant 气泡。它要么已经属于某个组（重试过），
        // 要么是普通气泡（首次重试，需要吸收成第 0 版）。
        //
        // 失败气泡（`BubbleRole.Error`）不算目标：它是事实记录，
        // 重试是在它**之前**那条回答上重来。
        val target = _state.value.bubbles.lastOrNull { it.role == BubbleRole.Assistant }
        val existingGroupId = target?.variantGroupId
        val groupId = existingGroupId ?: "vg-${System.nanoTime()}"
        // 新版本的 index = 组内现有最大 + 1。
        //
        // 首次重试时组是空的（原回答还没入组），所以新版本是 1，
        // 而原回答会被吸收成 0。这样「index 最大 = 当前版本」的约定成立：
        // 重试后最新结果自动成为显示的那条。
        val baseIndex = target?.variantIndex ?: 0
        val newIndex = if (existingGroupId == null) 1 else baseIndex + 1
        val newCount = (target?.variantCount ?: 1) + if (existingGroupId == null) 0 else 1

        _state.update { st ->
            val bubbles = st.bubbles
            // 移除目标那条：新版本要「顶替」它的位置与它并存为变体，
            // 内存里只保留一条气泡（否则又变成并排两条）。
            val withoutTarget = if (target != null) bubbles.filterNot { it.id == target.id } else bubbles
            st.copy(
                bubbles = withoutTarget + ChatBubble(
                    id = assistantId,
                    role = BubbleRole.Assistant,
                    text = "",
                    streaming = true,
                    timestamp = nextTimestamp(),
                    variantGroupId = groupId,
                    variantIndex = newIndex,
                    // 首次重试时组里最终会有 2 条（原回答 0 + 新的 1）；
                    // 之后每重试一次 +1。这里直接给最终值，省掉一次重组。
                    variantCount = if (existingGroupId == null) 2 else newCount,
                ),
                isStreaming = true,
                error = null,
                stage = ChatStage.Connecting,
            )
        }

        // 重试也要落盘：否则重启后这一轮回复凭空消失，与 UI 不一致。
        //
        // 变体路径下用 `appendVariant`（一次写入就带变体列），
        // 并在首次重试时把原回答 `attachToVariantGroup` 吸收成第 0 版。
        launchPersist {
            if (target != null && existingGroupId == null) {
                // 原回答的数据库行 id：`assistantRowIds` 里存的就是它。
                val targetRowId = assistantRowIds[target.id]
                if (targetRowId != null) {
                    attachToVariantGroup(targetRowId, groupId, 0)
                }
            }
            val rowId = appendVariant(sessionId, groupId, newIndex, "")
            assistantRowIds[assistantId] = rowId
        }
        startStream(assistantId)
    }

    /**
     * 切换一条消息的变体版本（`delta` = -1 上一个 / +1 下一个）。
     *
     * ## 为什么必须回库取正文
     *
     * 内存里同一组只保留**一条**气泡（见 `foldVariantGroups` 与 `retry`），
     * 它带的是当前版本的正文。要看别的版本就得去库里读那一条 ——
     * 这是一个必然的 IO，切换是低频操作（用户偶尔回看），可以接受。
     *
     * ## 不写回数据库
     *
     * 「当前显示哪一版」不做持久化（见 `ChatBubble.variantIndex` 的 KDoc）：
     * 重启后回到 index 最大的那版。所以这里只改内存。
     *
     * ## 流式中禁止切换
     *
     * 切换会把 `text` 换成另一版，而流式写入还在往当前 id 上追加 ——
     * 结果是「正文一半是旧版、一半是新版」。直接拒绝。
     */
    fun switchVariant(bubbleId: Long, delta: Int) {
        if (_state.value.isStreaming) return
        val bubble = _state.value.bubbles.firstOrNull { it.id == bubbleId } ?: return
        val groupId = bubble.variantGroupId ?: return
        val current = bubble.variantIndex ?: 0
        val targetIndex = current + delta
        // 边界处直接返回：气泡上的箭头已经置灰，能走到这里是防误调。
        if (targetIndex < 0 || targetIndex >= bubble.variantCount) return

        launchPersist {
            // 从库里取目标版本的正文。
            //
            // 这里用 `loadVariants` 拿整组再挑 —— 而不是「查 index 等于 N 的那一条」：
            // 组只有几条，一次查询比加一个 DAO 方法更省事，也不会因为
            // variantIndex 在库里重复（理论上不该，但没有唯一约束）而取错。
            val variants = loadVariants(sessionId, groupId)
            val target = variants.firstOrNull { it.variantIndex == targetIndex } ?: return@launchPersist
            _state.update { st ->
                st.copy(
                    bubbles = st.bubbles.map { b ->
                        if (b.id != bubbleId) {
                            b
                        } else {
                            b.copy(
                                text = target.content,
                                // 时间戳也要换：跳转定位以时间戳为键，
                                // 停在新版本上却指向旧版本的时间会让
                                // 「跳转到这条」落到别处。
                                timestamp = target.createdAt,
                                variantIndex = targetIndex,
                            )
                        }
                    },
                )
            }
        }
    }

    /**
     * 从 [bubbleId] 之后继续生成（长按菜单的「继续」）。
     *
     * ## 与 [retry] / `Regenerate` 的区别
     *
     * 三者都「再写一段」，但**保留什么**完全不同：
     *
     * | | 保留的历史 | 新增气泡 | 模型看到的上下文 |
     * |---|---|---|---|
     * | [retry] | 全部 | 一个空助手气泡 | 该有都有（可能最后是失败气泡） |
     * | `Regenerate` | 截断到目标之前 | 一个空助手气泡 | 没有目标那条回答 |
     * | 本方法 | 全部 | 一个空助手气泡 | **含目标那条半截回答** |
     *
     * ## 为什么要求目标必须是「最后一条」
     *
     * 「继续」的语义是「接着这句话往下写」。若用户对中间的某条点继续，
     * 而它后面还有别的对话，模型会基于**整段历史**（含后面那些）续写 ——
     * 于是新内容接在「最后一条」后面，而不是接在目标那条后面。
     * 用户看到的是「我点了第三条的继续，结果它回答了第五个问题」。
     *
     * 所以非末尾的目标直接拒绝并给出原因（[summary]，输入栏上方可见），
     * 而不是悄悄做错。想重写中间那条，该用「重新生成」——那会先截断。
     *
     * ## 为什么「继续」在工具气泡上也有意义
     *
     * 工具卡片是最容易「断在半路」的地方：模型发起调用、结果回来了，
     * 却没写总结就中断了。此时工具结果已在 `history`（`runToolCalls` 里
     * `history += ChatMessage(Tool, ...)`），模型完全有余料继续 ——
     * 且不需要重跑工具（重跑可能产生副作用，比如再写一次文件）。
     *
     * ## 关于「退出软件后还能继续」
     *
     * 这件事**不需要额外持久化**：重启进会话时 `restoreIfNeeded` 会从库里
     * 把消息读回来并重建 [history]（含工具结果），于是「最后一条是助手回答」
     * 这个前提照样成立，菜单里的「继续」照样可点。
     *
     * 唯一丢失的是「上一轮属于继续」这个标记 —— 那是**不需要**记住的
     * 中间态：用户只关心能不能接着写，不关心上次是怎么触发的。
     */
    fun continueGeneration(bubbleId: Long) {
        if (_state.value.isBusy) return
        val bubbles = _state.value.bubbles

        // 目标必须存在。`truncateFrom` / 删除之后菜单可能还指着已消失的条目。
        val target = bubbles.firstOrNull { it.id == bubbleId } ?: return

        // 只有「模型说过的话」或「工具结果」可以续写：
        //  - 用户消息之后的下一条本来就是 AI 的回答，对它点「继续」
        //    等价于「让 AI 再答一次」，那是 Regenerate 的语义；
        //  - 错误气泡不是模型产出，续写没有对象。
        if (target.role != BubbleRole.Assistant && target.role != BubbleRole.Tool) {
            _state.update { it.copy(notice = "只能对 AI 回复或工具结果点「继续」。") }
            return
        }

        // 位置校验：必须是最后一条（理由见 KDoc）。
        if (bubbles.lastOrNull()?.id != bubbleId) {
            _state.update {
                it.copy(notice = "「继续」只能接在最后一条上；想重写中间那条请用「重新生成」。")
            }
            return
        }

        // 已经有一条在流式（或刚建好还没吐字）时不重复发起：
        // 两个 proot 无关，但这里会并发出两条助手气泡，模型会各答一半。
        if (_state.value.isStreaming) return

        // 记下瞬时指令，`startStream` 会取用并清空。
        pendingContinueHint = CONTINUE_HINT

        val assistantId = nextId++
        _state.update {
            it.copy(
                bubbles = it.bubbles + ChatBubble(
                    id = assistantId,
                    role = BubbleRole.Assistant,
                    text = "",
                    streaming = true,
                    timestamp = nextTimestamp(),
                ),
                isStreaming = true,
                error = null,
                notice = null,
                stage = ChatStage.Connecting,
            )
        }
        // 与 [retry] 一样要落盘：否则重启后这段续写凭空消失，
        // 而 UI 上刚才明明显示过 —— 用户会认为「继续的成果丢了」。
        launchPersist {
            val rowId = appendMessage(sessionId, role = "assistant", content = "")
            assistantRowIds[assistantId] = rowId
        }
        startStream(assistantId)
    }

    /**
     * 把本轮回答沉淀为长期记忆——**带筛选**。
     *
     * ## 为什么不能每轮都记
     *
     * 模型每轮都会输出几百字。如果全部写入：
     *  - 记忆库一周内漲到几千条，RRF 全是噪声；
     *  - 「用户喜欢喝咖啡」和「这段代码的第 3 行有个拼写错误」被同等对待，
     *    而后者明天就过时了。
     *
     * 筛选只能做最粗略的启发式（长度 + 是否含具体事实词），因为一次语义判断
     * 要一次模型调用，写入就变成慢操作。宁可少记，也不要记一堆垃圾。
     *
     * ## 不记哪些
     *
     * - 太短（< 12 字）：通常是「好的」「已处理」这类过程话；
     * - 太长（> 300 字）：不是一条事实，而是整段教程，应让用户自己决定要不要存；
     * - 含省略号/「我无法」等拒绝句：这些是模型的边界说明，不是被事实。
     */
    private fun rememberIfWorth(assistantText: String) {
        val port = memoryPort ?: return
        val text = assistantText.trim()
        if (text.length < 12 || text.length > 300) return

        // 拒绝/免责类回答不做记忆：明天再问它还是会拒，存下来只是噪声。
        val noise = listOf("无法", "不能", "抱歉", "I can't", "I cannot", "sorry")
        if (noise.any { it in text }) return

        persistScope.launch {
            runCatching { port.remember(text) }
        }
    }

    /**
     * 把已启用技能拼成一条 system 消息。
     *
     * ## 为什么单独一条而不是拼进记忆那条
     *
     * 记忆是「关于用户的事实」，技能是「该怎么做事的指令」。
     * 混在一条里，模型难以区分「这是背景」与「这是要求」，
     * 而混错方向的代价不同：把事实当指令会执行错动作。
     *
     * ## 为什么放最前
     *
     * 与记忆同理：插在最后等于告诉模型「这是最新指令」，
     * 会压掉用户当前这句的意图。
     */
    /*
     * 旧的 withSkills / withMemory 已删除（2026-10-22）。
     *
     * 它们各自「往最前面插一条 system」，但两者都插在开头时顺序取决于调用顺序
     * （`withMemory(withSkills(...))` → 记忆在前、技能在后），
     * 与「用户提示词最高」的优先级要求冲突，且无法表达「用户 > 内置 > 技能 > 记忆」。
     *
     * 现在统一由 `SystemPromptBuilder.buildLayers` 按固定顺序产出，
     * 见 `buildPreparedMessages`。
     */

    /**
     * 读取本轮运行配置。
     *
     * 所有 provider 都是 suspend，且都允许为 null（单测里不注入）。
     * 用 runCatching 包住：配置读失败不该阻断发送 —— 用户要的是回答，
     * 不是「因为设置读不出来所以发不出去」。
     */
    private suspend fun readRuntimeConfig(): RuntimeConfig {
        val cfg = runCatching { contextConfigProvider?.invoke() }.getOrNull()
        return RuntimeConfig(
            systemPrompt = runCatching { systemPromptProvider?.invoke() }.getOrNull().orEmpty(),
            contextLimit = cfg?.contextLimit ?: 0,
            compactThreshold = cfg?.compactThreshold ?: ContextCompactor.DEFAULT_THRESHOLD,
            thinkingEffort = cfg?.thinkingEffort ?: ThinkingEffort.None,
            summaryModel = runCatching { summaryModelProvider?.invoke() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: model,
            // 缺省 true（流式）：配置读不出来时保持默认体验，
            // 而不是静默把用户切成非流式。
            stream = cfg?.stream ?: true,
        )
    }

    /**
     * 组装这一轮真正要发给上游的消息。
     *
     * 三步，顺序有语义（每步都依赖上一步的结果）：
     *  1. 分层 system（用户最高 → 内置 → 技能 → 记忆）：优先级顺序集中在一处决定；
     *  2. 应用已生效的摘要：插在 system 之后、对话之前，并丢掉被折叠的消息；
     *  3. 字符预算兜底裁剪：极端长文本时才生效（机械丢弃，不是语义压缩）。
     */
    private suspend fun buildPreparedMessages(config: RuntimeConfig): List<ChatMessage> {
        val memoryBlock = recallMemoryBlock()

        val skills = runCatching { skillsPromptProvider?.invoke() }.getOrNull()
        val layered = SystemPromptBuilder.buildLayers(
            settings = SystemPromptSettings(custom = config.systemPrompt),
            skills = skills,
            memory = memoryBlock,
            // 摘要由 CompactionApplier 单独插入（它还要负责丢掉被折叠的消息），
            // 这里再传一次会出现两份摘要。
            summary = null,
        )

        val full = layered + history.toList()
        val withSummary = CompactionApplier.apply(full, compacted)
        return ContextAssembler.assemble(withSummary)
    }

    private suspend fun recallMemoryBlock(): String? {
        val port = memoryPort ?: return null
        val query = history.lastOrNull { it.role == ChatRole.User }?.text ?: return null
        if (query.isBlank()) return null
        val recalled = runCatching { port.recall(query) }.getOrDefault(emptyList())
        if (recalled.isEmpty()) return null
        return recalled.joinToString("\n") { "- $it" }
    }

    /**
     * 把用量写进状态（供输入栏上方的进度条渲染）。
     *
     * ## 用 [TokenCache] 而不是每次全量估算
     *
     * 这个方法在流式期间每帧都可能被调（`buildPreparedMessages` 之后），
     * 而历史越长越贵。缓存只算公共前缀之后的新增部分 ——
     * 与上游 `TokenCacheManager.calculateInputTokens` 同一策略。
     */
    private fun publishUsage(messages: List<ChatMessage>, config: RuntimeConfig) {
        val used = tokenCache.count(messages)
        _state.update {
            it.copy(
                contextUsage = it.contextUsage.copy(
                    used = used,
                    limit = config.contextLimit,
                    threshold = config.compactThreshold,
                ),
                thinkingEffort = config.thinkingEffort,
            )
        }
    }

    /**
     * 达到阈值时压缩。
     *
     * ## 用户的两个硬要求
     *
     * > 「当达到阈值先等 ai 执行完工具然后再压缩」
     *
     * 所以有两道闸：
     *  1. **不在工具执行中压缩**：`stage == ExecutingTool` 时直接返回。
     *     在工具跑到一半插一次摘要调用，会把「模型正在等工具结果」的等待拉长一倍，
     *     而且摘要内容里会缺掉工具结果（往往是最重要的部分）；
     *  2. **压缩失败不影响发送**：摘要是一次额外模型调用，网络抖动、余额不足、
     *     模型不支持都可能失败。失败就当没压过，下一轮再试，这一轮照常发出。
     *
     * ## 为什么是串行 suspend
     *
     * 必须在「这一轮请求之前」完成，否则进度条刚显示已达阈值就又发一条超限请求。
     * 串行的代价是多等一次摘要时间，但比发出一个必然失败的请求好。
     */
    private suspend fun maybeCompact(config: RuntimeConfig) {
        if (_state.value.stage == ChatStage.ExecutingTool) return

        val current = _state.value.contextUsage

        // ── 判定：双阈值（1:1 对齐上游 `shouldGenerateSummary`）──────────
        //
        // 旧实现只有 `contextLimit <= 0 → return` + 只看 token 比例，
        // 于是「上限填 0（未知）」时**永不压缩**。上游还有第二个条件：
        // 「自上次摘要后的用户消息数 ≥ 条数阈值」。
        //
        // 条数阈值在这里格外重要：它让「上限未知」的用户也能压缩，
        // 也让「体积不大但轮次很多」的短消息对话能刷新摘要。
        val userMessagesSinceSummary = history
            .drop(compacted.coveredCount.coerceIn(0, history.size))
            .count { it.role == ChatRole.User }

        val should = ContextCompactor.shouldCompact(
            currentTokens = current.used,
            maxTokens = config.contextLimit,
            tokenUsageThreshold = config.compactThreshold,
            userMessagesSinceLastSummary = userMessagesSinceSummary,
            messageCountThreshold = compactionSettings.messageCountThreshold,
        )
        if (!should) return

        val total = history.size
        // ## 折叠范围的起点
        //
        // 旧实现是 `history.subList(start, end)` 且 `start = coveredCount`。
        // 那是对的，但有一个细节：条数阈值触发时，`compactCount` 可能
        // 算出「比 coveredCount 还小」的值（例如刚刚压缩过、历史只多了 2 条）。
        // 此时 `foldCount <= 0` 直接返回 —— 正是想要的（不值得再压一次）。
        val foldCount = ContextCompactor.compactCount(total) - compacted.coveredCount
        if (foldCount <= 0) return

        val start = compacted.coveredCount
        val end = (start + foldCount).coerceAtMost(total)
        if (end <= start) return
        val slice = history.subList(start, end).toList()
        if (slice.isEmpty()) return

        // 切到 Summarizing：用户要知道这几十秒在干什么。
        _state.update { it.copy(stage = ChatStage.Summarizing) }
        val summary = runCatching {
            collectSummary(
                ContextCompactor.buildRequest(
                    previousSummary = compacted.summary.takeIf { compacted.isActive },
                    messages = slice,
                    model = config.summaryModel,
                    // 强制语言优先；未指定时由 buildRequest 按内容自动判断。
                    useEnglish = compactionSettings.useEnglish
                        ?: ContextCompactor.detectUseEnglish(slice),
                    globalRules = compactionSettings.globalRules,
                ),
            )
        }.getOrNull()

        if (summary.isNullOrBlank()) {
            _state.update { it.copy(stage = ChatStage.Connecting) }
            return
        }
        compacted = CompactedContext(
            summary = summary,
            coveredCount = end,
            createdAt = System.currentTimeMillis(),
        )
        // ## 压缩后必须清缓存
        //
        // 历史的头部被换成了摘要，`TokenCache` 里记的公共前缀已经不成立了。
        // 不清的话下一次 `count` 会拿旧前缀当「已算过」，
        // 得到一个既不是旧值也不是新值的数字 —— 进度条会显示一个假用量。
        tokenCache.reset()
        // 压缩后重算用量（应显著下降），并把摘要本身推给 UI（分割线 + 可点开）。
        _state.update { it.copy(compacted = compacted) }
        publishUsage(buildPreparedMessages(config), config)
        _state.update { it.copy(stage = ChatStage.Connecting) }
    }

    /** 消费一次摘要流，拼成完整文本。 */
    private suspend fun collectSummary(request: ChatRequest): String {
        val sb = StringBuilder()
        provider.stream(request).collect { event ->
            when (event) {
                is AiStreamEvent.Delta -> sb.append(event.text)
                // 摘要请求不该发起工具调用；真发起了也忽略（没有工具可跑）。
                is AiStreamEvent.ToolCalls -> Unit
                is AiStreamEvent.Done -> Unit
            }
        }
        return sb.toString().trim()
    }

    private fun startStream(assistantId: Long) {
        streamingJob?.cancel()
        // ## 在这里就把「继续」标记取走（不能等到发请求前）
        //
        // 取用点若放在 `readRuntimeConfig` / `maybeCompact` 之后，这几步
        // 抛异常或协程被取消时标记会**残留**：下一次用户普通发送时模型
        // 会莫名其妙地「接着上文继续写」，而不是回答新问题。
        //
        // 放在启动瞬间取用后，无论后面怎么失败都不会泄漏 —— 标记本来
        // 就只服务这一次调用。
        val continueHint = pendingContinueHint
        pendingContinueHint = null

        streamingJob = viewModelScope.launch {
            val buffer = StringBuilder()
            try {
                // ① 现取运行配置（用户可能刚在设置页改过）。
                val config = readRuntimeConfig()

                // ② 用量统计：在**发出前**算，用户看到的就是实际要发出去的量。
                publishUsage(buildPreparedMessages(config), config)

                // ③ 达到阈值先压缩（工具执行中会自行跳过）。
                maybeCompact(config)

                val finalMessages = buildPreparedMessages(config)

                // 追加「继续」的瞬时指令（不落库、不进 history）。
                //
                // ## 为什么追加在末尾而不是插在第一条 user 前
                //
                // 模型对**最后一条**消息的注意力最强（这也是 `AiContextBudget.fit`
                // 把 `lastUser` 钉住不丢的理由）。「继续」是这一刻的指令，
                // 放在末尾才能压过前文的惯性。
                //
                // 标记已在 `startStream` 入口取出（见那里的说明），这里只用。
                val requestMessages = if (continueHint != null) {
                    finalMessages + ChatMessage(role = ChatRole.User, text = continueHint)
                } else {
                    finalMessages
                }

                provider
                    .stream(
                        ChatRequest(
                            model = model,
                            messages = requestMessages,
                            tools = buildToolDescriptors(),
                            // 思考强度透传，provider 层负责翻成各家参数。
                            thinkingEffort = config.thinkingEffort,
                            // 少一个流式开关会让 provider 永远用 SSE ——
                            // 用户关掉「流式输出」后本应看到整段一起出现。
                            stream = config.stream,
                        ),
                    )
                    .collect { event ->
                        when (event) {
                            is AiStreamEvent.Delta -> {
                                // 收到第一个增量就说明连接已建、模型在产出。
                                // 只在首次切换，避免每个 delta 都发一次状态更新
                                // （流式时一秒几十帧，多余的状态写入会拖动整页重组）。
                                if (_state.value.stage != ChatStage.Receiving) {
                                    _state.update { it.copy(stage = ChatStage.Receiving) }
                                }
                                buffer.append(event.text)
                                // ## 只写流式槽，不碰 `bubbles`（2026-10-24）
                                //
                                // 旧写法 `updateBubble(assistantId) { it.copy(text = ...) }`
                                // 会让 `bubbles` 的引用每个 delta 都变，连带让
                                // 定位器预览等下游把**全部历史**重算一遍 ——
                                // 那就是「越长越卡」的机制（见 `updateBubble` 的 KDoc）。
                                //
                                // 现在文本走独立的 map：`bubbles` 引用保持不动，
                                // 只有渲染这条气泡的地方读 map 并重组。
                                applyStreamText(assistantId, buffer.toString())
                                persistContent(assistantId, buffer.toString())
                            }

                            is AiStreamEvent.ToolCalls -> {
                                _state.update { it.copy(stage = ChatStage.ExecutingTool) }
                                // 工具调用前的正文要定稿：这一段的文本已经完整
                                // （模型切到工具模式前会结束这句话），之后再来的
                                // delta 属于**下一段**输出，不能接在这段后面。
                                sealStreamText(assistantId, buffer.toString())
                                // 工具调用必须先写进 history 再执行 —— 模型的下一轮
                                // 需要看到「我发起了什么」，否则它会重复发起同一个调用。
                                history += ChatMessage(
                                    role = ChatRole.Assistant,
                                    text = buffer.toString(),
                                    toolCalls = event.calls,
                                )
                                runToolCalls(event.calls, assistantId)
                            }

                            is AiStreamEvent.Done -> {
                                // 定稿并清槽：Done 之后不再有 delta，UI 该以
                                // `bubbles[i].text` 为准（这也是落库的值）。
                                // 用 `buffer` 而不是 `null`：最后一段 delta 之后
                                // 可能还有收尾内容没进过槽。
                                sealStreamText(assistantId, buffer.toString())
                                // 收尾强制写入：流可能没有触发最后一段 Delta，节流也可能暂存着。
                                flushContent(assistantId, buffer.toString())
                                history += ChatMessage(
                                    role = ChatRole.Assistant,
                                    text = buffer.toString(),
                                )
                                rememberIfWorth(buffer.toString())
                            }
                        }
                    }
                _state.update { it.copy(isStreaming = false, stage = ChatStage.Idle) }
                // 这一轮成功了：在途记录作废，否则下一轮失败会把它
                // 误当成「本轮失败的那条」回滚进队列（重复消息）。
                inFlightQueueMessage = null
                // 一轮正常结束后，若队列里还有待发消息就自动推进。
                // 放在「成功」分支而不是 finally：失败时用户手边有错误要读，
                // 自动把下一条发出去会让他看不到错误。
                drainQueueIfIdle()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // ★ 必须排在 `catch (e: Exception)` 之前。
                //
                // `Job.cancel()` 抛的是 CancellationException，它**是** Exception 的子类。
                // 排在后面就会被下面的分支接住，于是用户点一次「停止」会得到一条
                // 「出现未知错误：JobCancellationException」的错误气泡 ——
                // 明明是他自己按的停止，却被告知出错了。
                //
                // 这里只做收尾，不产生错误气泡（收尾动作 cancel() 自己已经做了，
                // 这条分支还可能由「新流挤掉旧流」触发，那时更不该报错）。
                throw e
            } catch (e: AiException) {
                onStreamFailure(assistantId, buffer.toString(), e.error)
            } catch (e: Exception) {
                onStreamFailure(
                    assistantId,
                    buffer.toString(),
                    AiError(
                        kind = AiErrorKind.UNKNOWN,
                        message = e.message ?: e::class.java.simpleName,
                        cause = e,
                    ),
                )
            }
        }
    }

    private fun onStreamFailure(assistantId: Long, partial: String, error: AiError) {
        val errorBubble = ChatBubble(
            id = nextId++,
            role = BubbleRole.Error,
            text = error.displayText(),
            timestamp = nextTimestamp(),
        )
        // 保留已收到的部分文本（UI）
        // ## 为什么用 seal 而不是 updateBubble
        //
        // 失败时 `streamingText` 里还留着最后一段。只 `updateBubble { copy(text=..) }`
        // 会**不清槽**，于是 UI 继续读 map（旧文本），而 `bubbles` 里的新文本
        // 被覆盖显示 —— 两个来源打架，表现是「失败后显示的文本和实际存的不一致」。
        // seal 一次把「写定稿 + 清槽」做掉。
        sealStreamText(assistantId, partial)
        // 同时落盘：失败时用户看到的那半截，重启后也应该还在。
        flushContent(assistantId, partial)
        // 从队列取出的那条要放回队首：它既不在输入框（send 清空了）
        // 也不在队列（drain 弹出了），不还回去就等于用户排的队凭空少一条。
        val restore = inFlightQueueMessage
        inFlightQueueMessage = null
        _state.update {
            it.copy(
                bubbles = it.bubbles + errorBubble,
                isStreaming = false,
                error = error,
                stage = ChatStage.Idle,
                queue = if (restore != null) {
                    PendingQueueOps.restore(it.queue, restore)
                } else {
                    it.queue
                },
                // 失败后不自动续发：用户手边有错误要读，自动把下一条
                // 发出去会让他看不到这条错误。
                suppressNextAutoDequeue = true,
            )
        }
    }

    /**
     * 执行一批工具调用，并把结果写回 history 与 UI，然后**自动续一轮**。
     *
     * 为什么必须自动续：模型给 tool_calls 时这一轮 assistant 消息是「空的」，
     * 真正对用户有用的回答在工具执行完之后。不续轮用户就只能看到一张工具卡片。
     */
    private suspend fun runToolCalls(
        calls: List<dev.praxis.core.ai.ToolCall>,
        assistantId: Long,
    ) {
        val executor = toolExecutor
        val context = toolContext
        if (executor == null || context == null) {
            // 未装配工具层：如实告知，不假装执行过。
            updateBubble(assistantId) {
                it.copy(text = it.text + "\n\n（工具执行层未装配，无法执行：${calls.joinToString(", ") { c -> c.name }}）")
            }
            _state.update { it.copy(isStreaming = false, stage = ChatStage.Idle) }
            return
        }

        val invocations = calls.map {
            dev.praxis.core.tool.ToolInvocation(
                callId = it.id,
                name = it.name,
                argumentsJson = it.argumentsJson,
            )
        }

        val executions = executor.executeAll(
            invocations = invocations,
            context = context,
            askHandler = null, // 弹框征询在本轮不做，ASK 会降级为拒绝。
        )

        // 结果按调用顺序逐条落库 + 渲染
        executions.forEach { exec ->
            history += ChatMessage(
                role = ChatRole.Tool,
                toolResult = ToolResult(
                    callId = exec.invocation.callId,
                    content = exec.result.content,
                    isError = exec.result.isError,
                    // 带上工具名：发给上游时 `role: "tool"` 消息需要 name，
                    // 不少兼容网关会校验它与 assistant 的 tool_calls[].function.name 一致。
                    name = exec.invocation.name,
                ),
            )
            _state.update { current ->
                current.copy(
                    bubbles = current.bubbles + ChatBubble(
                        id = nextId++,
                        role = BubbleRole.Tool,
                        text = buildToolSummary(exec),
                        timestamp = nextTimestamp(),
                        // 带上结构化信息：ToolCallCard 需要参数/结果/耗时才画得出
                        // 可折叠卡片。只给 text 的话 UI 只能当纯文本渲染，
                        // 用户看不到「模型调了什么参数」。
                        tool = ToolCallInfo(
                            name = exec.invocation.name,
                            argumentsJson = exec.invocation.argumentsJson,
                            result = exec.result.content,
                            isError = exec.result.isError,
                            elapsedMs = exec.elapsedMs,
                        ),
                    ),
                )
            }
            // 工具执行结果同样要落盘，否则重启后模型看不到自己做过什么。
            launchPersist {
                appendMessage(
                    sessionId = sessionId,
                    role = "tool",
                    content = buildToolSummary(exec),
                    toolName = exec.invocation.name,
                    toolArgs = exec.invocation.argumentsJson,
                    toolCallId = exec.invocation.callId,
                    isError = exec.result.isError,
                )
            }
        }

        // 续一轮：让模型基于工具结果给出最终回答。
        val followUpId = nextId++
        _state.update {
            it.copy(
                bubbles = it.bubbles + ChatBubble(
                    id = followUpId,
                    role = BubbleRole.Assistant,
                    text = "",
                    streaming = true,
                    timestamp = nextTimestamp(),
                ),
                isStreaming = true,
            )
        }
        startStream(followUpId)
    }

    /** 只把当前档位能执行的工具发给模型（发不能用的工具会让它反复白试）。 */
    private fun buildToolDescriptors(): List<dev.praxis.core.ai.ToolDescriptor> {
        val registry = toolRegistry ?: return emptyList()

        // 有快照就用快照（含用户禁用列表）；没有则退回只看档位。
        val snapshot = permissionSnapshotProvider?.invoke()
        val specs = if (snapshot != null) {
            registry.descriptorsFor(snapshot)
        } else {
            val context = toolContext ?: return emptyList()
            registry.descriptorsFor(context.tier)
        }

        return specs.map { spec ->
            dev.praxis.core.ai.ToolDescriptor(
                name = spec.name,
                description = spec.description,
                parametersJson = spec.parametersJson,
            )
        }
    }

    private fun buildToolSummary(exec: dev.praxis.core.tool.ToolExecution): String {
        val status = if (exec.result.isError) "失败" else "完成"
        val body = exec.result.content.take(600)
        return "[工具 ${exec.invocation.name} $status]\n$body"
    }

    // ── 待发队列（Operit 同款，且可调顺序） ──────────────────────

    /**
     * 发送或入队。
     *
     * ## 语义
     *
     * - 空闲：直接发送（与旧的 send() 一致）；
     * - 处理中：**入队**，不丢用户输入。前一轮结束后自动发队首。
     *
     * 旧行为是「处理中 canSend = false」，用户打完字点发送毫无反应 ——
     * 这是最容易让人烦躁的一种交互，因为它既不告诉他为什么，
     * 也不保留他已经打好的字。
     */
    fun sendOrEnqueue() {
        val text = _state.value.input.trim()
        if (text.isEmpty()) return
        if (_state.value.isBusy) {
            val next = PendingQueueOps.enqueue(_state.value.queue, nextQueueId++, text)
            // 达到上限时 enqueue 返回原实例（===），此时要告诉用户而不是静默丢弃。
            if (next === _state.value.queue) {
                _state.update {
                    it.copy(error = AiError(
                        kind = AiErrorKind.INVALID_REQUEST,
                        message = "待发队列已满（${PendingQueue.MAX_SIZE} 条），请先发送或删除。",
                    ))
                }
                return
            }
            _state.update {
                it.copy(
                    queue = next,
                    input = "",
                    // 入队即展开：用户按了发送却变成「排队中」，
                    // 不展开面板他会以为消息丢了（Operit 同款行为）。
                    queueExpanded = true,
                    // 记下「此刻是阻塞的」。自动出队要的正是这条跳变沿的起点：
                    // 若这里不记，生成结束时 `queueWasBlocked` 仍是 false，
                    // 跳变沿不成立 → 队列永远不动。
                    queueWasBlocked = true,
                )
            }
            return
        }
        send()
    }

    /** 手动展开 / 收起队列面板。 */
    fun setQueueExpanded(expanded: Boolean) {
        _state.update { it.copy(queueExpanded = expanded) }
    }

    /**
     * 立即发送队列里的某一条（跳过排队顺序）。
     *
     * ## 为什么先 `suppressNextAutoDequeue`
     *
     * 用户点「立即发送」取走的那条若恰好是队首，等这一轮跑完，
     * 「阻塞→解除」的跳变沿还会再触发一次自动出队 —— 结果是连着发两条。
     * 抑制一次正好抵消它。
     */
    fun queueSendNow(id: Long) {
        if (_state.value.isBusy) return
        val item = _state.value.queue.items.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(
                queue = PendingQueueOps.remove(it.queue, id),
                input = item.text,
                suppressNextAutoDequeue = true,
            )
        }
        send()
    }

    /** 队列上移一位。 */
    fun queueMoveUp(id: Long) {
        val next = PendingQueueOps.moveUp(_state.value.queue, id)
        if (next !== _state.value.queue) _state.update { it.copy(queue = next) }
    }

    /** 队列下移一位。 */
    fun queueMoveDown(id: Long) {
        val next = PendingQueueOps.moveDown(_state.value.queue, id)
        if (next !== _state.value.queue) _state.update { it.copy(queue = next) }
    }

    /** 队列条目置顶。 */
    fun queueMoveToTop(id: Long) {
        val next = PendingQueueOps.moveToTop(_state.value.queue, id)
        if (next !== _state.value.queue) _state.update { it.copy(queue = next) }
    }

    /** 删除队列条目。 */
    fun queueRemove(id: Long) {
        _state.update { it.copy(queue = PendingQueueOps.remove(it.queue, id)) }
    }

    /** 把队列条目取回输入框（用户想改一下再发）。 */
    fun queueEdit(id: Long) {
        val item = _state.value.queue.items.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(
                queue = PendingQueueOps.remove(it.queue, id),
                input = item.text,
            )
        }
    }

    /**
     * 队首出队并发送 —— 只在「阻塞 → 解除」的**跳变沿**发生一次。
     *
     * ## 为什么必须判跳变沿（这是 1:1 对齐 Operit 的核心）
     *
     * 旧写法是「空闲就取队首」。它的问题不在于会不会漏，而在于会**重复**：
     * 这个方法被多个收尾点调用（正常结束、工具续轮结束、取消），
     * 每一次「空闲」都取一条，于是用户排了三条会被连着发完 ——
     * 中间那两条的回复用户根本来不及看，也被计入上下文与费用。
     *
     * Operit 的做法（`PendingMessageQueueStore.consumeAutoDequeueSignal`）是
     * 记住上一刻的阻塞态 `wasBlocked`，只在「之前阻塞、现在不阻塞」时返回
     * 一次 true，并顺手把 `wasBlocked` 更新为当前值。这样：
     *
     * ```
     * 生成中（wasBlocked=true）
     *   → 结束 → consume() 返回 true，顺手把 wasBlocked=false
     *   → 再调一次 → wasBlocked 已是 false，返回 false（不会重复发）
     * ```
     *
     * 同时 `suppressNextAutoDequeue` 让「用户手动发送队首」「用户取消」能吃掉
     * 那一次跳变，避免主动操作后又被自动补一条。
     */
    private fun drainQueueIfIdle() {
        val current = _state.value
        val blocked = current.isBusy
        // 先把「上一刻是否阻塞」读出来，再更新为本刻值（顺序不能反，
        // 反了就变成「自己和自己比」，跳变沿永远不成立）。
        val wasBlocked = current.queueWasBlocked
        val shouldDequeue =
            wasBlocked && !blocked && !current.suppressNextAutoDequeue && current.queue.isNotEmpty

        // 无论是否出队都要更新标记：wasBlocked 归到当前值，
        // suppress 在解除时清掉（否则会一直抑制下去）。
        _state.update {
            it.copy(
                queueWasBlocked = blocked,
                suppressNextAutoDequeue = if (blocked) it.suppressNextAutoDequeue else false,
            )
        }
        if (!shouldDequeue) return

        val (head, rest) = PendingQueueOps.poll(_state.value.queue)
        if (head == null) return
        _state.update { it.copy(queue = rest, input = head.text) }
        // 记住「这一条是从队列里取出来的」。
        //
        // ## 为什么必须记
        //
        // `send()` 会清空 input 并开始请求。如果这一轮失败，那条消息既不在
        // 输入框（已被清空）也不在队列（已被弹出）—— 用户的排队消息凭空消失。
        // 失败分支据此把它 `restore` 回队首。
        inFlightQueueMessage = head
        send()
    }

    // ── 消息菜单动作（长按） ─────────────────────────────────

    /**
     * 执行一条消息动作。
     *
     * 复制需要平台剪贴板，不在 ViewModel 里做：
     * 这里只处理「会改状态」的动作，复制由 UI 层拿到文本后自己写剪贴板。
     * 这样 ViewModel 保持纯 JVM，单测不需要 Android 环境。
     *
     * @return 需要复制到剪贴板的文本（仅 Copy 返回非空；
     *   它其实是 null —— 复制走 `copyPreview` 两段式，见该分支的说明）
     */
    fun onMessageAction(bubbleId: Long, action: MessageAction): String? {
        val bubble = _state.value.bubbles.firstOrNull { it.id == bubbleId } ?: return null
        return when (action) {
            /**
             * 复制：返回**洗过的**正文（1:1 对齐 Operit `cleanMessageContentForCopy`）。
             *
             * ## 为什么不在 UI 层直接复制 `bubble.text`
             *
             * `bubble.text` 里夹着 `<think>`、`<tool>`、`<status>`、`<meta
             * provider="gemini:thought_signature">…` 这些内部标记。直接复制
             * 出去是一堆半截 XML + 长 base64，用户还得手动清。
             *
             * 清洗放在 ViewModel 而不是 UI：这是**纯函数**，可单测；
             * 而剪贴板写入必须留给 UI（ViewModel 保持纯 JVM）。
             */
            MessageAction.Copy -> {
                // ## 不直接返回文本（1:1 对齐上游的两段式）
                //
                // 上游点「复制」只做两件事：算出 `MessageCopyContent`（洗白版 +
                // XML 原文版），然后打开预览表让用户选档位。真正写剪贴板发生在
                // 预览表里。
                //
                // 旧实现直接 `return cleanMessageContentForCopy(text)`，UI 拿到
                // 非空字符串就写剪贴板 —— 结果「XML 原文」这档永远没有入口。
                // 现在改为置 `copyPreview`，由 UI 渲染 `MessageCopyPreviewSheet`。
                _state.update { it.copy(copyPreview = ChatCopyContent.of(bubble.text)) }
                null
            }
            // ## 为什么这一项被删了（2026-10-24 第 4 轮）
            //
            // 旧 `MessageAction.Quote` 只是把正文转成 Markdown 引用块塞进输入框，
            // 模型看到的是一段普通引用，**不知道**这是用户特意针对某一条的消息。
            // 而 `Reply` 会注入 `<reply_to sender timestamp>` 结构化标签，
            // 模型明确知道在回哪一条 —— 是 `Quote` 的严格超集。
            //
            // 两者并存时用户面对两个都叫「引用」的项，不知道该点哪个。
            // 删掉弱化的那个，菜单每一项都对应上游存在的动作。
            MessageAction.Edit -> {
                // ## 从「填回输入框」改为「打开消息编辑器」（1:1 对齐 Operit）
                //
                // 旧行为是把原文塞进 `state.input`。对**纯文本**消息勉强够用，
                // 但模型回复里大量内容是 `<think>…</think>`、`<tool>…</tool>`
                // 这类结构 —— 塞进单行输入框后用户面对的是几百个尖括号，
                // 改一个错字都找不到位置。
                //
                // Operit 的做法是弹 `MessageEditor`：把消息切成片段，
                // 文本片段给普通输入框、标签片段给可折叠卡片。
                // 这里改为打开同一个编辑器（`feature/chat/editor/MessageEditor`）。
                //
                // ## 为什么不在 ViewModel 里直接改
                //
                // 编辑器是**多步交互**（改片段 → 点保存）。ViewModel 只负责
                // 「打开/关闭 + 落库」，中间状态由编辑器自己持有 ——
                // 否则每敲一个字都要过一次 StateFlow，流式期间会被大量重组拖慢。
                _state.update {
                    it.copy(
                        editingBubbleId = bubbleId,
                        editingText = bubble.text,
                        // 只有用户消息才给「保存并重发」：助手消息重发等于让模型
                        // 重答一遍，而用户此刻的意图通常只是改错字。
                        editingCanResend = bubble.role == BubbleRole.User,
                    )
                }
                null
            }
            // 修改助手消息（对齐 Operit「修改记忆」）：与 Edit 同一个编辑器，
            // 但 editingCanResend = false（助手消息不能「重发」）。
            MessageAction.EditAssistant -> {
                _state.update {
                    it.copy(
                        editingBubbleId = bubbleId,
                        editingText = bubble.text,
                        editingCanResend = false,
                    )
                }
                null
            }

            // 插入摘要（**真·插入**，2026-10-24 重做，1:1 对齐 Operit）。
            //
            // ## 旧实现做的是另一件事
            //
            // 旧代码只是把被长按那条的正文转成 Markdown 引用块、拼一句提示词
            // 填进输入框：
            //
            // ```kotlin
            // it.copy(input = "请把下面这段内容压缩成简短摘要：\n\n" + block)
            // ```
            //
            // 不调模型、不落库、不插进对话。用户点完看到的是一条**待发**的消息，
            // 得自己按发送 —— 这不是 Operit 的「插入摘要」，而是「引用并追问」。
            //
            // ## Operit 的真实语义（ChatViewModel.performInsertSummary）
            //
            // 三段，缺一不可：
            //
            // 1. **按 sender 决定取哪一段**：
            //    - 长按 AI 消息 → 总结它**之前**的历史（`beforeTimestamp = 该消息时间`）
            //    - 长按用户消息 → 总结它**之后**的历史（`afterTimestamp = 该消息时间`）
            // 2. **真调模型**生成摘要（`AIMessageManager.summarizeMemory`）；
            // 3. **插到对话中间**（`addSummaryMessage(before, after)`），
            //    而不是追加到末尾 —— 它是「记忆压缩块」，位置本身有语义。
            //
            // ## 为什么这个操作是异步的、且有专门状态
            //
            // 它要等一次模型调用（几秒到几十秒）。期间界面必须说「正在生成总结」，
            // 否则用户会以为没反应而反复点。所以下面走 `insertSummaryAsync`，
            // 并且它会自己管 `stage`。
            MessageAction.InsertSummary -> {
                insertSummaryAsync(bubbleId)
                null
            }

            // 消息信息（对齐 Operit「信息」）。
            //
            // 把要展示的字段先算成文本，由 UI 弹窗展示 —— ViewModel 不碰 UI。
            // 用 infoText 字段而不是在 UI 里重新推导：UI 拿不到 `assistantRowIds`
            // 这类内部映射，字段必须在 ViewModel 里就算好。
            MessageAction.ShowInfo -> {
                _state.update { it.copy(infoBubbleId = bubbleId) }
                null
            }
            MessageAction.Regenerate -> {
                // 重新生成：把这条助手回答之后的内容全部丢弃（含它本身），
                // 再重新发一轮。丢历史是必须的 —— 否则模型看到自己上一版回答，
                // 会倾向于复述它而不是重写。
                truncateFrom(bubbleId)
                null
            }

            /**
             * 继续：保留已有内容，让模型接着往下写。
             *
             * 真正的实现在 [continueGeneration]（那里有位置校验与落盘）。
             * 这里只做转发，并把返回值固定为 null —— 它不产生要复制的文本。
             */
            MessageAction.Continue -> {
                continueGeneration(bubbleId)
                null
            }

            /**
             * 删除：**只置待确认**，不真删（1:1 对齐上游）。
             *
             * 上游点「删除」只做 `showDeleteMessageConfirmDialog = true`，
             * 真正删除在弹窗的确认按钮里，由 [confirmDeleteMessage] 执行。
             *
             * ## 为什么要多这一道
             *
             * 长按菜单是贴着手指弹出的，删除项在列表中段（不是最后一项）。
             * 旧实现点下去直接 `removeBubble`，误触一次就是「几百字的回复没了」，
             * 而且 Praxis 没有撤销。
             */
            MessageAction.Delete -> {
                _state.update { it.copy(pendingDeleteBubbleId = bubbleId) }
                null
            }

            /**
             * 回复：记下目标，输入栏上方显示预览条（对齐上游 `setReplyToMessage`）。
             *
             * ## 为什么这里不往输入框里塞东西
             *
             * 上游的回复是**结构化**的：`_replyToMessage` 单独一个 StateFlow，
             * 输入框内容不受影响；发送时才在正文前拼 `<reply_to sender timestamp>`
             * 标签（见 [buildReplyTag]）。这样用户可以「一边回复某条，
             * 一边正常写自己的内容」，而不是先被塞一段引用块再删。
             */
            MessageAction.Reply -> {
                _state.update { it.copy(replyToBubbleId = bubbleId) }
                null
            }

            /**
             * 回滚到此处（对齐上游 `rollbackToMessage`）。
             *
             * ## 四步中的四步（上游五步，第 2 步工作区快照 Praxis 没有）
             *
             * 1. 校验目标是用户消息（上游也校验：`chat_only_user_message_allowed`）；
             * 2. 【跳过】回滚工作区到快照 —— 没有 `WorkspaceBackupManager`；
             * 3. 删掉这条及其之后的所有消息；
             * 4. 把这条正文**洗干净**后填回输入框（上游 `AvatarEmotionManager
             *    .stripXmlLikeTags` 这一步，这里用 `cleanMessageContentForCopy`
             *    —— 它比 stripXmlLikeTags 更严格，连 `<tool_result>` 内部的
             *    结果文本都会去掉，那正是「不要的回滚残留」）。
             *
             * 第 4 步是这个功能存在的理由：回滚不是「删掉后面的话」，
             * 而是「回到那个问题、让我重写一遍」。只删不填，用户还得自己
             * 往上翻找原文重打。
             */
            MessageAction.RollbackHere -> {
                if (bubble.role != BubbleRole.User) return null
                // 先取正文再截断：截断会把这条气泡从 `bubbles` 里移除，
                // 之后再 `firstOrNull` 就查不到了。
                val recovered = cleanMessageContentForCopy(bubble.text)
                truncateFrom(bubbleId)
                _state.update { it.copy(input = recovered) }
                null
            }
        }
    }

    /** 确认删除（由确认弹窗调用）。 */
    fun confirmDeleteMessage() {
        val bubbleId = _state.value.pendingDeleteBubbleId ?: return
        removeBubble(bubbleId)
        _state.update { it.copy(pendingDeleteBubbleId = null) }
    }

    /** 取消删除（关掉确认弹窗）。 */
    fun dismissDeleteMessage() {
        _state.update { it.copy(pendingDeleteBubbleId = null) }
    }

    /** 关闭复制预览表。 */
    fun dismissCopyPreview() {
        _state.update { it.copy(copyPreview = null) }
    }

    // ── 压缩摘要（1:1 对齐 Operit 的「历史对话摘要」交互）──────────

    /** 打开摘要全文弹窗（点消息流里的那条分割线）。 */
    fun openSummaryDetail() {
        if (_state.value.compacted?.isActive != true) return
        _state.update { it.copy(showSummaryDetail = true) }
    }

    /** 关闭摘要全文弹窗。 */
    fun dismissSummaryDetail() {
        _state.update { it.copy(showSummaryDetail = false) }
    }

    /**
     * 手动触发一次压缩（对齐上游「手动总结对话」`manuallySummarizeConversation`）。
     *
     * ## 为什么要有手动入口
     *
     * 自动压缩只在发送时判定。如果用户注意到进度条快到阈值，想现在就压，
     * 旧实现没有任何办法 —— 只能干等到下一次发送，而那次发送很可能
     * 正好超限。上游的工具条里就有这个动作。
     *
     * ## 为什么 `strictThreshold` 不传
     *
     * 手动压缩时**不看阈值**：用户的意图就是「现在就压」。所以这里直接
     * 绕过 `shouldCompact`，只保留「有没有东西可压」的判断。
     */
    fun compactNow() {
        // 只要求「当前空闲」。
        //
        // ## 旧写法为什么是错的（本轮顺手修掉）
        //
        // 原先是两行判断：
        //
        // ```kotlin
        // if (_state.value.stage != ChatStage.Idle) return
        // if (_state.value.stage == ChatStage.ExecutingTool) return   // 永不成立
        // ```
        //
        // 第一行已经把「非 Idle」全拦掉了，第二行是**死代码**——
        // 既然 stage 一定是 Idle，就不可能等于 ExecutingTool。
        // 留着会让读代码的人以为「压缩时会特意避开工具执行」，
        // 实际上这条保护是无效的（真正的保护在第一行）。
        // 死代码比缺失更危险：它提供一个虚假的安全感。
        if (_state.value.stage != ChatStage.Idle) return
        startCompaction(trigger = CompactionTrigger.Manual)
    }

    /**
     * 内存压力驱动的自动压缩（由 [onMemoryPressureChanged] 调用）。
     *
     * ## 与 [compactNow] 的三处差异
     *
     * | 点 | 手动 | 自动 |
     * |---|---|---|
     * | 触发条件 | 用户点击 | 内存档位到 High / Critical |
     * | 空闲要求 | 必须空闲 | 同样必须空闲（压缩要动 history） |
     * | 可否被后续覆盖 | — | 需要抑制重复触发（见下） |
     *
     * ## 为什么要抑制重复触发
     *
     * 内存档位可能连续多帧停在 High（轮询 15 秒一次，会持续发同一个值）。
     * 若每次收到 High 都压一次，就会连压好几轮 —— 每轮一次模型调用。
     * 所以用 [memoryCompactInFlight] 与 [lastMemoryCompactLevel] 两个闸：
     *
     *  - `memoryCompactInFlight`：正在压时不重复进入；
     *  - `lastMemoryCompactLevel`：同一档位只压一次，除非档位**升级**。
     *
     * 升级（High → Critical）时允许再压一次是必要的：那说明上一轮压完之后
     * 内存又涨回去了，需要更激进的折叠。
     */
    private fun compactForMemoryPressure(level: MemoryPressure) {
        // ## 三个前置条件都必须在**写任何字段之前**判完
        //
        // `stage != Idle` 这条尤其重要：它是最常命中的（用户在流式回复中、
        // 工具执行中）。若把 `lastMemoryCompactLevel = level` 放在它之前，
        // 就会出现「因为忙所以没压，但记成了已压过」——
        // 之后内存真的到了 High，反而被去重挡掉。
        if (_state.value.stage != ChatStage.Idle) return
        if (memoryCompactInFlight) return
        val last = lastMemoryCompactLevel
        // 同一档位不重复；更低的档位不再压（内存已经缓解了）。
        if (last != null && level <= last) return

        lastMemoryCompactLevel = level
        memoryCompactInFlight = true
        startCompaction(trigger = CompactionTrigger.ByMemoryPressure(level))
    }

    /**
     * 真正的压缩执行体（手动与自动共用）。
     *
     * @param trigger 触发来源。只用于日志与「压缩原因」的说明，
     *   不影响压缩行为 —— 两种入口折叠的范围与提示词完全一致。
     */
    private fun startCompaction(trigger: CompactionTrigger) {
        viewModelScope.launch {
            // ## `memoryCompactInFlight` 的释放必须放在 finally
            //
            // 三条提前返回路径（end<=start / slice 空 / summary 空）都会
            // 绕过正常的收尾。若在每条路径各写一次释放，将来加一条新路径
            // 就会漏掉 —— 而漏掉的后果是「自动压缩从此永久失效」，
            // 且没有任何报错（只是一个 return 之后再也不会进入）。
            //
            // 用 finally 一次兜住所有路径。
            //
            // `succeeded` 是给下面 finally 用的：失败时要把
            // `lastMemoryCompactLevel` 退回去，否则「同一档位只压一次」
            // 这条去重会把一次失败当成一次成功 ——
            // 内存持续停在 High 的那一整段时间里都不会再重试。
            var succeeded = false
            try {
                // ## 「有没有东西可压」必须放在 try 里面（本轮修）
                //
                // 旧写法把这两行放在 `viewModelScope.launch` **外面**。
                // 那样 `foldCount <= 0` 的 return 在 `finally` 覆盖范围之外：
                //
                // ```
                // inFlight = true
                // startCompaction() → foldCount 为 0 → return（无 finally）
                // → inFlight 永远是 true
                // → 之后每一次 compactForMemoryPressure 都被 `if (inFlight) return` 挡死
                // ```
                //
                // 而 `foldCount <= 0` 恰恰是最常见的情况（历史还短、刚压过、
                // 或用户刚清过会话）。结果是：只要第一次自动压缩碰上「没东西可压」，
                // 这台设备上的自动压缩就永久失效，且没有任何报错。
                val total = history.size
                val foldCount = ContextCompactor.compactCount(total) - compacted.coveredCount
                if (foldCount <= 0) return@launch

                val config = readRuntimeConfig()
                val start = compacted.coveredCount
                val end = (start + foldCount).coerceAtMost(total)
                if (end <= start) return@launch
                val slice = history.subList(start, end).toList()
                if (slice.isEmpty()) return@launch

                _state.update { it.copy(stage = ChatStage.Summarizing) }
                val summary = runCatching {
                    collectSummary(
                        ContextCompactor.buildRequest(
                            previousSummary = compacted.summary.takeIf { compacted.isActive },
                            messages = slice,
                            model = config.summaryModel,
                            useEnglish = compactionSettings.useEnglish
                                ?: ContextCompactor.detectUseEnglish(slice),
                            globalRules = compactionSettings.globalRules,
                        ),
                    )
                }.getOrNull()

                if (summary.isNullOrBlank()) {
                    _state.update { it.copy(stage = ChatStage.Idle) }
                    return@launch
                }
                compacted = CompactedContext(
                    summary = summary,
                    coveredCount = end,
                    createdAt = System.currentTimeMillis(),
                )
                tokenCache.reset()
                _state.update { it.copy(compacted = compacted) }
                publishUsage(buildPreparedMessages(config), config)
                _state.update { it.copy(stage = ChatStage.Idle) }
                // ## 为什么用 UiDiagnostics 而不是 Timber
                //
                // `:feature:chat` 没有 Timber 依赖（全仓只有 `:app` 与
                // `core:data` 有），而这条日志的用途是「事后回答那次为什么压」——
                // 正好属于 `UiDiagnostics.note` 的定义（普通事件，非异常）。
                // 引入 Timber 会给 feature 层带一个新依赖，而 UiDiagnostics
                // 是 ui:design 已有的、已被 Application 接到真实日志的端口。
                succeeded = true
                dev.praxis.ui.design.diagnostics.UiDiagnostics.note(
                    screen = "Chat",
                    message = "compaction done, trigger=" + trigger.describe() +
                        ", covered=" + compacted.coveredCount +
                        ", summaryTokens=" + compacted.summaryTokens,
                )
            } finally {
                // 无论成功、失败还是提前返回，都要放开这道闸。
                memoryCompactInFlight = false

                // ## 失败要把「已压过这一档」退回去
                //
                // 没有这行时的真实后果：用户没配摘要模型 / 余额不足 →
                // 第一次自动压缩失败 → `lastMemoryCompactLevel` 仍停在 High →
                // 之后每次收到 High 都被 `level <= last` 挡掉。
                // 而档位要“回到 Normal 才清零”，内存若一直停在 High，
                // 就再也不会重试 —— 自动压缩形同不存在。
                //
                // 只对内存触发退：手动压缩没有这个字段参与去重。
                if (!succeeded && trigger is CompactionTrigger.ByMemoryPressure) {
                    lastMemoryCompactLevel = null
                }
            }
        }
    }

    /** 清除回复目标（输入栏预览条上的 × ）。 */
    fun clearReplyTo() {
        _state.update { it.copy(replyToBubbleId = null) }
    }

    /**
     * 拼回复标签（1:1 对齐上游 `AIMessageManager.buildUserMessageContent`）。
     *
     * ## 上游的格式与三个细节
     *
     * ```xml
     * <reply_to sender="AI" timestamp="1699999999999">正在回复上一条消息"被引用的正文"</reply_to>
     * ```
     *
     * 1. **正文先削掉所有 XML 标签再截断 100 字**：被引用的消息里带
     *    `<think>` / `<tool>` 时，不削就会把几百字符的标签一起塞进去；
     *    截断是为了不让「回复」把上下文预算吃掉一大块。
     * 2. **正文用引号包住**：模型能从格式上区分「指令」与「被引用的内容」。
     *    上游的 instruction 文案是「正在回复上一条消息」，紧跟引号。
     * 3. **只带 sender 名字与时间戳**：时间戳是消息的身份（跳转链路也用
     *    时间戳当键），模型据此能定位到是哪一条。
     *
     * ## 2026-10-24 修正：标签的**位置**与**结尾**都错了
     *
     * | | 上游 | 旧 Praxis | 影响 |
     * |---|---|---|---|
     * | 位置 | `listOf(proxy, 正文, 附件, workspace, replyTag).joinToString(" ")` | `replyTag + text`（正文**之前**） | 标签顶在开头，模型先读「正在回复上一条消息"…"」再读用户真正的问题，主次颠倒 |
     * | 结尾 | 无换行（靠 `joinToString(" ")` 的空格分隔） | 末尾带 `\n` | 标签自成一段，模型容易把它当成「系统消息」而非「这条 user 消息的附注」 |
     *
     * 上游的最终形态是「**正文在前、归属信息在后**」：
     *
     * ```
     * 用户的实际问题 <reply_to sender="AI" timestamp="...">…</reply_to>
     * ```
     *
     * 这与人类引用回复的阅读顺序一致：先说自己的话，再标注在回谁。
     * 模型处理这种形态时不会把标签误当作指令，也不会在回复里模仿「开头就是标签」。
     *
     * ## 为什么 sender 名用「用户」/「AI」而不是 role 枚举名
     *
     * 这段文本是给模型读的。`BubbleRole.User` 这种标识符对模型是噪音，
     * 与系统提示里的人称也对不上。上游同样用可读名。
     */
    private fun buildReplyTag(): String {
        val id = _state.value.replyToBubbleId ?: return ""
        val target = _state.value.bubbles.firstOrNull { it.id == id } ?: return ""
        val sender = when (target.role) {
            BubbleRole.User -> "用户"
            BubbleRole.Assistant -> "AI"
            BubbleRole.Tool -> "工具"
            BubbleRole.Error -> "错误"
            // 摘要也可以被回复（用户在摘要上点「回复」），
            // 此时告诉模型「在回一段摘要」比谎报成 AI 更诚实。
            //
            // 注意：`actionsForBubble` 里 Summary 只给 Copy/Delete，
            // 所以正常情况下走不到这里。这个分支是为了「将来放宽菜单」
            // 时不至于编译不过，也让穷举保持完整。
            BubbleRole.Summary -> "摘要"
        }
        // 先削标签再截断：顺序反了会先切出一个残缺的标签，削不干净。
        val plain = target.text.replace(Regex("<[^>]*>"), "").trim()
        val clipped = if (plain.length > 100) plain.take(100) + "..." else plain
        // 结尾**不加** `\n`：上游靠 `joinToString(" ")` 的空格分隔。
        // 加了换行会让标签自成一段，模型更倾向于把它读成独立系统消息。
        return "<reply_to sender=\"$sender\" timestamp=\"${target.timestamp}\">" +
            "正在回复上一条消息\"$clipped\"</reply_to>"
    }

    /**
     * 把用户正文与回复标签按上游顺序拼起来（正文在前、标签在后）。
     *
     * ## 为什么单独抽一个函数
     *
     * 上游是 `listOf(proxy, 正文, 附件, workspace, replyTag).joinToString(" ")`。
     * Praxis 目前只有两个元素，但**顺序**是关键约定：
     * `replyTag + text` 与 `text + " " + replyTag` 对模型的含义完全不同
     * （见 [buildReplyTag] 的 KDoc）。把它收敛到一个函数里，
     * 以后新增标签（附件、工作区）时只有一处需要改，也不会有人
     * 在调用点随手写成 `buildReplyTag() + text` 把顺序改回去。
     *
     * `filter { it.isNotBlank() }` 与上游 `filter { it.isNotBlank() }` 同义：
     * 没有回复目标时 `replyTag` 是空串，不能留下一个多余的空格。
     */
    private fun composeUserContent(text: String, replyTag: String): String =
        listOf(text, replyTag)
            .filter { it.isNotBlank() }
            .joinToString(" ")

    /** 关闭「消息信息」弹窗。 */
    fun dismissMessageInfo() {
        _state.update { it.copy(infoBubbleId = null) }
    }

    /**
     * 削掉 history 里的 `<reply_to …>` 前缀（[buildReplyTag] 的逆操作）。
     *
     * ## 为什么需要这个「逆操作」
     *
     * 回复标签只会出现在 `history`（送给模型的原文）里，不会出现在气泡文本里。
     * 而 `removeBubble` / `saveEditingMessage` 都是「按角色 + 文本相等」在
     * history 里定位气泡对应项 —— 一旦这条是「回复某条」，比较就永远失败。
     *
     * 一个前缀、一个削前缀，两者必须成对维护：将来若改了 `buildReplyTag`
     * 的格式（例如加换行符），这个函数要同步改。
     *
     * ## 为什么用正则而不是 `removePrefix` 拿固定长度
     *
     * 标签里的 sender 名与时间戳长度不固定（时间戳 13 位、sender 2~2 字），
     * 只有标签结构是稳定的。正则能精确匹配到 `</reply_to>` 为止。
     */
    private fun stripReplyTag(text: String): String {
        // ## 2026-10-24 修正：标签位置移到正文之后，正则必须同时覆盖两处
        //
        // 旧实现只匹配**行首**（`^<reply_to…`），因为那时标签是拼在正文前面的。
        // 现在（与上游一致）标签在正文之后、用空格分隔，所以两个位置都要削：
        //
        //  - 行首版本：兼容「本轮改动前写入库里的旧数据」；
        //  - 行尾版本：匹配「正文 + 空格 + 标签」里的标签及其前面的空白。
        //
        // 两边都削掉之后，剩下的就是用户敲的纯文本 —— 这正是
        // `removeBubble` / `saveEditingMessage` 用来和气泡 `text` 比较的东西。
        //
        // 用 `[\\s\\S]*?` 而不是 `.` + DOT_MATCHES_ALL：被引用的正文里可能有换行
        // （引用的是一条多行回答），而 `.` 默认不跨行，会让标签只被削掉一半。
        return text
            .replace(Regex("^<reply_to\\b[^>]*>[\\s\\S]*?</reply_to>\\s*"), "")
            .replace(Regex("\\s*<reply_to\\b[^>]*>[\\s\\S]*?</reply_to>\\s*$"), "")
    }

    /** 关闭消息编辑器（取消）。工作副本一并丢弃 —— 这正是「取消」的语义。 */
    fun cancelEditingMessage() {
        _state.update {
            it.copy(
                editingBubbleId = null,
                editingText = "",
                editingCanResend = false,
            )
        }
    }

    /** 编辑器内容变化（由 UI 的 `MutableState` 转写进来）。 */
    fun onEditingTextChange(text: String) {
        _state.update { it.copy(editingText = text) }
    }

    /**
     * 保存编辑结果（只改这条，不动对话流）。
     *
     * ## 三件事必须一起做
     *
     * 1. **改 UI 气泡**：否则用户点保存后界面没变化，会以为没生效；
     * 2. **改 [history]**：那是真正发给模型的原文。只改 UI 的话下一轮模型
     *    仍然看到旧内容 —— 表现为「我明明改了，AI 还是按旧的回答」；
     * 3. **落库**：不落库则重启后编辑丢失。
     *
     * ## 为什么 history 按「内容匹配」而不是按 index 改
     *
     * `history` 与 `bubbles` 不是一一对应（history 没有 Error 气泡）。
     * 用 index 对齐必然错位，所以先按「角色 + 旧文本」定位，再替换。
     * 定位失败（比如这条从未进过 history）就跳过 —— 不猜。
     */
    fun saveEditingMessage() {
        val bubbleId = _state.value.editingBubbleId ?: return
        val newText = _state.value.editingText
        val bubble = _state.value.bubbles.firstOrNull { it.id == bubbleId } ?: return
        val oldText = bubble.text

        // ① UI 气泡。
        //
        // ## 2026-10-24：编辑时必须同时清流式槽
        //
        // 流式文本存在 `streamingText`（id → text），而它是**读优先**的
        // （见 `ChatScreen` 里的取文本逻辑）。若只改 `bubbles` 不清槽：
        // 用户编辑一条**曾经流式过**的消息后，UI 仍读 map 里的旧文本，
        // 表现是「保存了没变化」；切一次会话回来（槽已清）又对了。
        //
        // `sealStreamText` 恰好就是「写定稿 + 清槽」，直接复用。
        sealStreamText(bubbleId, newText)

        // ② history（按角色 + 旧文本定位）。
        val historyRole = when (bubble.role) {
            BubbleRole.User -> ChatRole.User
            BubbleRole.Assistant -> ChatRole.Assistant
            BubbleRole.Tool -> ChatRole.Tool
            BubbleRole.Error -> null
            // 摘要进历史：它本来就是「上下文的一部分」，删掉它会让模型
            // 忘记之前压缩过的内容。用 Assistant 送进去 —— `ChatRole` 里
            // 没有 Summary（那是 UI 概念，core:ai 不需要知道）。
            BubbleRole.Summary -> ChatRole.Assistant
        }
        if (historyRole != null) {
            // 同 `removeBubble`：history 里的用户消息可能带 reply 标签前缀，
            // 直接按文本比较会在「编辑一条回复」时匹配不上，导致历史里
            // 留下旧版本、模型下一轮读到的是改前的内容。
            val idx = history.indexOfLast {
                it.role == historyRole && stripReplyTag(it.text) == oldText
            }
            if (idx >= 0) {
                // 保留原 text 里的 reply 标签：这次编辑只改用户敲的那部分，
                // 引用关系不变。
                val prefix = history[idx].text.removeSuffix(oldText)
                history[idx] = history[idx].copy(text = prefix + newText)
            }
        }

        // ③ 落库。用 `assistantRowIds` 里记的行 id；恢复出来的气泡也有映射，
        // 所以这条路对「编辑历史消息」同样有效。
        val rowId = assistantRowIds[bubbleId]
        if (rowId != null) {
            launchPersist { updateMessageContent(rowId, newText) }
        }

        cancelEditingMessage()
    }

    /**
     * 保存编辑并**从这条重新发**（仅用户消息）。
     *
     * ## 与 [saveEditingMessage] 的关键区别
     *
     * 这里要**丢弃这条以及它后面的一切**：用户改了自己那句话，意味着
     * 后续所有回答都是基于旧问题的，留着只会让模型看到自相矛盾的上下文。
     *
     * 顺序是「先截断 → 再把改后的文本当待发送 → 再发」：
     *  - `truncateFrom(bubbleId)` **含这条本身**，所以列表里那条旧的用户消息
     *    已经消失，不会出现「两条一模一样的用户消息」；
     *  - 把改后的文本放进 `input`，`send()` 会像正常发送一样新建一条用户气泡。
     *
     * 落库侧不删旧行：`send()` 会新写一条。历史里会多出一条被替换掉的旧行，
     * 但重启后用户看到的是最新那条 —— 这是可接受的取舍（真删要引入
     * 「按行 id 删除」的新 DAO 方法，而这条链路当前没有别处需要它）。
     */
    fun resendEditingMessage() {
        val bubbleId = _state.value.editingBubbleId ?: return
        val newText = _state.value.editingText.trim()
        if (newText.isEmpty()) return
        val bubble = _state.value.bubbles.firstOrNull { it.id == bubbleId } ?: return
        if (bubble.role != BubbleRole.User) return
        if (_state.value.isBusy) return

        // 先截断（含这条本身），再把它改后的文本作为「待发送内容」。
        truncateFrom(bubbleId)
        cancelEditingMessage()
        _state.update { it.copy(input = newText) }
        send()
    }

    /**
     * 插入摘要 —— **真的调模型生成，并插进对话中间**。
     *
     * ## 与「引用」的区别（这两个动作此前被混成了一个）
     *
     * | 动作 | 做什么 | 花不花钱 |
     * |---|---|---|
     * | [MessageAction.Reply] | 记回复目标，发送时注入 `<reply_to>` 标签 | 不花 |
     * | 本函数 | 立刻调一次模型把一段历史压缩成摘要，插进对话 | **花** |
     *
     * 这两个动作容易混（都像「处理某段对话」），区别在**结果落在哪**：
     * `Reply` 的产物是「下一条用户消息的前缀」，`InsertSummary` 的产物是
     * 「一条独立的摘要气泡」。前者等用户继续写，后者当场调模型。
     *
     * （旧注释拿 `Quote` 与它对比，`Quote` 已在 2026-10-24 删除 ——
     * 它和 `Reply` 语义重复，见 `MessageAction` 的 KDoc。）
     *
     * ## 取哪一段：照 Operit 的 sender 判据
     *
     * ```
     * 长按 AI 消息  → 摘要它**之前**的全部（beforeExclusive = 它的下标）
     * 长按用户消息  → 摘要它**之后**的全部（afterExclusive  = 它的下标）
     * ```
     *
     * 为什么是这个方向：用户长按 AI 的回答并选「插入摘要」，
     * 通常是想「把到这儿为止的对话压成一段，后面接着聊」；
     * 长按自己的提问则是想「把这个话题之后的内容压起来」。
     * 两个方向都验证过用途，而相反方向没有意义（会压到还没说的话）。
     *
     * ## 为什么插入位置是「被长按那条之后」
     *
     * `ChatPersistence.insertMessageAt` 支持按 orderIndex 中间插入
     * （2026-10-24 补上），所以「插到中间」这件事在**内存与库两处都成立**：
     *
     * ```
     * bubbles: list.add(index + 1, bubble)          // 界面
     * 库里:    insertMessageAt(atOrderIndex = 下一条的 orderIndex)  // 持久化
     * ```
     *
     * 两边位置等价，重启后读回来的顺序与本次会话内看到的一致。
     *
     * ## 历史：这里曾经是一处「假实现」
     *
     * 旧代码只调 `appendMessage`（orderIndex = MAX+1），KDoc 里把
     * 「重启后摘要沉到会话最底」写成「可接受的取舍」。但那个取舍**不必存在**：
     * `orderIndex` 一直都是 UI 的排序键，腾个位就够了。
     * 当时的说法掩盖了一个真实可见的缺陷 —— 用户重启应用后会发现
     * 摘要跑到了对话末尾，与它压缩的那段历史脱节。
     */
    private fun insertSummaryAsync(bubbleId: Long) {
        val bubbles = _state.value.bubbles
        val index = bubbles.indexOfFirst { it.id == bubbleId }
        if (index < 0) return
        val target = bubbles[index]

        // 工具气泡不给（与 Operit 同判据：只处理 user / ai）。
        if (target.role != BubbleRole.User && target.role != BubbleRole.Assistant) {
            _state.update {
                it.copy(notice = "只有用户消息与 AI 回复可以插入摘要。")
            }
            return
        }

        // 取区间：AI 消息取它**本身之前**的历史，用户消息取它**之后**的历史。
        //
        // ## 与 Operit 的边界逐字对齐（2026-10-24 修正）
        //
        // 上游 `performInsertSummary` 的判据是：
        //
        // ```kotlin
        // val beforeTimestamp = if (message.sender == "ai") message.timestamp else null
        // val afterTimestamp  = if (message.sender == "user") message.timestamp else null
        // loadMessagesForSummaryInsertion(
        //     beforeTimestampExclusive = afterTimestamp,   // 用户消息：严格在它之后
        //     upToTimestampInclusive   = beforeTimestamp,  // AI 消息：含它自己
        // )
        // ```
        //
        // 两个边界的方向**不一样**，这是有意的：
        //
        // | 长按 | 取哪段 | 边界 | 插入点 |
        // |---|---|---|---|
        // | AI 回答 | 到它**为止**（含它） | `upTo = 它的时间`（含） | 它之后 |
        // | 用户消息 | 从它**之后**开始 | `before = 它的时间`（不含） | 它之前？不——它之后 |
        //
        // 旧实现两个都用「不含」，于是长按 AI 回答时**漏掉了这条回答本身**。
        // 那是最该被总结的一条：用户想压掉的往往正是这轮长长的解释。
        // 结果摘要里少了核心内容，看起来像「总结得不全」。
        val slice = when (target.role) {
            // 含自己（index 本身）
            BubbleRole.Assistant -> bubbles.take(index + 1)
            // 不含自己
            else -> bubbles.drop(index + 1)
        }.filter {
            // ## 为什么把 Summary 也纳入可读范围（2026-10-24 修正后的保留理由）
            //
            // 摘要块是「已压缩的历史」。若某一段区间里已经有旧摘要，
            // 对它**视而不见**会让那段历史凭空消失：新摘要只压了区间里的
            // 新对话，而旧摘要覆盖的内容既没进新摘要、也不在结果里。
            // 把它当作文本一起喂进去，模型才能把「旧压缩块 + 新对话」
            // 合成一份完整的。这与 Operit 的
            // `loadMessagesAfterLatestSummaryInRange` 方向一致（它是从
            // 最近摘要之后开始读，避免重复压；我们则把旧摘要合进来，
            // 因为内存里没有「摘要时间戳」这类元数据可用来切窗口）。
            it.role == BubbleRole.User ||
                it.role == BubbleRole.Assistant ||
                it.role == BubbleRole.Summary
        }
            // 保留气泡对象而不只是文本：下一步要按**原 role** 映射成 ChatRole，
            // 不能靠奇偶位置猜（见下方 messages 构造处的说明）。
            .filter { it.text.isNotBlank() }

        if (slice.isEmpty()) {
            _state.update { it.copy(notice = "这一段里没有可总结的对话。") }
            return
        }

        _state.update { it.copy(stage = ChatStage.Summarizing, notice = null) }
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            try {
                val config = readRuntimeConfig()
                // 复用压缩链路的请求构造：措辞、语言判断、全局规则都在那里，
                // 两条摘要路径用同一份提示词才能保证风格一致。
                //
                // ## 角色必须按原气泡映射，不能按奇偶猜（2026-10-24 修正）
                //
                // 旧实现写的是 `if (i % 2 == 0) User else Assistant`。
                // 那假设「区间一定是 user/ai 交替，且以 user 开头」，
                // 而这个假设在四种常见情况下都不成立：
                //
                //  1. 长按 AI 回答取的是**它之前**那段，尾部是 AI —— 奇偶对得上纯属巧合；
                //  2. 区间里含工具气泡（被过滤掉了），交替被打断；
                //  3. 区间里含摘要气泡（前面已压缩过），交替被打断；
                //  4. 用户连发两条（界面允许），交替被打断。
                //
                // 一旦猜错，模型会把用户的话当成自己说的 —— 摘要里出现
                // 「我要求用户…」这种视角颠倒，用户一眼看出是坏的。
                val messages = slice.map { item ->
                    ChatMessage(
                        role = when (item.role) {
                            BubbleRole.User -> ChatRole.User
                            // 摘要块是「之前的压缩结果」，读它的视角是助手
                            // （它是被 AI 生成、供 AI 后续参考的内容）。
                            BubbleRole.Assistant,
                            BubbleRole.Summary,
                            -> ChatRole.Assistant
                            // Error 已在上面被 filter 掉，这里兜底成 Assistant
                            // 而不是 User：把系统错误当成用户发言会污染摘要。
                            else -> ChatRole.Assistant
                        },
                        text = item.text,
                    )
                }
                val summary = runCatching {
                    collectSummary(
                        ContextCompactor.buildRequest(
                            previousSummary = null,
                            messages = messages,
                            model = config.summaryModel,
                            useEnglish = compactionSettings.useEnglish
                                ?: ContextCompactor.detectUseEnglish(messages),
                            globalRules = compactionSettings.globalRules,
                        ),
                    )
                }.getOrNull()

                if (summary.isNullOrBlank()) {
                    _state.update {
                        it.copy(
                            stage = ChatStage.Idle,
                            notice = "摘要生成失败，这一段没有可压缩的内容或模型未响应。",
                        )
                    }
                    return@launch
                }

                // ── 插进对话中间 ──────────────────────────────────
                //
                // 摘要作为一条**独立类型**的气泡插入被长按那条之后。
                //
                // ## 为什么不用 Assistant（第 4 轮修正）
                //
                // 上一轮用 `BubbleRole.Assistant`，理由是「UI 不必加渲染分支」。
                // 实际代价是：它会得到「重新生成 / 继续 / 回复 / 修改记忆」
                // 这些对一段**已固化摘要**全都错误的菜单项，且看起来
                // 与模型的回答无法区分。现在改用 `BubbleRole.Summary`
                // （对应 Operit 的 `sender = "summary"`），见该枚举的 KDoc。
                //
                // 文本前加明文标题，模型与用户都能一眼看出这是压缩块。
                val header = "〔对话摘要〕以下为前文压缩：\n\n"
                val bubble = ChatBubble(
                    id = nextId++,
                    role = BubbleRole.Summary,
                    text = header + summary,
                    timestamp = System.currentTimeMillis(),
                )
                _state.update {
                    val list = it.bubbles.toMutableList()
                    list.add(index + 1, bubble)
                    it.copy(bubbles = list, stage = ChatStage.Idle)
                }
                // history 同步插入：不然模型下一轮看不到这段摘要，
                // 界面有、上下文没有，就会出现「它明明总结过却记不住」。
                //
                // ## 下标不能直接用 bubbles 的 index
                //
                // `history` 里**没有 Error 气泡**（见 `history` 字段的 KDoc），
                // 而 `truncateFrom` 依赖「history.size == bubbles 里非 Error 的条数」
                // 这条不变量。所以 history 的插入位置应当是
                // 「bubbles[0..index] 里非 Error 的条数」——
                // 也就是摘要气泡在 history 里该待的位置。
                //
                // 直接写 `index + 1` 会在会话中出现过错误气泡时错位：
                // 摘要插到了历史里比它实际所属更靠后的位置，
                // 之后截断就会把不该留的消息留下。
                val historyIndex = bubbles.take(index + 1)
                    .count { it.role != BubbleRole.Error }
                    .coerceAtMost(history.size)
                history.add(
                    historyIndex,
                    ChatMessage(role = ChatRole.Assistant, text = bubble.text),
                )
                // 落库：**按插入位置写**，不再追加到末尾（2026-10-24 修正）。
                //
                // ## 旧实现的问题
                //
                // 旧代码调 `appendMessage`（orderIndex = MAX+1），KDoc 里把
                // 「重启后摘要沉到最底」写成「可接受的取舍」。但那个取舍
                // 根本不必存在：`orderIndex` 就是 UI 的排序键，
                // 库层腾个位就能持久正确。
                //
                // ## 目标 orderIndex 怎么算
                //
                // 摘要要落在 `bubbles[index]` 之后，也就是原来的
                // `bubbles[index + 1]` 之前。所以它的 orderIndex 应该等于
                // **第 index+1 条在库里的 orderIndex**；若 index 已经是
                // 最后一条（没有下一条），则追加到末尾。
                //
                // 这里用「内存里的条数」推不出来（orderIndex 可能不连续：
                // 删消息不重排）。所以走 `insertMessageAt` 时传的是
                // 「下一行的 orderIndex」，而下一行的 orderIndex 由库层
                // 自己按 `sessionId + orderIndex 顺序` 取。
                //
                // ## 与内存插入的一致性
                //
                // 内存里是 `list.add(index + 1, bubble)`，库里的位置与它等价
                // （都在被长按那条之后、原来下一条之前）。两边一致，
                // 重启后读回来的顺序与本次会话内看到的相同。
                //
                // role 用 `"summary"` 而不是 `"assistant"`：恢复时要靠它
                // 还原成 `BubbleRole.Summary`（见 resume 分支的 when），
                // 否则重启后摘要会变成普通助手气泡、多出一堆错的菜单项。
                val persisted = runCatching {
                    val repo = chatRepository
                    if (repo == null) {
                        // 不落盘模式（单测 / 预览）：只保留内存插入。
                        null
                    } else {
                        repo.insertMessageAt(
                            sessionId = sessionId,
                            // `nextOrderIndexAfter` 是 ChatPersistence 的扩展，
                            // 必须显式 `.` 调用取 receiver —— 不能写成
                            // `nextOrderIndexAfter(index)`（那里没有隐式 receiver）。
                            atOrderIndex = repo.nextOrderIndexAfter(index),
                            role = "summary",
                            content = bubble.text,
                        )
                    }
                }.getOrNull()
                if (persisted != null) {
                    // 记住行 id：摘要气泡不需要流式更新，但保持一致，
                    // 便于将来「编辑摘要」这类功能按 id 定位。
                    assistantRowIds[bubble.id] = persisted
                }
                tokenCache.reset()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(stage = ChatStage.Idle, notice = "摘要生成失败：${t.message ?: "未知错误"}")
                }
            }
        }
    }

    /**
     * 求「插到第 [bubbleIndex] 条气泡之后」时该用的 orderIndex。
     *
     * ## 为什么不能直接用 `bubbleIndex + 1`
     *
     * `orderIndex` 只在同一会话内递增，且删消息后**不重排**
     * （重排要写整表，代价远大于收益）。所以它必然不连续：
     *
     * ```
     * 库里的 orderIndex：  0      3      7      8
     * 内存下标：            0      1      2      3
     * ```
     *
     * 对内存第 1 条（库里 orderIndex=3）插入摘要时，正确插入点是 **4**
     * （即「3 之后、7 之前」）。
     *
     * 若错传 `bubbleIndex + 1 = 2`：`insertMessageAt(2)` 把 `>= 2` 的行
     * 整体 +1（3→4, 7→8, 8→9），摘要落在 2 —— 顺序变成
     * `0 → 摘要(2) → 原第 1 条(4)`，摘要跑到了**目标之前**。
     * 用户看到的是「摘要插在我点的上面那条之后」，位置整体偏一位。
     *
     * ## 算法
     *
     * 拿「目标气泡的下一条」在库里的 orderIndex 作为插入点；
     * 若目标已是最后一条（没有下一条），插入点 = 最大 orderIndex + 1
     * （等价于追加，`shift` 不会命中任何行）。
     *
     * ## 为什么需要知道「下一条的库行 id」
     *
     * `bubbles` 的下标与库行之间只有 `assistantRowIds` 这一张映射表，
     * 而它只登记「创建/恢复过」的气泡。取不到 id 时不能猜：
     * 猜错就是上面那个偏一位的问题。此时退化为「把整段库读一遍，
     * 按行数定位」—— 这条路径慢，但只在映射缺失时走。
     */
    private suspend fun ChatPersistence.nextOrderIndexAfter(bubbleIndex: Int): Int {
        val rows = runCatching { loadMessages(sessionId) }.getOrNull().orEmpty()
        if (rows.isEmpty()) return 0

        val nextBubble = _state.value.bubbles.getOrNull(bubbleIndex + 1)
        val nextRowId = nextBubble?.let { assistantRowIds[it.id] }

        // 首选：按行 id 精确定位下一条。
        if (nextRowId != null) {
            val hit = rows.firstOrNull { it.id == nextRowId }
            if (hit != null) return hit.orderIndex
        }

        // 兜底：用「下一条气泡在内存里的下标」在同一位置找行。
        //
        // 只有当内存气泡与库行**一一对应**时才成立。恢复路径里
        // `mapNotNull` 会跳过无法映射的行（理论上没有），且 Error 气泡
        // 不进 history 但**会**落库，所以这里按 bubbles 下标取 rows 下标
        // 在正常数据下是准的；异常时最多偏一位，不会越界。
        val fallbackIndex = bubbleIndex + 1
        if (fallbackIndex in rows.indices) return rows[fallbackIndex].orderIndex
        return (rows.maxOfOrNull { it.orderIndex } ?: -1) + 1
    }

    /**
     * 从指定气泡开始截断（含它）。
     *
     * 同时要回退 [history]：UI 与送给模型的原文必须一致，
     * 只删界面上的气泡会让模型仍然“记得”那条被删掉的内容。
     */
    private fun truncateFrom(bubbleId: Long) {
        val bubbles = _state.value.bubbles
        val index = bubbles.indexOfFirst { it.id == bubbleId }
        if (index < 0) return
        val kept = bubbles.take(index)
        _state.update { it.copy(bubbles = kept) }

        // history 与 bubbles 不是一一对应（history 没有 Error 气泡），
        // 所以按「保留几条可进 history 的气泡」来截断。
        val keepForHistory = kept.count { it.role != BubbleRole.Error }
        while (history.size > keepForHistory) history.removeAt(history.lastIndex)
        // 截断后历史变短，压缩游标可能越界：一并回退，避免发出去时丢太多。
        if (compacted.coveredCount > history.size) {
            compacted = CompactedContext.None
            _state.update { it.copy(compacted = null) }
        }
        // ## 截断后必须清 token 缓存
        //
        // `TokenCache` 记着「上一次算过的完整历史」。截断把尾部砍掉之后，
        // 下次 `count` 会拿旧列表当公共前缀 —— 而新历史是旧历史的**前缀**，
        // 于是 `common == messages.size` 可能成立，直接返回 `previousTotal`：
        // 算出来的是**截断前**的用量，进度条显示一个偏大的假数字。
        tokenCache.reset()
    }

    /** 删除单个气泡。 */
    private fun removeBubble(bubbleId: Long) {
        val bubble = _state.value.bubbles.firstOrNull { it.id == bubbleId } ?: return
        _state.update { it.copy(bubbles = it.bubbles.filterNot { b -> b.id == bubbleId }) }
        if (bubble.role != BubbleRole.Error) {
            // 同样要回退 history：按内容删最后一条匹配的用户/助手消息，
            // 比按 index 删安全（Error 气泡不占 history 位置）。
            val role = when (bubble.role) {
                BubbleRole.User -> ChatRole.User
                BubbleRole.Assistant -> ChatRole.Assistant
                BubbleRole.Tool -> ChatRole.Tool
                // 摘要在 history 里是 Assistant 身份（`ChatRole` 没有 Summary），
                // 所以删它时要按 Assistant 去匹配 —— 写成 null 会让
                // history 里留下一条界面上已经不存在的摘要，
                // 模型下一轮还会读到它（「明明删了却还记得」）。
                BubbleRole.Summary -> ChatRole.Assistant
                else -> null
            }
            if (role != null) {
                // ## 为什么要先削 reply 标签再比（连带的必改点）
                //
                // history 里的用户消息可能带 `<reply_to …>` 前缀（见 `send()`
                // 里的注入），而气泡里只有纯文本。直接 `it.text == bubble.text`
                // 在「这条是回复别人的」时永远匹配不上 → 气泡删了、history
                // 没删 → 模型下一轮仍然「记得」这条被删掉的话。
                val idx = history.indexOfLast {
                    it.role == role && stripReplyTag(it.text) == bubble.text
                }
                if (idx >= 0) history.removeAt(idx)
            }
        }
    }

    /**
     * 只改一个气泡。
     *
     * ## 为什么不能用 `bubbles.map { ... }`（真机性能缺陷，2026-10-22 修）
     *
     * 流式时这个方法一秒被调几十次（每个 delta 一次）。旧实现每次都要
     * 遍历**整个**气泡列表：
     *
     * ```
     * 每次 delta：n 次比较 + n 个新引用（map 返回新 ArrayList）
     * 一秒 40 个 delta × n=200 条 → 8000 次/秒，且每次分配一个新列表
     * ```
     *
     * 对话越长 n 越大，卡顿越明显 —— 这正是用户说的「越聊越卡」。
     *
     * 改成「找到下标 → 复制一次列表 → 只替换那一项」：
     * 仍然要一次列表复制（不可变更新绕不开），但**不再对每一项调用
     * transform 闭包**，也不再构造 n 个新元素引用。
     *
     * ## 为什么找不到就直接返回
     *
     * 该 id 的气泡可能已被「重新生成」截断掉（`truncateFrom`）。
     * 此时若仍走 `copy(bubbles=...)`，会触发一次无意义的重组，
     * 而流式协程恰好还在往里写 —— 表现出来就是「删除后界面闪一下」。
     *
     * ## 2026-10-24：一次列表复制也是负担（长对话卡顿的真正来源）
     *
     * 上一版把代价从「n 个新元素」降到「一个 n 长度的 List」，但对**下游**
     * 来说代价没变：`copy(bubbles = next)` 让 `bubbles` 的**引用**变了，
     * 于是所有以它为 key 的 `remember` 全部失效。最贵的一处是 `ChatScreen`：
     *
     * ```
     * val locatorPreviews = remember(state.bubbles) {
     *     state.bubbles.map { it.toLocatorPreview() }   // 每条都逐字符归一化全文
     * }
     * ```
     *
     * 流式时每个 delta 都要把**全部历史**重新归一化 → O(n × 平均长度)、
     * 每秒几十次。这才是「越聊越卡」的真正机制，与元素分配无关。
     *
     * 所以文本更新改走 [ChatState.streamingText]（见 `applyStreamText`），
     * 本函数只负责**结构**字段（role / streaming / tool / timestamp）。
     */
    private fun updateBubble(id: Long, transform: (ChatBubble) -> ChatBubble) {
        _state.update { current ->
            val index = current.bubbles.indexOfFirst { it.id == id }
            if (index < 0) return@update current
            // toMutableList + 单点赋值：只分配一次，且只调用一次 transform。
            val next = current.bubbles.toMutableList()
            next[index] = transform(next[index])
            current.copy(bubbles = next)
        }
    }

    /**
     * 写入正在流式的正文（**不动 `bubbles`**）。
     *
     * ## 这是长对话优化的核心
     *
     * 只更新 `streamingText` 这个 map，`bubbles` 的引用保持不变。于是：
     *
     *  - 定位器预览（`remember(state.bubbles)`）**完全不重算**；
     *  - `itemsIndexed(key = ...)` 的 key 集合不变，列表结构不动；
     *  - 只有渲染这条气泡的那一处读 map 并重组。
     *
     * ## 为什么 map 要整体 copy
     *
     * `Map` 是不可变的（`mapOf` / `+`），一次 copy 的代价是 **O(流式条数)**
     * （通常 1 条，工具并发时 2~3 条），与对话长度无关 ——
     * 这正是我们要的：代价只跟「同时在流式几条」有关。
     *
     * 对比旧实现：`bubbles.toMutableList()` 是 O(全部消息数)，且让所有
     * 下游 `remember` 失效。两个量级完全不同。
     */
    private fun applyStreamText(id: Long, text: String) {
        _state.update { current ->
            // 同一个 id 且文本没变时早退：`snapshotFlow`/重组有时会重复提交
            // 相同内容，无谓地换一次 map 引用会让气泡多重组一帧。
            if (current.streamingText[id] == text) current
            else current.copy(streamingText = current.streamingText + (id to text))
        }
    }

    /**
     * 把流式文本**定稿**进 `bubbles` 并清掉槽位。
     *
     * ## 为什么必须清槽
     *
     * `streamingText` 是「在写」的临时态，`bubbles[i].text` 是**权威值**
     * （落库的那一份）。不定稿清槽会有两个具体后果：
     *
     *  1. 内存里长期存两份正文，长对话下是白占（一条几千字 × 几十条）；
     *  2. 「编辑消息」改的是 `bubbles`，而 UI 读 map 时**看不见编辑结果** ——
     *     表现为「编辑完没生效」，且刷新一次（比如切会话回来）又对了。
     *     这是最难排查的一类不一致。
     *
     * @param finalText 最终文本。传 `null` 表示「沿用槽里已有的值」
     *   （`streaming=false` 这类只改标记的收尾用它）。
     */
    private fun sealStreamText(id: Long, finalText: String? = null) {
        _state.update { current ->
            val text = finalText ?: current.streamingText[id] ?: return@update current
            val index = current.bubbles.indexOfFirst { it.id == id }
            // 气泡已被删（`truncateFrom` / 删除）时只清槽，不要复活一条幽灵消息。
            val nextBubbles = if (index < 0) {
                current.bubbles
            } else {
                current.bubbles.toMutableList().also {
                    it[index] = it[index].copy(text = text, streaming = false)
                }
            }
            current.copy(
                bubbles = nextBubbles,
                streamingText = current.streamingText - id,
            )
        }
    }
}

/** 错误文案（按 kind 给固定句子，不把上游原始报错直接甩给用户）。 */
private fun AiError.displayText(): String = when (kind) {
    AiErrorKind.NETWORK -> "网络连接失败，请检查网络后重试。"
    AiErrorKind.RATE_LIMIT -> "请求过于频繁，请稍后再试。"
    AiErrorKind.SERVER -> "服务端暂时不可用，请稍后重试。"
    AiErrorKind.STREAM_INTERRUPTED -> "回复中断了，可以重试继续。"
    AiErrorKind.AUTH -> "密钥无效或权限不足，请到设置里检查。"
    AiErrorKind.INVALID_REQUEST -> "请求参数有误，请检查模型配置。"
    AiErrorKind.CANCELLED -> "已取消。"
    AiErrorKind.PROTOCOL -> "服务端返回的数据无法解析，请检查接口地址是否正确。"
    AiErrorKind.UNKNOWN -> "出现未知错误：$message"
}

/**
 * 上下文运行配置。
 *
 * 由 `:app` 从设置仓储读好再传进 ViewModel：
 * `feature:chat` 不直接依赖 `core:data`（那会把 DataStore/Context 拖进来，
 * 单测就得起 Robolectric）。
 */
data class ContextConfig(
    /** 模型最大上下文（token）。0 = 未知。 */
    val contextLimit: Int = 0,
    /** 压缩阀值（0..1）。 */
    val compactThreshold: Float = ContextCompactor.DEFAULT_THRESHOLD,
    /** 思考强度。 */
    val thinkingEffort: ThinkingEffort = ThinkingEffort.None,
    /**
     * 是否用流式请求（底栏「行为」分区的开关，1:1 对齐 Operit `disableStreamOutput`）。
     *
     * 放在 [ContextConfig] 而不是新加一个 provider：这两者都是「用户偏好」，
     * 且都从同一份 `ModelSettings` 读出来，拆开会让 ChatTab 的接线多一次 await。
     */
    val stream: Boolean = true,
)

/**
 * 点「继续」时追加给模型的瞬时指令（不进 history、不落库）。
 *
 * ## 三段各解决一个具体问题
 *
 * 1. **「接着上文继续」** —— 不给这句，模型看到一条以半句话结尾的 assistant
 *    消息，最常见的反应是「重写整段」。用户点继续就是想省掉重看一遍前半段。
 * 2. **「不要重复已写内容」** —— 明确禁止复述。实测这两个要求要分开写：
 *    只写「继续」时约一半的情况仍会带上前半段。
 * 3. **「不要重新开头、不要加客套」** —— 模型倾向于在续写处补一句
 *    「好的，接下来我来说明…」。那是新段落的首句，接在半句话中间读起来是断的。
 *
 * ## 为什么用中文
 *
 * 与系统提示、界面文案同语种。多语种用户的实际输入通常是中文，
 * 中文指令能让模型保持同一语域 —— 用英文指令时它偶尔会切成英文续写。
 *
 * ## 为什么不加「如果已经写完就说明」
 *
 * 那样会给模型一个「偷懒出口」：它常常直接回「内容已经完整」，
 * 而用户点继续显然认为没写完。宁可让它硬着头皮往下写，也不要它替用户判断。
 */
private const val CONTINUE_HINT: String =
    "请接着你上一条回复的内容继续往下写。" +
        "不要重复已经写过的部分，不要重新开头，不要加「好的」「接下来」这类过渡语，" +
        "直接从断掉的地方接着写。"

/**
 * 单条消息最多附几张图。
 *
 * ## 为什么是 4
 *
 * 三个约束的交点：
 *  - **协议侧**：主流视觉模型单条消息的图片上限在 4~20 张不等，
 *    取 4 能覆盖绝大多数网关而不触发 400；
 *  - **体积侧**：4 张 1024px JPEG（各约 300KB）经 base64 后约 1.6MB，
 *    加上文本历史仍在多数网关的 10MB 限制内；
 *  - **实用侧**：「对比两张」「看这四张的差异」是真实需求，
 *    再多用户也不会在一轮里问，而是分几轮。
 */
private const val MAX_ATTACHMENTS = 4
