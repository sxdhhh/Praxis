package dev.praxis.feature.settings.env

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.praxis.ui.theme.PraxisRadius
import dev.praxis.ui.design.component.AutoFollowScrollLazy
import dev.praxis.ui.widgets.FoldableContent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 安装 / 运行日志面板。
 *
 * ## 为什么需要独立日志框（2026-11-06）
 *
 * 旧实现只在状态栏显示 `lastLine` **一行**。装环境时 apt 会打几千行，
 * 一旦某一步失败，**失败原因在上一行就已经滚掉了** —— 用户看到「失败了」，
 * 却拿不到任何可排查的信息。
 *
 * ## 为什么用等宽字体
 *
 * apt/dpkg 的输出是**列对齐**的（进度条、包名对齐）。比例字体下这些对齐
 * 全部错位，长命令的折行位置也变得随机，读起来很累。
 *
 * ## 滚动为什么用 [AutoFollowScrollLazy]
 *
 * 项目既有的弹簧跟随（k=130 / c=24 / m=1，已按真机手感调过）。
 *
 * 不用 `animateScrollToItem`：它是「启动时算一次目标位置」的补间，
 * 而日志在滚动过程中**仍在持续增长** —— 补间跑到一半目标就变了，
 * 观感是「滚到一半又被拽一下」。弹簧每帧现取目标，内容变长时自然接着追。
 */
@Composable
fun InstallLogPanel(
    lines: List<String>,
    logVersion: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    // 没有日志时不占屏幕（与队列面板同一条纪律）。
    if (lines.isEmpty()) return

    val clipboard = LocalClipboardManager.current
    // 日志是单一份列表，不需要 resetKey（见 AutoFollowScrollLazy 的 KDoc）。
    val listState = rememberLazyListState()

    // miuix 的 `Card` 只接 modifier 与内容：颜色/圆角/阴影由主题统一决定。
    // 传 Material3 的 `colors`/`shape`/`elevation` 会编译不过 ——
    // 那是另一套 Card。与 `LinuxEnvScreen` 里的用法保持一致。
    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 标题行：整行可点，切换展开 ────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "日志 · ${lines.size} 行",
                    style = MaterialTheme.typography.labelLarge,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                if (expanded) {
                    TextButton(
                        text = "复制",
                        onClick = {
                            clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                        },
                    )
                    if (onClear != null) {
                        TextButton(text = "清空", onClick = onClear)
                    }
                }
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // 展开/收起带动画：硬切会让整页在安装过程中高度突变。
            FoldableContent(visible = expanded, modifier = Modifier.fillMaxWidth()) {
                // AutoFollowScrollLazy 是**独立 Composable**，自己起跟随协程；
                // 它不包 LazyColumn，而是给现有的 listState 挂跟随逻辑。
                AutoFollowScrollLazy(
                    lazyState = listState,
                    itemCount = lines.size,
                    contentVersion = logVersion,
                    initialAutoFollow = true,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 240dp ≈ 12 行。再高会把安装按钮推出屏幕。
                        .heightIn(max = 240.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    itemsIndexed(
                        items = lines,
                        key = { index, _ -> index },
                    ) { _, line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
