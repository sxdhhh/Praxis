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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.praxis.core.memory.MemoryItem
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
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
    /** 当前活动空间 id。 */
    activeSpaceId: String = MemoryItem.DEFAULT_SPACE_ID,
    /** 各空间及条数（id to count）。 */
    spaceCounts: List<Pair<String, Int>> = emptyList(),
    /** 切换空间；null = 不显示选择器。 */
    onSelectSpace: ((String) -> Unit)? = null,
    /** 用户显式创建过的空间名（不含默认空间，已去重）。 */
    spaces: List<String> = emptyList(),
    /** 新建空间；null = 不显示新建入口。 */
    onCreateSpace: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // ── 空间列表（2026-10-29 重做）──
    //
    // ## 为什么不再只用 spaceCounts
    //
    // `spaceCounts` 是从记忆表反推的，于是「刚建、还没记东西」的空间不出现，
    // 用户永远建不出第一个空空间（死锁）。现在 `spaces` 由 SettingsRepository
    // 独立持有，两者合并成展示列表：先默认空间，再用户建的，最后是只在记忆里
    // 出现过的（历史数据兼容）。
    var showCreateDialog by remember { mutableStateOf(false) }
    val countOf: Map<String, Int> = spaceCounts.toMap()
    val displaySpaces: List<Pair<String, Int>> = remember(spaces, spaceCounts, activeSpaceId) {
        val merged = LinkedHashSet<String>()
        merged.add(MemoryItem.DEFAULT_SPACE_ID)
        merged.addAll(spaces)
        merged.addAll(spaceCounts.map { it.first })
        merged.add(activeSpaceId)
        merged.map { id -> id to (countOf[id] ?: 0) }
    }

    if (showCreateDialog && onCreateSpace != null) {
        CreateSpaceDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                onCreateSpace(name)
            },
        )
    }

    SettingsList(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SmallTitle("长期记忆")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // ── 空间选择器 ──
                    //
                    // 用 FlowRow 而不是下拉菜单：空间通常就两三个，直接展开比多点一层快。
                    if (onSelectSpace != null) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            displaySpaces.forEach { (id, count) ->
                                val label = spaceLabelOf(id)
                                TextButton(
                                    text = label + " · " + count,
                                    onClick = { onSelectSpace(id) },
                                )
                            }
                            if (onCreateSpace != null) {
                                TextButton(
                                    text = "+ 新建空间",
                                    onClick = { showCreateDialog = true },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

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

/** 默认空间的展示名。独立常量而不写死在 if 分支里：同一个名字
 * 在选择器与空列表两处都要用，写两遍迟早改漏一个。 */
private const val DEFAULT_SPACE_LABEL = "默认"

/** 空间 id → 展示名。空串是默认空间。 */
private fun spaceLabelOf(id: String): String =
    if (id == MemoryItem.DEFAULT_SPACE_ID) DEFAULT_SPACE_LABEL else id

/**
 * 新建空间对话框。
 *
 * ## 为什么不用 inline 输入框
 *
 * 空间列表本身就是一排胶囊，中间塞一个输入框会让「切换」与「创建」
 * 挤在同一行，误点率高。弹一层的好处是：取消/确认边界清楚，
 * 且输入内容不会因为点了别的胶囊而丢掉。
 */
@Composable
private fun CreateSpaceDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "新建记忆空间", style = MiuixTheme.textStyles.body1)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "空间之间互相隔离：在哪个空间里对话，就只能想起那个空间的记忆。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "例如：工作",
                    useLabelAsPlaceholder = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    TextButton(
                        text = "创建",
                        onClick = {
                            val name = text.trim()
                            if (name.isNotEmpty()) onConfirm(name)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}
