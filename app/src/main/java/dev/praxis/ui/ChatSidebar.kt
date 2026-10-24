package dev.praxis.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
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
) {
    val scope = rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<SessionItem>>(emptyList()) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var groupExpanded by remember { mutableStateOf(true) }
    /** 待确认删除的会话 id（长按后进入确认态）。 */
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val sidebarListState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 抽屉每次打开时重新拉一次：不然新建/删除之后抽屉里还是旧列表。
    // 以 open 作为 key 而不是只在首次加载 —— 这是「打开时刷新」的语义。
    LaunchedEffect(open, reloadTick) {
        if (!open) return@LaunchedEffect
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
        if (sessions.none { it.id == currentSessionId }) {
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
        title = "对话历史",
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
                            onClick = {
                                // 点了别的行就取消「待删除」态：
                                // 否则确认按钮会留在原处，用户会以为点错了行。
                                pendingDelete = null
                                onOpenSession(session.id)
                            },
                            // 长按进入删除确认态（同一条再长按一次则收起）。
                            onLongClick = {
                                pendingDelete = if (pendingDelete == session.id) null else session.id
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
                                scope.launch {
                                    runCatching { chatPersistence.deleteSession(session.id) }
                                    pendingDelete = null
                                    reloadTick++
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}