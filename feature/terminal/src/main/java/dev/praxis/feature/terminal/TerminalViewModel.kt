package dev.praxis.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.praxis.core.terminal.TerminalBuffer
import dev.praxis.core.terminal.TerminalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 回车换行。用码位拼接而不是转义字面量：生成/复制环节会把转义序列解成真实控制字符。 */
private val CRLF: String = 13.toChar().toString() + 10.toChar()

/**
 * 终端命令执行端口。
 *
 * 与 `feature:chat` 的 `ChatPersistence` 同一套思路：**端口在里、实现在外**。
 * `feature:terminal` 是纯 UI + 缓冲逻辑，不能碰 `ProcessBuilder`、不能碰 Android Context；
 * 真正怎么起 shell 由 `:app` 决定（本轮为 `:app/ShellCommandRunner`）。
 *
 * 好处：终端页的会话逻辑可以单测（给一个假 Runner 就行），而且以后换执行方式
 * （比如接 proot、接 adb 远端）只需要换实现，页面一行不动。
 */
interface CommandRunner {
    /**
     * 执行一条命令，把 stdout/stderr 边读边回调。
     *
     * 注意用回调而不是「返回 String」：交互式命令可能跑很久，等它结束才出字就不叫终端了。
     *
     * @param onOutput 收到一块输出（应为已解码的文本；ANSI 序列原样保留，由扫描器处理）。
     * @return 进程退出码。
     */
    suspend fun run(command: String, onOutput: (String) -> Unit): Int

    /**
     * 中断当前正在执行的命令。
     *
     * ## 为什么归到端口里而不是在 VM 里 `cancel()` 协程
     *
     * 只取消协程会留下**还活着的子进程**：`withContext(Dispatchers.IO)` 被取消后，
     * `Process` 仍跑着，且它的输出流没人读 → 写满管道缓冲后永久阻塞（僵尸）。
     * 必须由实现方负责 `destroy()`，所以中断是端口能力。
     *
     * 默认空实现：纯 mock 的测试不需要它。
     */
    fun cancel() = Unit
}

/**
 * 终端页 ViewModel。
 *
 * ## 三个必要的边界
 *
 * 1. **缓冲在 VM 里，不在 Composable 里**：旋转屏幕、切页返回都不能丢已输出的内容。
 * 2. **输出回调要立刻同步进缓冲**：如果先把 chunk 攒到协程里再刷，用户会看到输出成块跳。
 *    `TerminalSession.feed` 是纯同步的，直接在 IO 线程调用即可。
 * 3. **同一时刻只允许一条命令**：终端页不是并发执行器，同时跑两条会让输出交错、
 *    提示符错位。命令未结束时再提交，直接忽略（并给出提示）。
 *
 * ## 多会话（2026-10-21 新增）
 *
 * 对齐 Operit 终端的顶部会话标签：可以开多个会话、切换、关闭。
 *
 * **每个会话有自己的 `TerminalSession`（屏幕 + 回滚缓冲）与输入框内容**，
 * 但**共用同一个 [CommandRunner]** —— 底层是一条 shell 进程通道，
 * 所以 `running` 是**全局**的：A 会话在跑命令时，B 会话提交会被拒绝。
 * 这不是偷懒：并行执行会让两个会话的输出在同一个 runner 回调里交错，
 * 那正是「输出串台」bug 的来源。要真并行，得先让 runner 支持多进程句柄。
 */
class TerminalViewModel(
    private val runner: CommandRunner,
    columns: Int = DEFAULT_COLUMNS,
    rows: Int = DEFAULT_ROWS,
    /**
     * 回滚历史行数上限（第 12 项缺陷：设置页可调）。
     *
     * 由 `:app` 从 `SettingsRepository.terminalSettings` 读出后注入。
     * 放在构造参数里而不是可变的 var：缓冲区在创建时就把上限交给
     * `TerminalBuffer`，中途改它要么需要「重排已有历史」，要么只是
     * 影响新建会话 —— 两种都容易产生「设置了但没生效」的错觉。
     * 现在的行为明确：改设置后**新建的会话**用新上限，已有会话保持原值
     * （已有历史不能因为把上限调小而突然被截断）。
     */
    private val maxScrollback: Int = TerminalBuffer.DEFAULT_SCROLLBACK,
    /**
     * 新建会话时是否写首屏字符画。
     *
     * 同样在创建时固定：`clear` 之后要不要重画由它决定，
     * 所以这是一个「策略」而不是「一次性动作」。
     */
    private val showBanner: Boolean = true,
    /**
     * 执行命令用的调度器。
     *
     * 之所以参数化而不是写死 `Dispatchers.IO`：
     * 纯 JVM 单测里 `Dispatchers.IO` 是**真的线程池**，`withContext` 会真跨线程，
     * 测试断言会在命令结束之前就跑完（典型症状：退出码行“时有时无”）。
     * 单测传 `UnconfinedTestDispatcher()` 后整个链路同步完成，断言才确定。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 会话快照持久化端口；null 表示不落盘（单测/预览）。
     *
     * ## 为什么可空
     *
     * `TerminalViewModel` 的现有单测全部只关心「提交 → 输出 → 历史」这条链路，
     * 强制传一个假实现会让它们全都要改。可空 + 空实现的取舍在这里是合适的：
     * 不落盘时行为与改动前完全一致。
     */
    private val snapshotPort: TerminalSnapshotPort? = null,
    /**
     * 真终端端口。null / [NoPtySessionPort] 时退回「一次一条命令」模式。
     *
     * ## 为什么用可空 + 默认值而不是必填
     *
     * 现有单测全部只构造 `TerminalViewModel(runner = 假Runner)`。
     * 强制传一个假 Pty 会让那 30 多条用例全改一遍，而那些用例与真终端
     * 毫无关系。可空 + 默认值的好处是：不传时行为与改动前**完全一致**。
     */
    private val ptyPort: PtySessionPort? = null,
) : ViewModel() {

    /** 该会话对应的 PTY 会话 id（管道模式为 null）。 */
    /**
     * tab id → PTY 会话 id。
     *
     * 用 `ConcurrentHashMap`：写发生在 `viewModelScope`（主线程）与
     * `catch` / 快照恢复（可能在 IO 恢复点），读发生在 UI 点击与泵线程，
     * 普通 `mutableMapOf` 在跨线程读写时可能拋 `ConcurrentModificationException`。
     */
    private val ptySessionIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 当前是否走真终端（端口存在且已成功开会话）。 */
    val isRealTerminal: Boolean get() = ptyPort != null && ptyPort !== NoPtySessionPort


    /** 一个会话槽。`title` 可重命名，`session` 持有屏幕与缓冲。 */
    private class Tab(
        val id: String,
        var title: String,
        val session: TerminalSession,
    ) {
        var input: String = ""

        /**
         * 本会话执行过的命令（**旧的在前、新的在后**）。
         *
         * ## 为什么放在 Tab 里而不是 VM 全局
         *
         * 真实终端的命令历史是**按 shell 会话**算的：开两个窗口，`↑` 各自翻各自的。
         * 放全局会让 A 会话的历史出现在 B 会话里 —— 用户按 `↑` 拿到一条
         * 他根本没在这个会话敲过的命令，会以为串台了。
         *
         * ## 为什么要上限
         *
         * 键盘的 `↑`/`PGUP` 只在几十条范围内真正有用，而无限增长会
         * 随使用时长稳定泄漏内存（每条命令平均几十字节，长期用下来是可观数字）。
         * [HISTORY_LIMIT] 取 200：够翻，且总量可控。
         */
        val history: MutableList<String> = mutableListOf()

        /**
         * 屏幕行快照缓存（行号 → 上一次生成的 [TerminalLine] + 它的版本号）。
         *
         * ## 为什么必须有这个缓存（终端打字卡顿的根因，2026-10-24）
         *
         * `publish()` 每次都会调 `linesOf` 重建整个屏幕，而 `onInputChange`
         * **每按一个键**都会 `publish()`。原实现下每敲一个字符就分配
         * `行数 × 列数` 个 `RenderCell`（200×80 = 16000 个对象），
         * 加上随之而来的 GC，输入延迟肉眼可见。
         *
         * 现在按行缓存：`linesOf` 逐行比对 `buffer.rowVersion(row)`，
         * 版本没变就**复用同一个 `TerminalLine` 对象**。
         *
         * ## 复用对象为什么真的能省（不只是省分配）
         *
         * `TerminalRow` 里是 `remember(visible) { buildAnnotatedLine(visible) }`。
         * 当 `TerminalLine` 是**同一个引用**时，`visible` 由同一个 `cells` 列表
         * 得出、`data class` 判等直接短路（先比引用）→ `buildAnnotatedLine`
         * 完全不执行。否则即使内容没变，也要重跑一遍 span 合并与
         * `AnnotatedString` 构建（每行几十个 span）。
         *
         * ## 为什么缓存放在 Tab 而不是 `TerminalUiState`
         *
         * `TerminalUiState` 每次 `publish()` 都是**新实例**，缓存放进去
         * 等于每帧清空。必须挂在生命周期与 session 一致的对象上，
         * 而 `Tab` 正是那个对象（切会话各一份，关闭会话一起释放）。
         *
         * ## 容量
         *
         * 只缓存屏幕区（`buffer.rows` 行）。历史行（scrollback）不进缓存：
         * 它们是「已经定格的旧输出」，只在滚屏时被搬进屏幕区一次，
         * 之后就不再变 —— 而且 scrollback 可以有上千行，
         * 缓存它的收益远小于内存代价。
         */
        val rowCache: Array<VersionedLine?> = arrayOfNulls(session.buffer.rows)

        /**
         * 上一次发布出去的行列表（只有**活动会话**会更新它）。
         *
         * ## 为什么非活动会话也要留着这份
         *
         * `publish()` 会给**所有** tab 生成 `TerminalTab`（因为它们都要进
         * `state.tabs`），而 `TerminalTab` 有 `lines` 字段。
         *
         * 但 UI 只用活动会话的 `lines`（`state.active.lines`）——
         * 标签条读的是 `title` / `id`。所以为非活动会话重建整屏行是纯浪费：
         * 每按一个键就为每个后台会话重建一次（200 行 × 80 格）。
         *
         * 这里缓存上一次的列表，非活动会话直接复用**同一个 List 引用**。
         * 好处不只是省分配：`TerminalTab` 是 data class，
         * `lines` 引用不变时 `equals` 会短路，`StateFlow` 的
         * 「值没变就不发」也能正常工作。
         *
         * 切回某个会话时它会变成活动会话，那一刻才重建一次 —— 正是需要的。
         */
        var lastLines: List<TerminalLine> = emptyList()
    }

    private val tabList = mutableListOf<Tab>()
    private var activeIndex = 0

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    /** 全局执行闸门（见 KDoc 的多会话说明）。 */
    private var running = false

    /**
     * 待执行命令队列（**串行**执行，1:1 对齐 Operit `processNextQueuedCommand`）。
     *
     * ## 为什么从「拒绝」改成「排队」（2026-10-24 用户明确要求）
     *
     * 旧实现在 `running` 时直接回一句「上一条命令仍在执行」并丢弃。
     * 当时的理由写在注释里：「悄悄排队会让用户以为没执行」。
     * 但实测下来这个取舍更差 —— 用户连着敲三条命令（很常见的
     * `cd x` / `ls` / `cat y`），后两条被静默丢弃，得重新敲一遍。
     * 而 Operit 的终端是排队的，敲完就忘，符合「终端是命令流」的直觉。
     *
     * ## 为什么队列里只存「命令文本」而不存回调
     *
     * 入队时就已经把命令**回显**进对应会话的缓冲（见 [submit] 的队列分支），
     * 所以执行时不再需要任何额外上下文。存文本让队列可以安全地
     * 跨会话（A 会话排队的命令执行时仍写回 A 的缓冲 —— 靠 [QueuedCommand.tabId]）。
     *
     * ## 为什么必须记 tabId
     *
     * 命令排队期间用户可能切到别的会话。若执行时仍写「当前活动会话」，
     * 输出就会串到另一个 tab —— 这正是 Operit 用 sessionId 绑定的原因。
     */
    private val pending = ArrayDeque<QueuedCommand>()

    /** 一条排队中的命令。 */
    private data class QueuedCommand(
        /** 提交时所属会话；执行与输出都回写它，与「执行时的活动会话」无关。 */
        val tabId: String,
        val command: String,
    )

    /** 自增序号：会话标题按 shell 习惯叫 `Local` / `Local 2` / `Local 3`。 */
    private var tabSeq = 0

    init {
        // ## 启动顺序：先建一个默认会话（保证永远有可用 tab），再尝试恢复。
        //
        // 恢复是 suspend 的，而 `init` 不能挂起；所以这里先建空会话让 UI 立刻
        // 可用，随后异步用快照**替换**它。若快照为空（首次启动），替换不会发生，
        // 用户看到的就是带 banner 的干净终端。
        addTab()
        restoreFromSnapshot()
    }

    /**
     * 从快照恢复会话（异步）。快照为空 / 读失败时什么都不做。
     *
     * ## 为什么要「替换」而不是「追加」
     *
     * `init` 已经建了一个空会话。若把快照里的会话追加在后面，用户会看到
     * 一个空白 `Local` 外加几个恢复出来的 `Local 2/3/4` —— 多出一个幽灵会话。
     * 所以恢复成功后**整体替换** `tabList`。
     *
     * ## 为什么恢复时把 screenLines 逐行 feed 而不是直接塞 buffer
     *
     * `TerminalSession` 只暴露 `feed` / `reset` / `apply`，没有「设置光标到某行」。
     * 直接塞 buffer 需要新增一批公开 API，而 `feed(line + "\n")` 走的是
     * 与真实输出**完全相同**的路径 —— ANSI 解析、CJK 宽度、自动折行全都一致，
     * 不会出现「恢复后与运行时排版不一样」。
     *
     * 代价是颜色丢了（快照里没存样式）。这是 `TerminalSnapshotPort` 里写明的取舍。
     */
    private fun restoreFromSnapshot() {
        val port = snapshotPort ?: return
        viewModelScope.launch {
            val snapshot = runCatching { port.load() }.getOrNull() ?: return@launch
            if (snapshot.tabs.isEmpty()) return@launch

            val restored = snapshot.tabs.map { saved ->
                tabSeq++
                val session = newSession()
                // 逐行重放。末尾不额外加换行：最后一行的换行会让屏幕多滚一行。
                saved.screenLines.forEachIndexed { lineIndex, text ->
                    session.feed(text)
                    if (lineIndex != saved.screenLines.lastIndex) session.feed(CRLF)
                }
                Tab(
                    id = "t" + tabSeq,
                    title = saved.title.ifBlank {
                        if (tabSeq == 1) "Local" else "Local $tabSeq"
                    },
                    session = session,
                ).apply {
                    // 历史直接接管：它只是字符串列表，不需要经过终端解析。
                    history += saved.history.takeLast(HISTORY_LIMIT)
                    input = saved.input
                }
            }

            tabList.clear()
            tabList += restored
            // 夹到合法范围：存档里 activeIndex 可能越界（用户删过会话）。
            activeIndex = snapshot.activeIndex.coerceIn(0, tabList.lastIndex)
            publish()
            // ## 恢复出来的每个会话也要开 PTY（2026-10-29）
            //
            // 不补这一步：`init` 里 `addTab()` 给 t1 开了 PTY，随后快照把
            // 整个 tabList **替换**掉 —— t1 那个 PTY 的 sessionId 还挂在
            // `ptySessionIds` 里，但它对应的 tab 已经不存在了（孤儿 shell），
            // 而恢复出来的 t1..tN 全部没有 PTY，于是用户看到「有历史，但敲命令没反应」。
            //
            // 先把孤儿清掉，再给每个恢复的会话开一个新的。
            // 先把 init 里 addTab 开出来的 PTY 关掉（它对应的 tab 已被替换），
            // 否则那个 shell 会一直挂着（proot + sh 常驻）。
            ptySessionIds.values.forEach { sid -> runCatching { ptyPort?.close(sid) } }
            ptySessionIds.clear()
            restored.forEach { startPtySession(it) }
        }
    }

    /**
     * 把当前状态写成快照并交给端口。
     *
     * ## 为什么取「可见屏幕」而不是整段 scrollback
     *
     * 见 `TerminalSnapshotPort` 的 KDoc：scrollback 可以上千行，
     * 全存会让存档随使用时长无限增长。可见屏幕 + 命令历史已覆盖
     * 用户真正会回看的内容（`↑` 能翻命令，屏幕上能看到最近的输出）。
     */
    private fun persistSnapshot() {
        val port = snapshotPort ?: return
        // 快照要在**主线程之外**构建吗？不需要：读 buffer 只是内存遍历，
        // 200 行 × 80 格量级，几毫秒。真正的 IO 在 port 实现里（suspend）。
        val tabs = tabList.map { tab ->
            TabSnapshot(
                title = tab.title,
                screenLines = screenLinesOf(tab.session),
                history = tab.history.toList(),
                input = tab.input,
            )
        }
        val snapshot = TerminalSnapshot(tabs = tabs, activeIndex = activeIndex)
        viewModelScope.launch {
            runCatching { port.save(snapshot) }
        }
    }

    /**
     * 取会话的可见屏幕行文本（去掉行尾空白）。
     *
     * 只读 `rows` 行屏幕区，不含 scrollback —— 理由见 [persistSnapshot]。
     */
    private fun screenLinesOf(session: TerminalSession): List<String> {
        val buffer = session.buffer
        return (0 until buffer.rows).map { row ->
            buffer.line(row).joinToString("") { it.char.toString() }.trimEnd()
        }.dropLastWhile { it.isEmpty() }
    }

    /**
     * ViewModel 销毁时保存快照。
     *
     * ## 为什么还必须在别处（切后台/退出前）保存
     *
     * `onCleared` **不保证**被调用：进程被杀（系统回收、用户强停）时
     * ViewModel 不会走这个生命周期。所以 [submit] 里也会在命令结束后
     * 触发一次保存 —— 两条路径互补：
     *
     *  - `onCleared`：正常退出/换页，保存最终态；
     *  - 命令结束：进程被杀时也能留下「已执行过什么」。
     */
    override fun onCleared() {
        persistSnapshot()
        super.onCleared()
    }

    private fun newSession(): TerminalSession =
        TerminalSession(
            columns = columnsOf(),
            rows = rowsOf(),
            maxScrollback = maxScrollback,
        )

    /** 开一个新会话并切过去。 */
    fun addTab() {
        tabSeq++
        val tab = Tab(
            id = "t" + tabSeq,
            title = if (tabSeq == 1) "Local" else "Local " + tabSeq,
            session = newSession(),
        )
        if (showBanner) writeBanner(tab.session)
        tabList += tab
        activeIndex = tabList.lastIndex
        publish()
        // 真终端模式下给这个会话开一个 PTY（幂等，已在则不发）。
        // 放在 publish 之后：PTY 开会话是 suspend 的，不能让 UI 等它。
        startPtySession(tab)
    }

    /**
     * 关闭指定会话。
     *
     * 最后一个会话不允许关：终端页不能出现「一个会话都没有」的空态，
     * 那之后连输入框都没了，只能杀进程重开。
     */
    fun closeTab(id: String) {
        if (tabList.size <= 1) return
        val idx = tabList.indexOfFirst { it.id == id }
        if (idx < 0) return
        tabList.removeAt(idx)
        // 关掉对应的 PTY 会话，否则 shell 进程会一直挂着直到进程退出。
        ptySessionIds.remove(id)?.let { sessionId -> runCatching { ptyPort?.close(sessionId) } }
        activeIndex = activeIndex.coerceAtMost(tabList.lastIndex)
        publish()
    }

    /** 切换活动会话。 */
    fun selectTab(index: Int) {
        if (index !in tabList.indices) return
        activeIndex = index
        publish()
    }

    /** 活动会话 id（UI 用来做 tab 选中态）。 */
    fun activeTabId(): String = tabList.getOrNull(activeIndex)?.id.orEmpty()

    /** 输入框变化（不触发任何执行）。 */
    fun onInputChange(text: String) {
        tabList.getOrNull(activeIndex)?.input = text
        publish()
    }

    /**
     * 提交命令（串行排队，永不丢弃）。
     *
     * ## 流程（对齐 Operit `sendCommandToSession` → `processNextQueuedCommand`）
     *
     * ```
     * submit
     *   ├─ 回显 "$ 命令" 进本会话缓冲（立刻可见，用户知道已收到）
     *   ├─ 记历史（↑/↓ 数据源）
     *   ├─ pending.addLast(tabId, command)
     *   └─ pump()  ← 空闲就立刻执行队首，忙则什么都不做
     * ```
     *
     * ## 为什么回显放在队首之前
     *
     * 排队时如果等执行才回显，用户敲完三条命令后屏幕毫无动静，
     * 会以为输入被吞掉而反复敲。先回显、后执行，屏幕上的顺序
     * 也正好是「命令在前、输出在后」，与真实终端一致。
     */
    // ── 真终端模式（2026-10-29）─────────────────────────────

    /**
     * 为指定 tab 建立 PTY 会话并开始订阅输出。
     *
     * ## 为什么要「先建立、再订阅」而不是订阅里懒建立
     *
     * 订阅是冷流：`collect` 那一刻才真正执行 `callbackFlow` 体。若把
     * `open()` 也塞进去，每次重组（重进页面）都会重新执行一遍
     * `callbackFlow`，虽然 `open` 幂等不会多开会话，但每次都会多一个
     * 监听者 —— 而 `awaitClose` 只在协程取消时才摘，重组不取消协程，
     * 于是监听者会累加，同一条输出被渲染 N 次（表现为重复字符）。
     *
     * 所以：`open` 由这里显式调一次（按 tab id 幂等），订阅只负责搬运字节。
     */
    private fun startPtySession(tab: Tab) {
        val port = ptyPort ?: return
        if (ptySessionIds.containsKey(tab.id)) return
        viewModelScope.launch {
            // ## 初始尺寸用 24×80，不是 DEFAULT_ROWS(200)
            //
            // `DEFAULT_ROWS` 是**屏幕缓冲行数**（回滚 + 可视），给 PTY 会告诉
            // 内核「这个终端有 200 行高」—— `top` 会画 200 行、`less` 只翻
            // 200 行一屏。PTY 要的是可视行数，24 是终端界的默认值。
            // 真正精确的值由 UI 在 `onResize` 里量出来后覆盖。
            val sessionId = port.open(tab.title, INITIAL_PTY_ROWS, DEFAULT_COLUMNS)
            if (sessionId == null) {
                // ## 开会话失败必须说出来，不能静默降级（2026-10-30）
                //
                // 旧实现到这里直接 `return`。后果是终端**看起来完全正常**：
                // 能敲命令、`ls` 有输出 —— 但跑的是宿主自己的 `sh`，
                // 不是下载好的 Linux 容器。用户会以为「终端接的就是 shell」，
                // 而真相是容器会话压根没建起来。
                //
                // 这里把原因写到屏幕上。不做成弹窗：终端页的主信息面就是
                // 屏幕本身，用户回头看历史时也应当能看到「为什么不在容器里」。
                val why = port.lastError?.takeIf { it.isNotBlank() }
                    ?: "容器会话未能建立（原因未知）。"
                runCatching {
                    tab.session.feed(
                        "[当前不是 Linux 容器]\r\n" +
                            "原因：" + why + "\r\n" +
                            "现在输入的命令会在**宿主环境**执行，不是下载好的 Linux。\r\n" +
                            "可先去「设置 → Linux 环境」确认已安装并启动，再回到本页。\r\n",
                    )
                }
                publish()
                return@launch
            }
            ptySessionIds[tab.id] = sessionId
            // 回填一次屏幕快照：接上已有会话时历史不该空白。
            port.screen(sessionId)?.let { snapshot ->
                if (snapshot.isNotBlank()) {
                    tab.session.feed(snapshot.replace("\n", "\r\n") + "\r\n")
                    publish()
                }
            }
            port.outputFlow(sessionId)
                .onEach { bytes ->
                    // ## 为什么在 IO 线程直接 feed
                    //
                    // `TerminalSession.feed` 是纯同步纯函数（切 ANSI + 写格子），
                    // 几万字节也就毫秒级。转发到主线程再 feed 反而会攒帧、
                    // 让输出成块跳。publish 里用 StateFlow，线程安全。
                    withContext(ioDispatcher) {
                        tab.session.feed(String(bytes, Charsets.UTF_8))
                    }
                    publish()
                }
                .catch { throwable ->
                    // 流结束（会话被关）不该让整个 ViewModel scope 挂掉。
                    // 把原因写进屏幕：真终端断开时用户最需要知道「为什么没反应」。
                    val why = throwable.message ?: throwable::class.simpleName ?: "未知原因"
                    runCatching { tab.session.feed("\r\n[会话已结束：" + why + "]\r\n") }
                    // 会话已死：从映射里摘掉，后续 submit 回到管道模式而不是
                    // 往一个已关闭的 writer 里写（那只会得到一串失败）。
                    ptySessionIds.remove(tab.id)
                    publish()
                }
                .launchIn(viewModelScope)
        }
    }

    /**
     * 逐键发送（真终端模式）。
     *
     * @return false 表示当前不是真终端模式（调用方应回退到整条提交）
     */
    fun writeKey(bytes: ByteArray): Boolean {
        val port = ptyPort ?: return false
        val tab = tabList.getOrNull(activeIndex) ?: return false
        val sessionId = ptySessionIds[tab.id] ?: return false
        return port.write(sessionId, bytes)
    }

    /** 改窗口尺寸（键盘弹出 / 旋转 / 分屏）。 */
    fun resize(rows: Int, cols: Int) {
        val port = ptyPort ?: return
        val tab = tabList.getOrNull(activeIndex) ?: return
        val sessionId = ptySessionIds[tab.id] ?: return
        port.resize(sessionId, rows, cols)
    }

    /**
     * 当前会话的行列数（供 UI 计算要 resize 到多少）。
     *
     * 返回 null 表示非真终端。
     */
    fun currentPtySession(): String? =
        tabList.getOrNull(activeIndex)?.let { ptySessionIds[it.id] }

    fun submit(rawCommand: String) {
        val tab = tabList.getOrNull(activeIndex) ?: return
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        // ## 真终端分支（2026-10-29）
        //
        // 有 PTY 会话时，命令必须交给**那个 shell**，而不是交给 runner 另起
        // 一个进程。两者的差别不是实现细节，而是用户体验的全部：
        //
        //  - `cd /root` 之后 `ls`，runner 模式下每条命令都是新进程，目录回到
        //    `/`（用户看到「cd 没生效」）；
        //  - `export FOO=1` 同理，下一条命令读不到；
        //  - `vim a.txt` 在 runner 模式下只能把输出当纯文本倒进屏幕，
        //    既不能编辑也不能退出。
        //
        // 也**不做本地回显**（不 feed `$ cmd`）：PTY 里的 shell 自己会
        // echo 输入。两边都写就会看到命令出现两次。这是从管道模式切过来
        // 最容易犯的错。
        val ptyId = ptySessionIds[tab.id]
        if (ptyId != null) {
            // 历史三条规则与管道分支完全一致（连续重复不重复记）。
            if (tab.history.lastOrNull() != command) {
                tab.history += command
                if (tab.history.size > HISTORY_LIMIT) tab.history.removeAt(0)
            }
            tab.input = ""
            publish()
            // 回车用 `\r`（PTY 行规程的预期），不是 `\n`：
            // 发 `\n` 时有的 shell 不执行，只是换行。
            val bytes = (command + "\r").toByteArray(Charsets.UTF_8)
            if (ptyPort?.write(ptyId, bytes) != true) {
                tab.session.feed("[写入终端失败：会话可能已结束]" + CRLF)
                publish()
            }
            return
        }


        // ① 回显：无论是否要排队，都立刻写进缓冲。
        tab.session.feed("$ " + command + CRLF)

        // ② 记历史。三条规则与真实 shell 一致：
        //  1. **连续重复不重复记** —— 连按两次 `ls` 后按 `↑` 应该只回一条；
        //  2. 入队即算「真的提交过」，所以排在 `running` 判定之外；
        //  3. **超上限丢最旧** —— 见 [HISTORY_LIMIT]。
        if (tab.history.lastOrNull() != command) {
            tab.history += command
            if (tab.history.size > HISTORY_LIMIT) {
                tab.history.removeAt(0)
            }
        }

        tab.input = ""

        // ③ 入队。绑定 tabId，输出永远回写提交时的那个会话。
        pending.addLast(QueuedCommand(tabId = tab.id, command = command))
        publish()

        // ④ 空闲就泵。忙时什么都不做 —— 当前命令结束时会在 finally 里再泵一次。
        pump()
    }

    /**
     * 执行队首命令；若已在执行则直接返回。
     *
     * ## 为什么用「泵」而不是「递归调用」
     *
     * `pump()` 在命令结束后再次调用自己（通过协程），等价于 while 循环，
     * 但不占栈：队列有 100 条命令时不会堆 100 层调用。
     *
     * ## 为什么 `running` 的复位放在 `finally`
     *
     * 命令执行中途若抛异常（runner 实现崩、协程被取消），
     * 不复位 `running` 会让队列**永久卡死** —— 后续所有命令都在排队，
     * 用户看到「敲什么都没反应」。`finally` 保证无论如何都复位并继续泵。
     */
    private fun pump() {
        if (running) return
        val next = pending.removeFirstOrNull() ?: return
        // 目标会话可能已被关闭（排队期间用户点了 ×）。丢弃这条，
        // 但必须继续泵 —— 否则队列被一条死命令堵住。
        val tab = tabList.firstOrNull { it.id == next.tabId }
        if (tab == null) {
            pump()
            return
        }

        running = true
        publish()

        viewModelScope.launch {
            try {
                val exit = withContext(ioDispatcher) {
                    runCatching {
                        runner.run(next.command) { chunk ->
                            // 同步喂入：保证每一块都立刻可见。
                            // 写的是 `tab`（提交时的会话），不是 `activeTab()`。
                            tab.session.feed(chunk)
                        }
                    }.getOrElse { error ->
                        tab.session.feed("执行失败: " + (error.message ?: error::class.simpleName) + CRLF)
                        -1
                    }
                }
                if (exit != 0) {
                    tab.session.feed("[退出码 " + exit + "]" + CRLF)
                }
            } finally {
                running = false
                publish()
                // 每条命令结束都落一次盘：`onCleared` 不保证被调用
                // （进程被系统杀时不会走），这条路径才能保住「执行过什么」。
                persistSnapshot()
                // 继续下一条。放在 finally 内、publish 之后：
                // 先让 UI 看到本条的输出与退出码，再开始下一条，
                // 否则两条命令的输出可能在同一次重组里一起出现。
                pump()
            }
        }
    }

    /**
     * 清空待执行队列（不影响正在跑的那条）。
     *
     * 用户按下「取消队列」时用。正在执行的命令由 [cancel] 处理。
     */
    fun clearPending() {
        if (pending.isEmpty()) return
        val dropped = pending.size
        pending.clear()
        tabList.getOrNull(activeIndex)?.session?.feed(
            "已取消 $dropped 条排队中的命令" + CRLF,
        )
        publish()
    }

    /** 当前排队中的命令数（UI 用来显示「还有 N 条」）。 */
    fun pendingCount(): Int = pending.size

    /**
     * Ctrl+C：中断当前命令。
     *
     * 先让 runner 杀掉子进程，再在屏幕上补 `^C`。

     * 不补的话用户看不到任何反馈，会以为按钮没生效而反复点。
     */
    fun cancel() {
        val tab = tabList.getOrNull(activeIndex)
        // ## 真终端：Ctrl+C 是发给 shell 的字节，不是杀进程（2026-10-29）
        //
        // PTY 模式下 `running` 永远是 false（命令不经过 runner），旧写法会
        // 直接 feed `^C` 然后返回 —— 屏幕上出现一个 `^C`，而 shell 里那条
        // `ping` / `apt` **根本不会停**。这里必须真的发 0x03。
        val ptyId = tab?.let { ptySessionIds[it.id] }
        if (ptyId != null) {
            ptyPort?.write(ptyId, byteArrayOf(0x03))
            // 本地不补 `^C`：PTY 的 echo 会自己回显 `^C`。这里再写就成了两个。
            publish()
            return
        }
        if (!running) {
            // 管道模式且没有在跑的命令：回一个 `^C`，与真实终端行为一致。
            tab?.session?.feed("^C" + CRLF)
            publish()
            return
        }
        runner.cancel()
        tab?.session?.feed("^C" + CRLF)
        publish()
    }

    /** 清屏（只清当前会话）。 */
    fun clear() {
        val tab = tabList.getOrNull(activeIndex) ?: return
        tab.session.reset()
        if (showBanner) writeBanner(tab.session)
        publish()
    }

    /**
     * 首屏 banner：字符画 + 一行提示。
     *
     * ## 为什么画在缓冲里而不是用 Compose 单独排版
     *
     * 它是「终端里的内容」，用户 `clear` 之后应当消失、往上翻能看到。
     * 用 Composable 固定画在顶部的话，它会永远占着最上面几行，且宽度不随字号变化。
     *
     * 宽度控制：最宽一行 39 字符（默认 80 列），留足余量；
     * 60 列以上的设备不会折行。
     */
    private fun writeBanner(session: TerminalSession) {
        session.feed(CRLF)
        PRAXIS_ART.forEach { line ->
            session.feed(line + CRLF)
        }
        session.feed("  >> Android 上的 AI \u667a\u80fd\u4f53\u5de5\u4f5c\u53f0 <<" + CRLF)
        session.feed(CRLF)
        session.feed("  输入命令后回车执行；Ctrl+C 可中断。" + CRLF)
        session.feed(CRLF)
    }

    private fun publish() {
        // ## 只重建**活动会话**的行（2026-10-24 性能修）
        //
        // 旧实现给每个 tab 都调 `linesOf` —— 但 UI 只读活动会话的 `lines`
        // （标签条只读 `title` / `id`）。每按一个键都为所有后台会话重建
        // 整屏（200 行 × 80 格）是纯浪费。
        //
        // 非活动会话直接复用上一次的 `lastLines` 引用：`TerminalTab` 是
        // data class，引用不变时 `equals` 短路，`StateFlow` 也不会误发。
        val activeId = tabList.getOrNull(activeIndex)?.id

        _state.value = TerminalUiState(
            tabs = tabList.map { tab ->
                val isActive = tab.id == activeId
                val lines = if (isActive) {
                    // 带逐行缓存：没变的行复用上一次的 `TerminalLine` 对象，
                    // 于是 `remember(visible)` 直接命中，不重跑 `buildAnnotatedLine`。
                    TerminalUiState.linesOf(tab.session, tab.rowCache).also {
                        tab.lastLines = it
                    }
                } else {
                    tab.lastLines
                }
                TerminalTab(
                    id = tab.id,
                    title = tab.title,
                    lines = lines,
                    input = tab.input,
                    // 传副本：`history` 是可变列表，直接把引用交给 UI 后
                    // 下一次 submit 会就地修改它，而 `TerminalUiState` 是
                    // data class + StateFlow —— 引用不变时 `equals` 可能
                    // 判为「没变化」，UI 不重组，用户按 ↑ 看不到历史。
                    history = tab.history.toList(),
                )
            },
            activeIndex = activeIndex,
            running = running,
            pendingCount = pending.size,
        )
    }

    private fun columnsOf(): Int = DEFAULT_COLUMNS

    private fun rowsOf(): Int = DEFAULT_ROWS

    companion object {
        /**
         * 默认列数。
         *
         * 取 80 而不是按屏宽算：终端程序的换行、表格对齐都依赖列数稳定，
         * 屏幕转一下列数变了，`ls` 的输出会整片重排。80 是通用默认值。
         */
        const val DEFAULT_COLUMNS = 80

        /**
         * 每个会话的命令历史上限。
         *
         * 见 `Tab.history` 的说明：键盘翻历史只在几十条内有意义，
         * 而无上限会随使用时长稳定泄漏内存。200 条 × 平均几十字节
         * 约 10KB 量级，可以接受；再多也翻不到。
         */
        const val HISTORY_LIMIT = 200

        /** 默认行数。超过后进滚动历史。 */
        const val DEFAULT_ROWS = 200

        /**
         * PTY 的初始可视行数。
         *
         * 与 [DEFAULT_ROWS] 是两件事：那个是屏幕缓冲（含回滚）的总行数，
         * 这个是窗口高度。UI 量出真实尺寸后会 `resize` 覆盖它。
         */
        const val INITIAL_PTY_ROWS = 24

        /**
         * 字符画（standard figlet 风格，6 行 × 39 列）。
         *
         * 不用反斜杠转义：Kotlin 字符串里 `\\` 需要写两次，
         * 直接写真实字符更不容易出错（这几个字形本来就靠 `\` 和 `/` 拼）。
         */
        val PRAXIS_ART: List<String> = listOf(
            " ____   ____      _    __  _____   ____  ",
            "|  _ \\ |  _ \\    / \\   \\ \\/ /|_ _|/ ___| ",
            "| |_) || |_) |  / _ \\   \\  /  | | \\___ \\ ",
            "|  __/ |  _ <  / ___ \\  /  \\  | |  ___) |",
            "|_|    |_| \\_\\/_/   \\_\\/_/\\_\\|___||____/ ",
        )
    }
}
