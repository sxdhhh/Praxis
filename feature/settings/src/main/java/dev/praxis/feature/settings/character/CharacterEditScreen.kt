package dev.praxis.feature.settings.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.praxis.core.model.character.CharacterCard
import dev.praxis.core.model.character.PromptTag
import dev.praxis.feature.settings.common.SettingsList
import dev.praxis.ui.design.glass.kyant.LiquidToggle
import dev.praxis.ui.design.glass.kyant.rememberPageBackdropOrFallback
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 角色卡编辑页。
 *
 * ## 字段顺序 = 提示词拼接顺序
 *
 * 界面上从上到下就是 [dev.praxis.core.model.character.CharacterPromptComposer]
 * 的拼接顺序：
 *
 * ```
 * 角色设定 → 其他内容 → 标签 → 高级自定义
 * ```
 *
 * 这样用户改完一个字段就知道它在最终提示词里的位置，
 * 而不用去猜「高级自定义会不会盖掉角色设定」。
 *
 * ## 为什么每个文本框都标注「是否进入提示词」
 *
 * 简介、开场白与备注**不进**提示词，但它们和角色设定长得一样（都是多行文本框）。
 * 不标注的话，用户会把整段人设写进「备注」，然后发现模型完全没反应 ——
 * 这是最难自查的一类问题：内容明明写进去了，就是不生效。
 *
 * ## 为什么提示词预览放在最下面而不是顶部
 *
 * 预览是**结果**，字段是**输入**。放在顶部时用户每改一个字都要往上滚去看，
 * 而长表单往上滚会丢失输入焦点位置。放最下面，改完顺手往下一滑就能核对。
 */
@Composable
fun CharacterEditScreen(
    state: CharacterEditUiState,
    onUpdate: ((CharacterCard) -> CharacterCard) -> Unit,
    onToggleTag: (PromptTag) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    /**
     * 导出是否可用。
     *
     * 由接线层传入（`:app` 是否提供了写入端口）。默认 false：宁可
     * 不显示按钮，也不显示一个点了没反应的按钮 —— 后者会让用户
     * 以为「导出坏了」，而真实原因是功能没接上。
     */
    exportEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val card = state.card

    // 标签开关的玻璃采样源：与设置页其它开关同一做法
    // （页面没 provide 采样源时回退纯色，不会变成黑块）。
    // 提到列表外层而不是每行 remember：一页几十个标签，
    // 每行各建一个 Backdrop 会让滚动明显变沉。
    val tagToggleBackdrop = rememberPageBackdropOrFallback(MiuixTheme.colorScheme.surface)

    SettingsList(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // ── 基本信息 ─────────────────────────────────────────
        item {
            SmallTitle("基本信息")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = card.name,
                        onValueChange = { value -> onUpdate { it.copy(name = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = "名字（必填）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = card.description,
                        onValueChange = { value -> onUpdate { it.copy(description = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = "简介（只用于列表显示，不进提示词）",
                        useLabelAsPlaceholder = true,
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            }
        }

        // ── 角色设定（提示词第 1 段）────────────────────────────
        item {
            SmallTitle("角色设定")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "决定「你是谁」。这段排在最终提示词的最前面。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = card.characterSetting,
                        onValueChange = { value ->
                            onUpdate { it.copy(characterSetting = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "例如：你是一位说话简洁、只给结论的资深工程师…",
                        useLabelAsPlaceholder = true,
                        minLines = 6,
                        maxLines = 14,
                    )
                }
            }
        }

        // ── 其他内容（提示词第 2 段）────────────────────────────
        item {
            SmallTitle("其他内容")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "补充行为约束，排在角色设定之后。" +
                            "语音版仅在语音模式下替代聊天版。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = card.otherContentChat,
                        onValueChange = { value ->
                            onUpdate { it.copy(otherContentChat = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "聊天模式的其他内容",
                        useLabelAsPlaceholder = true,
                        minLines = 4,
                        maxLines = 10,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = card.otherContentVoice,
                        onValueChange = { value ->
                            onUpdate { it.copy(otherContentVoice = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "语音模式的其他内容",
                        useLabelAsPlaceholder = true,
                        minLines = 3,
                        maxLines = 8,
                    )
                }
            }
        }

        // ── 标签（提示词第 3 段）──────────────────────────────
        item {
            SmallTitle("标签")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "标签是可复用的提示词片段，排在角色设定与其他内容之后。" +
                            "改一处标签内容，所有引用它的角色卡同时生效。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (state.allTags.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "标签库还是空的。去「标签库」新建一条，回来就能挂在这里。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    }
                }
            }
        }

        items(state.allTags, key = { "tag:${it.id}" }) { tag ->
            val attached = tag.id in card.attachedTagIds
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tag.name,
                            style = MiuixTheme.textStyles.body1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            // 只取前 60 字做预览：标签正文可能很长，
                            // 全显示会让列表每行高度不可控。
                            text = tag.promptContent
                                .take(60)
                                .replace("\n", " ")
                                .ifBlank { "（内容为空，不会生效）" },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    // 用全项目统一的液态玻璃开关，不用 miuix Switch ——
                    // 否则这一行会跟设置页其它开关长得不一样（用户要求「所有开关都一样」）。
                    LiquidToggle(
                        selected = { attached },
                        onSelect = { onToggleTag(tag) },
                        backdrop = tagToggleBackdrop,
                    )
                }
            }
        }

        // ── 高级（提示词第 4 段：最后，用来覆盖前面）──────────────
        item {
            SmallTitle("高级")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "自定义引导词排在最终提示词的最后，用来覆盖前面的描述。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = card.advancedCustomPrompt,
                        onValueChange = { value ->
                            onUpdate { it.copy(advancedCustomPrompt = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "高级自定义引导词（可为空，进入提示词）",
                        useLabelAsPlaceholder = true,
                        minLines = 3,
                        maxLines = 8,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = card.openingStatement,
                        onValueChange = { value ->
                            onUpdate { it.copy(openingStatement = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "开场白（不进提示词，供参考/新会话使用）",
                        useLabelAsPlaceholder = true,
                        minLines = 3,
                        maxLines = 8,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = card.marks,
                        onValueChange = { value -> onUpdate { it.copy(marks = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = "备注（只给自己看，绝不进提示词）",
                        useLabelAsPlaceholder = true,
                        minLines = 2,
                        maxLines = 6,
                    )
                }
            }
        }

        // ── 错误提示（放在列表内，不浮在内容之上）──────────────────
        //
        // ## 为什么作为 item 而不是覆盖层
        //
        // 覆盖层（Box + align）会挡住下面的字段，用户想改那个字段时
        // 会点不到。作为普通 item 时它只是列表里的一段，滚过去就看不见了，
        // 但需要时能读到、能点「知道了」收起。
        val errorMessage = state.message
        if (errorMessage != null && !state.loading) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = errorMessage,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ── 最终提示词预览 ───────────────────────────────────
        item {
            SmallTitle("最终提示词预览")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        // 说清预览与实际的关系，避免用户以为预览是估的。
                        text = "下面就是实际会发给模型的内容" +
                            "（与聊天时用的是同一段拼接逻辑）。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    val previewBlank = state.promptPreview.isBlank()
                    Text(
                        text = if (previewBlank) {
                            "（空）这段角色卡没有任何内容会进入提示词。"
                        } else {
                            state.promptPreview
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = if (previewBlank) {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "共 ${state.promptPreview.length} 字",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ── 操作 ─────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 保存占 2 份宽度（有导出）或满宽（没导出）：
                // 它是这个页面唯一的高频主操作，不该因为少一个按钮就变窄。
                TextButton(
                    text = if (state.saving) "保存中…" else "保存",
                    onClick = onSave,
                    modifier = Modifier.weight(if (exportEnabled) 2f else 1f),
                    enabled = !state.saving,
                )
                if (exportEnabled) {
                    TextButton(
                        text = "导出",
                        onClick = onExport,
                        modifier = Modifier.weight(1f),
                        // 新建尚未落库时没有 id，导出会查不到卡。
                        enabled = !state.isNew && !state.saving,
                    )
                }
            }
        }

        // ── 删除 ─────────────────────────────────────────────
        if (state.canDelete) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            // 明确「删除不可撤销」，并说清会丢什么，
                            // 用户才会认真看一眼再点。
                            text = "删除后不可恢复。这张卡里的角色设定、其他内容、" +
                                "标签引用都会一起消失。标签本身不会被删除。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            text = "删除这张角色卡",
                            onClick = onDelete,
                        )
                    }
                }
            }
        } else if (!state.isNew) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = "内置默认角色卡不能删除。可以随意修改它的内容。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
        }
    }
}
