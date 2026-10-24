package dev.praxis.feature.settings.character
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.praxis.core.model.character.PromptTag
import dev.praxis.core.model.character.PromptTagType
import dev.praxis.feature.settings.common.SettingsList
import dev.praxis.ui.theme.PraxisMotion
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 提示词标签库。
 *
 * ## 标签存在的意义
 *
 * 同一段「回答必须给出可执行命令」可能要被 5 张角色卡引用。写 5 遍不仅麻烦，
 * 更糟的是**改一处要改 5 处**，用户必然会漏。标签把这段提示词抽成一处，
 * 改一次全部生效。
 *
 * ## 为什么这里只做「新建 + 列表 + 删除」，不做「编辑正文」
 *
 * 编辑标签正文会影响所有引用它的角色卡。用户点进去改一句、
 * 结果 5 张卡的人设都变了 —— 这种「远程副作用」必须让用户看得见
 * 有多少卡在用（列表里显示引用数），而不是悄悄改掉。
 *
 * 所以本轮只做最必要的：能建、能看、能删（删时明确告知引用会被清理）。
 * 正文编辑在标签被真正用起来之后再加，并配「影响 N 张卡」的确认。
 */
@Composable
fun PromptTagsScreen(
    tags: List<PromptTag>,
    /** 每个标签被多少张角色卡引用（key = tag id）。 */
    usageCounts: Map<String, Int>,
    onCreate: (name: String, content: String, type: PromptTagType) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftName by remember { mutableStateOf("") }
    var draftContent by remember { mutableStateOf("") }

    SettingsList(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SmallTitle("新建标签")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "标签是可复用的提示词片段。内容相同的标签会自动复用，" +
                            "不会重复创建。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "标签名（如：简洁回答）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = draftContent,
                        onValueChange = { draftContent = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "提示词内容（会原样拼进角色卡提示词）",
                        useLabelAsPlaceholder = true,
                        minLines = 4,
                        maxLines = 10,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = "添加",
                            onClick = {
                                onCreate(draftName, draftContent, PromptTagType.Custom)
                                // 成功后清空草稿：否则用户再点一次「添加」会建出
                                // 一条内容相同的标签（被复用，看起来像「没反应」）。
                                draftName = ""
                                draftContent = ""
                            },
                            enabled = draftContent.isNotBlank(),
                        )
                    }
                }
            }
        }

        item(key = "__title_tags__") { SmallTitle("已有标签（${tags.size}）") }

        if (tags.isEmpty()) {
            item(key = "__empty_tags__") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "还没有标签。上面新建一条后，就能在角色卡编辑页挂上去。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        items(tags, key = { "tag:${it.id}" }) { tag ->
            // ## `animateItem`（2026-10-24）
            //
            // 标签可以删除，删一条之后下面每条上移一格。这是本列表唯一的
            // 动态变化，也是最容易被注意到的（用户点删除后盯着列表看结果）。
            //
            // 只给 `placementSpec` + fadeOut，**不给 fadeIn**：
            // 新标签是用户在顶部输入框里新建的，它出现时上方还有内容，
            // 淡入会与「刚敲完回车、眼睛已在顶部」的注意力错位。
            // 而删除是「东西消失」，淡出是必要的（否则凭空不见）。
            Box(
                modifier = Modifier.animateItem(
                    placementSpec = spring(stiffness = 400f, dampingRatio = 0.85f),
                    fadeOutSpec = tween(
                        durationMillis = PraxisMotion.Durations.Fast,
                        easing = PraxisMotion.Curves.Accelerate,
                    ),
                ),
            ) {
                TagRow(
                    tag = tag,
                    usageCount = usageCounts[tag.id] ?: 0,
                    onDelete = { onDelete(tag.id) },
                )
            }
        }

        item(key = "__tail_spacer__") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TagRow(
    tag: PromptTag,
    usageCount: Int,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tag.name,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 引用数是删除前必须看到的信息：删掉一个被 8 张卡引用的标签，
                // 8 张卡的人设都会少一段。不显示的话用户无法判断后果。
                if (usageCount > 0) {
                    Text(
                        text = "$usageCount 张卡在用",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
            if (tag.promptContent.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tag.promptContent.take(120).replace("\n", " "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    // 文案带上后果，而不是干巴巴的「删除」。
                    text = if (usageCount > 0) "删除（并从 $usageCount 张卡移除）" else "删除",
                    onClick = onDelete,
                )
            }
        }
    }
}