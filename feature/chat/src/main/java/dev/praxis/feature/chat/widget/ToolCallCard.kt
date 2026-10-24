package dev.praxis.feature.chat.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.praxis.ui.theme.PraxisMotion
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工具调用卡片：可折叠的「工具名 + 参数 + 结果 + 耗时」。
 *
 * ## 为什么必须能折叠
 *
 * 工具返回体经常是几千字的文件内容或终端输出。默认展开会让聊天流被
 * 一屏屏代码淹没，用户找不到自己在问什么。上游 Operit 的处理是：
 * **默认收起，只显示一行摘要；点开才看全文**。这里照做。
 *
 * ## 为什么参数与结果都要显示
 *
 * 只显示结果会让用户无法判断「模型到底查了什么」。反过来只显示参数
 * 则看不出成功与否。两者都要，但都放在折叠区里。
 *
 * ## 状态色
 *
 *  - 执行中：`primary`（蓝）—— 表示进行中
 *  - 成功：`onSurfaceVariantSummary`（灰）—— 不抢注意力
 *  - 失败：`error`（红）—— 唯一需要立刻看见的
 *
 * @param name 工具名（与模型看到的原文一致）
 * @param argumentsJson 原始 JSON 参数，**不截断**
 * @param result 返回体；null 表示还在执行
 * @param isError 是否失败
 * @param elapsedMs 耗时毫秒；未完成时为 null
 * @param summary 折叠时显示的一行摘要（通常是结果首行）
 * @param onLongPress 长按（弹消息菜单）；null 时不响应长按
 */
@Composable
fun ToolCallCard(
    name: String,
    argumentsJson: String,
    result: String?,
    isError: Boolean,
    elapsedMs: Long? = null,
    summary: String? = null,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    val running = result == null && !isError
    val statusColor = when {
        isError -> MiuixTheme.colorScheme.error
        running -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val statusText = when {
        isError -> "失败"
        running -> "执行中"
        else -> elapsedMs?.let { formatElapsed(it) } ?: "完成"
    }

    // ## 为什么这里要局部关掉 squircle（2026-10-24，解决「滑到工具就卡」）
    //
    // 用户报：「翻文字很丝滑，滑到工具的时候就变卡了」。根因在 miuix 的
    // `Card`：它内部走 `Modifier.squircleSurface(...)`，而
    // `SquircleBackground.kt:255` 的 `squircleShaderMask` 会做两件重活：
    //
    // ```kotlin
    // .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    // .drawWithCache { ... drawSquircleFast(brush, fillColor) ... }
    // ```
    //
    // 也就是**每一张工具卡都额外开一个离屏图层**（一次全尺寸 render target
    // 的分配 + 合成），再跑 AGSL shader 逐像素求遮罩。
    // 一张两张看不出来；但当列表里连续出现 5~10 张工具卡（一次带工具的回答
    // 就是这么多），滚动时每帧要合成这么多离屏层 —— 掉帧非常明显。
    //
    // 相比之下**文字气泡用 `Surface`**（普通圆角），完全没有这层开销。
    // 这就是「文字丝滑、工具卡卡」的全部原因。
    //
    // ## 为什么用 `LocalSquircleEnabled = false` 而不是自己画背景
    //
    // miuix 为这个场景留了官方开关：`SquircleBackground.kt:370`
    // 在 `!LocalSquircleEnabled.current` 时 `rememberSquircleBrush` 直接返回
    // null，`squircleSurface` 于是走降级分支：
    //
    // ```kotlin
    // ?: return this.clip(RoundedCornerShape(...)).background(color)
    // ```
    //
    // 纯 `clip + background`，零 shader、零离屏。视觉上唯一的差别是
    // 圆角从「超椭圆（squircle）」变成「真圆角」—— 在 12dp 圆角、
    // 一张浅色卡片上，二者肉眼几乎不可区分，而代价差了一个数量级。
    //
    // 只在这一张卡的范围里关：`staticCompositionLocalOf` 作用域内生效，
    // 不影响页面其它 miuix 控件继续用正圆形 squircle。
    CompositionLocalProvider(LocalSquircleEnabled provides false) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
        cornerRadius = 12.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // ## 为什么折叠/展开用 combinedClickable 而不是 clickable
                //
                // 工具卡片也要能长按弹菜单（复制工具结果、看信息、删除）。
                // 只绑 `clickable` 的话长按会被消费成「点击 → 切换折叠」，
                // 菜单永远打不开 —— 而 `ChatScreen` 里工具气泡是**独立分支**，
                // 不走 `ChatMessageItem` 的 `onLongPress`。
                //
                // `combinedClickable` 两者都要给（只给 onLongClick 在
                // LazyColumn 里会有可见的按下延迟，见 `MessageBubble` 的同款注释）。
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = onLongPress,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // ── 标题行：状态点 + 工具名 + 状态 ──────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ## 箭头要转，不要换字符（2026-10-23 修，用户第 5 项）
                //
                // 旧写法是 `text = if (expanded) "▾" else "▸"` —— 两个不同的
                // 字符之间硬切，视觉上是「跳一下」。改成同一个 "▸" 绕中心旋转
                // 90°，`animateFloatAsState` 让转动连续。
                //
                // 为什么不用 `Icons` 里的箭头：这个字符与 miuix 的 body2 字号
                // 天然对齐，换成矢量图标要额外调 size，反而更容易错位。
                val arrowRotation by animateFloatAsState(
                    targetValue = if (expanded) 90f else 0f,
                    animationSpec = tween(
                        durationMillis = PraxisMotion.Expansion.Expand,
                        easing = PraxisMotion.Curves.Standard,
                    ),
                    label = "tool-arrow",
                )
                Text(
                    text = "▸",
                    style = MiuixTheme.textStyles.body2,
                    color = statusColor,
                    modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
                )
                Text(
                    text = name,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(text = statusText, style = MiuixTheme.textStyles.footnote1, color = statusColor)
            }
            // ── 折叠时的一行摘要 ────────────────────────────────────
            //
            // ## 为什么用 AnimatedVisibility 而不是裸 if（2026-10-23 修）
            //
            // 用户要求「所有的控件展开和开关展开和收回应该都有动画」。
            // 旧实现是两个裸 `if`：`expanded` 一变，卡片高度**瞬间**跳变
            // （几行代码块一次性顶出来），观感很硬。
            //
            // 用 `AnimatedVisibility` + `expandVertically/shrinkVertically`：
            // 高度按帧插值，卡片平滑长高/收短。fade 一并给上，
            // 避免文字在高度还很小的时候被裁成半行。
            //
            // 时长取 `PraxisMotion.Expansion` 的档位（与输入区阶段提示同源），
            // 全应用统一手感。
            AnimatedVisibility(
                visible = !expanded && summary != null,
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
                val summaryText = summary
                if (summaryText != null) {
                    Text(
                        text = summaryText,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 18.dp, top = 4.dp),
                    )
                }
            }
            // ── 展开：参数 + 结果 ──────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
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
                Column {
                    SectionLabel("参数")
                    CodeBlock(argumentsJson)
                    if (result != null) {
                        SectionLabel(if (isError) "错误" else "结果")
                        CodeBlock(result, isError)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** 代码块：等宽感 + 可换行的浅底容器。 */
@Composable
private fun CodeBlock(text: String, isError: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 2.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = if (isError) {
                MiuixTheme.colorScheme.error
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
    }
}

/** 耗时格式化：小于 1 秒显示毫秒，否则显示一位小数的秒。 */
private fun formatElapsed(ms: Long): String =
    if (ms < 1000) "${ms}ms" else String.format("%.1fs", ms / 1000.0)