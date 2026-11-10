package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.gateway.GatewayModelRow
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.MiuixSwitchWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 网关 → 模型管理（`gateway_model` 表的写入口）。
 *
 * ## 这张表原先为什么是死的
 *
 * 它在 P2 落库时建好（v12→v13），但一直没有写入路径 —— 建了、迁移了、永远没数据。
 * 而「结构化条目」正是比目录名多出来的那一层：逐条启停与能力位。
 *
 * ## 为什么先只做手动添加
 *
 * 网关的 `/v1/models` 拉取依赖 P5 的 Rust 内核（现在没有），所以条目只能手填。
 * 等内核就位，这一页会变成「拉取 + 手动补充」，而不是被推翻重做。
 */
@Composable
fun GatewayModelsScreen(
    rows: List<GatewayModelRow> = emptyList(),
    /** provider / id / enabled —— 逐条启停。 */
    onToggle: (String, String, Boolean) -> Unit = { _, _, _ -> },
    /** provider / id —— 删除。 */
    onDelete: (String, String) -> Unit = { _, _ -> },
    /** provider / id / name —— 添加。 */
    onAdd: (String, String, String) -> Unit = { _, _, _ -> },
    /** 最近一次写入失败的原因；null = 没有。 */
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    var addVisible by remember { mutableStateOf(false) }

    // 顶栏是浮层：内容要能滚到它下面（否则渐进模糊采不到内容，退化成一块实色）。
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    // 底栏同理：滚动终点必须抬高，否则最后一项被压住看不见。
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(top = topInset, bottom = 24.dp + scrollLimit),
    ) {
        item(key = "title") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Layers,
                title = "模型管理",
                subtitle = "结构化条目：逐条启停，选路时按它走",
            )
        }

        if (error != null) {
            item(key = "error") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = error,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        if (rows.isEmpty()) {
            item(key = "empty") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "还没有结构化条目。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = "模型页的候选仍然会列出各家声明的目录名；这里的条目多一层",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = "「逐条启停 + 能力位」，并且排在候选的前面。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        } else {
            items(rows, key = { row -> row.provider + "/" + row.id }) { row ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Column {
                        MiuixSwitchWidget(
                            title = row.name,
                            description = row.provider + " / " + row.id + " · " + row.kind,
                            checked = row.enabled,
                            onCheckedChange = { enabled ->
                                onToggle(row.provider, row.id, enabled)
                            },
                        )
                        MiuixNavigationItemWidget(
                            title = "删除",
                            description = "只删这条记录，不动账号与目录名",
                            onClick = { onDelete(row.provider, row.id) },
                        )
                    }
                }
            }
        }

        item(key = "add") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "添加模型",
                    description = "手动填 provider 与模型 id（自动拉取要等 Rust 内核）",
                    onClick = { addVisible = true },
                )
            }
        }

        item(key = "note") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "新增条目的能力位留空（未知）—— 不猜 true/false：",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "写 true 会让选路把纯文本模型当多模态发出去（上游 400）；写 false 又会让本来支持的功能用不上。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    AddModelDialog(
        visible = addVisible,
            onDismiss = { addVisible = false },
            onConfirm = { provider, id, name ->
                onAdd(provider, id, name)
                addVisible = false
            },
    )
}

/** 添加条目：provider 与模型 id 必填，名称可留空（留空就用 id）。 */
@Composable
private fun AddModelDialog(
    visible: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var provider by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val canConfirm = provider.trim().isNotEmpty() && id.trim().isNotEmpty()

    MiuixAnimatedDialog(visible = visible, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "添加模型条目", style = MiuixTheme.textStyles.body1)
                Text(
                    text = "provider 用内置家的 id（如 openai）或 custom-xxxxxxxx",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = provider,
                    onValueChange = { provider = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "provider",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = id,
                    onValueChange = { id = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "模型 id（如 gpt-4o）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "显示名（可留空）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                MiuixNavigationItemWidget(
                    title = if (canConfirm) "添加" else "填完 provider 与 id 才能添加",
                    description = "能力位留空（未知），不猜",
                    onClick = {
                        if (canConfirm) {
                            onConfirm(provider.trim(), id.trim(), name.trim())
                        }
                    },
                )
            }
        }
    }
}
