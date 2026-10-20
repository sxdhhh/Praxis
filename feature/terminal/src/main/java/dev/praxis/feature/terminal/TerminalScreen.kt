package dev.praxis.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.praxis.core.terminal.CellStyle
import dev.praxis.core.terminal.TerminalSession
import dev.praxis.ui.design.component.AutoFollowScrollLazy
import dev.praxis.ui.design.component.LocalScrollBottomLimit
import dev.praxis.ui.design.component.LocalTopBarInset
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 终端页。
 *
 * ## 为什么用 LazyColumn 逐行渲染，而不是 Canvas 自绘整屏
 *
 * 自绘整屏需要自己管行高、测量、裁剪、命中测试，代码量大且容易在各异于常规的
 * 字体度量上错位；而终端每一行本质上就是一段等宽文本，直接用 Compose 的
 * 文本渲染就能拿到字体回退、行高、选中、无障碍这些能力。
 *
 * 代价是每个格子不能单独设背景色。折中方案：把一段相同背景色的连续格子合成一个
 * span —— 这正是 SGR 输出的天然结构（同色连续字符本来就属于同一 span）。
 *
 * ## 丝滑滚动直接复用 AutoFollowScrollLazy
 *
 * 与练习页/日志页同样的行为：到底自动跟随，上滑关跟随，滑回底部再恢复。
 * 不另写一份，否则三处行为会各自漂移。
 */
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onSubmit: (String) -> Unit,
    onInputChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topInset = LocalTopBarInset.current
    val scrollLimit = LocalScrollBottomLimit.current
    val listState = rememberLazyListState()

    // 行数变化时触发自动跟随（和聊天页同一个组件）。
    AutoFollowScrollLazy(lazyState = listState, itemCount = state.lines.size)

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TerminalPalette.background),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = topInset + 12.dp,
                // 底栏是浮层：留出滚动终点，但不抬内容区（否则玻璃采不到内容）。
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state.lines.size) { index ->
                TerminalRow(line = state.lines[index])
            }
        }

        TerminalInputBar(
            value = state.input,
            onValueChange = onInputChange,
            onSubmit = onSubmit,
            paddingBottom = scrollLimit,
        )
    }
}

/** 一行终端输出。 */
@Composable
private fun TerminalRow(line: TerminalLine) {
    // 行尾空白不渲染：否则每行都会撑满宽度，且复制时带一堆空格。
    val cells = line.cells
    var lastNonBlank = -1
    for (i in cells.indices) if (!cells[i].char.isWhitespace()) lastNonBlank = i

    val visible = if (lastNonBlank >= 0) cells.subList(0, lastNonBlank + 1) else emptyList()

    Row(Modifier.fillMaxWidth()) {
        if (visible.isEmpty()) {
            // 空行也要占一行高度（终端里空行是有意义的输出）。
            androidx.compose.material3.Text(
                text = " ",
                style = TerminalTypography.lineStyle,
                color = TerminalPalette.foreground,
            )
        } else {
            visible.forEach { cell ->
                // 宽字符的右半格不单独画（由左半格的字画过去）。
                if (cell.isContinuation) return@forEach
                androidx.compose.material3.Text(
                    text = cell.char.toString(),
                    style = TerminalTypography.lineStyle,
                    color = TerminalPalette.foregroundOf(cell.style),
                )
            }
        }
    }
}

/** 底部输入条。终端没有命令历史也难用，因此保留一个单行输入。 */
@Composable
private fun TerminalInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    paddingBottom: androidx.compose.ui.unit.Dp,
) {
    val currentOnSubmit by rememberUpdatedState(onSubmit)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            // 浮层底栏的占位：输入条本身要让开底栏，避免被遮住。
            .padding(bottom = paddingBottom),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            text = "$",
            style = TerminalTypography.lineStyle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(end = 8.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = TerminalTypography.lineStyle.copy(
                color = MiuixTheme.colorScheme.onSurface,
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    currentOnSubmit(value)
                },
            ),
        )
    }
}

/** 一行的单元格。[cells] 里已经包含空白的格子。 */
data class TerminalLine(val cells: List<RenderCell>)

/** 渲染用的格子：把 [Cell] 与是否是宽字符右半格合并，避免渲染层再查缓冲。 */
data class RenderCell(val char: Char, val style: CellStyle, val isContinuation: Boolean)

/** 终端页状态。 */
data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val input: String = "",
) {
    companion object {
        /** 从会话快照生成 UI 状态（含历史行）。 */
        fun from(session: TerminalSession, input: String): TerminalUiState {
            val buffer = session.buffer
            val lines = ArrayList<TerminalLine>(buffer.rows + buffer.scrollbackSize())

            // 历史行是整行搬走的，不涉及宽字符跨行，右半格一律不标记。
            for (i in 0 until buffer.scrollbackSize()) {
                val row = buffer.scrollbackLine(i) ?: continue
                lines += TerminalLine(row.map { cell -> RenderCell(cell.char, cell.style, false) })
            }

            for (r in 0 until buffer.rows) {
                lines += TerminalLine(buffer.line(r).mapIndexed { index, cell ->
                    RenderCell(cell.char, cell.style, buffer.isContinuation(r, index))
                })
            }

            return TerminalUiState(lines = lines, input = input)
        }

    }
}

/** 终端配色。不跟随主题的强调色：终端前景色由 ANSI 决定，主题只决定默认色。 */
object TerminalPalette {
    val background = Color(0xFF1B1B1F)
    val foreground = Color(0xFFE3E3E6)

    /**
     * 16 色调色板。
     *
     * 只做 16 色（+ 256 色按近似取模）：真有必要再扩，但绝大多数 CLI 只用前 16 色。
     */
    private val palette = listOf(
        Color(0xFF1B1B1F), Color(0xFFE06C75), Color(0xFF98C379), Color(0xFFE5C07B),
        Color(0xFF61AFEF), Color(0xFFC678DD), Color(0xFF56B6C2), Color(0xFFABB2BF),
        Color(0xFF5C6370), Color(0xFFFF7B86), Color(0xFFA9E08D), Color(0xFFF6D36B),
        Color(0xFF7BC4FF), Color(0xFFD79BEE), Color(0xFF6CD4E0), Color(0xFFE3E3E6),
    )

    /** 按 [CellStyle] 取前景色，并处理反显与真彩。 */
    fun foregroundOf(style: CellStyle): Color {
        if (style.inverse) return background
        return resolve(style.foreground)
    }

    /** 按 [CellStyle] 取背景色。 */
    fun backgroundOf(style: CellStyle): Color {
        if (style.inverse) return resolve(style.foreground)
        if (style.background == CellStyle.DEFAULT_COLOR) return background
        return resolve(style.background)
    }

    private fun resolve(value: Int): Color {
        if (value == CellStyle.DEFAULT_COLOR) return foreground
        // 真彩：高位标记 + RGB。
        if (value >= 0x1000000) {
            val rgb = value and 0xFFFFFF
            return Color(0xFF000000.toInt() or rgb)
        }
        // 调色板索引：256 色按取模近似到 16 色（不引完整 256 色表）。
        return palette[value % palette.size]
    }
}

/** 终端字体：等宽是硬要求，行高另算以免中文被裁。 */
object TerminalTypography {
    val lineStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
}
