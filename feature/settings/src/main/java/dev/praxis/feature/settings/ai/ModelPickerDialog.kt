package dev.praxis.feature.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.praxis.core.ai.AiModelOption
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模型列表选择弹窗 —— **多选**，1:1 对齐 Operit `ModelApiSettingsSection`
 * 的 `showModelsDialog` 区块（控件换成本项目统一的 miuix）。
 *
 * ## 上游结构（逐项对应）
 *
 * ```
 * ┌──────────────────────────────────────┐
 * │ 可用模型列表                    [⟳]  │  ← 标题 + 刷新（刷新时转圈）
 * │ [🔍 搜索模型…                    ]   │
 * │ ──────────────────────────────────── │
 * │ ☑ gpt-4o-mini                        │  ← 每行 Checkbox + 名称
 * │ ☐ gpt-4o                             │
 * │ ──────────────────────────────────── │
 * │ 显示 42 个 • 已选 3                   │  ← 底部计数
 * │                    [关闭] [确认 (3)] │
 * └──────────────────────────────────────┘
 * ```
 *
 * ## 为什么从单选改成多选（2026-10-24，本轮核心）
 *
 * 上一版这里是**单选**，KDoc 里写的理由是「Praxis 的 `model` 是单个字符串，
 * 多选就是假控件」。那个判断本身没错，但**解法错了**：
 *
 * 上游的做法不是「砍掉多选」，而是把 `modelName` 当**模型池**
 * （逗号分隔）+ 用 `modelIndex` 指向当前用的那个。本轮按这个口径把
 * 数据层补齐了（`ModelSettings.model` 存池、`activeModel` 取单个），
 * 于是多选成为**真控件**：勾几个就存几个，发请求时按索引取一个。
 *
 * 对齐证据见 `ModelSettings.model` 的 KDoc。
 *
 * ## 确认时的拼接顺序：**按 options 原序，不按点击顺序**
 *
 * 上游是：
 *
 * ```kotlin
 * val orderedSelection = modelsList.map { it.id }        // 服务端返回顺序
 *     .filter { selectedModels.value.contains(it) }
 * modelNameInput = orderedSelection.joinToString(",")
 * ```
 *
 * 这个细节很重要：`modelIndex` 是「池里的第几个」。如果拼接顺序随点击
 * 变化，用户下次打开设置时「当前使用」就指到了另一个模型 ——
 * 属于看着像随机行为的 bug。所以这里也按 `options` 的原序拼。
 *
 * ## 为什么错误态画在列表位置而不是弹 Snackbar
 *
 * 拉取失败时用户视线就在这个弹窗里。Snackbar 挂在页面 Scaffold 上，
 * 会出现在弹窗**下层**，用户看不到 —— 那正是上游修过的坑。
 *
 * @param options 已拉到的模型（可能为空 —— 空时显示空态而不是空白）
 * @param selectedIds 当前已选中的模型 id 集合（来自已保存的模型池）
 * @param loading 是否正在拉取
 * @param error 上一次失败的原因；null 表示没有错误
 * @param onRefresh 重新拉取
 * @param onConfirm 确认选择，参数是**按 options 原序排好的** id 列表
 * @param onDismiss 关闭
 */
@Composable
fun ModelPickerDialog(
    options: List<AiModelOption>,
    selectedIds: Set<String>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    /**
     * 是否显示。由调用方持有（`ModelScreen` 的 `showModelPicker`）。
     *
     * ## 为什么是参数而不是「挂不挂载」
     *
     * 退场动画要求组件在关闭时**仍然在组合里**。所以调用方必须无条件
     * 调用本函数、只用这个布尔表达「要不要显示」，不能包 `if`。
     * 详见 `MiuixAnimatedDialog` 的 KDoc 第三节。
     *
     * 默认 `true`：兼容既有的预览/测试调用点（它们只在要显示时调用一次）。
     */
    visible: Boolean = true,
) {
    // 搜索词是纯 UI 态：不写回 settings，也不该在弹窗重开时保留。
    var search by remember { mutableStateOf("") }
    // 草稿选中项：确认前不回调上层，取消就等于没选过。
    //
    // `remember(selectedIds)`：外部已保存的池子变了（例如用户在输入框里
    // 手动改了模型名），重开弹窗时要反映新值，而不是沿用上一次的草稿。
    var draftIds by remember(selectedIds) { mutableStateOf(selectedIds) }

    // ## 为什么用「从关到开」这个边沿来重置，而不是 `remember(visible)`
    //
    // 弹窗现在是**常驻挂载**的（调用点无条件调用，只靠 `visible` 控制显示），
    // 所以 `remember` 的生命周期从「弹窗存在期间」变成了「整个页面存在期间」。
    // 不重置的话，上次搜的「gpt」会留到下次打开 —— 列表看起来少了一大半，
    // 用户会以为拉取失败。
    //
    // 但**不能用 `remember(visible)`**：那样 `visible` 变成 false 的那一刻
    // 状态就被重建了，而退场动画还有 160ms 要播 —— 那 160ms 里
    // 列表会突然变空、搜索框突然清空，比「记住上次搜索」难看得多。
    //
    // 正确做法是只在**重新打开**时清一次，而且必须放在副作用里：
    //
    // 直接在组合函数体里写 `search = ""` 是「组合期间修改 state」——
    // Compose 允许但会带来「读到旧值 / 写入触发再重组」的推理困难，
    // 且本文件里 `search` 同时是下方 `remember(search, options)` 的 key。
    // 放进 `LaunchedEffect` 就变成「提交后执行」，语义清晰。
    LaunchedEffect(visible) {
        if (visible) {
            search = ""
            draftIds = selectedIds
        }
    }

    val filtered = remember(search, options) {
        if (search.isBlank()) {
            options
        } else {
            options.filter {
                it.id.contains(search, ignoreCase = true) ||
                    it.name.contains(search, ignoreCase = true)
            }
        }
    }

    // ## 换成 `MiuixAnimatedDialog`（2026-10-24）
    //
    // 原来直接 `Dialog { Card { ... } }`：挂上就出现、关闭就整块消失，没有任何过渡。
    // `MiuixAnimatedDialog` 负责三件事：进场/退场动画、整体上移、全屏点击关闭层。
    //
    // 注意 `onDismissRequest` 传的是 `onDismiss`：组件**不改**可见性，
    // 由调用方（`ModelScreen`）把 `showModelPicker` 置 false，随后组件播完
    // 退场动画才拆 window。所以调用点必须常驻挂载（那里有说明）。
    MiuixAnimatedDialog(
        visible = visible,
        onDismissRequest = onDismiss,
    ) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                // ── 标题行 ────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "可用模型",
                            style = MiuixTheme.textStyles.body1,
                        )
                        Text(
                            // 说清「多选之后怎么用」：用户勾了三个会担心
                            // 「到底发哪个」，这一句直接回答。
                            text = "可多选；对话使用其中标记为「使用中」的那个",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        // 拉取中禁用：连点会并发多个请求，且后到的响应会覆盖先到的
                        // （列表顺序在切换之间跳一下，看起来像"刷新出了不同结果"）。
                        enabled = !loading,
                        modifier = Modifier.size(40.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                // 用 `size` 参数而不是 `Modifier.size`：miuix 内部是
                                // `modifier.size(size)`，后应用者生效，默认 30dp 会
                                // 覆盖掉 Modifier 上的 18dp（详见 ChatScreen 里的说明）。
                                size = 18.dp,
                                strokeWidth = 1.8.dp,
                            )
                        } else {
                            Text(
                                text = "刷新",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── 搜索 ──────────────────────────────────────────────
                TextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "搜索模型",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )

                Spacer(Modifier.height(8.dp))

                // ── 全选 / 取消全选（拉取后一键全选）───────────────────
                //
                // ## 为什么作用域是 `filtered` 而不是 `options`
                //
                // 用户搜「gpt」是想「把 gpt 系列全勾上」，不是「把所有模型全勾上」。
                // 作用在 `filtered` 上，「筛选 + 全选」就自然组合成批量操作；
                // 作用在 `options` 上则会悄悄选中一堆他看不见的模型，
                // 确认后模型池里多出几十个名字 —— 那是最难解释的一种结果。
                //
                // 没有任何可选项时整行不画（列表处于错误态或空态时这一行毫无意义）。
                if (filtered.isNotEmpty()) {
                    val allFilteredSelected = filtered.all { it.id in draftIds }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            // 全选 / 取消全选合成一个按钮：
                            // 两个按钮同时存在时用户每次都要先判断「现在是哪种状态」，
                            // 而按钮上的文字已经把这个判断做完了。
                            text = if (allFilteredSelected) {
                                if (search.isBlank()) "取消全选" else "取消选中这批"
                            } else {
                                if (search.isBlank()) "全选" else "选中这批"
                            },
                            onClick = {
                                draftIds = if (allFilteredSelected) {
                                    // 只从可见项里移除，保留之前选中的其它模型。
                                    draftIds - filtered.map { it.id }.toSet()
                                } else {
                                    // 同理：只并入可见项，不清掉已选的其它项。
                                    draftIds + filtered.map { it.id }
                                }
                            },
                            textStyle = MiuixTheme.textStyles.footnote1,
                        )
                    }
                }
                // ── 全选 / 取消全选（拉取后一键全选）───────────────────
                //
                // ## 为什么作用域是 `filtered` 而不是 `options`
                //
                // 用户搜「gpt」是想「把 gpt 系列全勾上」，不是「把所有模型全勾上」。
                // 作用在 `filtered` 上，「筛选 + 全选」就自然组合成批量操作；
                // 作用在 `options` 上则会悄悄选中一堆他看不见的模型，
                // 确认后模型池里多出几十个名字 —— 那是最难解释的一种结果。
                //
                // 没有任何可选项时整行不画（列表处于错误态或空态时这一行毫无意义）。
                if (filtered.isNotEmpty()) {
                    val allFilteredSelected = filtered.all { it.id in draftIds }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            // 全选 / 取消全选合成一个按钮：
                            // 两个按钮同时存在时用户每次都要先判断「现在是哪种状态」，
                            // 而按钮上的文字已经把这个判断做完了。
                            text = if (allFilteredSelected) {
                                if (search.isBlank()) "取消全选" else "取消选中这批"
                            } else {
                                if (search.isBlank()) "全选" else "选中这批"
                            },
                            onClick = {
                                draftIds = if (allFilteredSelected) {
                                    // 只从可见项里移除，保留之前选中的其它模型。
                                    draftIds - filtered.map { it.id }.toSet()
                                } else {
                                    // 同理：只并入可见项，不清掉已选的其它项。
                                    draftIds + filtered.map { it.id }
                                }
                            },
                            textStyle = MiuixTheme.textStyles.footnote1,
                        )
                    }
                }
                // ── 列表 / 错误 / 空态 ────────────────────────────────
                //
                // 三者互斥地占同一块区域。高度上限 320dp：超过这个高度弹窗会把
                // 键盘往上顶，在文字输入时列表跳动。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 320.dp),
                ) {
                    when {
                        loading && options.isEmpty() -> Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                // 同上：必须用 `size` 参数，默认 30dp。
                                size = 24.dp,
                            )
                        }

                        error != null -> Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        ) {
                            Text(
                                text = "拉取失败",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = error,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(text = "重试", onClick = onRefresh)
                        }

                        filtered.isEmpty() -> Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                // 区分「搜不到」与「服务商一个都没返回」：
                                // 前者是用户输入问题，后者是服务端问题。
                                text = if (search.isNotBlank()) {
                                    "没有匹配「$search」的模型"
                                } else {
                                    "该服务商的 /models 没有返回任何模型，请手动填写模型 ID"
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }

                        else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(filtered, key = { it.id }) { option ->
                                ModelRow(
                                    option = option,
                                    checked = option.id in draftIds,
                                    onToggle = {
                                        // 用不可变 Set 的 `+` / `-` 生成新集合：
                                        // 直接改 mutableSetOf 不会触发重组。
                                        draftIds = if (option.id in draftIds) {
                                            draftIds - option.id
                                        } else {
                                            draftIds + option.id
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // ── 底部计数 ──────────────────────────────────────────
                //
                // 照上游那句「显示 N 个 • M 已选」的语义，但把「筛选前/后」
                // 也说清：用户搜索时容易以为「总共就这么几个」。
                if (filtered.isNotEmpty()) {
                    Text(
                        text = buildString {
                            append("显示 ${filtered.size} 个")
                            if (search.isNotBlank()) append("（已筛选，共 ${options.size} 个）")
                            if (draftIds.isNotEmpty()) append(" • 已选 ${draftIds.size}")
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // ── 底部按钮 ──────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "关闭", onClick = onDismiss)
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        // 一个都没勾时禁用：确认空集合等于把用户已填的模型池清空。
                        text = if (draftIds.isEmpty()) "确认" else "确认（${draftIds.size}）",
                        enabled = draftIds.isNotEmpty(),
                        onClick = {
                            // ## 关键：按 `options` 的原序拼接，不按 draftIds 的顺序
                            //
                            // `draftIds` 是 Set，遍历顺序对 `LinkedHashSet` 而言是
                            // 插入顺序，而插入顺序又取决于用户点击顺序 ——
                            // 那会让 `modelIndex` 的语义失效（见类 KDoc）。
                            //
                            // 这里用「遍历服务端列表、只留勾上的」复刻上游写法。
                            //
                            // ## 为什么用 `options` 而不是 `filtered`
                            //
                            // 搜索状态下 `filtered` 只是一部分。若按 `filtered`
                            // 拼接，用户「先搜 A 勾上、再搜 B 勾上」两次确认后
                            // 会丢掉第一次的选择。用全量 `options` 才安全。
                            val ordered = options.map { it.id }.filter { it in draftIds }
                            onConfirm(ordered)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 单行模型（多选）。
 *
 * ## 为什么用 miuix `Checkbox` 而指示器不处理点击
 *
 * 整行可点（点哪都能勾），而 `Checkbox` 自带点击处理 —— 套在可点 Row 里
 * 会出现「点勾选框触发两次」（勾上又取消）的经典问题。
 * 所以 `Checkbox(onClick = null)`：它只负责**画**，点击统一由外层 Row 接。
 *
 * 与上一版自绘圆点相比，换成真 Checkbox 是因为多选需要「多选」这个
 * 视觉语义 —— 圆点会被读成单选（RadioButton）。
 *
 * ## 为什么外层用 `toggleable` 而不是 `clickable`
 *
 * 无障碍上这一行是「可切换」而不是「按钮」：读屏会念「已选中/未选中」
 * 并给出切换提示。`clickable` 只会念「按钮」，用户不知道当前什么状态。
 */
@Composable
private fun ModelRow(
    option: AiModelOption,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    onValueChange = { onToggle() },
                )
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                // null = 不自己处理点击（见上方 KDoc）。
                onClick = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.name,
                    style = MiuixTheme.textStyles.body2,
                    color = if (checked) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // id 与显示名不同时才显示第二行（Gemini 的 displayName 场景）。
                // 相同就重复一遍，是纯噪音。
                if (option.name != option.id) {
                    Text(
                        text = option.id,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 4.dp),
            color = MiuixTheme.colorScheme.dividerLine,
        )
    }
}