package dev.praxis.feature.chat.widget

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.praxis.ui.widgets.ChatDisplayStyle
import dev.praxis.ui.widgets.MarkdownText
import dev.praxis.ui.widgets.StreamingText
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 气泡角色。与 `core:ai` 的 `ChatRole` 分开：UI 多一个 Error 态。 */
enum class BubbleKind {
    User,
    Assistant,
    Tool,
    Error,

    /**
     * 摘要块（用户插入的历史压缩）。
     *
     * ## 为什么单独一个 kind 而不是复用 Assistant
     *
     * 摘要要看起来「不像一句回话」——它是用户主动插进来的一块历史。
     * 复用 Assistant 的话，配色、圆角、对齐全与模型回答一致，
     * 用户在长对话里根本分不出哪条是模型说的、哪条是自己压的。
     *
     * 视觉上用：
     *  - 虚线/细边框（`surfaceContainer` + 1dp outline）而不是实心气泡；
     *  - 更小的字号 + 更宽的左右留白（读起来像「注释」不像「发言」）；
     *  - 顶部一行小标签「对话摘要」，与正文分开。
     *
     * 这与 Operit 给 `sender = "summary"` 单独渲染分支同思路。
     */
    Summary,
}

/**
 * 一条消息的渲染入口：按 [ChatDisplayStyle] 分流到两套形态。
 *
 * ## 为什么要有统一入口，而不是让 ChatScreen 自己 when
 *
 * `ChatScreen` 的 `items` lambda 里已经有「工具卡片 / 气泡 / 长按菜单」三层分支，
 * 再塞一个风格分支会到四层。而两套风格**共用同一个长按菜单锚点**
 * （菜单挂在 `ChatScreen` 里的 Box 上），所以分流只发生在「画什么」这一层，
 * 手势与菜单不需要各写一遍。
 *
 * [BubbleKind.Tool] 正常不走这里 —— 工具卡片要占满整行且有自己的折叠逻辑，
 * 由 `ToolCallCard` 渲染。
 */
@Composable
fun ChatMessageItem(
    style: ChatDisplayStyle,
    kind: BubbleKind,
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    /**
     * 这条消息附带的图片（仅用户消息会有）。
     *
     * 默认空列表：保住所有现有调用点（含预览与测试）。
     */
    images: List<dev.praxis.core.ai.ChatImage> = emptyList(),
    /**
     * 是否画角色标签（`提问` / `回复`）。
     *
     * ## 为什么需要这个开关（2026-10-29）
     *
     * 一次「回复」在数据上是**多条** Assistant 气泡：模型先说话、调工具、
     * 再说话、再调工具、最后收尾。每条都会建一个独立气泡（`runToolCalls`
     * 里 `followUpId`），于是界面上同一轮回复被拆成「回复 / 回复 / 回复」
     * 三段，中间夹着工具卡片。
     *
     * 正确的形态是：**一轮只打一个头**，工具卡片与后续正文都挂在同一个头下面。
     * 由调用方（`ChatScreen`）按列表顺序算出「本条是不是本轮第一条正文」，
     * 本组件只负责跟不跟标签。
     *
     * 默认 true：所有存量调用点（预览、测试）观感不变。
     */
    showLabel: Boolean = true,
) {
    when (style) {
        ChatDisplayStyle.Bubble -> MessageBubble(
            kind = kind,
            text = text,
            modifier = modifier,
            streaming = streaming,
            onRetry = onRetry,
            onLongPress = onLongPress,
            images = images,
            showLabel = showLabel,
        )
        ChatDisplayStyle.Cursor -> CursorMessage(
            kind = kind,
            text = text,
            modifier = modifier,
            streaming = streaming,
            onRetry = onRetry,
            onLongPress = onLongPress,
            images = images,
            showLabel = showLabel,
        )
    }
}

/**
 * 聊天气泡（**Bubble 风格**）：用户 / AI / 工具 / 错误四种。
 *
 * ## 四种形态
 *
 * | 角色 | 背景 | 内容色 | 布局 |
 * |---|---|---|---|
 * | User | `primary` | `onPrimary` | 右对齐，最大 300dp |
 * | Assistant | `surfaceContainerHigh` | `onSurface` | 左对齐，最大 360dp |
 * | Tool | 走 `ToolCallCard`，不用气泡 | — | 占满整行 |
 * | Error | `errorContainer` | `onErrorContainer` | 左对齐 + 重试按钮 |
 *
 * 宽度用**上限 dp**而不是百分比：窄屏上自然收缩到可用宽度，宽屏上才落到上限。
 *
 * ## 工具为什么不用气泡
 *
 * 工具卡片要显示参数、结果、耗时、状态，塞进气泡会被圆角与内边距夹住，
 * 长内容换行很难看。工具直接占满整行。
 *
 * ## 长按为什么用 [combinedClickable]
 *
 * `pointerInput { detectTapGestures }` 要自己处理「滑动时不触发、松手才触发」，
 * 而 combinedClickable 由 Compose 保证与滚动容器的手势分发正确配合 ——
 * 在 LazyColumn 里手写 tap 手势经常会出现「一滑就误触」。
 */
@Composable
fun MessageBubble(
    kind: BubbleKind,
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    images: List<dev.praxis.core.ai.ChatImage> = emptyList(),
) {
    val isUser = kind == BubbleKind.User
    val isError = kind == BubbleKind.Error
    // 摘要块：不是「发言」，是一块插入的历史。
    val isSummary = kind == BubbleKind.Summary

    val container: Color = when {
        isError -> MiuixTheme.colorScheme.errorContainer
        isUser -> MiuixTheme.colorScheme.primary
        // 摘要用比助手气泡**更浅**的底：视觉上退后一层，
        // 读起来像「注释」而不是「另一个人的发言」。
        isSummary -> MiuixTheme.colorScheme.surfaceContainer
        else -> MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val content: Color = when {
        isError -> MiuixTheme.colorScheme.onErrorContainer
        isUser -> MiuixTheme.colorScheme.onPrimary
        // 摘要正文也用弱一档的前景色，进一步拉开与模型回答的对比。
        // 用 `onSurfaceVariantSummary` 而不是 `onSurfaceVariant`：miuix 的
        // `Colors` 里根本没有后者（编译期实测 Unresolved reference）。
        isSummary -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MiuixTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier
                // 用户消息更紧凑（通常短），AI 消息放宽（代码/列表需要宽度）。
                // 摘要更窄：它是一段压缩文本，不该占满整行宽度抢视觉重心。
                .widthIn(max = if (isUser) 300.dp else if (isSummary) 320.dp else 360.dp)
                .then(
                    // 摘要画一圈细边框（而不是靠阴影/颜色对比）：
                    // 边框让它读起来像「一个块」，与气泡的实心感明确不同。
                    if (isSummary) {
                        Modifier.border(
                            width = 1.dp,
                            color = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(12.dp),
                        )
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    // onClick 保持空实现：气泡本身没有点击语义，但 combinedClickable
                    // 要求两者都给（只给 onLongClick 时 LazyColumn 里的
                    // 按下状态反馈会缺失，长按也更容易被滚动手势吃掉）。
                    onClick = {},
                    onLongClick = onLongPress,
                ),
            color = container,
            contentColor = content,
            // 摘要圆角更小（12dp vs 18dp）：更接近「卡片/块」而不是「气泡」。
            shape = RoundedCornerShape(if (isSummary) 12.dp else 18.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // 摘要顶部的小标签。它不是正文的一部分，单独一行、更小字号、
                // 用更弱的颜色 —— 用户一眼就知道「下面是压缩内容」。
                if (isSummary) {
                    Text(
                        text = "对话摘要",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                when {
                    isError -> {
                        // 错误气泡：正文 + 可点的重试。错误信息本身常带技术细节，
                        // 直接给用户看，不做「出错了，请重试」这种没有信息量的话术。
                        StreamingText(text = text, streaming = false, color = content)
                        if (onRetry != null) {
                            RetryRow(onRetry)
                        }
                    }
                    isUser -> {
                        // 用户消息不会流式，直接 Text 更省一层组合。
                        //
                        // ## 图片画在文字**上方**（2026-10-24，多模态）
                        //
                        // 与微信/Telegram 一致：先看图、再看配文。反过来（文在上）
                        // 读起来的顺序是「先问这是什么，再看到图」，不自然。
                        //
                        // 图片为空时这一整段不渲染，不影响原有布局与性能。
                        if (images.isNotEmpty()) {
                            MessageImages(images = images, contentColor = content)
                            if (text.isNotBlank()) Spacer(Modifier.height(6.dp))
                        }
                        // 只发图不发字时 text 为空串 —— 空 Text 会占一行高度，
                        // 让气泡底部多出一块空白。判空后跳过。
                        if (text.isNotBlank()) {
                            Text(
                                text = text,
                                style = MiuixTheme.textStyles.body2,
                                color = content,
                            )
                        }
                    }
else -> {
                        // 助手正文走 Markdown 渲染（标题/列表/代码块/表格）。
                        // 光标由 MarkdownText 自己画（`streaming` 参数），
                        // 所以不再叠 StreamingText —— 两者叠用会出现两个闪块。
                        //
                        // ## `asyncParse = !streaming`（2026-10-24）
                        //
                        // 已完成的助手消息一律异步解析：长回答第一次滚进视口时
                        // 不再在组合阶段同步解析（那会让快速滑动掉帧）。
                        // 解析期间先显示纯文本，几十毫秒内切成 Markdown。
                        //
                        // 流式时必须同步：文本每帧都在变，异步会让呈现落后于
                        // 数据源，观感是「文字一跳一跳」。而流式走的本来就是
                        // `StreamingMarkdownState` 的增量路径，代价已经很低。
                        MarkdownText(
                            text = text,
                            color = content,
                            streaming = streaming,
                            asyncParse = !streaming,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Cursor 风格（Operit 默认形态）
// ─────────────────────────────────────────────────────────────

/**
 * Cursor 风格的一条消息。
 *
 * 对应 Operit `CursorStyleChatMessage.kt` 的三分支（user / ai / summary）。
 * Praxis 没有 summary 消息、多一个 Error，因此映射为：
 *
 * | Praxis | Operit `CursorStyle` | 形态 |
 * |---|---|---|
 * | [BubbleKind.User] | `UserMessageComposable` | 全宽卡片 + `提问` 标签 |
 * | [BubbleKind.Assistant] | `AiMessageComposable` | **无容器** + `回复` 标签 |
 * | [BubbleKind.Error] | 无对应 | 全宽卡片（`errorContainer`）+ `出错` 标签 |
 *
 * ## 标签为什么用中文，而不是照抄 `Prompt` / `Response`
 *
 * Operit 那两个词是硬编码英文。Praxis 界面全中文，混两个英文单词会像漏翻。
 * 语义一一对应，不引入新信息，只改文案语言。
 *
 * ## 助手消息为什么不要容器
 *
 * 这是 Cursor 风格与气泡风格的**唯一实质差别**，也是它读长回复更舒服的原因：
 * 助手正文直接铺在页面底色上，代码块自己的背景（Markdown 渲染出来的）
 * 不会与气泡灰底叠成「灰块里套深灰块」；长段落也不必在容器的内边距里反复折行。
 */
@Composable
private fun CursorMessage(
    kind: BubbleKind,
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    images: List<dev.praxis.core.ai.ChatImage> = emptyList(),
    /** 是否画 `提问` / `回复` 标签。见 [ChatMessageItem.showLabel]。 */
    showLabel: Boolean = true,
) {
    when (kind) {
        BubbleKind.User -> Surface(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            contentColor = MiuixTheme.colorScheme.onSurface,
            // 8dp 而气泡风格是 18dp：小圆角是「面板 / 文档块」的语言，
            // 大圆角是「聊天气泡」的语言，这个差别本身是两种风格的识别点。
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (showLabel) {
                    MessageLabel(text = "提问", color = MiuixTheme.colorScheme.onSurface)
                }
                // 图片同样画在文字之上，理由见 `MessageBubble` 里 isUser 分支。
                if (images.isNotEmpty()) {
                    MessageImages(
                        images = images,
                        contentColor = MiuixTheme.colorScheme.onSurface,
                    )
                    if (text.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        BubbleKind.Assistant -> Column(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(vertical = 2.dp),
        ) {
            if (showLabel) {
                MessageLabel(text = "回复", color = MiuixTheme.colorScheme.onSurface)
            }
            MarkdownText(
                text = text,
                color = MiuixTheme.colorScheme.onSurface,
                streaming = streaming,
            )
        }

        BubbleKind.Error -> Surface(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
            color = MiuixTheme.colorScheme.errorContainer,
            contentColor = MiuixTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                MessageLabel(text = "出错", color = MiuixTheme.colorScheme.onErrorContainer)
                StreamingText(
                    text = text,
                    streaming = false,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
                if (onRetry != null) {
                    RetryRow(onRetry)
                }
            }
        }

        // 工具卡片正常不走这里（由 ToolCallCard 渲染）。
        // 真被调到时给纯文本兜底，而不是静默丢一条 —— 丢消息比样式不对更难排查。
        BubbleKind.Tool -> Column(modifier = modifier.fillMaxWidth()) {
            MessageLabel(text = "工具", color = MiuixTheme.colorScheme.onSurface)
            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
        // 摘要块（用户插入的历史压缩）。
        //
        // 2026-10-24 补：上一轮给 `BubbleRole` 加了 `Summary` 却没补到这条
        // `when`，`BubbleKind` 因此变成非穷举 —— 编译期报
        // `'when' expression must be exhaustive. Add the 'Summary' branch`。
        //
        // ## 为什么 Cursor 风格下不加边框（与气泡风格不同）
        //
        // 气泡风格给摘要画 1dp 细边框，因为那里所有内容都有容器，不加边框
        // 摘要会与助手气泡混在一起。Cursor 风格的所有段落本来就都是「裸」的，
        // 摘要再加边框反而成了全页唯一带框的东西，视觉上比正文更重。
        // 这里改用「标签 + 正文压一档前景色」区分：与 `CursorMessage` 给
        // 用户/助手加 `提问` / `回复` 标签的做法同构，只用弱色不引入新形状。
        BubbleKind.Summary -> Column(modifier = modifier.fillMaxWidth()) {
            MessageLabel(
                text = "对话摘要",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * Cursor 风格的段落标签（`提问` / `回复` / `出错`）。
 *
 * 照 Operit 的排版：小字号、70% 不透明度、下边距 8dp。
 * 用 70% 而不是全色 —— 它标记「这一段的性质」而不是内容，压低一档才不抢正文。
 */
@Composable
private fun MessageLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = color.copy(alpha = 0.7f),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun RetryRow(onRetry: () -> Unit) {
    Row(Modifier.padding(top = 6.dp)) {
        // miuix TextButton 收 `text: String`，没有内容槽（与 M3 不同）。
        top.yukonga.miuix.kmp.basic.TextButton(
            text = "重试",
            onClick = onRetry,
            textStyle = MiuixTheme.textStyles.footnote1,
        )
    }
}

/**
 * 消息里附带的一组图片（用户消息专用）。
 *
 * ## 为什么排成一列而不是网格
 *
 * 气泡最大宽度只有 300dp（见 `MessageBubble` 的 `widthIn`）。两张图并排时
 * 每张只有约 140dp 宽，长截图里的文字会糊到看不清；而竖排单张可以占满
 * 气泡宽度（最多 300dp），可读性远好于并排。
 *
 * 代价是 4 张图会很高 —— 但这只在用户自己一次发 4 张时发生，属可接受。
 *
 * ## 为什么给固定宽高比 4:3 而不是让图片按自身比例
 *
 * 让图片按自身比例会让气泡高度随图片内容剧烈变化（一张竖拍截图能顶满屏）。
 * 固定 4:3 + `ContentScale.Fit` 保证：
 *  - 气泡高度可预期（用户滚动时不会突然遇到一张超高的图）；
 *  - 图片不被裁切（Fit 而非 Crop）—— 用户发图通常就是要看**完整**内容，
 *    裁掉边缘可能正好裁掉他要问的部分。
 *
 * 留 `surfaceContainer` 作底：Fit 会在 4:3 框里留出空白边，
 * 用底色托住比直接透出气泡色更清楚「这里是一张图」。
 */
@Composable
private fun MessageImages(
    images: List<dev.praxis.core.ai.ChatImage>,
    contentColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        images.forEach { image ->
            // 按 base64 缓存解码结果：气泡会随流式输出高频重组，
            // 不缓存等于每帧重解一张几 MB 的位图。
            val bitmap = remember(image.base64) {
                runCatching {
                    val bytes = android.util.Base64.decode(
                        image.base64,
                        android.util.Base64.NO_WRAP,
                    )
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            val shape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MiuixTheme.colorScheme.surfaceContainer, shape)
                    .clip(shape),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "消息图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // 解码失败：显示一行小字而不是空白框。
                    // 空白框会让用户以为是加载中，一直等下去。
                    Text(
                        text = "图片无法显示",
                        style = MiuixTheme.textStyles.footnote2,
                        color = contentColor.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}