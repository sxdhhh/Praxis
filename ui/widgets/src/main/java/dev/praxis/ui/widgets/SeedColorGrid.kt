package dev.praxis.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.praxis.ui.theme.PresetSeedColors
import dev.praxis.ui.theme.seedSwatch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 品牌色选择网格。
 *
 * ## 布局算法照抄 InstallerX `MiuixThemeSettingsPage.kt:171-224`
 *
 * ```
 * BoxWithConstraints {
 *     val columns = (maxWidth / 88.dp).toInt().coerceAtLeast(1)
 *     colors.chunked(columns).forEach { rowItems ->
 *         Row(Arrangement.Center) {
 *             rowItems.forEach { Box(Modifier.weight(1f)) { Swatch(it) } }
 *             // 末行不足列数时补 Spacer(weight(1f))，否则最后一行会左对齐
 *             repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
 *         }
 *     }
 * }
 * ```
 *
 * 三个**必须照抄**的点：
 *
 *  1. **列数由宽度算，不写死**：`88.dp` 是色卡的最小可用宽度（64dp 卡 + 24dp 间距）。
 *     写死 4 列在窄屏会挤成一团、宽屏会留大片空白。
 *  2. **`weight(1f)` 均分 + 末行补 `Spacer(weight)`**：只靠 `Arrangement` 会让
 *     最后一行的色卡按自身宽度排列、与上面几行不对齐 —— 18 个色卡按 4 列分
 *     最后一行只有 2 个，不补 spacer 就会看到「最后两个色卡跳到中间」。
 *  3. **选中态画在中心圆里**（勾），不是给整个卡加边框：外框加粗会让相邻色卡
 *     的间距视觉上忽大忽小。
 *
 * ## 与上游的差异
 *
 * 上游每个色卡跑一次完整 `dynamicColorScheme`（后台线程 + 缓存 + 降级 UI），
 * 因为它要展示 Material 的精确 container 色阶。Praxis 用
 * [dev.praxis.ui.theme.seedSwatch] 做 HSL 近似 —— 一屏 18 个色卡零成本渲染，
 * 也不会在切换调色板时整屏重算。取舍理由见该函数的 KDoc。
 *
 * @param selectedArgb 当前选中的种子色（0xAARRGGBB）。
 * @param dark 是否深色主题。决定色卡用亮档还是暗档。
 * @param onSelect 选中回调，参数是 `0xAARRGGBB`。
 */
@Composable
fun SeedColorGrid(
    selectedArgb: Long,
    dark: Boolean,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        // 88.dp 最小单元宽：64dp 色卡 + 左右各 12dp 呼吸。
        val columns = (maxWidth / 88.dp).toInt().coerceAtLeast(1)
        val rows = remember(columns) { PresetSeedColors.all.chunked(columns) }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    rowItems.forEach { (key, argb) ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            SeedColorSwatch(
                                key = key,
                                argb = argb,
                                dark = dark,
                                selected = argb == selectedArgb,
                                onClick = { onSelect(argb) },
                            )
                        }
                    }
                    // 末行补位：不补则最后一行的色卡按自身宽度居中，与上面几行错位。
                    val remaining = columns - rowItems.size
                    if (remaining > 0) {
                        repeat(remaining) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * 单个色卡：外框 + 三扇形 + 中心实心圆（选中时带勾）。
 *
 * 层次顺序与上游一致（不能换）：
 *  `frame(半透明 primary, 16dp 圆角)` → `三扇形圆` → `solid 中心圆` → `勾`。
 */
@Composable
private fun SeedColorSwatch(
    key: String,
    argb: Long,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 每个色卡的派生色只算一次：重组（如滚动）时不该重算 HSL。
    val swatch = remember(argb, dark) { seedSwatch(argb, dark) }
    val label = remember(key) { PresetSeedColors.labelOf(key) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(color = swatch.frame, shape = RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // 三个扇形拼成一个完整圆：
                //   主轴（180° 起，扫 180°）= 上半圆
                //   换色相（90° 起，扫 90°）  = 右上
                //   更淡（0° 起，扫 90°）     = 右下
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawArc(
                        color = swatch.containerMain,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = true,
                    )
                    drawArc(
                        color = swatch.containerAlt,
                        startAngle = 90f,
                        sweepAngle = 90f,
                        useCenter = true,
                    )
                    drawArc(
                        color = swatch.containerThird,
                        startAngle = 0f,
                        sweepAngle = 90f,
                        useCenter = true,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(swatch.solid),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            imageVector = MiuixIcons.Basic.Check,
                            contentDescription = null,
                            // 深色卡上用白勾、亮色卡上用深勾：固定白色在黄/青柠卡上几乎看不见。
                            tint = if (dark) Color.White else Color.Black.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (selected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}