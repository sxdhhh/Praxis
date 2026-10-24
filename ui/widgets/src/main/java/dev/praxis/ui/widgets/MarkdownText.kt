package dev.praxis.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import dev.praxis.ui.widgets.markdown.MarkdownParseCache
import dev.praxis.ui.widgets.markdown.StreamingMarkdownState
import dev.praxis.ui.widgets.markdown.MarkdownParser
import dev.praxis.ui.widgets.markdown.MdBlock
import dev.praxis.ui.widgets.markdown.MdInline
import kotlinx.coroutines.ensureActive
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Markdown 渲染：把 [MarkdownParser] 的块列表画成 Compose 节点。
 *
 * ## 为什么按块渲染，而不是整段拼成一个 AnnotatedString
 *
 * 标题、列表、代码块、表格的**排版属性不一样**（字号、左边距、等宽、背景），
 * 拼成一个 AnnotatedString 只能改字符样式，改不了块间距与背景块。
 * 按块渲染每个块是一个独立 composable，代价是多几层组合，
 * 但换行、缩进、代码块底色都能正确表达。
 *
 * ## 代码块为什么单独横滚
 *
 * AI 输出的代码行经常超过屏宽。整段软换行会让代码变成「折行的散文」，
 * 看不清缩进层级。这里用 `horizontalScroll` 让代码块自己横滚，
 * 与 IDE 的行为一致 —— 这是 Operit 也做的（它更彻底，自绘 Canvas）。
 *
 * ## 流式期间的表现
 *
 * `streaming = true` 时不做特殊处理，正常渲染已收到的块。
 * 因为解析器对「围栏未闭合」是宽容的（吃到文末并保留内容），
 * 所以流式写代码的中途也能看到代码块，而不是整段跳变。
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MiuixTheme.textStyles.body2,
    streaming: Boolean = false,
    /**
     * 是否允许「先显示纯文本，再异步解析成 Markdown」（对齐 Operit 的分帧加载）。
     *
     * ## 为什么需要这个开关（滚动不卡的关键）
     *
     * `MarkdownParser.parse` 是同步纯文本扫描。一条 8000 字回答第一次进入
     * 视口时，解析发生在**组合阶段**（主线程）—— 实测几毫秒到十几毫秒。
     * 单条不致命，但快速滑动时一屏可能同时进入 2~3 条长消息，
     * 加起来就是几十毫秒的掉帧，观感是「滑到长回答处卡一下」。
     *
     * 打开后：
     *
     * ```
     * 首次组合 → 缓存命中？ 是 → 直接渲染（零成本）
     *                    否 → 先渲染纯文本（立即可见、无卡顿）
     *                         后台线程解析 → 解析完切到 Markdown
     * ```
     *
     * ## 为什么默认 false
     *
     * 流式输出时必须同步解析（否则每帧都要等异步结果，文本会跳）。
     * 短文本（< 512 字）解析是微秒级，异步反而多一帧延迟与切换闪烁。
     */
    asyncParse: Boolean = false,
) {
    // ## 三条解析路径，按「文本是否还会变」分流
    //
    // | 场景 | 路径 | 为什么 |
    // |---|---|---|
    // | 流式中 | [StreamingMarkdownState] 增量 | 全文每帧都变，不能缓存；
    // |      |                              | 但只有尾部在长，前段可冻结 → O(n²) 降为 O(n) |
    // | 已完成 + 长 | [MarkdownParseCache] 跨组合 LRU | 会随滚动反复重组，值得缓存 |
    // | 已完成 + 短 | 直接解析 | 512 字以内是微秒级，走缓存反而更贵 |
    //
    // 流式**不进** LRU：每个 delta 的全文都不同，写进去只会把缓存冲干净，
    // 反而挤掉那些真正会被反复复用的已完成消息。
    val streamState = androidx.compose.runtime.remember { StreamingMarkdownState() }

    // 异步解析结果。null = 还没解析完（此时渲染纯文本占位）。
    //
    // ## 为什么要用 `mutableStateOf` 包一层
    //
    // 解析在后台线程完成，需要一个「通知组合重新读」的机制。
    // 直接改普通变量 UI 不会更新；`mutableStateOf` 才能让组合在赋值后重组。
    val asyncBlocks = androidx.compose.runtime.remember(text) {
        androidx.compose.runtime.mutableStateOf<List<MdBlock>?>(null)
    }

    // ## 异步模式下「同步路径」要做的事只剩查缓存
    //
    // 关键点：`asyncParse = true` 且文本够长时，**不能**在这里调
    // `MarkdownParseCache.getOrParse` —— 那个函数未命中时会**同步解析**
    // （解析就在 `parse` 参数里执行），等于把异步优化完全抵消。
    //
    // 所以拆成两件事：
    //  - 这里只「查」缓存（未命中返回 null，不动主线程）；
    //  - 解析交给下面的 `LaunchedEffect` 在 `Dispatchers.Default` 上做。
    //
    // 缓存命中时这里直接拿到结果，异步分支不会启动 —— 滚回来的消息
    // （以及已经解析过的）都是零成本。
    val needsAsync = asyncParse && !streaming && text.length >= SHORT_TEXT_LIMIT

    val cachedBlocks: List<MdBlock>? = androidx.compose.runtime.remember(text, streaming, needsAsync) {
        when {
            streaming -> streamState.blocksFor(text)
            text.length < SHORT_TEXT_LIMIT -> MarkdownParser.parse(text)
            needsAsync -> MarkdownParseCache.peek(text)
            else -> MarkdownParseCache.getOrParse(text, MarkdownParser::parse)
        }
    }

    // ## 异步解析只在「需要且未命中」时启动
    //
    // 三个前置条件缺一不可：
    //  1. 调用方允许（`asyncParse`）；
    //  2. 已完成（流式必须同步，否则文本会跳）；
    //  3. 足够长（短文本同步解析是微秒级，异步只会多一帧闪烁）。
    //
    // 命中缓存时 `cachedBlocks` 已经非空，这个分支不会进入。
    if (needsAsync && cachedBlocks == null) {
        androidx.compose.runtime.LaunchedEffect(text) {
            // ## 为什么走 `MarkdownParseCache.parseAsync`
            //
            // 并发必须由缓存对象统一限制（2 个线程）。若这里直接
            // `withContext(Dispatchers.Default)`，快速滑动时一屏涌进十几条
            // 长消息会同时抢 CPU，渲染本身反而被拖慢。
            // 详见 `MarkdownParseCache.parseAsync` 的 KDoc。
            //
            // 这个函数内部还会再查一次缓存（挡掉「同一文本被两条路径同时请求」），
            // 解析完自动写回 —— 所以这里不再单独 `put`。
            val parsed = MarkdownParseCache.parseAsync(text, MarkdownParser::parse)
            // 竞态保护：`LaunchedEffect(text)` 在 text 变化时会取消旧协程，
            // 但取消是**协作式**的 —— 已经跑完的解析仍会返回。
            // 若此时 text 已换成新的，写进 `asyncBlocks` 就是错的内容。
            //
            // ## 为什么用 `ensureActive()` 而不是读 `isActive`
            //
            // `isActive` 是 `CoroutineContext` / `CoroutineScope` 上的**扩展属性**
            // （`kotlinx.coroutines.isActive`）。不显式 import 它就会报
            // `Unresolved reference 'isActive' on receiver of type 'CoroutineContext'`
            // —— 因为它不是成员，找不到扩展就等于不存在。
            //
            // `ensureActive()` 是 `CoroutineScope` 的**成员**函数（无需 import），
            // 语义正好是我们要的：协程已被取消就抛 `CancellationException`，
            // 于是下面那行不会执行。比「自己判 true/false 再写」更直接，
            // 也顺带把「取消」本身传播出去。
            ensureActive()
            asyncBlocks.value = parsed
        }
    }

    // 渲染用的块：缓存/同步结果优先，其次异步结果。
    // 都为空时走纯文本占位（见下方 Column）。
    val renderBlocks: List<MdBlock>? = cachedBlocks ?: asyncBlocks.value


    // ## 为什么不能按 text 变化去 reset（会毁掉增量本身）
    //
    // 最初写成 `LaunchedEffect(text) { streamState.reset() }`，结果是：
    // 每个 delta 文本都变 → 每个 delta 都 reset → frozen 永远为空 →
    // 每次仍然全量解析，增量优化**完全不生效**，还多了一层对象开销。
    //
    // 正确做法：只在「从流式转为已完成」这一刻 reset。
    // 用 `streaming` 做 key，它在一轮回答里只会变一次（true → false）。
    //
    // 为什么必须 reset：`streamState` 由 `remember {}` 持有，生命周期跟
    // 这个气泡走。同一个气泡在「重新生成」时会被复用（id 相同），
    // 新的回答文本从零开始 —— 留着上一版的 frozen 会把长度判断搞错。
    androidx.compose.runtime.LaunchedEffect(streaming) {
        if (!streaming) streamState.reset()
    }

    Column(modifier = modifier) {
        // ## 异步解析未就绪时用纯文本占位（2026-10-24）
        //
        // 这一个分支是「滚动到长回答不卡」的关键：解析在后台跑，
        // 前台立刻能画出文字。用户看到的是「内容在，格式稍后变」，
        // 而不是「这一屏空白、卡一下、然后全部出现」。
        //
        // ## 为什么不画一个转圈/骨架屏
        //
        // 解析通常只要几毫秒到十几毫秒 —— 一个转圈会「闪一下」然后消失，
        // 比直接给纯文本更让人不舒服（闪动本身就是干扰）。
        // 纯文本是**有价值的内容**，即使格式没出来也能读。
        //
        // 纯文本用 `baseStyle` 而不是粗体/标题：它只是过渡态，
        // 不该看起来像「渲染完成的样子」，否则格式出来时会有割裂感。
        if (renderBlocks == null) {
            Text(
                text = text,
                style = baseStyle,
                color = color,
            )
            if (streaming) {
                Spacer(Modifier.height(2.dp))
                StreamingCursor(color)
            }
        } else {
            renderBlocks.forEachIndexed { index, block ->
                if (index > 0) {
                    // 块间距按块类型给：标题前留得多，列表项之间基本不留。
                    Spacer(Modifier.height(blockSpacing(renderBlocks[index - 1], block)))
                }
                when (block) {
                    is MdBlock.Heading -> HeadingBlock(block, color, baseStyle)
                    is MdBlock.Paragraph -> InlineBlock(block.text, color, baseStyle)
                    is MdBlock.Code -> CodeBlock(block)
                    is MdBlock.BulletList -> ListBlock(block.items, ordered = false, color = color, baseStyle = baseStyle)
                    is MdBlock.OrderedList -> ListBlock(block.items, ordered = true, color = color, baseStyle = baseStyle)
                    is MdBlock.Quote -> QuoteBlock(block.text, color, baseStyle)
                    is MdBlock.Table -> TableBlock(block, color, baseStyle)
                    MdBlock.Divider -> DividerBlock()
                }
            }
            if (streaming) {
                // 光标与 StreamingText 的同款：600ms 呼吸，2dp 宽方块。
                Spacer(Modifier.height(2.dp))
                StreamingCursor(color)
            }
        }
    }
}

/** 两个块之间的垂直间距。 */
private fun blockSpacing(prev: MdBlock, next: MdBlock): androidx.compose.ui.unit.Dp = when {
    // 列表项之间紧贴：靠行高本身分隔。
    prev is MdBlock.BulletList && next is MdBlock.BulletList -> 2.dp
    prev is MdBlock.OrderedList && next is MdBlock.OrderedList -> 2.dp
    // 标题后面紧跟内容：小间距。
    prev is MdBlock.Heading -> 4.dp
    // 代码块前后都要留够，否则与段落挤在一起难分辨。
    prev is MdBlock.Code || next is MdBlock.Code -> 8.dp
    next is MdBlock.Heading -> 10.dp
    else -> 6.dp
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading, color: Color, baseStyle: TextStyle) {
    // 标题字号按级别递减，且**不跟随正文的 13sp**：标题要有层级感。
    val size = when (block.level) {
        1 -> 20.sp
        2 -> 18.sp
        3 -> 16.sp
        else -> 15.sp
    }
    Text(
        text = inlineToAnnotated(block.text, color, baseStyle),
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 普通段落 / 引用正文：走同一套行内解析。 */
@Composable
private fun InlineBlock(text: String, color: Color, baseStyle: TextStyle) {
    Text(
        text = inlineToAnnotated(text, color, baseStyle),
        style = baseStyle,
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ListBlock(
    items: List<String>,
    ordered: Boolean,
    color: Color,
    baseStyle: TextStyle,
) {
    Column(Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                // 项目符号：有序用 1. / 2.，无序用 ·（比 • 更细，与中文字重更配）。
                Text(
                    text = if (ordered) "${index + 1}." else "·",
                    style = baseStyle,
                    color = color.copy(alpha = 0.75f),
                    modifier = Modifier.width(if (ordered) 22.dp else 14.dp),
                )
                Text(
                    text = inlineToAnnotated(item, color, baseStyle),
                    style = baseStyle,
                    color = color,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuoteBlock(text: String, color: Color, baseStyle: TextStyle) {
    Row(Modifier.fillMaxWidth()) {
        // 左侧竖线：用 3dp 宽的有色条，比符号更容易读成「这里是引用」。
        Box(
            Modifier
                .width(3.dp)
                .height(quoteBarHeight(baseStyle))
                .background(color.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = inlineToAnnotated(text, color.copy(alpha = 0.85f), baseStyle),
            style = baseStyle.copy(fontStyle = FontStyle.Italic),
            color = color.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 引用竖线高度。
 *
 * 不给 `fillMaxHeight()`：Row 的高度由文字决定，竖线若用 fillMaxHeight
 * 会先测量、再被文字撑开，出现「竖线比文字短一截或长一截」。
 * 用行高 × 行数估算即可 —— 引用通常是单行或两行，误差不影响观感。
 */
private fun quoteBarHeight(baseStyle: TextStyle): androidx.compose.ui.unit.Dp {
    val lineHeight = baseStyle.lineHeight
    return if (lineHeight.isSp) (lineHeight.value * 2).dp else 36.dp
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 8.dp),
    ) {
        if (block.language != null) {
            // 语言标签：放在代码块左上，方便用户判断这是什么语言。
            Text(
                text = block.language,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 0.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        // 横滚：代码长行不软换行，保住缩进。`fillMaxWidth` 在外层，
        // `horizontalScroll` 在内层 —— 反过来会让代码块撑破气泡。
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = block.code,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                ),
                color = MiuixTheme.colorScheme.onSurface,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}

@Composable
private fun TableBlock(block: MdBlock.Table, color: Color, baseStyle: TextStyle) {
    // 表格也横滚：列多时整表超出屏宽，压缩列宽会读不出来。
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(6.dp),
    ) {
        Row {
            block.header.forEach { cell ->
                TableCell(cell, color, baseStyle, bold = true)
            }
        }
        Spacer(Modifier.height(4.dp))
        block.rows.forEach { row ->
            Row {
                row.forEach { cell -> TableCell(cell, color, baseStyle, bold = false) }
            }
            Spacer(Modifier.height(3.dp))
        }
    }
}

@Composable
private fun TableCell(cell: String, color: Color, baseStyle: TextStyle, bold: Boolean) {
    Text(
        text = inlineToAnnotated(cell, color, baseStyle),
        style = baseStyle.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = color,
        maxLines = 3,
        modifier = Modifier
            .width(96.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun DividerBlock() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

/**
 * 行内片段 → `AnnotatedString`。
 *
 * 一处刻意的处理：**行内代码用等宽 + 淡底**，而不是只换颜色。
 * 只换颜色在长段落里几乎看不出来；加上淡底才有「这是标识符」的视觉重量。
 */
@Composable
private fun inlineToAnnotated(
    text: String,
    color: Color,
    baseStyle: TextStyle,
): AnnotatedString = buildAnnotatedString {
    MarkdownParser.parseInline(text).forEach { piece ->
        when (piece) {
            is MdInline.Plain -> append(piece.text)
            is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(piece.text)
            }
            is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(piece.text)
            }
            is MdInline.Strike -> withStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough),
            ) { append(piece.text) }
            is MdInline.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = color.copy(alpha = 0.10f),
                ),
            ) { append(piece.text) }
            is MdInline.Link -> withStyle(
                SpanStyle(
                    color = MiuixTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            ) { append(piece.label) }
        }
    }
}

/** Markdown 流式光标（与 [StreamingText] 的视觉一致）。 */
@Composable
private fun StreamingCursor(color: Color) {
    val transition = rememberInfiniteTransition(label = "mdCursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mdCursorAlpha",
    )
    Box(
        Modifier
            .size(width = 2.dp, height = 14.dp)
            .background(color.copy(alpha = alpha), RoundedCornerShape(1.dp)),
    )
}
/**
 * 短文本直接解析，不走二级缓存的阈值。
 *
 * 512 字符的解析是微秒级，走缓存（哈希全文 key + LRU 链表操作）
 * 与直接解析差不多贵，还白占一个缓存槽位。
 */
private const val SHORT_TEXT_LIMIT: Int = 512
