package dev.praxis.feature.chat

import dev.praxis.core.ai.AiError
import dev.praxis.core.ai.CompactedContext
import dev.praxis.core.ai.ThinkingEffort

/**
 * 消息在 UI 上的角色（与 core:ai 的 ChatRole 分开：UI 多一个 Error 态）。
 *
 * ## 为什么 `Summary` 是**独立角色**而不是复用 `Assistant`（2026-10-24 第 4 轮）
 *
 * 上一轮「插入摘要」把结果做成 `Assistant` 气泡，理由是「UI 不必加一套渲染
 * 分支」。那是错的，代价有三处：
 *
 *  1. **它看起来像一句 AI 回话**。用户在对话里看到一条普通助手气泡，
 *     分不清「这是模型刚说的」还是「这是我插入的历史压缩块」。
 *  2. **菜单项不对**。`Assistant` 会得到「重新生成 / 继续 / 回复 / 修改记忆」——
 *     而这些对一段**已经固化的摘要**全是错的：重新生成会重跑一次摘要、
 *     回复会去回一段摘要、修改记忆会去编辑摘要正文。
 *  3. **上游本来就有独立类型**。Operit 的 `sender = "summary"`
 *     （`ChatScrollNavigator.kt:1172` 有专门的 `chat_sender_summary` 文案），
 *     有三处独立渲染分支（`ChatArea.kt:752/784`）。
 *
 * 所以加一个独立角色，视觉与菜单都按类型分开。
 */
enum class BubbleRole {
    User,
    Assistant,
    Tool,
    Error,

    /**
     * 对话摘要块（用户在长按菜单里点「插入摘要」生成）。
     *
     * 它不是模型说的话，也不是工具结果 —— 是**用户主动插入的一段历史压缩**。
     * 视觉上做成带边界的块（而不是气泡），菜单只给「复制 / 删除」。
     */
    Summary,
}

/**
 * 一条消息的 UI 投影。
 *
 * [id] 用 Long 自增而非随机 UUID：列表 key 要稳定且可比较，
 * 随机值在重组时不会变，但排序时会乱。
 */
data class ChatBubble(
    val id: Long,
    val role: BubbleRole,
    val text: String,
    /**
     * 消息时间戳（毫秒）。
     *
     * ## 为什么这个字段是「消息跳转」的前提
     *
     * Operit 的整套跳转链路（导航浮条的居中判定、定位器对话框、
     * `ChatMessageLocatorPreview`、`onJumpToMessageTimestamp`）全部以
     * **时间戳**为键，而不是列表下标。原因很实在：下标在流式追加、
     * 历史裁剪（`HISTORY_WINDOW`）、重新生成之后都会变，而时间戳不会。
     *
     * 所以这里存库里那一行的 `createdAt`，不存「当前下标」。
     * 用 [id] 当跳转键是不行的：它是本次进程内的自增计数，
     * 重启后同一条消息会拿到完全不同的 id。
     *
     * 默认 `0L` 只用于「还没落库的内存气泡」；跳转时 `indexOfFirst`
     * 找不到时间戳为 0 的条目会退回按下标定位，不会跳错。
     */
    val timestamp: Long = 0L,
    /** 是否还在流式写入（决定是否显示光标）。 */
    val streaming: Boolean = false,
    /**
     * 工具调用信息（仅 [BubbleRole.Tool] 有值）。
     *
     * 单独一个字段而不是塞进 [text]：工具卡片要能折叠、能单独渲染耗时与风险，
     * 塞进正文就只能当纯文本，用户没法区分“模型说的话”与“工具返回的内容”。
     */
    val tool: ToolCallInfo? = null,

    /**
     * 这条消息附带的图片（仅 [BubbleRole.User] 可能非空）。
     *
     * ## 为什么要存进气泡而不是只留在 history
     *
     * 用户发完图后需要能**回看自己发了什么** —— 尤其在多轮之后，
     * 屏幕上只有一句「这是什么」，没有图就无法确认当时问的是哪张。
     * 这也是 Operit 的做法（它的消息模型里图片是消息的一部分）。
     *
     * ## 为什么存完整的 [dev.praxis.core.ai.ChatImage]（含 base64）
     *
     * 只存本地 Uri 的话，重启后可能读不到（相册项被删/权限失效），
     * 且与「history 里已经内联 base64」形成两份不同来源的真相。
     * 代价是内存：一张 1024px 的 JPEG 约 150~400KB，
     * 而 `HISTORY_WINDOW` 限制了同时载入的消息数，可接受。
     *
     * 默认空列表：保住所有 `ChatBubble(...)` 现有构造点。
     */
    val images: List<dev.praxis.core.ai.ChatImage> = emptyList(),

    /**
     * 变体组 id（对齐 Operit 的消息变体）。
     *
     * ## 为什么气泡需要知道这个
     *
     * 「重新生成」原本会**再插一条气泡**：重试三次就出现四条并排的助手回答，
     * 前三条是垃圾，而且它们都在 `history` 里会被送给模型（既费 token
     * 又干扰上下文）。
     *
     * 变体组把这些回答归成一个「位置」，界面上只有一条气泡，
     * 用 [variantIndex] / [variantCount] 在气泡底部显示 `‹ 2 / 4 ›` 切换条。
     *
     * `null` = 普通消息，不属于任何变体组（绝大多数气泡都是）。
     */
    val variantGroupId: String? = null,

    /**
     * 当前显示的是组内第几个版本（0 起）。
     *
     * ## 为什么「当前版本」不做持久化
     *
     * Operit 在消息上存 `selectedVariantIndex`（每条都存一份冗余状态）。
     * 这里改为**约定**：组内 [variantIndex] 最大的那条就是当前版本。
     *
     * 理由：
     *  - 重新生成 = 追加一个更大的 index，天然成为当前版，不需要额外写入；
     *  - 「用户回看旧版本」是短暂行为，重启回到最新版符合直觉
     *    （用户要看的是「我最后一次让它生成的结果」）；
     *  - 少一列状态就少一处「库里的 selected 指向一条已删除的版本」的失配。
     *
     * 代价写清楚：切到旧版后重启会回到最新版。这是刻意的取舍，不是遗漏。
     */
    val variantIndex: Int? = null,

    /**
     * 组内版本总数。`1`（默认）= 没有变体，UI 不画切换条。
     *
     * 存总数而不是每次现算：气泡渲染在 `LazyColumn` 里，
     * 每帧去别处查「这组有几个」会让滚动掉帧（与工具卡 summary 同一类问题）。
     */
    val variantCount: Int = 1,
)

/** 一次工具调用的 UI 投影。 */
data class ToolCallInfo(
    val name: String,
    /** 原始 JSON 参数。**不截断**：截断后无法复制出去复现。 */
    val argumentsJson: String,
    /** 工具返回体。执行中为 null。 */
    val result: String? = null,
    /** 是否失败。 */
    val isError: Boolean = false,
    /** 耗时（毫秒）。未完成时为 null。 */
    val elapsedMs: Long? = null,
)

/**
 * 发送→收尾之间的阶段（对齐 Operit `InputProcessingState` 的可用子集）。
 *
 * ## 为什么只保留四档
 *
 * Operit 有 Connecting / Processing / ExecutingTool / ToolProgress /
 * ProcessingToolResult / Summarizing / Receiving 七档。Praxis 的链路只有
 * 「连上 → 收增量 → 可能跑工具 → 续写」四段，其余阶段在本项目没有代码会进入，
 * 列出来就是永不出现的假状态。
 */
enum class ChatStage {
    /** 空闲（没有进行中的请求）。 */
    Idle,

    /** 已发出请求，还没收到第一个增量。 */
    Connecting,

    /** 正在接收流式增量。 */
    Receiving,

    /** 正在执行工具调用。 */
    ExecutingTool,

    /**
     * 正在压缩历史（上下文达到阈值）。
     *
     * ## 为什么必须是一个独立阶段
     *
     * 压缩是一次**额外的模型调用**，在长对话里可能耗时十几秒。
     * 没有这个阶段，用户会看到进度条已经超过阈值、然后界面静止十几秒，
     * 以为卡死。而他唯一能做的「取消」会连带取消掉本来要发的那一轮。
     */
    Summarizing,
}

/** 阶段对应的用户可读文案。 */
val ChatStage.label: String
    get() = when (this) {
        ChatStage.Idle -> ""
        ChatStage.Connecting -> "正在连接…"
        ChatStage.Receiving -> "正在回复…"
        ChatStage.ExecutingTool -> "正在执行工具…"
        ChatStage.Summarizing -> "正在压缩历史对话…"
    }

/** 聊天页状态。 */
data class ChatState(
    val bubbles: List<ChatBubble> = emptyList(),
    /**
     * 正在流式输出的正文（气泡 id → 当前全文）。
     *
     * ## 为什么它必须是独立字段，而不能只改 `bubbles[i].text`
     *
     * 长对话卡顿的根因就在这里。`bubbles` 是 `List`，改其中一条的文本必须
     * `copy(bubbles = 新列表)` —— 于是**引用变了**，所有以 `bubbles` 为 key
     * 的 `remember` 全部失效。下游最贵的一处是定位器预览：
     *
     * ```
     * remember(state.bubbles) { state.bubbles.map { it.toLocatorPreview() } }
     *          // toLocatorPreview 内部逐字符归一化整条正文
     * ```
     *
     * 流式一个 3000 字回复、上下文 100 条，就是每 delta 重算 100 条消息的
     * 全文归一化 → 每秒几十次。**对话越长越卡**，且与正在输出的那条多长无关。
     *
     * 拆出这个字段后：
     *  - `bubbles` 的引用在流式期间**完全不变** → 下游全表重算消失；
     *  - 只有「读这个 map 的那条气泡」重组，其余气泡连组合都不用重跑。
     *
     * ## 为什么只装正在流式的那几条，而不是所有消息文本
     *
     * 已完成的消息文本以 `bubbles[i].text` 为准（那是落库的值，跨重启稳定）。
     * 这里只放「还没定稿」的，流结束时就把它清掉、值合进 `bubbles`。
     * 这样内存里不会有「两份权威文本」长期共存 —— 那是更难查的一类 bug
     * （编辑消息后界面不更新，因为流式槽里的旧值还在）。
     *
     * key 用气泡 id（不是下标）：`truncateFrom` / 删除会让下标漂移。
     */
    val streamingText: Map<Long, String> = emptyMap(),
    val input: String = "",
    /**
     * 已选好、**尚未发送**的图片（多模态输入）。
     *
     * ## 为什么是「待发」而不是直接进气泡
     *
     * 用户选图的顺序是「先挑图 → 再写配文 → 一起发」。若选完立刻进气泡，
     * 就没法在发送前反悔，也无法与配文合并成同一条 user 消息
     * （协议要求文本与图片在**同一个** content 数组里）。
     *
     * ## 为什么元素是 [ChatImage] 而不是本地 Uri
     *
     * 选图那一刻就完成了「读取 + 缩放 + 编码 base64」。好处：
     *  - 发送时零 IO，不会卡住发送键；
     *  - 用户可以先把相册里的临时文件删掉，发送仍能成功；
     *  - `canSend` 的判断是纯内存的。
     *
     * 代价是选图时有一次性开销（见 `ChatImagePreparer`），
     * 但那个开销发生在用户明确点了「选图片」之后，是可感知、可接受的。
     */
    val attachments: List<dev.praxis.core.ai.ChatImage> = emptyList(),
    val isStreaming: Boolean = false,
    /** 当前错误；非 null 时显示错误气泡与重试入口。 */
    val error: AiError? = null,
    /**
     * 输入栏上方的「正在做什么」提示。
     *
     * ## 为什么这个字段必须存在
     *
     * 上一版发送后唯一的反馈是按钮变成「生成中」三个字。而真实流程里有几段
     * **明显不同的阶段**：正在连服务端 → 正在等第一个 token → 正在执行工具 →
     * 正在基于工具结果续写。这几段的耗时差一到两个数量级，
     * 用户看到「生成中」三十秒不动时的判断是「卡死了」，实际可能只是工具在跑。
     *
     * 文案与阶段一一对应，用户才知道「该等还是该掐掉」。
     * 注意：这是**过程态**，不是错误。失败仍走 [error]。
     */
    val stage: ChatStage = ChatStage.Idle,

    /**
     * 一次性操作提示（如「继续」被拒绝的原因）。
     *
     * ## 为什么不用 [error]
     *
     * [error] 的语义是「这一轮失败了」，UI 会据此渲染错误气泡 + 重试按钮。
     * 而这里要表达的是「你这个操作不适用」——没有任何请求发出去，
     * 也没有东西可重试。混用会让用户看到一条「错误」，但点重试又什么都没发生。
     *
     * ## 为什么不用 Snackbar / Toast
     *
     * 那要 UI 层持有额外状态与自动消失定时器。这里只是一个字符串：
     * UI 渲染在输入栏上方，用户下一次操作或下一条消息进来时自然被覆盖
     * （见 `continueGeneration` / `send` 里的清空）。
     *
     * 之所以放进 `ChatState` 而不是 `ChatEvent`：它需要**留在屏幕上**几秒
     * 让人读完，而 `ChatEvent` 是 Fire-and-forget 的一次性事件通道，
     * 消费掉就没了。这里宁可让它多停一会儿，也不要一闪而过。
     */
    val notice: String? = null,

    /**
     * 待发送队列（Operit 同款：生成中也能继续写，前一轮结束后自动发下一条）。
     *
     * 顺序可调（见 `PendingQueueOps.moveUp/moveDown/moveToTop`）。
     */
    val queue: PendingQueue = PendingQueue(),

    /**
     * 队列面板是否展开。
     *
     * ## 为什么展开态要放在状态里（1:1 对齐 Operit）
     *
     * 入队时**强制展开**（见 `ChatViewModel.sendOrEnqueue`）：用户刚打了一条
     * 却没看到它进列表，第一反应是「消息丢了」。展开态若由 UI 自己
     * `remember`，入队这条路径就没法把面板顶开 —— 因为 UI 不知道
     * 「这一帧的入队是用户主动的」还是「上一次折叠状态的恢复」。
     */
    val queueExpanded: Boolean = true,

    /**
     * 队列阻塞信号：上一刻是否处于「不能立刻发送」的状态。
     *
     * ## 这是自动出队的唯一触发依据
     *
     * 自动出队必须只在**阻塞→解除的跳变沿**发生一次。若改成「空闲就发」，
     * 生成中的每一帧都会判定为空闲之外的另一种状态，然后一旦空闲就反复
     * 取队首 —— 会把整队列连着发出去。
     *
     * Operit 的做法（`PendingMessageQueueStore.wasBlocked`）是记住上一刻的
     * 阻塞态，只在「之前阻塞、现在不阻塞」时返回一次 true。这里照抄。
     */
    val queueWasBlocked: Boolean = false,

    /**
     * 吃掉下一次自动出队。
     *
     * 两个必须抑制的场景：
     *  1. 用户手动发了队首（或点了「立即发送」）—— 否则紧接着又自动发一条；
     *  2. 用户点了「停止」—— 他不是想立刻发下一条，他是想把整件事停下来。
     */
    val suppressNextAutoDequeue: Boolean = false,

    /** 上下文用量（进度条 + 「3.2k / 128k」两端文案）。 */
    val contextUsage: ContextUsage = ContextUsage(),

    /**
     * 正在编辑的气泡 id；`null` 表示没有打开编辑器。
     *
     * ## 为什么编辑态要进 StateFlow（而不是只放 Composable 的 remember）
     *
     * 打开编辑器的是**长按菜单**（在气泡的 Box 里），而编辑器本身要渲染在
     * 页面顶层（否则会被 LazyColumn 的滚动裁剪）。两个组件不在同一棵子树，
     * 只能通过共享状态通信。
     *
     * 另外「保存」要落库、「重发」要清空后续消息 —— 这两件事都必须走 ViewModel，
     * 所以编辑目标（哪个气泡）天然属于 ViewModel 的状态。
     */
    val editingBubbleId: Long? = null,

    /**
     * 编辑器里的工作副本。
     *
     * ## 为什么不直接改 `bubbles` 里那条
     *
     * 编辑是**可取消**的。直接改原气泡会让用户点「取消」后内容已经变了 ——
     * 那是最糟的一种「取消」。所以编辑期间只在 [editingText] 上改，
     * 点保存时才写回；点取消直接清空这个字段。
     */
    val editingText: String = "",

    /**
     * 编辑器是否显示「保存并重发」。
     *
     * 只有用户消息为 true —— 见 `MessageAction.Edit` 分支的 KDoc。
     */
    val editingCanResend: Boolean = false,

    /**
     * 正在查看「消息信息」的气泡 id；`null` 表示不显示信息弹窗。
     *
     * ## 为什么信息弹窗需要独立字段（而不是复用编辑态）
     *
     * 两者互斥且语义不同：编辑是可改的工作副本，信息是只读的元数据快照。
     * 复用同一个字段会让「打开信息 → 再长按编辑」把两者搅在一起。
     */
    val infoBubbleId: Long? = null,

    /**
     * 复制预览表的内容；`null` 表示不显示。
     *
     * ## 为什么复制不是「算完直接进剪贴板」（1:1 对齐上游）
     *
     * 上游点「复制」后先算出 `MessageCopyContent`（两份：洗白的 Markdown 与
     * 只剥元数据的 XML 原文），然后弹 `MessageCopyPreviewBottomSheet`，
     * 让用户在**纯文本 / Markdown 源 / XML 源**三档里选一档再复制。
     *
     * 这个「多一步」不是多余的：模型的回复里有大量结构化标签，有时候用户
     * 要的就是带标签的原文（例如「照这个格式再生成一遍」）。直接复制洗白版
     * 就把这条路径堵死了。
     *
     * 把内容放进 state（而不是让 UI 自己 remember）是因为要复制的那条消息
     * 在 `bubbles` 里，UI 层拿到的是 `ChatBubble`，而「洗白后的两份文本」
     * 是纯函数结果 —— 放在 ViewModel 里算，UI 只负责展示与写剪贴板。
     */
    val copyPreview: ChatCopyContent? = null,

    /**
     * 「回复」的目标气泡 id；`null` 表示没有回复目标。
     *
     * ## 为什么存 id 而不是存整条消息
     *
     * 回复目标可能在等待期间被删掉（用户回滚 / 删除）。存 id 就能在发送时
     * `firstOrNull { it.id == id }` 现查，查不到就静默不带 reply 标签 ——
     * 存快照则会带着一条已经不存在的消息去回复。
     */
    val replyToBubbleId: Long? = null,

    /**
     * 等待确认删除的气泡 id；`null` 表示没有待确认的删除。
     *
     * ## 上游有确认弹窗，这里也必须补上
     *
     * 上游 `ChatArea.kt` 的删除项点下去是 `showDeleteMessageConfirmDialog = true`，
     * 不是直接删。Praxis 旧实现直接 `removeBubble` —— 长按菜单里误触一次，
     * 一条几百字的回复就没了，且没有撤销。
     */
    val pendingDeleteBubbleId: Long? = null,

    /** 思考强度（模型页可配；这里给出当前轮实际使用的值以便回显）。 */
    val thinkingEffort: ThinkingEffort = ThinkingEffort.None,

    /**
     * 当前生效的压缩摘要；`null` 表示还没压缩过。
     *
     * ## 为什么要把摘要放进 UI 状态（1:1 对齐 Operit 的摘要消息）
     *
     * 上游把摘要存成一条 `sender == "summary"` 消息，在消息流里渲染成一条
     * **细分割线 + 「历史对话摘要」徽标**，点开能看全文。
     *
     * 这个显示不是装饰：用户看到自己的消息「消失」而不知道发生了什么，
     * 会以为程序丢数据。有了这条分割线，「之前的内容被折叠」这件事
     * 就是可见、可点开、可确认的事实。
     *
     * ## 为什么存整个 [CompactedContext] 而不是只存文本
     *
     * 三个字段 UI 都要用：
     *  - `summary` → 点开看的全文；
     *  - `coveredCount` → 徽标上写「已折叠 N 条」；
     *  - `summaryTokens` → 写「压缩为 X token」，让用户知道省了多少。
     */
    val compacted: CompactedContext? = null,

    /**
     * 摘要全文弹窗是否展开。
     *
     * 与 `infoBubbleId` / `copyPreview` 同层：三者互斥，同一时刻只开一个。
     */
    val showSummaryDetail: Boolean = false,
) {
    /**
     * 能否发送。
     *
     * ## 为什么判据是「有文字 **或** 有图片」
     *
     * 多模态下「只发图不写字」是合法且常见的用法（拍张照问「这是什么」）。
     * 旧判据 `input.isNotBlank()` 会让这种消息发不出去 —— 用户选完图发现
     * 发送键是灰的，会以为选图失败。
     */
    val canSend: Boolean
        get() = (input.isNotBlank() || attachments.isNotEmpty()) && !isStreaming

    /** 是否处于「已发出、未收尾」的中间态（决定发送键显示停还是发送）。 */
    val isBusy: Boolean get() = isStreaming || stage != ChatStage.Idle
}

/** 一次性事件（不该进状态，否则重组会重复触发）。 */
sealed interface ChatEvent {
    /** 流式写入的增量。 */
    data class Delta(val bubbleId: Long, val text: String) : ChatEvent

    /** 流结束。 */
    data class Finished(val bubbleId: Long) : ChatEvent

    /** 失败。 */
    data class Failed(val bubbleId: Long, val error: AiError) : ChatEvent
}
/**
 * 上下文用量。
 *
 * 两个数字都按 token 计：
 *  - [used] 本轮真正发出去的量（含 system / 记忆 / 摘要）；
 *  - [limit] 用户为当前模型填的上限。
 *
 * ## 为什么 limit 是用户填的而不是内置表
 *
 * 同一个模型 ID 在不同服务商那里上限不同（有的 128k、有的 32k、
 * 有的按套餐给），内置表必然过时且会误报。宁可让用户在模型页填一次，
 * 也不要给出一个“系统认为你超了但其实能发”的假告警。
 * 填 0 表示未知，此时只显示已用量、不画比例。
 */
data class ContextUsage(
    val used: Int = 0,
    val limit: Int = 0,
    /** 触发压缩的阀值（0..1）。用于在进度条上画一条分界线。 */
    val threshold: Float = 0.75f,
) {
    /** 占比 0..1。limit 未知时为 0（不画进度）。 */
    val ratio: Float
        get() = if (limit <= 0) 0f else (used.toFloat() / limit).coerceIn(0f, 1f)

    /** 是否已达压缩阀值（且确实有上限）。 */
    val atThreshold: Boolean
        get() = limit > 0 && ratio >= threshold

    /** 左侧文案：已用量。 */
    val usedLabel: String get() = dev.praxis.core.ai.TokenEstimator.format(used)

    /** 右侧文案：上限。未知时显示“？”而不是 0。 */
    val limitLabel: String
        get() = if (limit <= 0) "?" else dev.praxis.core.ai.TokenEstimator.format(limit)
}

/**
 * 长按消息弹出的菜单动作（1:1 对齐 Operit `ChatArea.kt:706-1200` 的 14 项）。
 *
 * ## 顺序即上游顺序
 *
 * 上游把「高频、无副作用」的放上面，「破坏性」的放最后。枚举声明顺序
 * 不影响菜单顺序（顺序由 `actionsForBubble` 决定），但为了读代码时能
 * 对照上游，这里也按同样次序排列。
 *
 * ## 上游 14 项 → 本枚举的映射与取舍
 *
 * | 上游 | 这里 | 说明 |
 * |---|---|---|
 * | 复制 | [Copy] | 语义相同，但上游**不直接写剪贴板**，先开预览表 → 见 [Copy] 的 KDoc |
 * | 朗读 | （无） | 需要 TTS 引擎，Praxis 没有 → 见下方「不做」 |
 * | 插件菜单项 | （无） | 需要 `ChatMessageMenuItemRegistry`（插件 DSL 菜单注册） |
 * | 编辑并重发 | [Edit] | 已接编辑器 |
 * | 回滚到此处 | [RollbackHere] | 已接：截断 + 正文回填输入框 |
 * | 重新生成 | [Regenerate] | 已接 |
 * | 修改记忆 | [EditAssistant] | 已接编辑器（只保存，不重发） |
 * | 删除当前变体 | （无） | 需要消息变体模型（`variantCount`） |
 * | 删除 | [Delete] | 已接，**补上确认弹窗**（上游有） |
 * | 回复 | [Reply] | 已接：置回复目标 + 发送时注入 reply 标签 |
 * | 插入摘要 | [InsertSummary] | 已接 |
 * | 创建分支 | （无） | 需要分支表 / 分支会话模型 |
 * | 信息 | [ShowInfo] | 已接 |
 * | 多选 | （无） | 需要多选模式状态机 + 批量操作 |
 *
 * `Quote`（引用）**已删除**（2026-10-24 第 4 轮）。原因不是「上游没有」，
 * 而是它的功能被 `Reply` 完整覆盖，两者并存只会让用户在菜单里看到
 * 两个都叫「引用别人说的话」的项而不知道该点哪个：
 *
 * | | `Quote`（已删） | `Reply`（保留） |
 * |---|---|---|
 * | 效果 | 洗白正文 → `> ` 前缀 → 塞进输入框 | 记回复目标 → 输入栏显示预览条 → 发送时注入 `<reply_to>` |
 * | 模型看到 | 一段普通 Markdown 引用，**不知道**这是用户特意引用的 | 结构化标签，**明确知道**在针对哪一条 |
 * | 上游有吗 | 没有 | 有（`reply_message`） |
 *
 * 结论：`Reply` 是超集，`Quote` 只是它的弱化版。删掉后菜单更短，
 * 且每一项都对应一个上游存在的动作。
 *
 * 「不做」的四项都是**底层能力缺失**，不是懒得做：留着就是点了没反应的
 * 假按钮，与本项目「不放假按钮」的原则冲突。
 */
enum class MessageAction(
    val label: String,
) {
    /**
     * 复制。
     *
     * ## 为什么不是「直接写剪贴板」（上游的真实逻辑）
     *
     * 上游点「复制」后做两件事：
     *  1. 算出 `MessageCopyContent(markdownSource, xmlSource)`；
     *  2. 打开 `MessageCopyPreviewBottomSheet`，让用户选**纯文本 / Markdown 源 /
     *     XML 源**三种形态之一再复制。
     *
     * 直接 `clipboard.setText(洗过的正文)` 会丢掉后两种：用户想把带 `<tool>`
     * 标记的原文贴回去时没有入口。所以这里改为「打开预览表」，
     * 与上游一致。
     */
    Copy("复制"),

    /**
     * 回复这条消息（对齐上游「回复」，**仅助手消息**）。
     *
     * ## 为什么只给助手消息（与上游同判据）
     *
     * 上游 `ChatArea.kt:1076` 的判据是 `if (message.sender == "ai")`。
     * 语义上「回复」= 「就你说的这句话，我再说点什么」——
     * 针对 AI 的输出。用户自己的消息不需要「回复自己」。
     *
     * 旧实现给两侧都开了，看似更灵活，实际造成两个问题：
     *  1. 用户回自己的消息会把同一条内容以 `<reply_to>` 再喂一遍模型，
     *     纯属浪费 token；
     *  2. 菜单项数不一致，与上游对不上。
     *
     * ## 它是怎么把「针对哪一条」告诉模型的
     *
     * 发送时注入结构化标签（格式照抄上游
     * `AIMessageManager.buildUserMessageContent`）：
     *
     * ```xml
     * <reply_to sender="AI" timestamp="1699999999999">正在回复上一条消息"被引用正文"</reply_to>
     * ```
     *
     * 正文先削 XML 标签再截断 100 字 —— 见 `ChatViewModel.buildReplyTag`。
     */
    Reply("回复"),

    /** 把这条内容重新填回输入框（用户/助手都适用）。 */
    Edit("编辑重发"),

    /**
     * 回滚到此处（对齐上游「回滚到此处」，仅用户消息）。
     *
     * ## 上游的完整语义（`ChatViewModel.rollbackToMessage`）
     *
     * 1. 校验目标必须是用户消息；
     * 2. 回滚**工作区**到这条消息之前的快照（`WorkspaceBackupManager.syncState`）；
     * 3. 删掉这条及其之后的所有消息（`truncateChatHistory`）；
     * 4. 把这条正文洗掉标签后**填回输入框**（`updateUserMessage`）；
     * 5. toast「已回滚，消息已放回输入框」。
     *
     * Praxis 没有工作区快照（`WorkspaceBackupManager` 那套），所以第 2 步
     * 跳过 —— 其余四步 1:1。第 4 步是关键：回滚完正文回到输入框，
     * 用户改一改就能重发，这正是这个功能存在的理由。
     */
    RollbackHere("回滚到此处"),

    /**
     * 修改这条助手消息（1:1 对齐 Operit 的「修改记忆」）。
     *
     * ## 为什么不能和 [Edit] 合并成一个
     *
     * 两者打开的是同一个编辑器，但**保存语义不同**：
     *  - [Edit] 只给用户消息，且编辑器显示「保存并重发」（改的是自己的提问）；
     *  - [EditAssistant] 只给助手消息，只显示「保存」。
     *
     * 菜单文案也不一样（上游分别叫「编辑并重发」与「修改记忆」）。
     * 合成一个枚举值就得在 UI 层再按角色分叉，反而更容易漏。
     */
    EditAssistant("修改记忆"),

    /** 重新生成（仅助手，从这条之后重来）。 */
    Regenerate("重新生成"),

    /**
     * 继续（仅助手与工具消息）：保留已有内容，让模型接着往下写。
     *
     * ## 为什么它不能复用 [Regenerate]
     *
     * 两者对「已有内容」的处理**相反**：
     *
     * | | [Regenerate] | [Continue] |
     * |---|---|---|
     * | 已有回答 | **丢弃**（truncateFrom） | **保留** |
     * | 历史里的这条 | 删掉 | 留着 |
     * | 模型看到 | 只有提问 | 提问 + 已写的那半截 |
     * | 用户意图 | 「重写一遍」 | 「接着说」 |
     *
     * 把「继续」做成 `truncateFrom` 后再发一轮，等于让模型从头再答一次 ——
     * 用户点「继续」时会得到一段**完全不同**的回答，而不是接着上文写。
     * 这是语义级错误，不是参数调优能掩盖的。
     *
     * ## 「继续」为什么不往历史里插一条假用户消息
     *
     * 最省事的实现是 `history += ChatMessage(User, "请继续")`，但那会破坏
     * 一条全局不变量：`truncateFrom` / `removeBubble` 都靠
     * 「history 与 bubbles 按条数对齐」来裁剪（见它们的实现）。
     * 多插一条不在 UI 里显示的历史，之后每次删消息都会**永久错位**：
     * 删 A 结果删掉了 B 的上下文。
     *
     * 所以「继续」的提示词是**仅本次请求追加、不落库、不进 history** 的
     * 瞬时物料（见 `ChatViewModel.continueGeneration` 里对
     * `buildPreparedMessages` 结果的处理）。
     *
     * ## 为什么工具消息也给这一项
     *
     * 工具卡片之后经常「卡住」：模型发起调用、结果回来了，但没写总结就断了
     * （网络中断 / 用户点了停止）。此时该条工具结果已经在 history 里
     * （`runToolCalls` 里 `history += ChatMessage(Tool, ...)`），
     * 点「继续」让模型基于它写总结是**有效**的 —— 而不是重跑工具。
     */
    Continue("继续"),

    /**
     * 把这条内容作为摘要插入上下文。
     *
     * 对齐 Operit 的「插入摘要」：用户挑一条消息，让模型把它压缩成一段摘要。
     * Praxis 已有 `CompactedContext` / 摘要模型链路，所以这一项能真接上。
     */
    InsertSummary("插入摘要"),

    /** 查看这条消息的元信息（角色 / 时间 / 字符数 / 工具名）。 */
    ShowInfo("消息信息"),

    /** 删除这条（仅本地）。 */
    Delete("删除"),
}

/**
 * 哪些角色能用哪些动作 —— **已迁到** `menu/MessageContextMenu.kt` 的
 * [dev.praxis.feature.chat.menu.actionsForBubble]。
 *
 * ## 为什么删掉这份（而不是保留旧签名转发）
 *
 * 旧版是 `actionsFor(role)`，只按角色分、不接触消息本身。而 Operit 的菜单
 * 需要看更多字段（变体数、是否隐藏等）。新的 `actionsForBubble(bubble)`
 * 拿到完整消息，语义更强。
 *
 * 留着两个函数必然分叉：改了一处忘另一处，菜单就会「有时少一项」。
 * 所以直接删掉，不保留兼容层（内部 API，调用点只有一个）。
 */

/**
 * 复制时清洗正文（1:1 移植 Operit `cleanMessageContentForCopy`）。
 *
 * ## 为什么"复制"要这么麻烦
 *
 * 模型的回复里夹着大量内部标记：`<think>`、`<tool>`、`<tool_result>`、
 * `<status>`、`<emotion>`、`<attachment>`、`<workspace_attachment>`。
 * 直接 `bubble.text` 复制出去，粘到别处是一堆半截 XML，用户还得手动删。
 *
 * 这个函数把它们全部移除，只留 Markdown 正文。
 *
 * ## 供应商元数据为什么也要删
 *
 * `<meta provider="gemini:thought_signature">…</meta>` 这类内容是**给下一轮
 * 请求用的**（思考签名 / Responses 协议载荷），对人毫无意义，但 base64 很长
 * —— 粘进聊天框会占掉几百字符。
 *
 * ## 与「读取正文」的区别
 *
 * 朗读 / 引用 / 摘要都应当用**同一份**清洗结果，否则用户会听到思考内容，
 * 或者引用到一堆标签。所以这几个动作统一走本函数。
 */
fun cleanMessageContentForCopy(content: String): String {
    return content
        // ① 先剥供应商协议元数据：它们包着 base64，后面几步的正则扫它们没意义。
        .let(ChatMarkupRegex::removeGeminiThoughtSignatureMeta)
        .let(ChatMarkupRegex::removeOpenAiResponsesProtocolMeta)
        // ② 逐类移除内部标签（成对 + 自闭合两种形态都要清）。
        .replace(ChatMarkupRegex.statusTag, "")
        .replace(ChatMarkupRegex.statusSelfClosingTag, "")
        .replace(ChatMarkupRegex.thinkTag, "")
        .replace(ChatMarkupRegex.thinkSelfClosingTag, "")
        .replace(ChatMarkupRegex.searchTag, "")
        .replace(ChatMarkupRegex.searchSelfClosingTag, "")
        .replace(ChatMarkupRegex.toolTag, "")
        .replace(ChatMarkupRegex.toolSelfClosingTag, "")
        .replace(ChatMarkupRegex.toolResultTag, "")
        .replace(ChatMarkupRegex.toolResultSelfClosingTag, "")
        .replace(ChatMarkupRegex.emotionTag, "")
        .replace(ChatMarkupRegex.workspaceAttachmentTag, "")
        .replace(ChatMarkupRegex.attachmentTag, "")
        .replace(ChatMarkupRegex.attachmentSelfClosingTag, "")
        // ③ 最后削多媒体链接块。
        //
        // ## 为什么这一步在标签清理**之后**
        //
        // `<link type="image" id="x">` 本身是自闭合/成对标签，但它内部的
        // 内容可能是几百 KB 的 base64。放在最后做，是为了先让前面的正则
        // 把外层结构拆掉再扫；同时顺序与上游一致（上游也是 attachment 之后
        // 才调 MediaLinkParser）。
        //
        // 两个函数必须**都**调：`removeImageLinks` 只管 image，
        // `removeMediaLinks` 只管 audio/video/file。少调一个就漏一类。
        .let(ChatMarkupRegex::removeImageLinks)
        .let(ChatMarkupRegex::removeMediaLinks)
        .trim()
}

/**
 * 复制时保留结构化标记的版本（1:1 移植 Operit `cleanMessageContentForXmlCopy`）。
 *
 * 只剥供应商元数据，其余标签原样保留。
 *
 * ## 什么时候该用它
 *
 * 用户想把**带标签的原始内容**贴回给模型（例如「照这个格式再来一遍」）。
 * 此时把 `<tool>` 清掉就丢了关键信息。
 *
 * 上游的做法是同时准备两份（markdownSource / xmlSource）交给上层按场景取用，
 * 这里提供同样两份，用 [ChatCopyContent] 打包。
 */
fun cleanMessageContentForXmlCopy(content: String): String {
    return content
        .let(ChatMarkupRegex::removeGeminiThoughtSignatureMeta)
        .let(ChatMarkupRegex::removeOpenAiResponsesProtocolMeta)
        .trim()
}

/**
 * 一条消息的两种可复制形态（对齐 Operit `MessageCopyContent`）。
 *
 * 打包成一个类型而不是两个独立函数调用：调用点必须**同时**算出两份，
 * 否则「复制」与「复制原始标记」两个入口会拿到不一样的清洗逻辑。
 */
data class ChatCopyContent(
    /** 洗干净的 Markdown 正文（默认复制这一份）。 */
    val markdownSource: String,
    /** 保留内部标签的原文（仅剥供应商元数据）。 */
    val xmlSource: String,
) {
    companion object {
        fun of(raw: String): ChatCopyContent = ChatCopyContent(
            markdownSource = cleanMessageContentForCopy(raw),
            xmlSource = cleanMessageContentForXmlCopy(raw),
        )
    }
}
