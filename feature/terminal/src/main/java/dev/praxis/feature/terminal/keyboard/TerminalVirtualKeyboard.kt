package dev.praxis.feature.terminal.keyboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 终端虚拟键盘（**结构尺寸 1:1 对齐 Operit，配色全部取自莫奈**）。
 *
 * ```
 * ESC    /      -     HOME   ↑     END   PGUP
 * TAB    CTRL   ALT   ←      ↓     →     PGDN
 * ```
 *
 * ## 1:1 对齐的是哪几项（全部来自 Operit `TerminalHome.kt`）
 *
 * | 项 | Operit 原值 | 位置 |
 * |---|---|---|
 * | 面板底色 | `Color(0xFF1A1A1A)` | `VirtualKeyboard` 的 Surface |
 * | 面板阴影 | `shadowElevation = 4.dp` | 同上 |
 * | 面板内边距 | `padding(horizontal = p, vertical = p * 0.5f)` | 同上 |
 * | 行列间距 | `spacedBy(p * 0.5f)`（行间与列间同值） | 同上 |
 * | 键帽圆角 | `RoundedCornerShape(4.dp)` | `KeyButton` |
 * | 键帽内边距 | `padding(horizontal = p * 0.5f, vertical = p * 0.8f)` | `KeyButton` |
 * | 键帽字号 | `fontSize * 0.7f`（调用方传 `fontSize * 0.7f`，键内不再缩放） | `TerminalHome` |
 * | 键帽字体 | `FontFamily.Monospace` + `FontWeight.Medium` | `KeyButton` |
 * | 激活色 | 蓝底白字（此处换成 `primary`） | `KeyButton` |
 * | 等宽 | 每键 `Modifier.weight(1f)` | 同上 |
 *
 * ## 与 Operit 唯一的差异：颜色
 *
 * Operit 的键帽色是硬编码的 `0xFF3A3A3A` / 激活 `0xFF2563EB`。Praxis 要求
 * 跟随莫奈取色，所以换成 **三档色阶**，而不是把深灰硬编码进来：
 *
 * | 层 | 色 | 为什么 |
 * |---|---|---|
 * | 输出区 | `surfaceContainer` | 已有（`TerminalPalette.defaultBackground`） |
 * | 键盘面板 | `surfaceContainerHigh` | 比输出区高一档 —— 键盘是「浮在上面的一层」 |
 * | 键帽 | `surfaceContainerHighest` | 比面板再高一档 —— 键帽必须从面板上「凸起」 |
 * | 键帽字 | `onSurface` | |
 * | 激活（CTRL/ALT） | `primary` + `onPrimary` | 莫奈的 primary 就是「选中/激活」语义 |
 *
 * **为什么键帽不能用 `surfaceContainerHigh`**：那是面板的色阶，键帽与面板同色
 * 就完全看不出「一个键一个键」的边界，整块变成一片灰。这不是审美问题，
 * 是功能问题 —— 用户点不准时不知道是自己手抖还是键边界不清楚。
 *
 * **为什么 ANSI 16/256 色绝不动**：见 `TerminalPalette` 的 KDoc。
 * `ls` 的蓝目录、`git diff` 的红绿必须与所有工具文档的截图一致。
 *
 * ## 为什么用 `Surface` 而不是 `Box + background`
 *
 * `Surface` 自带 `LocalContentColor` 下发、`isTraversalGroup` 语义分组，
 * 且 miuix 的实现里处理了「颜色与阴影共存」的绘制顺序。手写 `Box` 要自己补这些。
 * 项目内 `MiuixBadge` / `ProgressButton` / `MiuixSidebar` 都是这个用法。
 *
 * @param layout 布局（默认 2×7，见 [DefaultTerminalKeyLayout]）。
 * @param modifiers 当前修饰符状态，决定 CTRL/ALT 是否高亮。
 * @param onKey 按下普通键。
 * @param onToggleCtrl 按下 CTRL。
 * @param onToggleAlt 按下 ALT。
 * @param fontSize 键帽字号。**调用方传入已缩放过的值**（Operit 传 `fontSize * 0.7f`），
 *   本组件不再二次缩放 —— 两层缩放会让实际字号变成 0.49 倍，小到看不清。
 * @param spacing 面板内边距基准（Operit 的 `padding`，调用方传 `terminalPadding * 0.5f`）。
 */
@Composable
fun TerminalVirtualKeyboard(
    modifier: Modifier = Modifier,
    layout: TerminalKeyLayout = DefaultTerminalKeyLayout,
    modifiers: TerminalModifiers = TerminalModifiers(),
    onKey: (TerminalKeyId) -> Unit = {},
    onToggleCtrl: () -> Unit = {},
    onToggleAlt: () -> Unit = {},
    fontSize: TextUnit = 10.sp,
    spacing: Dp = 4.dp,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        // 面板比输出区高一档：键盘是浮在终端之上的独立一层。
        color = MiuixTheme.colorScheme.surfaceContainerHigh,
        contentColor = MiuixTheme.colorScheme.onSurface,
        // 4.dp 与 Operit 同值。这是「键盘与输出区之间有分界」的唯一视觉线索；
        // 去掉它键盘看起来就是输出区的一部分。
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing, vertical = spacing * 0.5f),
            // 行间与列间同值（Operit 的 `spacedBy(p * 0.5f)` 两处一致）。
            // 行间距大于列间距会让键盘「纵向松散、横向拥挤」，是常见的粗糙感来源。
            verticalArrangement = Arrangement.spacedBy(spacing * 0.5f),
        ) {
            layout.rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing * 0.5f),
                ) {
                    row.forEach { key ->
                        KeyCap(
                            key = key,
                            fontSize = fontSize,
                            spacing = spacing,
                            // 只有修饰符键有「激活」态。
                            active = when (key.id) {
                                TerminalKeyId.Ctrl -> modifiers.ctrl
                                TerminalKeyId.Alt -> modifiers.alt
                                else -> false
                            },
                            onClick = {
                                // ## 为什么在这里分发到两个回调而不是统一 onKey
                                //
                                // 是因为「修饰符是开关，不消耗自己」——
                                // 见 `applyTerminalKey` 里那一段。把三者的接线分开，
                                // 调用方就不可能把 CTRL 误接到普通键的路径上
                                // （那条路径会 `consumed()` 掉刚打开的修饰符）。
                                when (key.id) {
                                    TerminalKeyId.Ctrl -> onToggleCtrl()
                                    TerminalKeyId.Alt -> onToggleAlt()
                                    else -> onKey(key.id)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个键帽。
 *
 * 尺寸与内边距照 Operit 的 `KeyButton`：
 * `padding(horizontal = spacing * 0.5f, vertical = spacing * 0.8f)`。
 *
 * ## 纵向内边距比横向大（0.8 vs 0.5）
 *
 * 这个不对称是刻意的：键帽的**高度**主要由纵向内边距撑出来，而文字本身
 * 只有一行。若两向同值，键帽会变成扁条（宽 47dp、高 20dp 左右），
 * 手指点不准。0.8 倍让高度接近宽度的 0.6，形成「接近方形」的可点区域。
 *
 * ## 为什么用 `Role.Button`
 *
 * 键帽是按钮，不是文字。没有 role 时读屏会逐个念「ESC、斜杠、减号」而不知道
 * 它们可点。项目内先例：`PraxisOnboardingPage` 的跳过按钮、`LiquidButton`。
 *
 * @param active 修饰符激活态。非修饰符键恒为 false。
 */
@Composable
private fun KeyCap(
    key: TerminalKey,
    fontSize: TextUnit,
    spacing: Dp,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 激活时用 primary 底 + onPrimary 字；否则 surfaceContainerHighest + onSurface。
    // 两套都是莫奈语义色，不出现任何硬编码 RGB。
    val container = if (active) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.surfaceContainerHighest
    }
    val content = if (active) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        MiuixTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier.clickable(
            // 键帽是按钮语义（见 KDoc）。
            role = Role.Button,
            onClickLabel = key.label,
            onClick = onClick,
        ),
        color = container,
        contentColor = content,
        // 4.dp 圆角与 Operit 同值。这是「键帽」与「色块」的分界 ——
        // 直角会看起来像表格单元格。
        shape = RoundedCornerShape(4.dp),
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = spacing * 0.5f,
                vertical = spacing * 0.8f,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = key.label,
                // 等宽是必须的：`PGUP` / `PGDN` 与 `HOME` / `END` 在同一列，
                // 比例字体下每键宽度虽然被 weight 拉平，但字面宽度不一，
                // 视觉上键帽里的字会左右乱跳。
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = fontSize,
                // 单行 + 省略：用户改标签后可能写很长的词，
                // 换行会把键盘高度撑大（挤掉输出区），截断更可控。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}