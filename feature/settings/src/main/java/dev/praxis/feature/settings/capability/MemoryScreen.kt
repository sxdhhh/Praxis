package dev.praxis.feature.settings.capability

import dev.praxis.feature.settings.common.SettingsList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.memory.MemoryItem
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 记忆库页。
 *
 * ## 为什么这是「必须有的页」而不是可选设置
 *
 * 长期记忆是 Agent 的隐含行为：用户看不到它记了什么，就无法判断
 * 「它为什么记得这件事」或「它为什么忘了」。没有这个页，记忆就是个黑盒，
 * 不可信任的自动化比没有自动化更糟。
 *
 * ## 为什么不做搜索框
 *
 * 记忆条数是千级以内，滚动查看比输入搜索更快。加搜索框会让这一页
 * 看起来功能很多，但实际增加了一次输入成本。等条数真的上千再加不迟。
 */
@Composable
fun MemoryScreen(
    items: List<MemoryItem>,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsList(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SmallTitle("长期记忆")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "共 ${items.size} 条",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // 这句是产品承诺，必须说清：用户有权知道自动化的边界。
                        text = "对话中会自动提取值得记住的事实。合并重复、不截断、不静默丢弃。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }

        if (items.isEmpty()) {
            item(key = "__empty_memory__") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "还没有记忆。",
                            style = MiuixTheme.textStyles.body2,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "多聊几轮，它会自己开始记。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            // ## 空态：给 key，然后早退（2026-10-24 修）
            //
            // key 是必需的：本容器里还有 `items(items, key = { it.id })`，
            // 空态项无 key 时会占下标 0，而第一条记忆也落到下标 0 ——
            // 两者被判成同一个 item，「空态 → 首条」会被当成内容更新而不是
            // 新增，`animateItem` 的 fadeIn 因此不触发。
            //
            // `return@SettingsList` 保留：它让「空态时不再往下注册 items」
            // 这件事在**代码结构上**可见（而不是依赖 `items(emptyList)` 的空转）。
            // 注意这里早退的是 `LazyListScope` lambda，不是整个 composable。
            return@SettingsList
        }

        items(items, key = { it.id }) { item ->
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = item.content, style = MiuixTheme.textStyles.body1)

                    if (item.tags.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        // ## `FlowRow` 而不是 `Row`（2026-10-24 修）
                        //
                        // 标签数量不定（记忆条目的 tag 由用户/模型自由生成，
                        // 常见 3~8 个）。用 `Row` 时总宽度一旦超过卡片内容宽，
                        // 后面的标签会被**裁掉** —— 既不换行也不可滚动，
                        // 用户看不到自己打过哪些标签。
                        //
                        // 这与 `MemoryCard`（同一个功能在另一处的实现）的做法
                        // 对齐：那边早就用 `FlowRow` 并在注释里写明
                        // 「标签数量不定。用 Row 会溢出（末尾被裁掉）」。这里是
                        // 漏改的第二处。
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            item.tags.forEach { tag ->
                                Text(
                                    text = "#$tag",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (item.hitCount > 0) "被引用 ${item.hitCount} 次" else "尚未被引用",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        TextButton(
                            text = "删除",
                            onClick = { onDelete(item.id) },
                        )
                    }
                }
            }
        }
    }
}
