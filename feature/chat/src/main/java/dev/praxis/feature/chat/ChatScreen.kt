package dev.praxis.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.praxis.core.model.settings.ThinkingEffort
import dev.praxis.core.tool.PermissionTier
import dev.praxis.ui.design.component.AutoFollowScrollLazy
import dev.praxis.ui.design.component.LocalScrollBottomLimit
import dev.praxis.ui.design.component.LocalTopBarInset
import dev.praxis.ui.design.component.imeLiftPadding
import dev.praxis.ui.design.component.rememberImeLift
import dev.praxis.feature.chat.queue.PendingQueuePanel
import dev.praxis.feature.chat.menu.MessageContextMenu
import dev.praxis.feature.chat.menu.MessageCopyPreviewSheet
import dev.praxis.feature.chat.menu.MessageDeleteConfirmDialog
import dev.praxis.feature.chat.menu.MessageInfoDialog
import dev.praxis.feature.chat.navigator.ChatScrollNavigator
import dev.praxis.feature.chat.navigator.ChatMessageLocatorPreview
import dev.praxis.feature.chat.navigator.toLocatorPreview
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import dev.praxis.ui.theme.PraxisMotion
import dev.praxis.feature.chat.widget.BubbleKind
import dev.praxis.feature.chat.widget.ChatMessageItem
import dev.praxis.ui.widgets.ChatDisplayStyle
import dev.praxis.feature.chat.widget.SummaryDetailDialog
import dev.praxis.feature.chat.widget.SummaryDivider
import dev.praxis.feature.chat.widget.ToolCallCard
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Reply
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Stopwatch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聊天页。
 *
 * ## 结构对齐 Operit（`ClassicChatInputSection.kt`，778 行）
 *
 * Operit 的输入区不是「一个输入框 + 一个按钮」，而是一个纵向容器：
 *
 * ```
 * ┌──────────────────────────────────────┐
 * │ 阶段提示（进度条 + 当前在做什么）        │  ← 只在处理中显示
 * ├──────────────────────────────────────┤
 * │ [ 多行输入框 ]              [●] [➤] │  ← 圆形图标按钮，不是文字按钮
 * └──────────────────────────────────────┘
 * ```
 *
 * 上一版 Praxis 只有「输入框 + `TextButton("生成中")`」。两个问题：
 *  1. **按钮是文字**：`TextButton` 宽高随文案变，「生成中」比「发送」宽，
 *     按下瞬间按钮会跳一下宽度，整体布局跟着抖。
 *  2. **「生成中」不告诉用户在等什么**：连服务端、等首个 token、跑工具
 *     三段的耗时差一到两个数量级，一句「生成中」让人以为卡死。
 *
 * ## 哪些 Operit 能力**没有**抄，以及为什么
 *
 * | Operit 能力 | 为什么不做 |
 * |---|---|
 * | 附件面板（+ 按钮展开九宫格） | `ChatRequest` 没有图片/文件字段，`ChatMessage` 只有 `text`；加了只能填不能发 |
 * | 全屏输入（Fullscreen 图标 + 弹窗） | 输入框已支持 5 行自适应，长文本场景未出现；加一个弹窗只是多一层 |
 * | 待发队列（processing 时发下一条） | 需要队列 UI + 逐条编辑/删除/重发，`ChatViewModel` 也没有队列状态 |
 * | @ 提及高亮（mention visualTransformation） | Praxis 没有「@ 工具/文件」的解析管线，高亮了也不生效 |
 * | Token 超限预警 | 上下文预算由 `AiContextBudget` 按 token 自动丢弃，不存在「超限」这个状态 |
 * | 语音 / 悬浮窗 | 语音引擎在放弃清单（`core:speech` 未做） |
 *
 * 判断标准与上一轮一致：**只放接得上真实链路的控件**。
 *
 * ## 两个关键点（与原实现一致）
 *
 * 1. **消息列表用 [AutoFollowScrollLazy]**：apixiaoyuan 的弹簧跟随引擎
 *    （`core/design/component/AutoFollowScroll.kt` 245 行，k=130 / c=24 / m=1）。
 *    注意**不是** `animateScrollToItem` —— 那是另一个项目（cn.nizou.sxd
 *    `CustomScoreScreen.kt:317`）的写法，Praxis 曾误标成「照抄 apixiaoyuan」换掉过一次。
 * 2. **输入区不是浮层**：聊天页是 `AppFullscreen` 形态，输入框占据布局空间，
 *    消息列表高度自己收缩 —— 不然键盘弹出会盖住最后一条消息。
 */
/**
 * 定位器条目：**展示序号**与**真实气泡下标**的对应关系。
 *
 * ## 为什么需要这个类型（而不是只传 previews）
 *
 * 定位器把工具气泡过滤掉之后，「定位器里的第 N 条」不再等于
 * 「`bubbles` 里的第 N 条」。而 `scrollToItem` 要的是后者。
 *
 * 若在 `jumpToMessage` 里拿 `index` 直接当 `bubbles` 下标用，
 * 一旦对话里有工具气泡，跳转就会**偏移**（工具越多偏得越远）。
 * 这正是要带的第二个值。
 *
 * @param messageIndex 在 `state.bubbles` 里的真实下标（给 `scrollToItem`）。
 * @param preview 给定位器渲染的预览（内容、发送者、长度）。
 */
internal data class LocatorEntry(
    val messageIndex: Int,
    val preview: ChatMessageLocatorPreview,
)

@Composable
fun ChatScreen(
    state: ChatState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    /**
     * 切换消息变体（`delta` = -1 上一个 / +1 下一个）。
     *
     * ## 为什么默认 null 而不是必填
     *
     * 这个页面有 Preview 与测试调用点。变体切换依赖 ViewModel 的实现，
     * 给默认值能让它们不改就继续编译；传 null 时切换条不显示
     * （见调用处的 `onSwitchVariant != null` 判断），不会出现「按钮点了没反应」。
     */
    onSwitchVariant: ((bubbleId: Long, delta: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * 掐断当前这一轮。
     *
     * 默认实现什么都不做 —— 但**不给默认值**会强迫所有调用点改签名。
     * 这里给 `null` 默认值，UI 在 `null` 时不显示停止键（而不是显示一个按了没反应的键）。
     */
    onCancel: (() -> Unit)? = null,
    /**
     * 一次性提示已展示完（4 秒后由输入栏调用）。
     *
     * 默认 null：预览/测试环境没有 ViewModel，提示留在原地即可。
     */
    onNoticeConsumed: (() -> Unit)? = null,
    /**
     * 列表顶部的可选内容（当前用于模型配置摘要）。
     *
     * ## 为什么用插槽而不是直接依赖 ModelSettings
     *
     * `feature:chat` 不该知道「模型配置」长什么样 —— 那是 `ui:widgets` +
     * `:app` 的职责。插槽让聊天页保持纯粹的「消息流 + 输入」结构，
     * 而把「我现在在跟谁说话」这件事交给上层决定要不要显示、显示什么。
     *
     * ## 为什么是 LazyColumn 的 item 而不是固定头部
     *
     * 固定头部会永久占掉一块屏幕。作为 item 时它随消息滚走 ——
     * 用户看完一眼就不再需要它，但往上滚回顶部时它还在。
     */
    header: (@Composable () -> Unit)? = null,
    /** 发送或入队（处理中会自动转为入队）。 */
    onSendOrEnqueue: (() -> Unit)? = null,
    /**
     * 请求启动系统图片选择器（多模态输入）。
     *
     * ## 为什么是「请求」而不是「已选好的图」
     *
     * `ChatScreen` 在 `feature:chat` 模块，拿不到 `Context`/Activity 去注册
     * `ActivityResultLauncher`（那要用 `androidx.activity.compose`，
     * 属于 `:app` 层职责）。所以这里只发一个「用户想选图」的信号，
     * 真正的启动与「读图 → 压缩 → base64」都在 `:app` 的 `ChatTab` 完成，
     * 完成后通过 `onAddImage`/`notifyImageUnreadable` 回填到 VM。
     *
     * null 表示不渲染图片按钮（预览/未接线），与其它可选回调同一纪律。
     */
    onRequestPickImage: (() -> Unit)? = null,
    /** 选文件（附件面板的「文件」格）。null = 上层没接。 */
    onPickFile: (() -> Unit)? = null,
    /** 附件面板是否展开。 */
    attachmentPanelOpen: Boolean = false,
    /** 切换附件面板；null = 不显示加号按钮。 */
    onOpenAttachmentPanel: (() -> Unit)? = null,
    /** 插入位置（结果为文本，写进输入框）。null = 不提供。 */
    onAttachLocation: (() -> Unit)? = null,
    /** 插入当前通知（结果为文本）。null = 不提供。 */
    onAttachNotifications: (() -> Unit)? = null,
    /** 插入最近记忆（文本）。null = 不提供。 */
    /** 拍照（系统相机）。null = 不提供。 */
    /** 屏幕内容（截当前这屏）。null = 不提供。 */
    onCaptureScreen: (() -> Unit)? = null,
    onTakePhoto: (() -> Unit)? = null,
    onAttachMemory: (() -> Unit)? = null,
    /** 插入已安装应用列表（文本）。null = 不提供。 */
    onAttachPackages: (() -> Unit)? = null,
    /** 移除第 index 张待发图片。 */
    onRemoveAttachment: ((Int) -> Unit)? = null,
    /** 队列操作（可空：不传则不显示队列面板）。 */
    onQueueMoveUp: ((Long) -> Unit)? = null,
    onQueueMoveDown: ((Long) -> Unit)? = null,
    onQueueMoveToTop: ((Long) -> Unit)? = null,
    onQueueRemove: ((Long) -> Unit)? = null,
    onQueueEdit: ((Long) -> Unit)? = null,
    /** 立即发送队列里的某一条（跳过排队顺序）。 */
    onQueueSendNow: ((Long) -> Unit)? = null,
    /** 展开 / 收起队列面板。 */
    onQueueExpandedChange: ((Boolean) -> Unit)? = null,
    /** 跳转到第 index 条消息（导航浮条 / 定位器回调）。 */
    onJumpToMessage: ((Int) -> Unit)? = null,
    /** 编辑器：内容变化（`MutableState` 双向绑定）。 */
    onEditingTextChange: ((String) -> Unit)? = null,
    /** 编辑器：取消。 */
    onCancelEditingMessage: (() -> Unit)? = null,
    /** 编辑器：保存（只改这条）。 */
    onSaveEditingMessage: (() -> Unit)? = null,
    /** 编辑器：保存并重发（仅用户消息）。 */
    onResendEditingMessage: (() -> Unit)? = null,
    /** 关闭「消息信息」弹窗。 */
    onDismissMessageInfo: (() -> Unit)? = null,
    /**
     * 点消息流里的「历史对话摘要」分割线 → 打开全文弹窗。
     *
     * 可空：不传时点击无反应（而不是弹一个空对话框）。
     * 与 `onDismissMessageInfo` 同层：压缩摘要与消息信息都是「点开看详情」
     * 这类二级交互，接线方式一致。
     */
    onOpenSummaryDetail: (() -> Unit)? = null,
    /** 关闭摘要全文弹窗。 */
    onDismissSummaryDetail: (() -> Unit)? = null,
    /** 关闭复制预览表。 */
    onDismissCopyPreview: (() -> Unit)? = null,
    /** 确认删除（来自删除确认弹窗）。 */
    onConfirmDeleteMessage: (() -> Unit)? = null,
    /** 取消删除。 */
    onDismissDeleteMessage: (() -> Unit)? = null,
    /** 清除回复目标（预览条上的 ×）。 */
    onClearReplyTo: (() -> Unit)? = null,
    /** 长按消息的动作回调。返回要复制到剪贴板的文本则自动写入。 */
    onMessageAction: ((Long, MessageAction) -> String?)? = null,
    /**
     * 当前模型名（显示在输入栏下方的设置条上）。
     *
     * ## 为什么由外部传入而不是从 ViewModel 里读（2026-10-24）
     *
     * `ChatViewModel` 刻意**不持** `SettingsRepository`：它的构造参数里只有
     * 一组 `xxxProvider: (suspend () -> T)?` 回调（见 `contextConfigProvider`
     * 等）。这样 ViewModel 的单测不必起 DataStore，也不会出现
     * 「两个地方各读一份设置、谁先谁生效」的混乱。
     *
     * 模型名是纯展示，`:app` 层的 `ChatTab` 已经持有 `modelSettings` 流，
     * 直接传下来最省事，也不用为了一个字符串打破上面那条纪律。
     */
    modelName: String = "",
    /** 模型池（模型胶囊的就地选择列表）。空表示没配置过。 */
    models: List<String> = emptyList(),
    /** 当前生效的模型下标。 */
    activeModelIndex: Int = 0,
    /** 选第 index 个模型；null 表示不支持就地切换。 */
    onSelectModelIndex: ((Int) -> Unit)? = null,
    /** 是否自动保存记忆（记忆分区的开关）。 */
    autoMemoryEnabled: Boolean = true,
    onToggleAutoMemory: ((Boolean) -> Unit)? = null,
    /** 已加载的技能名（插件分区数据源）。空则整个分区不显示。 */
    skillNames: List<String> = emptyList(),
    disabledSkills: Set<String> = emptySet(),
    onToggleSkill: ((String, Boolean) -> Unit)? = null,
    /** 全部工具名（底栏工具组的就地开关数据源）。 */
    toolNames: List<String> = emptyList(),
    disabledTools: Set<String> = emptySet(),
    onToggleTool: ((String, Boolean) -> Unit)? = null,
    /**
     * 切换思考强度。null 表示不提供切换（该下拉项不渲染）。
     *
     * 由 `:app` 层接到 `SettingsRepository.setThinkingEffort`。
     */
    onPickThinkingEffort: ((ThinkingEffort) -> Unit)? = null,
    /**
     * 当前思考强度（设置条胶囊上显示的那个）。
     *
     * ## 为什么不直接用 `state.thinkingEffort`（2026-10-24）
     *
     * `ChatState.thinkingEffort` 只在**发送时**由
     * `readRuntimeConfig()` → `prepare()` 写回（见 `ChatViewModel:1007`）。
     * 用户在设置条上切了档位，若不发消息，state 里还是旧值 ——
     * 胶囊停在「不思考」，看起来就是「点了没反应」。
     *
     * 而 `ModelSettings.thinkingEffort` 是 DataStore 流的产物：写入后
     * 立刻回流。所以由 `:app` 层把那个值传下来，UI 与下一轮请求读的是
     * 同一份数据，不存在两处不一致。
     *
     * null 时回落到 `state.thinkingEffort`（预览/单测环境不下发设置）。
     */
    currentThinkingEffort: ThinkingEffort? = null,
    /**
     * 进模型设置页。null 表示不提供入口（设置条上的模型胶囊与 Tune 都不渲染）。
     */
    onOpenModelSettings: (() -> Unit)? = null,
    /**
     * 工具权限档位（底栏 Tune 菜单里的「工具」分区，1:1 对齐 Operit）。
     *
     * ## 为什么是 `PermissionTier?` 而不是非空
     *
     * null = 不提供该分区。与 `onOpenModelSettings` 同一纪律：上层没接
     * 就不画入口，而不是画一个点不动的三档。
     *
     * ## 为什么档位要摆在对话页
     *
     * Operit 把「工具权限」放在输入区底栏，而不是塞进设置页深处。
     * 理由是可切换的档位**直接影响这一轮能做什么**：用户发「清掉缓存目录」时，
     * 若档位是基础，模型会答自己做不到。在对话现场能看到并改掉，比
     * 「去设置里翻三层」少三次点击。
     */
    toolTier: PermissionTier? = null,
    /** 切换工具档位。null 表示不提供（此时「工具」分区不渲染）。 */
    onSetToolTier: ((PermissionTier) -> Unit)? = null,
    /** 进工具管理页（逐项禁用）。null 时不显示该行。 */
    onOpenToolSettings: (() -> Unit)? = null,
    /** 是否关闭流式输出（底栏「行为」分区，1:1 对齐 Operit `disableStreamOutput`）。 */
    disableStreamOutput: Boolean = false,
    /** 切换流式输出。null 表示不提供（此时「行为」分区不渲染）。 */
    /** 是否自动朗读（null = 设备无 TTS）。 */
    autoReadReplies: Boolean? = null,
    onToggleAutoReadReplies: ((Boolean) -> Unit)? = null,
    onToggleDisableStreamOutput: ((Boolean) -> Unit)? = null,
    /**
     * 对话显示风格。
     *
     * 默认 [ChatDisplayStyle.Cursor] —— 与 Operit 的默认一致（它 `chatStyleSetting`
     * 的 `else` 分支落 CURSOR，BUBBLE 要用户手动开）。用户 2026-10-22 看过两边后
     * 认为 Operit 那种更好读，所以这里把默认按 Operit 走，而不是按「我们原来是气泡」走。
     */
    displayStyle: ChatDisplayStyle = ChatDisplayStyle.Cursor,
    /**
     * 会话标识（**必须传**，见下面的 KDoc）。
     *
     * ## 为什么这个参数是「消息不显示」与「模型卡错乱」的共同修复点
     *
     * 旧实现用「第一个气泡的 id」作为滚动状态的 key：
     *
     * ```kotlin
     * val sessionAnchor = state.bubbles.firstOrNull()?.id ?: -1L
     * val listState = remember(sessionAnchor) { LazyListState() }
     * items(state.bubbles, key = { it.id })
     * ```
     *
     * 看起来没问题，但它依赖一个**不成立的前提**：不同会话的气泡 id 不同。
     *
     * 实际上 `ChatViewModel` 的 `nextId` 是**每个实例从 0 开始的普通字段**，
     * 而会话切换的实现是 `viewModel(key = sessionId)` —— 换实例，计数器归零。
     * 于是：
     *
     * ```
     * 会话 A 恢复 30 条  → 气泡 id = 0,1,2 … 29
     * 切到会话 B（8 条） → 气泡 id = 0,1,2 … 7      ← 与 A 完全重叠
     * ```
     *
     * 两个后果都很严重：
     *
     * 1. **`remember(sessionAnchor)` 不重置**：A 与 B 的首条 id 都是 0，
     *    key 相等 → 复用同一个 `LazyListState` → 滚动偏移被带过去。
     *    A 停在底部时切到只有 8 条的 B，偏移被夹到末尾 ——
     *    用户看到的是「消息一条都没有」或「只剩最后一条」。
     * 2. **LazyColumn 的 key 撞车**：`key = { it.id }` 在两会话间是同一批值，
     *    Compose 认为它们是**同一批 item**，直接复用已有的组合节点而不是重建。
     *    新会话内容被塞进旧节点里，或者旧节点因为数量更多而不被回收 ——
     *    表现同样是「消息不对 / 不显示」。
     *
     * 修法不是「让 nextId 全局递增」（那能解决第 2 点，但第 1 点还得靠
     * 会话身份），而是**显式用会话身份做 key 前缀**：
     * `remember(sessionKey)` 重建滚动状态，item key 也带上会话前缀。
     * 这也与 Operit 的做法一致 —— 它按 `chatId` 区分一切会话内状态。
     */
    sessionKey: String = "default",
) {
    // ## 为什么 listState 要跟着「会话身份」重建（2026-10-22 修，第 9 项）
    //
    // 旧写法是裸 `rememberLazyListState()`：它在组合位置不变时会被复用。
    // 而切换会话并不会改变 `ChatScreen` 在组合树里的位置 ——
    // 于是**滚动偏移被带到新会话上**。
    //
    // 具体表现：在会话 A 滑到第 50 条，切到只有 3 条消息的会话 B，
    // 列表会试图停在第 50 条的偏移处 → Compose 把它夹到末尾 → 用户看到
    // 「一条消息都没有 / 停在最底部」，以为会话是空的。再切回 A，
    // 位置又没了。这正是「不能切换其他对话」的观感来源之一。
    //
    // 用**会话身份**作 key（旧实现用的是第一个气泡的 id，那是错的：
    // `nextId` 每个 ViewModel 实例都从 0 开始，跨会话必然重叠 ——
    // 详见 `sessionKey` 参数的 KDoc）。
    val listState = androidx.compose.runtime.remember(sessionKey) {
        androidx.compose.foundation.lazy.LazyListState()
    }

    // 长按菜单的状态：被点的那条气泡 id + 它可用的动作。
    //
    // ## 为什么把状态放在 ChatScreen 而不是每条气泡自己持有
    //
    // 菜单是**全局唯一**的浮层（同一时刻只能开一个）。若每条气泡各自 remember
    // 一个 expanded 布尔，快速长按两条会出现两个浮层叠在一起。
    val menuBubbleId = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Long?>(null)
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // 注：这里原本还有一个 `menuActions`（按 menuBubbleId 现算的动作列表）。
    // 现在动作列表在气泡自己的 Box 里按 `bubble.role` 现算（见 items 内部），
    // 这个中转变量没有用处了，删掉以免误导后来的维护者。

    val topInset = LocalTopBarInset.current
    val scrollLimit = LocalScrollBottomLimit.current

    /**
     * 定位器用的预览列表。
     *
     * ## 为什么要 `remember(state.bubbles)`
     *
     * 定位器里有 `remember(previews) { maxOfOrNull ... }`（O(n) 求最长消息）
     * 与过滤扫描。若每次重组都新建一个 list，`remember` 的 key 在流式期间
     * 每秒变几十次，这几处 O(n) 扫描就会被反复执行 —— 消息越多越掉帧。
     * 用 `state.bubbles` 作 key，只有气泡列表真的换了才重算。
     *
     * ## 2026-10-24：这一处曾是「越长越卡」的主因
     *
     * 在此之前，流式正文是写进 `bubbles[i].text` 的 —— 每个 delta 都会
     * `copy(bubbles = 新列表)`，于是这个 `remember` **每个 delta 都失效**，
     * 把全部历史消息重新跑一遍 `toLocatorPreview()`（内部逐字符归一化全文）。
     *
     * 流式一个 3000 字回复、上下文 100 条 → 每 delta 处理十万级字符、
     * 每秒几十次。对话越长越卡，与正在输出的那条多长无关。
     *
     * 现在文本改走 `state.streamingText`（见 `ChatViewModel.applyStreamText`），
     * `bubbles` 的引用在流式期间**完全不变**，这里因此天然不再重算。
     *
     * ## 为什么预览里用 `bubble.text` 而不是流式槽里的文本
     *
     * 定位器是「按内容搜索历史消息」用的，搜索期间不应该有消息在变。
     * 而流式过程中用户根本不会去搜（他还在等回答）。所以预览用落库文本即可，
     * 省掉「每 delta 重建一条预览」的代价。收到 Done 之后 seal 会把它合进
     * `bubbles`，搜索时看到的就是完整内容。
     */
    /**
     * 定位器条目 —— **排除工具气泡**（1:1 对齐 Operit `buildSelectedMessagesPlainText`
     * 与 `ChatScreenContent.selectableMessageIndices` 的过滤口径）。
     *
     * ## 为什么要过滤（用户原话：「跳转消息不应该把工具也算进去」）
     *
     * 旧实现是 `state.bubbles.map { it.toLocatorPreview() }` —— 全部气泡都进定位器，
     * 包括 `BubbleRole.Tool`。后果有两层，都很实际：
     *
     *  1. **定位器列表被工具刷屏**：一次带工具的回答可能产生 5~10 条工具气泡
     *     （每次调用一条）。用户在定位器里翻「我跟 AI 聊了什么」，
     *     滚动一大半都是 `read_file` / `shell` 这种条目，
     *     真正想找的那句回答被埋掉了。
     *  2. **跳转会落到工具上**：定位器的 `index` 直接当 `bubbles` 下标用
     *     （见下方 `jumpToMessage`），所以「第 12 条」是包含工具计数的名次。
     *     用户在工具气泡上看到的预览往往是一行 JSON 或空字符串，
     *     跳过去观感是「跳到了个莫名其妙的地方」。
     *
     * Operit 的同一过滤出现在两处：
     *  - `ChatScreenContent.kt:168` → `if (sender == "user" || sender == "ai") index else null`
     *    决定哪些下标记账（可选中 / 可跳转）；
     *  - `ChatArea.kt:179` → 复制时也只要 user/ai。
     *
     * 也就是说上游口径是「**只有用户与 AI 的话才算消息**」，工具与错误是实现细节。
     * 这里照抄，并保留 `Summary`：它是用户主动插入的历史压缩块，属于「内容」。
     *
     * ## 为什么必须在过滤后携带真实下标
     *
     * 定位器组件（`ChatScrollNavigator`）把 previews 的**列表下标**当消息序号显示
     * （「当前第 N / M 条」），而 `jumpToMessage(index)` 需要的是 `bubbles` 的
     * **真实下标**。过滤之后这两者不再相等 —— 所以用
     * [LocatorEntry] 把两者一起带出去，避免在调用处再查一遍
     * （那会引入 O(n) 重复查找，而且容易写错）。
     */
    val locatorEntries = remember(state.bubbles) {
        buildList {
            state.bubbles.forEachIndexed { index, bubble ->
                // 工具不是「消息」：不进定位器、不参与跳转计数。
                if (bubble.role == BubbleRole.Tool) return@forEachIndexed
                // 其余都是用户能读的内容：提问、回答、错误提示、摘要块。
                add(LocatorEntry(messageIndex = index, preview = bubble.toLocatorPreview()))
            }
        }
    }

    // 定位器组件只吃 previews（它不认识真实下标），跳转时用它回查。
    val locatorPreviews = remember(locatorEntries) {
        locatorEntries.map { it.preview }
    }

    /**
     * 反向索引表：**列表 item 下标 → 定位器序号**。
     *
     * ## 为什么要有它
     *
     * 定位器排除工具气泡后，「LazyColumn 的第 N 个 item」与「定位器的第 N 条」
     * 不是一回事（中间隔着工具气泡，还隔着 header）。而
     * `ChatScrollNavigator` 内部拿到的是**列表下标**（`LazyListItemInfo.index`），
     * 需要翻成定位器序号才能正确显示「当前第几条」。
     *
     * ## 为什么是 remember 预建而不是每次查时扫一遍
     *
     * 这个翻译由 `snapshotFlow` **每滚动帧**调用。写成
     * `locatorEntries.indexOfFirst { ... }` 就是每帧一次 O(n) 扫描；
     * 几百条 × 60fps 是每秒上万次比较 —— 在「要让滑动更丝滑」的任务里
     * 这样写属于帮倒忙。预建 Map 后查表 O(1)，且只在 entries/header 变化时重建。
     *
     * @see listIndexToPreviewIndex 的 key 已含 header 偏移，调用方不必再减。
     */
    val listIndexToPreview = remember(locatorEntries, header) {
        val headerOffset = if (header != null) 1 else 0
        locatorEntries
            .mapIndexed { previewIndex, entry ->
                (entry.messageIndex + headerOffset) to previewIndex
            }
            .toMap()
    }

    /**
     * 跳转到定位器里的第 [index] 条（对齐 Operit 的 `onJumpToMessage`）。
     *
     * ## 为什么是 `scrollToItem` 而不是 `animateScrollToItem`
     *
     * 从定位器点一条时，用户要的是「立刻到那条」。带动画会先滑过中间所有
     * 消息（几百条时耗时数秒）；更要紧的是动画期间列表位置在连续变化，
     * 而 `AutoFollowScrollLazy` 的弹簧也在往底部追 —— 两个滚动源同时写
     * `LazyListState`，观感是「跳过去又被拽回来」。
     *
     * 一次性 `scrollToItem` 把位置定死，弹簧随后会因为「不在底部」而停下
     * （它只在内容变多且用户仍在底部时才滚），两者天然不冲突。
     *
     * ## 偏移为什么要加 header
     *
     * `header` 是 LazyColumn 的第 0 个 item。消息下标记账从 0 开始时，
     * 列表里的真实下标要 +1，否则跳转永远差一条。
     *
     * ## 为什么不能直接用 [index]
     *
     * [index] 是**定位器里的序号**（已排除工具气泡），而 `scrollToItem`
     * 要的是 `bubbles` 的真实下标。两者在有工具时不等 —— 见 [LocatorEntry]。
     * 这里先查表拿到真实下标，查不到（列表刚变、条目已不存在）就退回
     * 「按原数量比例夹取」，保证不崩也不跳到离谱位置。
     */
    val scope = rememberCoroutineScope()
    val jumpToMessage: (Int) -> Unit = onJumpToMessage ?: { index ->
        scope.launch {
            val entries = locatorEntries
            val targetBubbleIndex = entries.getOrNull(index)?.messageIndex
                ?: entries.lastOrNull()?.messageIndex
                ?: 0
            val lastIndex = (state.bubbles.size - 1).coerceAtLeast(0)
            val target = targetBubbleIndex.coerceIn(0, lastIndex)
            val headerOffset = if (header != null) 1 else 0
            listState.scrollToItem(target + headerOffset)
        }
    }

    // 页面自铺底色：二级页在 NavDisplay 的 entry 里渲染，
    // 根容器不画背景就会透出下层（用户报的「二级页面背景竟然是透明的」）。
    // 对齐 InstallerX：它每页自带 Scaffold/Surface 铺底。
    //
    // ## 为什么必须自己吃键盘抬升（2026-10-23 修）
    //
    // 用户报「输入法抬升在对话页也没有了」。
    //
    // `AndroidManifest.xml` 里虽然写了 `windowSoftInputMode="adjustResize"`，
    // 但 `MainActivity` 调了 `enableEdgeToEdge()` —— 在 edge-to-edge 模式下
    // window 尺寸**不再随键盘变化**，键盘高度只以 IME inset 的形式存在，
    // 必须由 Compose 侧主动消费。那条 manifest 声明在此语境下是无效配置，
    // 看着像处理过了，实际一处都没生效。
    //
    // 加在根 Column 上而不是只给输入栏：
    //  - 只给输入栏 → 输入栏浮起来、消息列表仍被键盘盖住最后几条；
    //  - 加在根上   → 列表高度一起收缩，正在看的内容不被遮。
    // 这与终端页 `TerminalScreen.kt` 的口径一致（那边早就这么做了）。
    //
    // 键盘可见时底栏已被键盘完全盖住，此时还留一份底栏限位会在列表底部
    // 空出一条「看不见的底栏高度」，表现为键盘弹出瞬间内容往上跳一下。
    //
    // ## 四次修法的完整历史（都写在这里，避免后人再踩）
    //
    // 第 1 次：`if (imeHeight > 0.dp) 0.dp else scrollLimit` → 一帧突变。
    //
    // 第 2 次：给上面结果套 `animateDpAsState(tween(200))` → 仍抖。因为
    //          `if (imeHeight > 0.dp)` 判断的**本身就是一个正在动的量**：
    //          键盘收起时 `imeHeight` 从键盘高度衰减到 0，中途跨过阈值的
    //          那一帧目标值翻转，动画再从那一刻起追 200ms。两条曲线起点
    //          不同 → 中间必然有一段 `imePadding()` 已收回、`scrollLimit`
    //          还没补上 → 「先收回到 tab 栏下面，然后突然瞬移到 tab 上面」。
    //
    // 第 3 次：`(scrollLimit - imeHeight).coerceAtLeast(0.dp)` 配原始
    //          `.imePadding()`。不错位了，但**假设错了**：当时写的
    //          「平台会把 inset 动画逐帧传导过来」不成立。Praxis 用了
    //          `enableEdgeToEdge()` 且没注册 `WindowInsetsAnimationCallback`，
    //          Compose 只拿到 IME 的起止两态 → 两个量一起阶跃
    //          → **抬升完全没有动画**（用户 2026-10-24 报的问题）。
    //
    // 第 4 次（现在）：交给 `rememberImeLift`。它只给「键盘高度」这一个量
    //          做动画，两个 padding 都由同一个动画值派生，因此
    //          `imeLift + bottomPadding == scrollLimit` 恒成立 —— 既不会
    //          中间态错位，又保留了缓动。完整推导见
    //          `ui/design/.../component/ImeLift.kt`。
    //
    // 注意：这里**不再调 `.imePadding()`**。同时用会造成两份抬升叠加，
    // 键盘弹起时底部空出一整个键盘的高度。
    val imeLift = rememberImeLift(scrollLimit)

    // 键盘占掉多少，就少留多少；永不为负。供列表 contentPadding 与输入栏复用。
    val effectiveScrollLimit = imeLift.bottomPadding
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .imeLiftPadding(imeLift),
    ) {

        // ## 为什么消息区外面还要一个 Box
        //
        // 导航浮条要贴在**消息区右缘并垂直居中**，且不能占布局宽度
        // （它只有 34dp 宽，占位会把每条气泡挤窄，滚动时宽度变化很难看）。
        // `Box` + `Alignment.CenterEnd` 正好：浮条叠在列表上，不参与测量。
        //
        // 也不能放在最外层 Column 里 —— 那样它会居中于「列表 + 输入栏」整块，
        // 视觉上偏向输入栏，与上游（居中于消息区）不一致。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                state = listState,
                // 在 Box 里不能用 weight（那是 Column/Row 的语义），用 fillMaxSize
                // 填满 Box；Box 自己已经在 Column 里承担了 weight(1f)。
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = topInset + 12.dp,
                    // 底部限位：滚动区只是「给输入栏让位」不够 —— 输入栏本身也向上抬了
                    // scrollLimit（浮层底栏高度），所以列表最后一条会被底栏压住。
                    // 这里再加一次 scrollLimit，最后一条才能滚到输入栏上方。
                    //
                    // 注意职责分工：根容器的 `imeLiftPadding` 让出的是**键盘**
                    // 占掉的高度，这里让出的是**底栏**高度，两者互补不叠加
                    // （见 `ImeLift.imeLiftPadding` 的 KDoc）。
                    bottom = 12.dp + effectiveScrollLimit,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (header != null) {
                    item(key = "chat-header") { header() }
                }

                // ## 压缩摘要分割线插在「被折叠的最后一条」之后
                //
                // 上游把摘要插在「最后一条 ai 消息之后」（`findProperSummaryPosition`
                // 返回 `indexOfLast { sender == "ai" } + 1`），也就是被折叠那一批的末尾。
                //
                // ## 为什么不能用 `item {}` 直接插在中间
                //
                // `LazyColumn` 的 `item {}` 只能**按调用顺序**追加。这里的气泡是
                // 一个整体 `items(state.bubbles)`，中途再插一个 `item` 只会再加到
                // 列表最末尾，不在中间。
                //
                // 正确做法是把分割线做成气泡列表的**一部分**：用一个统一的
                // index 空间（`0..bubbles.size`，摘要占其中一格），
                // 用 `items(count)` 遍历并在对应位置换成分割线。
                //
                // 代价是所有气泡的 item 变成一个 `items(count)`，key 必须自己算 ——
                // 下面用 `"$sessionKey#${bubble.id}"` 保持与原 key 一致，
                // 分割线用固定 key（它只有一条）。
                val draft = state.compacted?.takeIf { it.isActive }
                val summaryIndex = draft?.let { it.coveredCount.coerceIn(0, state.bubbles.size) }

                // ## key 为什么要带会话前缀
                //
                // `key = { it.id }` 在单个会话内是唯一且稳定的，这没问题。
                // 但气泡 id 来自 `ChatViewModel.nextId`，而它**每个实例从 0 开始**；
                // 会话切换是 `viewModel(key = sessionId)` 换实例，计数器归零。
                // 于是 A 会话与 B 会话的 id 是同一批数字（0,1,2…）。
                //
                // Compose 只按 key 判断「是不是同一个 item」，不关心它属于哪个会话。
                // 两会话 key 重叠时，切换会话会被理解成「同一批 item 换了内容」，
                // 已组合的节点被复用而不是重建 —— 长消息的文本、tool 卡片的展开态、
                // 甚至 Markdown 的解析缓存都会被带到另一个会话里。
                //
                // 拼上 `sessionKey` 之后，跨会话的 key 必然不同，切换时整批重建。
                // ## 为什么用 `itemsIndexed` 而不是 `items`
                //
                // 摘要分割线要插在「被折叠的最后一条之后」，也就是气泡列表的
                // **中间**。`LazyColumn` 的 `item {}` 只能按调用顺序追加，
                // 中途插一个只会加到末尾；正确做法是在遍历气泡时按索引判断
                // 「该不该在此之前插一条分割线」。
                // ## 为什么要给 `contentType`（2026-10-24，性能）
                //
                // LazyColumn 的复用池按 `contentType` 分桶：同 type 的 item 才会
                // 互相复用**槽位**。不写 `contentType` 时所有条目都归同一个桶 ——
                // 滚动中「一条文字气泡」的槽位可能被拿去渲染「一张工具卡」，
                // 于是两者都要走一遍完整的重组 + 测量（文字气泡的 Text 布局与
                // 工具卡内部的 Column/AnimatedVisibility 结构完全不同）。
                //
                // 分桶之后：文字气泡只复用文字气泡的槽、工具卡只复用工具卡的槽，
                // 滚动时的重组量显著下降。这里用 `role` 做区分即可 ——
                // 同一 role 的气泡内部结构一致。
                //
                // 注意 `contentType` 只影响**复用**，不影响 key 与顺序。
                itemsIndexed(
                    items = state.bubbles,
                    key = { _, bubble -> "$sessionKey#${bubble.id}" },
                    contentType = { _, bubble -> bubble.role },
                ) { index, bubble ->
                    // 分割线插在 index == summaryIndex 的气泡**之前**，
                    // 等价于「跟在第 summaryIndex-1 条之后」——与上游
                    // `findProperSummaryPosition`（最后一条 ai 之后）的语义一致。
                    //
                    // ## 为什么包 Column，并且把 `animateItem` 挂在它上面
                    //
                    // 一个 `itemsIndexed` 的 lambda 只能产生**一个**可测量节点。
                    // 直接并列两个 composable（分割线 + Box）时，LazyColumn 会把
                    // 它们当成同一个 item 内部的并列内容 —— 布局上勉强能出，
                    // 但 `key` 只作用于外层 item，滚动复用与无障碍语义都会错。
                    // 包 Column 后语义明确：这个 item 是「一条摘要 + 一条气泡」。
                    //
                    // ## `animateItem` 只加 fadeIn / fadeOut，不加 placement（2026-10-24）
                    //
                    // 气泡列表与「会话列表」是两种不同的动态：
                    //
                    // | | 会话列表 | 气泡列表 |
                    // |---|---|---|
                    // | 主要变化 | 条目被删除/置顶重排 | 末尾**追加**一条 |
                    // | 需要 placement | 要（下面每条都要滑上来） | **不要** |
                    //
                    // 气泡几乎只在末尾追加，`placementSpec` 在这里没有用武之地；
                    // 而一旦中途插入摘要（`insertMessageAt` 会整段后移 orderIndex），
                    // placement 会让整屏气泡同时做一次位移 —— 那时**滚动位置刚被
                    // 自动跟随调整过**，两股位移叠加会看到明显的「整屏抖一下」。
                    //
                    // 所以只给淡入淡出：新气泡「出现」而不是「滑出来」，
                    // 删除的气泡淡出。滚动跟随仍然由 `AutoFollowScroll` 单独负责。
                    Column(
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(
                                durationMillis = PraxisMotion.Durations.Base,
                                easing = PraxisMotion.Curves.Decelerate,
                            ),
                            fadeOutSpec = tween(
                                durationMillis = PraxisMotion.Durations.Fast,
                                easing = PraxisMotion.Curves.Accelerate,
                            ),
                        ),
                    ) {
                        if (summaryIndex != null && index == summaryIndex) {
                            SummaryDivider(
                                compacted = draft!!,
                                onClick = onOpenSummaryDetail ?: {},
                            )
                        }
                        // ## 菜单必须锚在**气泡自己**身上（2026-10-22 修，第 5 项）
                    //
                    // 用户原话：「长按 ai 和用户的消息 没有菜单」。
                    //
                    // 代码链路其实是通的（`onLongPress` 有值 → `menuBubbleId` 置位 →
                    // `MessageContextMenu` 的 `show` 变 true），所以点下去**确实弹了**。
                    // 问题是弹在看不到的地方 —— 锚点错了。
                    //
                    // 机制：`ListPopupLayout` 用 `Spacer` 的
                    // `onGloballyPositioned { parentLayoutCoordinates.positionInWindow() }`
                    // 取**父布局**位置作为锚点，再按 `alignment` 摆放。
                    //
                    // 旧写法把菜单放在页面顶层 `Column` 里
                    // （`LazyColumn` 与 `ChatInputBar` 之间），于是锚点 = 整个 Column，
                    // 弹窗被摆到「页面某处」，而不是「你长按的那条气泡旁边」。
                    // 屏幕高度不够时 `dropdownPositionProvider` 会翻到上方，
                    // 最终可能落在状态栏后面或被输入栏盖住 —— 观感就是「没反应」。
                    //
                    // miuix 官方 demo（`OverlayListPopupDemo.kt`）的写法是**把
                    // `OverlayListPopup` 与触发它的控件放进同一个 Box**，锚点即那个 Box。
                    // 这里照此实现：每条气泡外面套一个 Box，popup 就放在同一个 Box 里。
                    //
                    // 为什么仍然只有一个 popup 实例在渲染：`show` 判据带上了
                    // `menuBubbleId.value == bubble.id`，其它气泡的 Box 里 show 恒为 false，
                    // `ListPopupLayout` 在 `!show && !internalVisible` 时直接 return，
                    // 不占位也不注册 popupState。这样既保持了「同一时刻只开一个」，
                    // 又让锚点精确落在被长按的气泡上。
                    Box {
                        val tool = bubble.tool
                        if (tool != null) {
                            ToolCallCard(
                                name = tool.name,
                                argumentsJson = tool.argumentsJson,
                                result = tool.result,
                                isError = tool.isError,
                                elapsedMs = tool.elapsedMs,
                                // ## 摘要在外面 remember 一次，而不是每帧现算
                                //
                                // 旧写法传的是 `tool.result?.lineSequence()?.firstOrNull()`。
                                // 那是**在组合阶段**执行的：`lineSequence()` 会建一个
                                // 序列、`firstOrNull()` 触发一次逐字符扫描到第一个换行。
                                // 结果体常有几千字，而这段代码在**每次重组**都会跑一遍
                                // （滚动时 LazyColumn 会重组合可见项）——
                                // 工具卡本来就多，这属于纯浪费。
                                //
                                // 用 `remember(tool.result)` 把它变成「结果变了才重算」。
                                // 键只取 `result`：`isError` 不影响首行内容。
                                summary = remember(tool.result) {
                                    tool.result?.lineSequence()?.firstOrNull()
                                },
                                // 工具气泡也必须能长按弹菜单。
                                //
                                // ## 为什么这一处之前漏了（以及漏掉的表现）
                                //
                                // 工具气泡走的是上面这个 `if (tool != null)` 分支，
                                // **不经过** `ChatMessageItem` —— 而长按回调只加在
                                // `ChatMessageItem` 上。于是 `actionsForBubble` 里
                                // 明明给 Tool 配了「复制 / 信息 / 删除」，界面上却
                                // 一个都按不出来：长按只会被 `clickable` 吃成
                                // 「切换折叠」。
                                onLongPress = if (onMessageAction != null) {
                                    {
                                        menuBubbleId.value = bubble.id
                                        dev.praxis.ui.design.diagnostics.UiDiagnostics.note(
                                            screen = "Chat",
                                            message = "long-press bubble=${bubble.id} role=${bubble.role}",
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        } else {
                            // ## 流式文本从独立槽里读（2026-10-24）
                            //
                            // 没有这一步，`bubbles[i].text` 在流式期间**不再更新**
                            // （那正是消除卡顿的代价：文本不再走 `bubbles`），
                            // 界面会停在开始输出前的空文本上 —— 用户看到的是
                            // 「AI 一直不回话，最后突然全出现」。
                            //
                            // 取值优先级：流式槽（正在写）→ bubble.text（已定稿）。
                            // 槽里没有这个 id 就说明这条已定稿，读 bubbles 是对的。
                            val displayText = state.streamingText[bubble.id] ?: bubble.text
                            // ## 变体切换条（2026-10-24）
                            //
                            // 只有「有多个版本的回答」才画。判据是
                            // `variantCount > 1`：普通气泡默认 1，永远不画 ——
                            // 否则每条 AI 回复底下都挂一条 `‹ 1 / 1 ›`，纯噪音。
                            //
                            // 用 Column 包（气泡 + 切换条），而不是把切换条塞进
                            // `ChatMessageItem` 内部：气泡有两种显示风格
                            // （Bubble / Cursor），各自独立实现，改两处容易漏；
                            // 而且切换条是「这条消息的附属控件」，不是气泡的一部分。
                            Column {
                            ChatMessageItem(
                                style = displayStyle,
                                kind = when (bubble.role) {
                                    BubbleRole.User -> BubbleKind.User
                                    BubbleRole.Assistant -> BubbleKind.Assistant
                                    BubbleRole.Error -> BubbleKind.Error
                                    BubbleRole.Tool -> BubbleKind.Tool
                                    // 摘要**不能**映射到 Assistant：那样会走
                                    // `CursorMessage` 的助手分支（无标签、全尺寸正文），
                                    // 与普通回复完全一致，用户在长对话里分不出
                                    // 「哪些是我压的历史」。`BubbleKind.Summary`
                                    // 在 `MessageBubble` 里两种风格各有对应渲染。
                                    BubbleRole.Summary -> BubbleKind.Summary
                                },
                                text = displayText,
                                streaming = bubble.streaming,
                                // 图片透传：气泡里要能回看用户发过的图
                                // （见 `ChatBubble.images` 的 KDoc）。
                                images = bubble.images,
                                // ## 一轮只开一个「回复」头（2026-10-29）
                                //
                                // 数据上「一次回复」是**多条** Assistant 气泡：
                                // 模型说话 → 调工具 → 再说话 → 再调工具 → 收尾，
                                // 每一段都由 `runToolCalls` 新建一个 `followUpId` 气泡。
                                // 若每段都打「回复」标签，同一轮就被切成
                                // 「回复 / 回复 / 回复」三段，中间夹着工具卡片 ——
                                // 那正是用户看到的形态。
                                //
                                // 正确形态：本轮**第一条**正文打头，后续正文与工具
                                // 卡片都挂在这个头下面：
                                //
                                //     回复
                                //     ├ 正文①
                                //     ├ 工具卡片
                                //     └ 正文②
                                //
                                // ## 判据为什么是「往前找，遇到 User / Summary 就停」
                                //
                                // 一轮的边界就是上一条 User（或摘要块）。中间无论
                                // 夹多少 Tool 都属于同一轮，因为工具调用是**回复的一部分**，
                                // 不是新的发言轮次。
                                //
                                // Error 也算「已开过头」：错误气泡本身就是这一轮的结果，
                                // 后面若还有正文（重试后继续），不该再打第二个「回复」。
                                //
                                // ## 为什么用前向扫描而不是给 ChatBubble 加字段
                                //
                                // 加字段就要在 `publish()`、恢复路径、发送路径、
                                // 重试路径四处同步维护，任一处漏了就又错位。
                                // 而「本条之前、同轮内、有没有 Assistant」是列表顺序的
                                // 纯函数，随时算都对，存下来反而是第二份需要同步的真相。
                                //
                                // 代价：每条气泡渲染时扫一次前置条目。轮内条数是个位数，
                                // 而 `itemsIndexed` 只为可见项调用 —— 可忽略。
                                showLabel = remember(index, state.bubbles) {
                                    !hasEarlierAssistantInTurn(state.bubbles, index)
                                },
                                onRetry = if (bubble.role == BubbleRole.Error) onRetry else null,
                                // 长按弹出动作菜单（复制/引用/编辑重发/重新生成/删除）。
                                // 传 null 时不响应长按（不传回调时不该有「按了没反应」的感觉）。
                                onLongPress = if (onMessageAction != null) {
                                    {
                                        menuBubbleId.value = bubble.id
                                        // 埋点：区分「长按没触发」与「弹窗没显示」（2026-10-22）。
                                        // 上一轮定位第 5 项时，日志里什么都没有，
                                        // 无法判断是哪一环断的。现在两环各留一条：
                                        //  - 这里是「长按确实进来了」；
                                        //  - MessageContextMenu 拿到 show=true 后展开。
                                        dev.praxis.ui.design.diagnostics.UiDiagnostics.note(
                                            screen = "Chat",
                                            message = "long-press bubble=${bubble.id} role=${bubble.role}",
                                        )
                                    }
                                } else {
                                    null
                                },
                            )

                            // ── 变体切换条 ────────────────────────────
                            //
                            // 只在有多版本时出现。位置在气泡**下方**：
                            // 与 AI 回复的左对齐一致，也不盖住气泡内容。
                            //
                            // 流式期间不显示：那时正文还在写，切换没有意义；
                            // 而且 `variantCount` 已包含正在生成的这一版，
                            // 显示「‹ 3 / 3 ›」会让人以为还有别的版本可看。
                            if (bubble.role == BubbleRole.Assistant &&
                                bubble.variantCount > 1 &&
                                !bubble.streaming
                            ) {
                                VariantSwitcher(
                                    index = bubble.variantIndex ?: 0,
                                    count = bubble.variantCount,
                                    onPrev = if (onSwitchVariant != null) {
                                        { onSwitchVariant(bubble.id, -1) }
                                    } else {
                                        null
                                    },
                                    onNext = if (onSwitchVariant != null) {
                                        { onSwitchVariant(bubble.id, +1) }
                                    } else {
                                        null
                                    },
                                )
                            }
                            }
                        }

                        // 锚定到本条气泡的菜单（与上面的内容同级，同一个 Box）。
                        //
                        // ## 1:1 换成 Operit 的 `DropdownMenu` 实现
                        //
                        // 旧实现在 `ChatContextUi.kt`（miuix `OverlayListPopup` + 纯文字）。
                        // 现在直接用 `menu/MessageContextMenu.kt`：Material3
                        // `DropdownMenu` + leadingIcon + 36dp 行高 + 13sp 字号，
                        // 与上游 `ChatArea.kt` 的菜单逐项一致。
                        //
                        // 仍然放在本条气泡的 `Box` 里 —— 这是本项目已经验证过的锚点
                        // 方案（popup 与触发器同属一个 Box，锚点才落在被长按的那条上）。
                        // 上游是整条消息一个 Box 包住 DropdownMenu，结构相同。
                        MessageContextMenu(
                            bubble = if (menuBubbleId.value == bubble.id) bubble else null,
                            show = menuBubbleId.value == bubble.id,
                            onAction = { action ->
                                // ## 剪贴板写入已移到复制预览表里（1:1 对齐上游）
                                //
                                // 旧写法是「回调返回非空字符串 → 这里写剪贴板」。
                                // 现在「复制」不再返回文本，而是置 `copyPreview`
                                // 让 UI 弹预览表（三档可选），真正写剪贴板发生在
                                // `MessageCopyPreviewSheet` 里。
                                //
                                // 返回值仍然保留：将来若某些动作也要顺带复制
                                // （例如插件菜单项），UI 不必再改签名。
                                val toCopy = onMessageAction?.invoke(bubble.id, action)
                                if (!toCopy.isNullOrEmpty()) {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(toCopy))
                                }
                            },
                            onDismiss = { menuBubbleId.value = null },
                        )
                    }
                    }
                }
                // ## 尾部情况：摘要覆盖了「全部气泡」
                //
                // 若 summaryIndex 等于气泡总数（最后一次压缩把剩下的全折了），
                // 循环里 index == summaryIndex 永远不会成立，于是分割线不渲染，
                // 用户看不到「已经压缩过」这件事。这里补一个独立的尾部 item。
                if (summaryIndex != null && summaryIndex >= state.bubbles.size) {
                    item(key = "chat-summary-divider-tail") {
                        SummaryDivider(
                            compacted = draft!!,
                            onClick = onOpenSummaryDetail ?: {},
                        )
                    }
                }
            }

            // ── 消息导航浮条（1:1 复刻 Operit `ChatScrollNavigator`）──────
            //
            // 叠在列表上、贴右缘垂直居中，不参与测量（见上面 Box 的注释）。
            // `minVisibleIndex` = header 的占位：有 header 时消息项从列表下标 1 开始。
            ChatScrollNavigator(
                previews = locatorPreviews,
                listState = listState,
                minVisibleIndex = if (header != null) 1 else 0,
                onJumpToMessage = jumpToMessage,
                listIndexToPreviewIndex = { listIndex -> listIndexToPreview[listIndex] },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // 丝滑跟随：apixiaoyuan 的弹簧跟随引擎（`AutoFollowScroll.kt`，k=130 / c=24 / m=1）。
        //
        // 不是 `animateScrollToItem` 的「一次性滚动动画」：那个在流式追加时
        // 每一帧都会重启动画、速度接不上，观感是「追一下顿一下」。
        // 弹簧版把「内容高度的离散变化」转成每帧连续的速度轨迹，新内容不停涌入时
        // 速度也不归零，这才是「丝滑」的来源。
        AutoFollowScrollLazy(
            lazyState = listState,
            // ★ itemCount 是「列表项数」，不是「消息数」（2026-10-22 修）
            //
            // `AutoFollowScrollLazy` 拿它与上一帧的计数比较来判断「内容变多了」，
            // 只有变多才滚动。列表里 header 也是一个 item，所以真实项数是
            // `bubbles.size + 1`。
            //
            // 旧写法只传 `bubbles.size`，在有 header 时会漏掉那一次增量：
            // header 的显隐（比如首次加载完模型配置）不会触发跟随，
            // 表现为「上一条消息之后，列表没有跟着往下走」。
            // 传真实项数即可，语义与函数签名一致。
            itemCount = state.bubbles.size + (if (header != null) 1 else 0),
            // ★ 流式输出版本号（2026-10-24 修，第二次 —— 第一次修被自己打回）
            //
            // ## 历史：这个值被改坏过两次
            //
            // 1) 最初只传 `itemCount` → 流式时气泡数量不变 → 判据恒为
            //    「没有新增」→ 弹簧一次都没启动。用户报「AI 吐字时不滚」。
            // 2) 2026-10-23 补了「末条正文长度」进版本号，当时是对的，
            //    因为那时 `updateBubble(id) { copy(text = ...) }` 每个 delta
            //    都会改 `bubbles` 里的正文。
            // 3) 2026-10-23 晚些「长对话卡顿优化」把流式正文搬进了
            //    `ChatState.streamingText: Map<Long, String>` ——
            //    `applyStreamText` **只改 map、不动 `bubbles`**
            //    （不动才不必每个 delta 重建整个消息列表、重跑全部历史
            //    Markdown）。于是 `bubbles.last().text` 在流式期间**恒为空**，
            //    这个版本号又变回恒定不变，弹簧再次不启动 ——
            //    用户报「等 AI 输出完后再滚动」。这就是回归的成因。
            //
            // ## 现在的判据
            //
            // 优先读流式槽（流式期间唯一在变的东西），槽空了再回落到
            // `bubbles` 里的定稿正文（已 seal 的消息、或非流式场景）。
            //
            // 为什么用 `lastOrNull()?.id` 去查槽而不是「取 map 里长度最大的」：
            // 流式只可能发生在最后一条（`continueGeneration` 也要求最后一条），
            // 用 id 查是精确的；取最大值会把「上一轮残留但还没清掉的槽」
            // 也算进来，那种情况虽然不该出现，但真出现时会表现成
            // 「明明没在输出却在滚」，排查成本高。
            contentVersion = run {
                val last = state.bubbles.lastOrNull()
                val streamLen = last?.let { state.streamingText[it.id]?.length } ?: 0
                val finalLen = last?.text?.length ?: 0
                maxOf(streamLen, finalLen) + state.bubbles.size * 100_000
            },
        )

        // 长按菜单**不在这里**（2026-10-22 修，第 5 项）。
        //
        // 旧实现把长按菜单放在这个位置（`LazyColumn` 与
        // `ChatInputBar` 之间），结果是长按能弹、但弹在看不到的地方：
        // `OverlayListPopup` 的锚点是它的**父布局**（`ListPopupLayout` 用
        // `Spacer.onGloballyPositioned { parentLayoutCoordinates }` 取位置），
        // 放在这一层锚点就变成了整个 `Column`，而不是被长按的那条气泡。
        //
        // 现在菜单移到气泡自己的 `Box` 里（见上面 `items` 内部），
        // 与 miuix 官方 demo 的结构一致：popup 与触发器同属一个 Box。

        ChatInputBar(
            state = state,
            onInputChange = onInputChange,
            onSend = onSendOrEnqueue ?: onSend,
            onCancel = onCancel,
            // 键盘态不给底栏留位：底栏已被键盘盖住，留位只会多出一段空白。
            // 根容器的 `imeLiftPadding` 已让出键盘高度，这里让的是底栏高度，
            // 两者互补不叠加（见 `ImeLift.imeLiftPadding` 的 KDoc）。
            bottomPadding = effectiveScrollLimit,
            // 回复目标：从 state 里现查（气泡可能已被删，查不到就不显示预览条）。
            replyTarget = state.replyToBubbleId?.let { id ->
                state.bubbles.firstOrNull { it.id == id }
            },
            onClearReplyTo = onClearReplyTo,
            onQueueMoveUp = onQueueMoveUp,
            onQueueMoveDown = onQueueMoveDown,
            onQueueMoveToTop = onQueueMoveToTop,
            onQueueRemove = onQueueRemove,
            onQueueEdit = onQueueEdit,
            onQueueSendNow = onQueueSendNow,
            onQueueExpandedChange = onQueueExpandedChange,
            onNoticeConsumed = onNoticeConsumed,
            // ── 底部设置条（1:1 对齐 Operit `ClassicChatSettingsBar`）──
            //
            // 放在输入行**下面**，与上游一致：上游是
            // `ClassicChatSettingsBar` 用 `.align(Alignment.BottomEnd)` 贴在
            // 输入区右下，视觉上就在输入行之下。
            //
            // Praxis 没有走「覆盖在输入区上」的浮层做法，而是排进同一个
            // Column —— 浮层会盖住输入行的一部分，且键盘弹起时的位移要
            // 额外同步（上游为此写了 `translationY = -inputBarTranslationYPx`）。
            // 排进流里这两件事都不需要处理。
            modelName = modelName,
            models = models,
            activeModelIndex = activeModelIndex,
            onSelectModelIndex = onSelectModelIndex,
            autoMemoryEnabled = autoMemoryEnabled,
            onToggleAutoMemory = onToggleAutoMemory,
            skillNames = skillNames,
            disabledSkills = disabledSkills,
            onToggleSkill = onToggleSkill,
            toolNames = toolNames,
            disabledTools = disabledTools,
            onToggleTool = onToggleTool,
            // 优先用外部下发的当前档位（随 DataStore 回流即时更新）；
            // 没下发则回落到 state（预览环境）。
            thinkingEffort = currentThinkingEffort ?: state.thinkingEffort,
            onPickThinkingEffort = onPickThinkingEffort,
            onOpenModelSettings = onOpenModelSettings,
            toolTier = toolTier,
            onSetToolTier = onSetToolTier,
            onOpenToolSettings = onOpenToolSettings,
            disableStreamOutput = disableStreamOutput,
            onToggleDisableStreamOutput = onToggleDisableStreamOutput,
            autoReadReplies = autoReadReplies,
            onToggleAutoReadReplies = onToggleAutoReadReplies,
            // 图片入口与移除：直接透传。ChatScreen 不自己实现选图
            // （见 `onRequestPickImage` 的 KDoc）。
            onPickImage = onRequestPickImage,
            onPickFile = onPickFile,
            attachmentPanelOpen = attachmentPanelOpen,
            onOpenAttachmentPanel = onOpenAttachmentPanel,
            onAttachLocation = onAttachLocation,
            onAttachNotifications = onAttachNotifications,
            onCaptureScreen = onCaptureScreen,
            onTakePhoto = onTakePhoto,
            onAttachMemory = onAttachMemory,
            onAttachPackages = onAttachPackages,
            onRemoveAttachment = onRemoveAttachment,
        )

        // ── 消息编辑器（1:1 复刻 Operit `MessageEditor`）──────────────
        //
        // ## 为什么渲染在这里（根 Column 末尾）而不是在气泡的 Box 里
        //
        // 编辑器是 `Dialog`。Dialog 走独立的 window，理论上放哪都一样；
        // 但放在气泡的 `Box` 里会让每个气泡各创建一个 Dialog 宿主节点，
        // LazyColumn 复用节点时容易出现「已 dismiss 的 Dialog 又被显示」
        // 这类脏状态。放在页面顶层只创建一次，显隐由 `editingBubbleId`
        // 控制 —— 这也是 Operit 的做法。
        //
        // ## 为什么还要接一个本地 `mutableStateOf`
        //
        // `MessageEditor` 的签名要 `MutableState<String>`（它内部要边输入
        // 边把片段回写成全文，见该文件的 ①② 两个 LaunchedEffect）。
        // 这里接一个本地 state，并通过 `onEditingTextChange` 上报给 ViewModel。
        val editingBubbleId = state.editingBubbleId
        if (editingBubbleId != null && onEditingTextChange != null &&
            onCancelEditingMessage != null && onSaveEditingMessage != null &&
            onResendEditingMessage != null
        ) {
            // key = editingBubbleId：切到另一条消息时必须重建，
            // 否则 `remember` 里的片段列表还是上一条的。
            val editingContent = remember(editingBubbleId) {
                mutableStateOf(state.editingText)
            }
            // 把用户输入同步回 ViewModel（保存时 ViewModel 需要最终文本）。
            //
            // 用 `snapshotFlow` 而不是每帧直接调：编辑器内部一次输入会触发
            // 多次重组（片段列表 → 全文回写），直接调会把 ViewModel 的
            // StateFlow 打成高频写入，流式期间叠加起来会掉帧。
            LaunchedEffect(editingBubbleId) {
                snapshotFlow { editingContent.value }
                    .collect { onEditingTextChange.invoke(it) }
            }
            dev.praxis.feature.chat.editor.MessageEditor(
                editingMessageContent = editingContent,
                onCancel = onCancelEditingMessage,
                onSave = onSaveEditingMessage,
                onResend = onResendEditingMessage,
                // 只有用户消息显示「保存并重发」。
                showResendButton = state.editingCanResend,
            )
        }

        // ── 消息信息弹窗（对齐 Operit「信息」项）────────────────
        //
        // 与编辑器并列放在页面顶层：两者互斥（不会同时打开），
        // 但都由 state 里的 id 字段驱动，放在同一层最直观。
        val infoBubbleId = state.infoBubbleId
        if (infoBubbleId != null && onDismissMessageInfo != null) {
            MessageInfoDialog(
                bubble = state.bubbles.firstOrNull { it.id == infoBubbleId },
                onDismiss = onDismissMessageInfo,
            )
        }

        // ── 复制预览表（1:1 对齐 Operit「复制」后弹的那一层）──────
        //
        // 与信息弹窗、编辑器同层放页面顶层：三者互斥（同一时刻只会有一个
        // 非 null 的 id / 内容），放在同一层最直观。
        val copyPreview = state.copyPreview
        if (copyPreview != null && onDismissCopyPreview != null) {
            MessageCopyPreviewSheet(
                content = copyPreview,
                onDismiss = onDismissCopyPreview,
            )
        }

        // ── 删除确认弹窗（对齐上游 `showDeleteMessageConfirmDialog`）──
        if (state.pendingDeleteBubbleId != null &&
            onConfirmDeleteMessage != null &&
            onDismissDeleteMessage != null
        ) {
            MessageDeleteConfirmDialog(
                onConfirm = onConfirmDeleteMessage,
                onDismiss = onDismissDeleteMessage,
            )
        }

        // ── 摘要全文弹窗（1:1 对齐 Operit 点摘要分割线后的 Dialog）──
        //
        // 与其它三个弹窗同层。判据用 `showSummaryDetail` 而不是「compacted 非空」：
        // 摘要一直存在（分割线要一直显示），但弹窗只在用户点开时才出现。
        val summaryDetail = state.compacted
        if (state.showSummaryDetail && summaryDetail != null &&
            onDismissSummaryDetail != null
        ) {
            SummaryDetailDialog(
                compacted = summaryDetail,
                onDismiss = onDismissSummaryDetail,
            )
        }
    }
}

/**
 * 输入栏（对齐 Operit `ClassicChatInputSection` 的纵向结构）。
 *
 * 顺序照抄上游：**阶段提示在上、输入行在下**。理由是提示紧贴输入行时，
 * 用户视线本来就在输入行附近，不需要去找。
 */
@Composable
private fun ChatInputBar(
    state: ChatState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: (() -> Unit)?,
    bottomPadding: Dp,
    /** 回复目标（null 表示不在回复）。 */
    replyTarget: ChatBubble? = null,
    /** 清除回复目标。 */
    onClearReplyTo: (() -> Unit)? = null,
    onQueueMoveUp: ((Long) -> Unit)? = null,
    onQueueMoveDown: ((Long) -> Unit)? = null,
    onQueueMoveToTop: ((Long) -> Unit)? = null,
    onQueueRemove: ((Long) -> Unit)? = null,
    onQueueEdit: ((Long) -> Unit)? = null,
    onQueueSendNow: ((Long) -> Unit)? = null,
    onQueueExpandedChange: ((Boolean) -> Unit)? = null,
    /**
     * 提示已展示完，可以清掉。
     *
     * 由 `ChatInputBar` 内部的 4 秒计时器调用；null 表示不自动清除
     * （预览/测试环境没有 ViewModel，让提示留在那里比崩溃好）。
     */
    onNoticeConsumed: (() -> Unit)? = null,
    /** 当前模型名（显示在设置条上）。 */
    modelName: String = "",
    /** 模型池（模型胶囊的就地选择列表）。空表示没配置过。 */
    models: List<String> = emptyList(),
    /** 当前生效的模型下标。 */
    activeModelIndex: Int = 0,
    /** 选第 index 个模型；null 表示不支持就地切换。 */
    onSelectModelIndex: ((Int) -> Unit)? = null,
    autoMemoryEnabled: Boolean = true,
    onToggleAutoMemory: ((Boolean) -> Unit)? = null,
    skillNames: List<String> = emptyList(),
    disabledSkills: Set<String> = emptySet(),
    onToggleSkill: ((String, Boolean) -> Unit)? = null,
    /** 全部工具名（底栏工具组的就地开关数据源）。 */
    toolNames: List<String> = emptyList(),
    disabledTools: Set<String> = emptySet(),
    onToggleTool: ((String, Boolean) -> Unit)? = null,
    /** 当前思考强度（设置条上的胶囊）。 */
    thinkingEffort: ThinkingEffort = ThinkingEffort.None,
    /** 切换思考强度；null 表示不渲染该下拉。 */
    onPickThinkingEffort: ((ThinkingEffort) -> Unit)? = null,
    /** 进模型设置；null 表示不渲染模型分区。 */
    onOpenModelSettings: (() -> Unit)? = null,
    /** 工具权限档位（Tune 菜单「工具」分区）；null 表示不渲染该分区。 */
    toolTier: PermissionTier? = null,
    /** 切换工具档位。 */
    onSetToolTier: ((PermissionTier) -> Unit)? = null,
    /** 进工具管理页。 */
    onOpenToolSettings: (() -> Unit)? = null,
    /** 是否关闭流式输出（Tune 菜单「行为」分区）。 */
    disableStreamOutput: Boolean = false,
    /** 切换流式输出；null 表示不渲染「行为」分区。 */
    /** 是否自动朗读（null = 设备无 TTS）。 */
    autoReadReplies: Boolean? = null,
    onToggleAutoReadReplies: ((Boolean) -> Unit)? = null,
    onToggleDisableStreamOutput: ((Boolean) -> Unit)? = null,
    /**
     * 点击「选图片」。
     *
     * ## 为什么是回调而不是在这里直接调 ActivityResult
     *
     * `rememberLauncherForActivityResult` 必须在 **Activity 的 Compose 树**里
     * 注册，而 `ChatInputBar` 是 private 的纯展示组件（预览环境也会渲染它）。
     * 在这里注册会让 `@Preview` 崩（没有 Activity）。所以把「启动选图器」
     * 这件事上提到 `ChatScreen`/`ChatTab`，这里只画按钮。
     *
     * null 表示不渲染图片按钮（预览/未接线的调用点），与其它可选回调同一纪律。
     */
    onPickImage: (() -> Unit)? = null,
    /** 选文件（附件面板的「文件」格）。null = 上层没接。 */
    onPickFile: (() -> Unit)? = null,
    /** 附件面板是否展开（由上层持有，便于点外部关闭）。 */
    attachmentPanelOpen: Boolean = false,
    /** 切换附件面板；null = 不显示加号按钮。 */
    onOpenAttachmentPanel: (() -> Unit)? = null,
    /** 插入位置（结果为文本，写进输入框）。null = 不提供。 */
    onAttachLocation: (() -> Unit)? = null,
    /** 插入当前通知（结果为文本）。null = 不提供。 */
    onAttachNotifications: (() -> Unit)? = null,
    /** 插入最近记忆（文本）。null = 不提供。 */
    /** 拍照（系统相机）。null = 不提供。 */
    /** 屏幕内容（截当前这屏）。null = 不提供。 */
    onCaptureScreen: (() -> Unit)? = null,
    onTakePhoto: (() -> Unit)? = null,
    onAttachMemory: (() -> Unit)? = null,
    /** 插入已安装应用列表（文本）。null = 不提供。 */
    onAttachPackages: (() -> Unit)? = null,
    /** 移除第 index 张待发图片。 */
    onRemoveAttachment: ((Int) -> Unit)? = null,
) {
    // ## 提示自动消失（4 秒）
    //
    // `notice` 存在 `ChatState` 里，所以不会随重组消失。不自动清除的话
    // 它会一直挂在输入栏上方，直到用户下一次发送 —— 而用户可能只是
    // 看了一眼菜单就退出了，再进来还看到「继续只能接在最后一条上」，
    // 会以为是自己刚刚做错了什么。
    //
    // 4 秒是「读得完但不会等太久」的长度：这两句最长 30 字，正常阅读
    // 不到 3 秒。用 `notice` 作 key：新提示进来时会取消旧计时重新开始，
    // 不会出现「第二条被第一条的计时器提前清掉」。
    if (state.notice != null) {
        LaunchedEffect(state.notice) {
            kotlinx.coroutines.delay(4_000)
            onNoticeConsumed?.invoke()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MiuixTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = bottomPadding + 8.dp,
                ),
        ) {
            // ── 待发图片预览条（2026-10-24，多模态输入）──────────
            //
            // ## 为什么放在回复预览条**之上**
            //
            // 图片是「即将发送的内容」，比「在回复谁」更靠近用户当下的操作；
            // 而且图片条更高（80dp），放下面会把输入行推得太远。
            //
            // ## 为什么必须有 × 号
            //
            // 选错图是最常见的操作失误（相册点错一张）。没有单独的移除入口，
            // 用户只能清空整个附件再重选 —— 而 `canSend` 允许「只发图」，
            // 清空后发送键直接变灰，容易让人以为功能坏了。
            if (state.attachments.isNotEmpty() && onRemoveAttachment != null) {
                AttachmentStrip(
                    images = state.attachments,
                    onRemove = onRemoveAttachment,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            // ── 回复预览条（1:1 复刻 Operit `ClassicChatInputSection` 的 reply preview）──
            //
            // ## 上游的版式
            //
            // ```
            // Surface(fillMaxWidth, padding h16 v4, surfaceVariant, 圆角 8dp)
            //  └ Row(padding 8dp, 居中)：[Reply 图标 16dp primary] 8dp [预览文本]
            // ```
            //
            // 预览文本的处理与上游一致：**先削掉所有 XML 标签**，再截断 50 字加省略号。
            // 削标签是必须的：被回复的消息里可能有 `<think>` / `<tool>`，不削会把
            // 上百字符的标签挤进这条只有一行高的预览里。
            if (replyTarget != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Reply,
                            contentDescription = "回复",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = replyPreviewText(replyTarget.text),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (onClearReplyTo != null) {
                            // 关闭按钮：这是唯一的「我不想回复了」出口，必须有。
                            // 没有它用户只能发出去一条不想发的回复。
                            //
                            // ## 为什么必须显式传 minWidth / minHeight
                            //
                            // miuix 的 `IconButton` 内部是
                            // `defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)`
                            // （见 `IconButtonDefaults`）。只写 `size(22.dp)` 会被
                            // 这个下限**撑到 40dp**，整条预览条从 26dp 高变 48dp，
                            // 输入区会被顶上去一大块。两个参数一起传，下限才真的
                            // 降到 22dp。
                            IconButton(
                                onClick = onClearReplyTo,
                                minWidth = 22.dp,
                                minHeight = 22.dp,
                                modifier = Modifier.size(22.dp),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Regular.Close,
                                    contentDescription = "取消回复",
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ── 上下文用量条 ────────────────────────────────────────
            //
            // 位置在**阶段提示之上**：它是「本轮请求的规模」，
            // 属于请求的元信息；阶段提示是「此刻在做什么」，
            // 按「静态信息在下、动态状态在上」的常规排布反过来更贴
            // 用户视线（输入栏 → 用量 → 状态）。
            ContextUsageBar(
                usage = state.contextUsage,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            // ── 待发队列 ──────────────────────────────────────────
            //
            // 只在「操作回调全齐」时才显示：否则会出现「列得出来但按不动」
            // 的死面板。`onQueueSendNow` 也是必需项 —— 它是 Operit 面板里的
            // 第二个动作，缺了它这一行就比上游少一个按钮。
            if (onQueueMoveUp != null && onQueueMoveDown != null &&
                onQueueMoveToTop != null && onQueueRemove != null &&
                onQueueEdit != null && onQueueSendNow != null &&
                onQueueExpandedChange != null
            ) {
                PendingQueuePanel(
                    messages = state.queue.items,
                    expanded = state.queueExpanded,
                    onExpandedChange = onQueueExpandedChange,
                    onEdit = onQueueEdit,
                    onSendNow = onQueueSendNow,
                    onDelete = onQueueRemove,
                    onMoveUp = onQueueMoveUp,
                    onMoveDown = onQueueMoveDown,
                    onMoveToTop = onQueueMoveToTop,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            // ── 阶段提示 ────────────────────────────────────────────
            //
            // 上游用 SimpleAnimatedVisibility + 线性进度条 + 文案。
            // 这里用同样的入场/退场曲线（fade 300 / expand 400，比默认稍慢更稳）。
            //
            // 为什么不是 LinearProgressIndicator 而是「转圈 + 文案」：
            // 上游的线性进度条承载的是**可量化进度**（工具执行到第几 %）。
            // Praxis 的工具层没有进度上报（`ToolExecutor.executeAll` 是同步阻塞返回），
            // 画一条不动或乱动的进度条比如实说「正在进行」更糟。
            AnimatedVisibility(
                visible = state.stage != ChatStage.Idle,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = PraxisMotion.Expansion.FadeIn,
                        easing = PraxisMotion.Curves.Standard,
                    ),
                ) + expandVertically(
                    animationSpec = tween(
                        durationMillis = PraxisMotion.Expansion.Expand,
                        easing = PraxisMotion.Curves.Standard,
                    ),
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = PraxisMotion.Expansion.FadeOut,
                        easing = PraxisMotion.Curves.Standard,
                    ),
                ) + shrinkVertically(
                    animationSpec = tween(
                        durationMillis = PraxisMotion.Expansion.Shrink,
                        easing = PraxisMotion.Curves.Standard,
                    ),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        // ## 必须用 `size` 参数，不能只给 `Modifier.size`
                        //
                        // miuix 的 `CircularProgressIndicator` 内部是
                        // `Canvas(modifier = modifier.semantics{}.size(size))`，
                        // 而 `size` 的默认值是
                        // `ProgressIndicatorDefaults.DefaultCircularProgressIndicatorSize = 30.dp`。
                        //
                        // 链式 Modifier 里**后应用的尺寸约束生效**，所以只传
                        // `Modifier.size(14.dp)` 会被内部那句 `.size(30.dp)` 覆盖 →
                        // 圆环实际按 30dp 画。表现是：这一行被撑到 30dp 高，
                        // 圆环超出 14dp 文案的行高，视觉上「钻」到下方输入框底下
                        // （用户截图里那个错位的转圈）。
                        size = 14.dp,
                        strokeWidth = 1.6.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.stage.label,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // ── 一次性操作提示（如「继续」不适用时的原因）──────────
            //
            // ## 为什么用 AnimatedVisibility 而不是直接 if
            //
            // 提示会在下一次操作时被清空（见 `send` / `continueGeneration`），
            // 直接从有到无会让下方输入栏跳一下。淡出 + 收缩与上面的
            // 「正在回复」提示保持同一套动效，两处位置相邻、行为才一致。
            //
            // ## 为什么它自己一排，不与「正在回复」共用一行
            //
            // 两者可能同时存在（用户点了继续、同时上一轮还在收尾）。
            // 挤在一行会让文案互相截断，而这两句都必须完整读得到 ——
            // 一句是「现在在干什么」，一句是「你刚才那个操作为什么没生效」。
            AnimatedVisibility(
                visible = state.notice != null,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = PraxisMotion.Expansion.FadeIn,
                        easing = PraxisMotion.Curves.Standard,
                    ),
                ) + expandVertically(
                    animationSpec = tween(
                        durationMillis = PraxisMotion.Expansion.Expand,
                        easing = PraxisMotion.Curves.Standard,
                    ),
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = PraxisMotion.Expansion.FadeOut,
                        easing = PraxisMotion.Curves.Standard,
                    ),
                ) + shrinkVertically(
                    animationSpec = tween(
                        durationMillis = PraxisMotion.Expansion.Shrink,
                        easing = PraxisMotion.Curves.Standard,
                    ),
                ),
            ) {
                Text(
                    text = state.notice ?: "",
                    style = MiuixTheme.textStyles.footnote1,
                    // 用 error 色：这是一句「你的操作没生效」的负面反馈。
                    // 用普通灰会像一条普通说明，用户容易忽略掉真正的原因。
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // ── 输入行 ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 输入框：miuix TextField，不是 BasicTextField。
                //
                // 上一版用裸 BasicTextField + 自己画的 Surface 背景。换成 miuix
                // TextField 后才有：聚焦时边框动画、squircle 圆角、标签态。
                // `useLabelAsPlaceholder = true` 让 label 在空输入时充当占位符 ——
                // 这正是「说点什么…」该有的行为，不需要自己写 decorationBox。
                TextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    label = "说点什么…",
                    useLabelAsPlaceholder = true,
                    // 只有一项设置：让回车保持「换行」。
                    //
                    // 不绑 Send：用户既会用回车换行，也会用它选词（中文输入法），
                    // 绑成发送会误发。
                    // 也不加 `KeyboardCapitalization.Sentences`：中文没有首字母大写，
                    // 而用户在这里贴 URL / 代码时被自动大写首字母是纯粹的干扰。
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    minLines = 1,
                    maxLines = 5,
                    // 不传 textStyle：默认是 MiuixTheme.textStyles.main（17sp）。
                    //
                    // 这里曾写成 body2（14sp），与 miuix TextField 的
                    // `LabelFontSizeNormal = 17sp` 对不上 —— 空输入时占位符是 17sp、
                    // 一打字正文变 14sp，字会「缩一下」。要么两者一致，要么不用 label。
                    // 默认值恰好与 label 同字号，所以直接省掉这个参数。
                )

            }

            // ═══════════════════════════════════════════════════════════
            // 底栏功能行 —— 1:1 对齐 Operit `AgentChatInputSection.kt:883`
            // ═══════════════════════════════════════════════════════════
            //
            // ## 上一轮我找错了组件
            //
            // 我一直对着 `ClassicChatSettingsBar.kt` 改 —— 那是 classic 输入样式
            // 的组件，只有一个孤零零的齿轮，**不是**用户圈的那一条。
            //
            // 真正的那一条来自 `AgentChatInputSection.kt:883` 的 Row：
            //
            // ```
            // [模型胶囊 weight(1f)] [Tune 34dp] [Add 36dp] [发送 36dp]
            // ```
            //
            // 它在输入框**下方**，与输入框隔 `Spacer(8.dp)`（见第 881 行）。
            //
            // ## 数值逐项照搬上游
            //
            // | 项 | 上游 | 说明 |
            // |---|---|---|
            // | 胶囊圆角 | 12dp | 与输入框同族 |
            // | 胶囊描边 | 1dp, outline alpha 0.2 | 极淡，底栏要轻 |
            // | 胶囊最大宽 | 220dp | 防长模型名把后面按钮挤出去 |
            // | 箭头 | 18dp | 展开时翻上/下（Praxis 跳设置页，无展开态） |
            // | Tune 容器 | 34dp / 图标 20dp | 展开时 tint 变 primary |
            // | Add 容器 | 36dp / 图标 24dp | 展开时 tint 变 primary |
            // | 间距 | Tune 前 6dp、Add 前 8dp、发送前 6dp | |
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ── 模型胶囊（撑满剩余空间）──────────────────
                if (models.isNotEmpty() || onOpenModelSettings != null) {
                    ModelPill(
                        modelName = modelName,
                        models = models,
                        activeIndex = activeModelIndex,
                        onClickModel = { index: Int -> onSelectModelIndex?.invoke(index) },
                        onManageModels = { onOpenModelSettings?.invoke() },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // ── 附件面板（加号点开）────────────────────
                //
                // ## 2026-10-29：从内联展开改为 Popup 浮层
                //
                // 旧实现是 `AnimatedVisibility` 内联在底栏之前，展开时把底栏
                // 往下推。同一个底栏里，齿轮（Tune）浮起一张卡、加号却推布局，
                // 两条路径观感不一致。现在 `AttachmentPanel` 内部改成
                // `Popup(TopStart)` + 满屏 `Box` + `Card` 贴 `BottomEnd`，
                // 与 Tune 逐字同构——所以这里的物理位置不再重要，保留在原处。
                //
                // `onDismiss` 必须是**关闭**而不是 toggle：点空白处时面板已经是
                // 展开态，toggle 会先关再被同一次点击重开。这里包一层
                // `if (attachmentPanelOpen)`，避免面板已关时点击又把面板「关」一次
                // （状态无变化，无害，但语义更干净）。
                AttachmentPanel(
                    visible = attachmentPanelOpen,
                    onDismiss = { if (attachmentPanelOpen) onOpenAttachmentPanel?.invoke() },
                    onPickImage = onPickImage,
                    onPickFile = onPickFile,
                    onAttachLocation = onAttachLocation,
                    onAttachNotifications = onAttachNotifications,
                    onCaptureScreen = onCaptureScreen,
                    onTakePhoto = onTakePhoto,
                    onAttachMemory = onAttachMemory,
                    onAttachPackages = onAttachPackages,
                )

                // ── Tune（齿轮）──────────────────────────────
                if (onOpenModelSettings != null ||
                    onPickThinkingEffort != null ||
                    onToggleDisableStreamOutput != null ||
                    (toolTier != null && onSetToolTier != null)
                ) {
                    ChatSettingsBar(
                        modelName = modelName,
                        thinkingEffort = thinkingEffort,
                        onPickThinkingEffort = onPickThinkingEffort,
                        onOpenModelSettings = onOpenModelSettings,
                        toolTier = toolTier,
                        onSetToolTier = onSetToolTier,
                        onOpenToolSettings = onOpenToolSettings,
                        disableStreamOutput = disableStreamOutput,
                        onToggleDisableStreamOutput = onToggleDisableStreamOutput,
                        autoReadReplies = autoReadReplies,
                        onToggleAutoReadReplies = onToggleAutoReadReplies,
                        // ── 记忆 / 插件（对齐 Operit 的记忆组与插件组）──
                        autoMemoryEnabled = autoMemoryEnabled,
                        onToggleAutoMemory = onToggleAutoMemory,
                        skillNames = skillNames,
                        disabledSkills = disabledSkills,
                        onToggleSkill = onToggleSkill,
                        toolNames = toolNames,
                        disabledTools = disabledTools,
                        onToggleTool = onToggleTool,
                        // ── 模型池（就地切换，不再跳页）──
                        models = models,
                        activeModelIndex = activeModelIndex,
                        onSelectModel = onSelectModelIndex,
                        onManageModels = onOpenModelSettings,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }

                // ── 加号（附件）──────────────────────────────
                //
                // ## 加号 → 附件面板（2026-10-25，对齐 Operit）
                //
                // Operit 的加号（AgentChatInputSection.kt:960-986）点开的是
                // `AttachmentSelectorPanel`（AttachmentSelector.kt:88），里面有
                // 8 项：图片 / 拍照 / 记忆 / 文件 / 屏幕内容 / 通知 / 位置 / 智能包。
                // 面板从**底边向上**展开（AnimatedVisibility + expandVertically，
                // expandFrom = Bottom，200ms）。
                //
                // 之前这里是相册图标直接跳选图 —— 因为只有图片一条通路。
                // 现在改成加号 + 面板，面板里能用的项真实可用，不能用的项
                // 显示为禁用态（见 `AttachmentPanel` 的 `enabled` 参数）。
                if (onOpenAttachmentPanel != null) {
                    IconButton(
                        onClick = onOpenAttachmentPanel,
                        minWidth = 36.dp,
                        minHeight = 36.dp,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "添加附件",
                            tint = if (attachmentPanelOpen) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // ── 发送 / 停止 ──────────────────────────────
                SendAction(
                    state = state,
                    onSend = onSend,
                    onCancel = onCancel,
                )
            }
        }
    }
}


/**
 * 预览条上的一行摘要（对齐上游 `ClassicChatInputSection` 的 reply 预览）。
 *
 * ## 两个处理都有必要
 *
 * 1. **先削掉所有 XML 标签**：被回复的消息里常有 `<think>` / `<tool name=…>`
 *    这类块。不削的话预览条第一行会是 `<think>` 或一大段属性，
 *    用户看不到任何正文。
 * 2. **再截断 50 字**：预览条只有一行高（`maxLines = 1`），
 *    但仍要截断 —— 否则 `Text` 的测量会为一整个千字消息排版，
 *    每次重组都白算一遍。上游同样截 50 字。
 *
 * 顺序不能反：先截断会切出半截标签，`<[^>]*>` 就匹配不到了。
 */
/**
 * 本条气泡之前、**同一轮内**是否已经出现过助手正文。
 *
 * ## 「同一轮」的边界
 *
 * 一轮 = 一条用户消息（或一块摘要）之后、到下一条用户消息之前的全部内容。
 * 工具卡片算这一轮的**组成部分**，不构成新轮次 —— 模型「想 → 调 → 看结果 → 再说」
 * 在语义上是同一次回答。
 *
 * 所以向前扫到 `User` / `Summary` 就停，中途遇到 `Tool` 直接跳过。
 *
 * ## 为什么 Error 也算「已经开过头」
 *
 * 错误气泡本身就是这一轮的回答结果。用户重试后若能继续，屏幕上不该出现
 * 第二个「回复」头 —— 那会读成「AI 又开口了一次」。
 *
 * ## 返回值的用法
 *
 * `true` 表示本轮已经有头了，当前这条**不**再画标签。
 * 因此调用方写 `showLabel = !hasEarlierAssistantInTurn(...)`。
 */
private fun hasEarlierAssistantInTurn(bubbles: List<ChatBubble>, index: Int): Boolean {
    var i = index - 1
    while (i >= 0) {
        when (bubbles[i].role) {
            BubbleRole.User, BubbleRole.Summary -> return false
            BubbleRole.Assistant, BubbleRole.Error -> return true
            // 工具卡片属于本轮内容，继续往前找。
            BubbleRole.Tool -> Unit
        }
        i--
    }
    return false
}

private fun replyPreviewText(raw: String): String {
    val plain = raw.replace(Regex("<[^>]*>"), "").trim().replace(Regex("\\s+"), " ")
    return if (plain.length > 50) plain.take(50) + "..." else plain
}

/**
 * 发送 / 停止按钮（圆形图标，对齐上游的 36dp 圆钮）。
 *
 * ## 为什么从 TextButton 改成 IconButton
 *
 * 文字按钮的宽度随文案变：「发送」两字比「生成中」三字窄，状态切换时
 * 按钮会横向跳一下，整行跟着重排。图标按钮尺寸固定，切换时只有图标与配色变。
 *
 * ## 三态
 *
 * | 状态 | 图标 | 底色 | 行为 |
 * |---|---|---|---|
 * | 空闲有内容 | Send | primary | 发送 |
 * | 处理中且传了 onCancel | Stopwatch | error | 掐断 |
 * | 空闲无内容 | Send | surfaceContainerHigh | 禁用 |
 *
 * 上游第二态用的是「取消/停止」语义；这里用 `Stopwatch` 而不是 `Close`，
 * 因为 `Close` 在本项目里已经表示「删除/关闭」（如清除密钥），
 * 混用会让用户以为按了会丢弃输入。
 */
@Composable
private fun SendAction(
    state: ChatState,
    onSend: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    val busy = state.isBusy
    // 处理中但没传 onCancel：显示禁用态而不是假装能停。
    val canCancel = busy && onCancel != null
    val canSendNow = !busy && state.canSend

    val container = when {
        canCancel -> MiuixTheme.colorScheme.error
        canSendNow -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val tint = when {
        canCancel -> MiuixTheme.colorScheme.onError
        canSendNow -> MiuixTheme.colorScheme.onPrimary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    IconButton(
        onClick = { if (canCancel) onCancel?.invoke() else onSend() },
        enabled = canCancel || canSendNow,
        backgroundColor = container,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = if (canCancel) {
                MiuixIcons.Regular.Stopwatch
            } else {
                MiuixIcons.Regular.Send
            },
            contentDescription = if (canCancel) "停止" else "发送",
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 待发图片预览条（横向可滚动缩略图 + 每张一个移除按钮）。
 *
 * ## 为什么用横向 Row 而不是网格
 *
 * 最多 4 张（`MAX_ATTACHMENTS`），横排一行放得下 2~3 张 80dp 缩略图，
 * 超出的部分横向滚动。用网格会让输入区高度随图片数量跳变
 * （2 张一行、3 张两行），那会让输入框在选图过程中上下移动 ——
 * 用户正在打字时会很难受。
 *
 * ## 为什么自己解码 base64 而不是复用相册 URI
 *
 * `ChatImage` 里只有 base64（见它的 KDoc：图片内容在选中时就被固定，
 * 不依赖相册文件还在不在）。预览也必须从这里解，
 * 否则会出现「预览有图、发送后模型看不到」的偏差。
 *
 * ## 为什么要 `remember` 解码结果
 *
 * `BitmapFactory.decodeByteArray` 每张要分配几 MB 位图。不缓存的话
 * 每次重组（打字、流式更新）都会重解一次 —— 那是明显的掉帧来源。
 */
@Composable
private fun AttachmentStrip(
    images: List<dev.praxis.core.ai.ChatImage>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = images,
            // 用下标作 key：同一张图可以被重复选中两份（用户可能想用同一张
            // 图问两个问题），按内容哈希会撞 key → LazyRow 直接抛重复 key 异常。
            key = { index, _ -> index },
        ) { index, image ->
            Box {
                AttachmentThumbnail(image)
                // × 号叠在右上角。
                //
                // 用 surfaceContainerHigh 打底而不是全透明：缩略图可能是任意
                // 颜色（白图上的白 × 完全看不见），必须有底色托住。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(20.dp)
                        .background(
                            color = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                            shape = CircleShape,
                        )
                        .clickable { onRemove(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Close,
                        contentDescription = "移除这张图片",
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }
}

/**
 * 单张缩略图。
 *
 * 解码失败时画占位而不是崩：base64 理论上不会坏（是我们自己编的），
 * 但宁可显示「读取失败」也不要让整个聊天页崩。
 */
@Composable
private fun AttachmentThumbnail(image: dev.praxis.core.ai.ChatImage) {
    val bitmap = remember(image.base64) {
        runCatching {
            val bytes = android.util.Base64.decode(image.base64, android.util.Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(80.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, shape)
            .clip(shape)
            .border(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.3f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "待发送图片",
                // ContentScale.Crop：缩略图统一 80×80 方框，不等比填充会把
                // 人像拉变形；Crop 保比例、裁边缘，与系统相册缩略图观感一致。
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "读取失败",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 变体切换条：`‹  2 / 4  ›`。
 *
 * ## 为什么这个控件长得这么小
 *
 * 它挂在**每条有多版本的回答**下面。做成按钮组或带边框的行会让长对话
 * 里到处是方块，视觉噪音太大。这里用最小的可点区域（每侧 28dp），
 * 数字用 footnote 级字号 —— 与 Operit 的做法一致：
 * 它的变体切换也是「两个箭头 + 一个分数」的低调样式。
 *
 * ## 为什么边界处的按钮不隐藏而是禁用
 *
 * 到第 1 版时隐藏「‹」会让整条**宽度变化**，相邻的分数跟着平移 ——
 * 用户连点两次会点到被挪走的位置。禁用（灰掉）保持布局稳定。
 *
 * @param index 当前第几版（0 起）。
 * @param count 总共几版。
 * @param onPrev 上一版；null = 不可用（例如回调未接线）。
 * @param onNext 下一版；null = 不可用。
 */
@Composable
private fun VariantSwitcher(
    index: Int,
    count: Int,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    // 边界判定：第一版没有「上一个」，最后一版没有「下一个」。
    // 传进来的回调用 `enabled` 控制而不是置 null —— null 表示
    // 「整条切换条不可用」，与「这一侧到边界了」是两件事。
    val canPrev = onPrev != null && index > 0
    val canNext = onNext != null && index < count - 1

    Row(
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VariantArrow(
            symbol = "‹",
            enabled = canPrev,
            description = "上一个版本",
            onClick = onPrev,
        )
        Text(
            // 显示用 1 起（用户看到的是「第 2 个，共 4 个」），
            // 而内部 index 是 0 起 —— 转换只在这一处做。
            text = "${index + 1} / $count",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        VariantArrow(
            symbol = "›",
            enabled = canNext,
            description = "下一个版本",
            onClick = onNext,
        )
    }
}

/**
 * 切换条上的单个箭头。
 *
 * ## 为什么点击区域比字形大
 *
 * 字形（`‹`）本身只有约 6dp 宽，直接用它的边界做点击区几乎点不中。
 * 这里给 28dp 的可点区域，字形居中 —— 达到 48dp 的最小触控标准
 * 会显得很空，28dp 是「小控件但不难点」的折中。
 */
@Composable
private fun VariantArrow(
    symbol: String,
    enabled: Boolean,
    description: String,
    onClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            // 无障碍：字形 `‹` 读屏会念成「小于号」之类无意义的词。
            // 用 `contentDescription` 覆盖成「上一个版本」。
            //
            // 不可用时置空描述而不是保留原描述：读屏不应该把一个
            // 已禁用的按钮念成可操作项。
            .semantics {
                contentDescription = if (enabled) description else ""
            }
            .then(
                if (enabled && onClick != null) {
                    // 只在可用时才挂 clickable：不可用时挂空 lambda 会让
                    // 点击**被吞掉**（有涟漪但没反应），比不可点更糟。
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = MiuixTheme.textStyles.body2,
            color = if (enabled) {
                MiuixTheme.colorScheme.primary
            } else {
                // 用「次级文字色 + 低透明」表示禁用：直接不画会让
                // 用户以为布局错了。
                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
            },
        )
    }
}