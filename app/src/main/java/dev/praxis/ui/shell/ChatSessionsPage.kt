package dev.praxis.ui.shell
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.feature.chat.ChatPersistence
import dev.praxis.feature.chat.SessionItem
import dev.praxis.ui.design.component.PraxisPage
import dev.praxis.ui.navigation.LocalNavigator
import dev.praxis.ui.theme.PraxisMotion
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话列表（对话域的二级页）。
 *
 * ## 为什么从「侧边栏」改成「独立二级页」
 *
 * 旧实现把会话列表做成左侧抽屉（`overlay` 槽）。抽屉的问题不在代码，
 * 在**导航语义**：它既不是栈里的一层（返回键不会关它），
 * 又要和内容抢手势（横滑边缘既是「打开抽屉」又是「返回上一页」）。
 * 用户这次报的「导航逻辑极乱」里就包括这一块。
 *
 * 现在它是 `PraxisRoute.ChatSessions`，是栈里真实的一层：
 *  - 返回键 = 退栈 = 回对话页，与所有二级页一致；
 *  - 侧滑返回由 `PraxisNavDisplay` 统一给了 `swipeDismiss`；
 *  - 侧边栏仍然保留（`MainShellPage` 的 overlay），因为「快速切换」这个
 *    交互本身有价值 —— 但两处的数据源与行为完全一致。
 *
 * ## 为什么删除是「点两次」而不是长按
 *
 * 长按在手势上要等 400ms 左右（系统长按判定），在列表里误触成本高。
 * 点一次进入「确认删除」态、再点一次才真删，比长按更容易发现、
 * 也更容易取消（点别处即退出确认态）。
 */
@Composable
fun ChatSessionsPage(
    chatPersistence: ChatPersistence,
    currentSessionId: String,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<SessionItem>>(emptyList()) }
    var reloadTick by remember { mutableIntStateOf(0) }
    /** 待确认删除的会话 id。 */
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    // 每次进入 / 删除后重读。runCatching：迁移失败时显示空列表比整页崩好。
    LaunchedEffect(reloadTick) {
        sessions = runCatching { chatPersistence.listSessions() }.getOrDefault(emptyList())
    }

    PraxisPage(
        title = "会话列表",
        onBack = { navigator.pop() },
        modifier = modifier,
        // 右上角：新建会话。
        actions = {
            TextButton(
                text = "新建",
                onClick = {
                    // 必须**立刻落库**：只换 id 不写库的话，再进列表看不到新会话
                    // （listSessions 读的是数据库）。这是第 6 项的根因之一。
                    val newId = "s" + System.currentTimeMillis()
                    scope.launch {
                        runCatching { chatPersistence.ensureSession(newId, "新对话") }
                        onOpenSession(newId)
                        // ## 必须退栈（本轮修）
                        //
                        // `onOpenSession` 只改 `sessionId` 这个状态，**不动导航栈**。
                        // 旧实现因此停在「会话列表」页：用户看到自己刚建的新会话
                        // 出现在列表里并被高亮，却要再按一次返回才回到对话页 ——
                        // 而那一页此时已换成空的新会话，观感是「新建后没变化」。
                        //
                        // 与侧边栏的差异：侧边栏是 overlay（不占栈），所以那边
                        // 只需 `sidebarOpen = false`；本页是**栈里真实的一层**，
                        // 必须显式退栈。这是把「侧边栏」改造成「独立页」时
                        // 必然要补、但当时漏掉的一步。
                        //
                        // 用 popNow 而不是 pop：这是程序语义上的退回，
                        // 不该被 100ms 防抖窗口吞掉。
                        navigator.popNow()
                    }
                },
            )
        },
    ) { _ ->
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { Spacer(Modifier.height(12.dp)) }

            if (sessions.isEmpty()) {
                // ## 空态 item 必须给 key（2026-10-24 修）
                //
                // LazyColumn 的 item 身份由 key 决定（隐性默认是下标）。
                // 这个列表里「空态」与「每条会话」是**同一个滚动容器内**的两类
                // 内容：没有 key 时，空态项的身份是下标 0，而第一条会话若也
                // 恰好占下标 0，两者会被判成「同一个 item」——
                // 从空态变成有数据时，框架会**复用**那个节点并只做内容更新，
                // 于是 `Text` 的测量结果与 `Card`/`animateItem` 的动画状态
                // 都可能带着上一轮的残留（典型表现：第一条进入时没有淡入、
                // 或者空态文字闪一下再被替换）。
                //
                // 给一个稳定且不会与业务 key 相撞的字符串 key：
                // 会话 id 形如 `s<timestamp>`，`__empty_sessions__` 不可能冲突。
                item(key = "__empty_sessions__") {
                    Text(
                        text = "还没有会话。点右上角「新建」开始。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            items(sessions, key = { it.id }) { session ->
                // ## `animateItem` 让「删除 / 置顶重排」有位移过渡（2026-10-24）
                //
                // 这个列表的两种变化都会导致**其他行整体移动**：
                //  - 删除一条 → 下面每一条上移一格；
                //  - 置顶一条 → 它跳到最前，中间每一行都要挪位。
                //
                // 没有 `animateItem` 时这些都是瞬移：点完置顶，列表「啪」地
                // 重排一次，用户的眼睛跟不上哪一行去了哪。
                //
                // `placementSpec` 用 spring（不是 tween）：重排是「跟手」的动作，
                // 弹簧的收尾比固定时长更自然，也与底栏指示器的位移同一手感。
                //
                // 包一层 `Box` 而不是给 `SessionRow` 加 `modifier` 参数：
                // 那个 composable 没有 modifier 形参，为了一处动画去改签名
                // 会让所有调用点都要跟着动（而且它内部是 Card，动画挂在
                // Card 外层更不会与阴影/圆角重绘打架）。
                Box(
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(
                            durationMillis = PraxisMotion.Durations.Base,
                            easing = PraxisMotion.Curves.Decelerate,
                        ),
                        placementSpec = spring(stiffness = 400f, dampingRatio = 0.85f),
                        fadeOutSpec = tween(
                            durationMillis = PraxisMotion.Durations.Fast,
                            easing = PraxisMotion.Curves.Accelerate,
                        ),
                    ),
                ) {
                SessionRow(
                    session = session,
                    isCurrent = session.id == currentSessionId,
                    pendingDelete = pendingDelete == session.id,
                    onClick = {
                        if (pendingDelete == session.id) {
                            // 已处于确认态：这一次点击执行删除。
                            scope.launch {
                                runCatching { chatPersistence.deleteSession(session.id) }
                                pendingDelete = null
                                reloadTick++
                            }
                        } else {
                            onOpenSession(session.id)
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
                            // 置顶会改动 SQL 排序，必须重读才看得到新顺序。
                            reloadTick++
                        }
                    },
                    onRequestDelete = {
                        // 「删除」入口：进入/退出确认态。
                        //
                        // 不用长按：Compose 的长按要等系统 400ms 判定，列表里误触成本高；
                        // 单独一个按钮更直白，也更容易取消（再点一次即退出）。
                        pendingDelete = if (pendingDelete == session.id) null else session.id
                    },
                )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * 一行会话。
 *
 * 三种视觉状态必须能区分，否则用户不知道自己在删哪个：
 *  - 当前会话：标题用 primary 色；
 *  - 待删除：整行变红底，文案改为「再点一次删除」；
 *  - 置顶：标题前加图钉标记。
 */
@Composable
private fun SessionRow(
    session: SessionItem,
    isCurrent: Boolean,
    pendingDelete: Boolean,
    onClick: () -> Unit,
    /** 置顶开关。 */
    onTogglePin: () -> Unit,
    /** 请求删除：进入或退出「确认删除」态。 */
    onRequestDelete: () -> Unit,
) {
    val title = session.title.ifBlank { "未命名对话" }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (session.pinned) "★ $title" else title,
                    style = MiuixTheme.textStyles.body1,
                    color = if (isCurrent) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                )
                if (pendingDelete) {
                    Text(
                        text = "再点一次删除这个会话（不可撤销）",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            // 两个独立小按钮，各自可点区域分开 —— 不与「打开会话」抢点击。
            TextButton(
                text = if (session.pinned) "取消置顶" else "置顶",
                onClick = onTogglePin,
            )
            TextButton(
                text = if (pendingDelete) "取消" else "删除",
                onClick = onRequestDelete,
            )
        }
    }
}