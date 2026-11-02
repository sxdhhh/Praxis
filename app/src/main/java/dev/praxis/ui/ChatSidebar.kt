package dev.praxis.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import dev.praxis.ui.widgets.SidebarActionButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.feature.chat.ChatPersistence
import dev.praxis.feature.chat.SessionItem
import dev.praxis.ui.theme.PraxisMotion
import dev.praxis.ui.widgets.MiuixSidebar
import dev.praxis.ui.widgets.MiuixTextActionButton
import dev.praxis.ui.widgets.SidebarEmptyState
import dev.praxis.ui.widgets.SidebarSectionLabel
import dev.praxis.ui.widgets.SidebarSessionRow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 对话侧边栏（左侧抽屉）的内容装配。
 *
 * ## 信息架构照 Operit `ChatHistorySelector.kt`（2509 行）取值
 *
 * 上游那一页有：标题 / 搜索 / 筛选 / 返回 / 新建对话 / 左右滑动提示 /
 * 分组（未分组折叠）/ 会话项（置顶图钉 + 当前选中高亮 + 左滑删除右滑编辑）。
 *
 * 本轮做了哪些、没做哪些：
 *
 * | 上游能力 | 本轮 | 原因 |
 * |---|---|---|
 * | 标题 + 新建对话 | ✅ | 基础 |
 * | 分组（未分组）可折叠 | ✅ | 结构简单，且后面加自定义分组时位置已留好 |
 * | 会话列表 + 当前选中高亮 | ✅ | 基础 |
 * | 置顶（图钉） | ✅ | 已加 `pinned` 字段与迁移 |
 * | 左滑删除 / 右滑编辑 | ⚠️ 只做删除（长按） | miuix 没有 Swipe 组件，自写手势层风险大于收益；见下 |
 * | 搜索 | ❌ | 会话通常只有几十条，搜索的收益低于维护成本 |
 * | 筛选（按角色/分组） | ❌ | Praxis 没有角色，没有可筛的维度 |
 *
 * ## 为什么「滑动删除」改成长按
 *
 * 上游用 `me.saket.swipe:SwipeableActionsBox`（第三方库）。miuix 没有等价物，
 * 自写需要在 `pointerInput` 里维护偏移量、阈值、回弹动画，并且与 LazyColumn 的
 * 纵向滚动抢手势 —— 这是最典型的手势冲突场景。
 *
 * 用**长按 + 二次确认**替代：不占手势、不会与滚动打架、误触成本更低。
 * 这不是「省事」，是在没有可靠的滑动组件时更稳的选择。
 */
@Composable
fun ChatSidebar(
    open: Boolean,
    /**
     * 抽屉开合进度（0 = 收起，1 = 展开）。
     *
     * ## 为什么是从上面传下来的（2026-10-23）
     *
     * 抽屉打开时**主内容**要同时缩放 / 位移 / 起圆角（1:1 对齐 WeKit 的
     * `applyProgress`）。主内容与抽屉是兄弟层，只有共同父级（`MainShellPage`）
     * 能同时拿到两边 —— 所以进度由父级持有，这里只做转发。
     *
     * 详见 `MiuixSidebar` 的 `progress` 参数 KDoc。
     */
    progress: Animatable<Float, AnimationVector1D>,
    onDismiss: () -> Unit,
    chatPersistence: ChatPersistence,
    currentSessionId: String,
    onOpenSession: (String) -> Unit,
    /**
     * 删掉了**当前会话**之后切到哪个会话。
     *
     * ## 为什么侧边栏和会话列表面板都要这个回调（2026-11-01）
     *
     * 两处入口（本抽屉 / `ChatSessionsPage`）各自独立删除，但 `sessionId`
     * 的唯一持有者是 `PraxisApp`。只在其中一处修，用户换个入口删当前会话
     * 依旧会把界面留在一条已经不存在的会话上 —— 缺陷表现会随入口不同而
     * 时好时坏，那种「有时能删有时不能」最难排查。
     *
     * null = 上层不给兜底：此时**不删当前会话**（宁可不删，也不悬空）。
     */
    onDeleteCurrentSession: ((deletedSessionId: String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<SessionItem>>(emptyList()) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var groupExpanded by remember { mutableStateOf(true) }
    /** 待确认删除的会话 id（长按后进入确认态）。 */
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    /**
     * 刚刚被删掉的会话 id（等上层把 `sessionId` 切走后再清）。
     *
     * ## 为什么必须有（2026-11-02，真机复现「当前对话删不掉」）
     *
     * 删除是异步的：先 `deleteSession(id)`，再由 `onDeleteCurrentSession`
     * 通知 `PraxisApp` 把 `sessionId` 切到下一条。**这中间有一个窗口**，
     * 其间 `sessionId` 仍指着那条已经被删掉的会话。
     *
     * 而下面那段「补齐当前会话的行」的守卫写在 `LaunchedEffect(open, reloadTick)`
     * 里 —— 删除后 `reloadTick++` 会把它重跑一遍，于是它看到
     * 「列表里没有 currentSessionId」就 `ensureSession(...)` 把刚删的行**又插回去**。
     *
     * 真机（模拟器 + 直读数据库）实测：删完当前会话后 `sessions` 表里多出
     * 一条 `('main', '当前对话')` —— 用户看到的就是「这条删不掉」。
     *
     * 所以记下 id，在 `sessionId` 真正切走之前**跳过补齐**。
     */
    var justDeletedId by remember { mutableStateOf<String?>(null) }

    /**
     * 多选模式（2026-11-03，对齐 DeepSeek）。
     *
     * 用户原话：「侧边栏的管理应该像DeepSeek一样」。
     * DeepSeek 的行为是：长按某条 → 进入多选 → 每条左侧出圆形复选框
     * → 顶部标题变「选择对话」+ 右侧 × → 底部出现 [置顶][删除] 操作条。
     *
     * 旧实现只有「长按 → 二次确认删除」**单条**，删三条要点三次 + 等三次动画。
     *
     * 两个状态分开存：
     *  - [selectionMode] 控制「是不是多选态」（决定行样式、底部条是否出现）；
     *  - [selectedIds] 是勾选集合。
     * 退出多选时清空集合，避免下次进来带着上次的残留勾选。
     */
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val sidebarListState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 抽屉每次打开时重新拉一次：不然新建/删除之后抽屉里还是旧列表。
    // 以 open 作为 key 而不是只在首次加载 —— 这是「打开时刷新」的语义。
    LaunchedEffect(open, reloadTick, currentSessionId) {
        if (!open) return@LaunchedEffect
        // 上层已经把 sessionId 切到别的会话了：删除流程结束，清掉标记。
        //
        // ## 为什么标记还有「超时自清」的兜底（2026-11-02）
        //
        // 正常路径：删除 → onDeleteCurrentSession → 上层切 sessionId → 这里清标记。
        // 但如果上层那次切换**失败或被吞掉**（协程取消、写库异常被 runCatching 吃掉），
        // sessionId 会一直等于 justDeletedId，标记就永远不清 —— 之后这个 id
        // 即使被重新创建，侧边栏也不会为它补行，表现是「列表里少一条」。
        //
        // 所以打开抽屉时再检一次：只要该 id **已经不在列表里**，
        // 说明删除确实生效了，标记就没必要留着（它原本只为挡住「补行」）。
        if (justDeletedId != null) {
            val deleted = justDeletedId!!
            val stillListed = sessions.any { it.id == deleted }
            if (!stillListed && currentSessionId != deleted) justDeletedId = null
        }
        // ## 先补当前会话的行，再列（2026-10-22 修，第 6 项）
        //
        // 默认会话 id 是字面量 `"main"`（`PraxisApp` 里 `rememberSaveable` 的初值），
        // 而建行只发生在**首次发送消息**时。结果：用户刚打开 App、还没发过消息，
        // 抽屉列表是空的 —— 明明停在那个会话上，侧边栏却一条都不显示，像是坏了。
        //
        // ## 这里**不能**用 `ensureSession`（差点造成数据丢失，必须写清）
        //
        // 我第一版写的是 `ensureSession(currentSessionId, "当前对话")`，看起来无害，
        // 实际会把聊天记录删光：
        //
        //   `ensureSession` → `sessionDao.upsert` → `@Insert(onConflict = REPLACE)`
        //   → SQLite 的 REPLACE 是**先 DELETE 再 INSERT**
        //   → `messages` 表对 `sessions` 有 `ON DELETE CASCADE`
        //   → 该会话的**全部消息被级联删除**。
        //
        // 也就是说：每打开一次侧边栏，当前会话的聊天记录就被清一次。
        // 这恰好会造出用户报的第 9 项「对话记录退出后怎么就没了」。
        // `SessionDao.setPinned` 的注释里已经记过同一个坑（所以它用 UPDATE 而非 upsert），
        // 我这次是在另一个调用点又踩了一遍 —— 记在这里防止第三次。
        //
        // 正确做法：只在该会话**不存在**时才插入真正的新行。
        // 用 `listSessions()` 的结果判断即可，不必多查一次库。
        sessions = runCatching { chatPersistence.listSessions() }.getOrDefault(emptyList())
        // ⚠️ 第二个条件不能省：刚删掉的那条此刻仍等于 currentSessionId，
        // 补它等于把用户的删除操作撤销（见 justDeletedId 的 KDoc）。
        if (sessions.none { it.id == currentSessionId } && currentSessionId != justDeletedId) {
            // 走到这里说明这个会话还没有任何消息、也没被建过行。
            // `ensureSession` 此时是安全的：库里本来就没有它的行，
            // REPLACE 的 DELETE 步骤删不到任何东西，CASCADE 也就不会触发。
            runCatching { chatPersistence.ensureSession(currentSessionId, "当前对话") }
            sessions = runCatching { chatPersistence.listSessions() }.getOrDefault(emptyList())
        }
    }

    MiuixSidebar(
        open = open,
        progress = progress,
        onDismiss = onDismiss,
        // 多选态下标题变「选择对话」—— 与 DeepSeek 一致，
        // 让用户一眼知道现在点行是「勾选」而不是「打开」。
        title = if (selectionMode) "选择对话" else "对话历史",
        onCreateLabel = "＋ 新建对话",
        onCreate = {
            // ## 新建对话必须**立刻落库**（2026-10-22 修，第 6 项）
            //
            // 旧实现只调 `onOpenSession(newId)`：换个 id、关抽屉，什么都不写库。
            // 于是用户点「＋ 新建对话」后：
            //  - 抽屉关了，看起来成功了；
            //  - 再打开抽屉 —— **新对话不在列表里**，因为 `listSessions()`
            //    读的是数据库，而库里没有这一行；
            //  - 用户会重复点几次，每次都是一个新 id，全都看不见。
            //
            // 这是「侧边栏一堆 bug」里最直接的感知：新建了却看不到。
            //
            // 修法：先在库里建行（`ensureSession`），再切过去。
            // 标题先给「新对话」占位：`ChatViewModel` 在**首次发送**时会先写
            // 兜底标题（`SessionTitle.fallback`，首句压平后截 24 字），
            // 再异步调模型生成 AI 标题（`SessionTitle.sanitize(模型输出)`）。
            // 所以这里的占位只是「模型还没回来之前」的可读名字。
            val newId = "s${System.currentTimeMillis()}"
            scope.launch {
                runCatching { chatPersistence.ensureSession(newId, "新对话") }
                onOpenSession(newId)
            }
        },
        // ── 多选模式的底部操作条（对齐 DeepSeek）─────────────────────
        //
        // 只在多选态出现，且由 MiuixSidebar 的 bottomBar 槽承载：
        // 放在 content 里会被 LazyColumn 滚走，用户在长列表里找不到「删除」。
        bottomBar = if (!selectionMode) {
            null
        } else {
            {
                SidebarSelectionActions(
                    selectedCount = selectedIds.size,
                    allSelected = sessions.isNotEmpty() && selectedIds.size == sessions.size,
                    onToggleSelectAll = {
                        selectedIds = if (selectedIds.size == sessions.size) {
                            emptySet()
                        } else {
                            sessions.map { it.id }.toSet()
                        }
                    },
                    onPin = {
                        val ids = selectedIds.toList()
                        scope.launch {
                            ids.forEach { id ->
                                runCatching {
                                    chatPersistence.setSessionPinned(sessionId = id, pinned = true)
                                }
                            }
                            selectionMode = false
                            selectedIds = emptySet()
                            reloadTick++
                        }
                    },
                    onDelete = {
                        val ids = selectedIds.toList()
                        if (ids.isEmpty()) return@SidebarSelectionActions
                        // ## 批量删除的三条红线（每一条都是踩过的坑）
                        //
                        // 1. **只调 deleteSession，绝不调 ensureSession / upsert**。
                        //    SQLite 的 `@Insert(onConflict = REPLACE)` 是 DELETE+INSERT，
                        //    会连带触发 messages 表的 ON DELETE CASCADE —— 整个会话
                        //    的消息被删光。本项目已因为这个丢过两次数据。
                        // 2. **当前会话在被删集合里时，必须交给上层切走**，
                        //    否则对话页会停在一条已不存在的会话上发不出消息。
                        // 3. **先记 justDeletedId 再删**，不然 reloadTick 触发的
                        //    补齐守卫会把刚删的行插回去（「删不掉」的老 bug）。
                        val deletingCurrent = ids.contains(currentSessionId)
                        justDeletedId = if (deletingCurrent) currentSessionId else null
                        selectionMode = false
                        selectedIds = emptySet()
                        scope.launch {
                            ids.forEach { id ->
                                runCatching { chatPersistence.deleteSession(id) }
                            }
                            reloadTick++
                            if (deletingCurrent) {
                                onDeleteCurrentSession?.invoke(currentSessionId)
                            }
                        }
                    },
                    onExit = {
                        selectionMode = false
                        selectedIds = emptySet()
                    },
                )
            }
        },
        hint = "长按会话可删除",
    ) {
        if (sessions.isEmpty()) {
            SidebarEmptyState()
            return@MiuixSidebar
        }

        // ## 这里为什么**不用** `AutoFollowScrollLazy`（2026-10-22 修）
        //
        // 旧代码在这里接了 `AutoFollowScrollLazy(itemCount = sessions.size + 1)`。
        // 两个问题叠在一起：
        //
        // 1. **语义错位**：那个组件是给「持续追加内容」的页面用的
        //    （聊天流、终端输出、日志）。它的作用是「新内容进来时自动滚到底」。
        //    会话列表是**一次性加载的静态列表**，没有「新内容」这回事，
        //    接上去只会带来它内部的 `snapshotFlow` 开销与自滚标记，
        //    换不来任何收益。
        // 2. **itemCount 传错**：分组折叠时列表只有 1 项（分组标题本身），
        //    但传的是 `sessions.size + 1`。跟随引擎据此判断「是否到底」，
        //    折叠状态下这个判断恒定失真 —— 后续若要往里加新会话，
        //    会得到「不跟随」或「乱跳」两种错法之一。
        //
        // 侧边栏只需要一个普通的 `LazyColumn`：Compose 自己的滚动就够了。
        LazyColumn(state = sidebarListState, modifier = Modifier.fillMaxWidth()) {
            // 分组标题也要 key：它是「折叠/展开」时唯二的常驻 item 之一，
            // 无 key 时会与第一条会话抢下标 0 —— 展开的瞬间标题节点会被
            // 复用成会话行，表现为「标题闪一下变成一条会话」。
            item(key = "__section_label__") {
                SidebarSectionLabel(
                    text = "未分组（${sessions.size}）",
                    expanded = groupExpanded,
                    onToggle = { groupExpanded = !groupExpanded },
                )
            }

            if (groupExpanded) {
                items(sessions, key = { it.id }) { session ->
                    // 必须是 Column 而不是 Box。
                    //
                    // `Box` 会把「会话行」与下方的「确认删除」都摆在 (0,0) 叠起来，
                    // 确认按钮会压在会话文字上。这类叠放问题在 LazyColumn 的
                    // 单个 item 里很容易复现（本项目已踩过多次），一律用 Column。
                    //
                    // ## `animateItem` + `animateContentSize` 两个都要（2026-10-24）
                    //
                    // 这个 item 有**两种**独立的高度/位置变化，缺一个就有硬跳：
                    //
                    // | 变化 | 现象 | 需要哪个 |
                    // |---|---|---|
                    // | 长按露出「确认删除」 | 本行高度从 1 行变 2 行 | `animateContentSize` |
                    // | 删除一条 / 置顶重排 | 其他行整体上下移 | `animateItem` |
                    //
                    // 只加 `animateItem` 时，行内高度变化仍然是瞬变
                    // （placement 只管「item 在列表里的位置」，不管 item 自身尺寸）。
                    // 只加 `animateContentSize` 时，列表重排仍然是瞬移。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = tween(
                                    durationMillis = PraxisMotion.Durations.Base,
                                    easing = PraxisMotion.Curves.Decelerate,
                                ),
                                placementSpec = spring(stiffness = 400f, dampingRatio = 0.85f),
                                fadeOutSpec = tween(
                                    durationMillis = PraxisMotion.Durations.Fast,
                                    easing = PraxisMotion.Curves.Accelerate,
                                ),
                            )
                            .animateContentSize(
                                animationSpec = tween(
                                    durationMillis = PraxisMotion.Durations.Base,
                                    easing = PraxisMotion.Curves.Decelerate,
                                ),
                            ),
                    ) {
                        SidebarSessionRow(
                            title = session.title.ifBlank { "未命名对话" },
                            subtitle = null,
                            selected = session.id == currentSessionId,
                            pinned = session.pinned,
                            selectionMode = selectionMode,
                            checked = session.id in selectedIds,
                            onClick = {
                                if (selectionMode) {
                                    // 多选态：点行 = 切换勾选，不打开会话。
                                    selectedIds = if (session.id in selectedIds) {
                                        selectedIds - session.id
                                    } else {
                                        selectedIds + session.id
                                    }
                                } else {
                                    // 点了别的行就取消「待删除」态：
                                    // 否则确认按钮会留在原处，用户会以为点错了行。
                                    pendingDelete = null
                                    onOpenSession(session.id)
                                }
                            },
                            // 长按：进入多选态并勾上这一条（DeepSeek 的行为）。
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = setOf(session.id)
                                    // 进多选先把单条待删除态清掉：两套删除入口不能并存。
                                    pendingDelete = null
                                }
                            },
                            onTogglePin = {
                                scope.launch {
                                    runCatching {
                                        chatPersistence.setSessionPinned(
                                            sessionId = session.id,
                                            pinned = !session.pinned,
                                        )
                                    }
                                    // 置顶会改变 SQL 排序，必须重拉才看得到新顺序。
                                    reloadTick++
                                }
                            },
                        )

                        // 删除确认：长按后在同一行下面露一条「确认删除」。
                        //
                        // 为什么不用弹窗：抽屉本身就是覆盖层，再叠一层弹窗会让
                        // 返回键的层级变得难以预期（先关弹窗还是先关抽屉？）。
                        MiuixTextActionButton(
                            visible = pendingDelete == session.id,
                            text = "确认删除「${session.title.ifBlank { "未命名对话" }}」",
                            onClick = {
                                // ## 删的就是当前会话时，必须交给上层切走（2026-11-01）
                                //
                                // 旧实现只做「删库 + 重读」。删别的会话没问题，
                                // 删**正打开着的**这一个时：行从抽屉里消失，而
                                // `sessionId` 仍指着它 —— 对话页变成一条空会话，
                                // 发消息写库失败（外键无对应 sessions 行）。
                                // 用户那句话就是「当前对话删不掉」。
                                val deletingCurrent = session.id == currentSessionId
                                // 先记标记再删：下面的补齐守卫会在 reloadTick 后重跑，
                                // 不记的话它会把这条刚删的会话插回去。
                                justDeletedId = session.id
                                pendingDelete = null
                                if (deletingCurrent && onDeleteCurrentSession == null) {
                                    // 没有兜底会话可切：什么都不做，先切走再删。
                                } else {
                                    scope.launch {
                                        runCatching { chatPersistence.deleteSession(session.id) }
                                        reloadTick++
                                        if (deletingCurrent) {
                                            onDeleteCurrentSession?.invoke(session.id)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 多选模式的底部操作条：[全选] [置顶] [删除]，对齐 DeepSeek。
 *
 * ## 为什么单独抽出来
 *
 * 它由 [MiuixSidebar] 的 `bottomBar` 槽承载（在内容区外面），
 * 所以必须是一个独立的 Composable，不能内联在 `content` 里。
 *
 * ## 为什么「删除」不是红色以外的强调
 *
 * 删除是破坏性操作，用 `error` 色区分。但因为是多选批量删除、
 * 且没有二次确认（DeepSeek 也没有），文案里带上条数（「删除 3」）
 * 让人在按下之前就知道会影响几条 —— 这比弹一个确认框更轻、也更安全。
 */
@Composable
private fun SidebarSelectionActions(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
) {
    val hasSelection = selectedCount > 0
    // ## 紧凑横排，不是几个铺满的大色块（2026-11-03 修）
    //
    // 第一版用 `MiuixTextActionButton`（内部 fillMaxWidth + error 色），
    // 真机截图里变成**四块刺眼的红条**，与 DeepSeek 的细线条操作条完全不同。
    //
    // 现在：一行 [全选] …… [置顶 N] [删除 N] [完成]。
    // 中性操作走 secondaryContainer，只有删除用 error 色。
    //
    // 没勾选时不显示置顶/删除（而不是「显示但禁用」）：
    // 没有可执行的操作就不展示，这也贴近 DeepSeek。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SidebarActionButton(
            text = if (allSelected) "取消全选" else "全选",
            onClick = onToggleSelectAll,
        )
        Spacer(Modifier.weight(1f))
        if (hasSelection) {
            SidebarActionButton(
                text = "置顶 $selectedCount",
                onClick = onPin,
            )
            Spacer(Modifier.width(8.dp))
            SidebarActionButton(
                text = "删除 $selectedCount",
                onClick = onDelete,
                danger = true,
            )
            Spacer(Modifier.width(8.dp))
        }
        SidebarActionButton(
            text = "完成",
            onClick = onExit,
        )
    }
}
