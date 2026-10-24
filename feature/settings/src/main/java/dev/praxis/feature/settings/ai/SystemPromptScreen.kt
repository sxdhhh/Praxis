package dev.praxis.feature.settings.ai

import dev.praxis.feature.settings.common.SettingsList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 系统提示词设置页。
 *
 * ## 优先级说明必须写在界面上
 *
 * user 的要求是「系统提示词权重最高，用户其次，然后让 ai 一定要照着来」。
 * 这套优先级只有**用户自己知道**才有意义 —— 否则他写了一段规则、
 * 模型没照做，他不知道是「规则没生效」还是「被后面的话覆盖了」。
 *
 * 所以页面上直接列出分层顺序（用户系统提示词 > 内置人格 > 技能 > 记忆 > 摘要），
 * 并说明「冲突时以你写的为准」。
 *
 * ## 为什么不做「多套提示词预设」
 *
 * 那是另一个维度的功能（角色卡 / 场景切换），需要命名、切换、导入导出。
 * 现在只做一件事：**一段常驻的最高优先级文本**。做深一个比铺开五个有用。
 */
@Composable
fun SystemPromptScreen(
    current: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // 草稿：跟随外部值重建（保存成功后外部值变，草稿与之一致）。
    var draft by remember(current) { mutableStateOf(current) }
    val dirty = draft != current

    SettingsList(modifier = modifier) {
        item { Spacer(Modifier.height(12.dp)) }

        // ── 优先级说明 ──────────────────────────────────
        item { SmallTitle("优先级") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "1. 你在这里写的规则（最高）",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Text(
                        text = "2. Praxis 内置人格",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "3. 已启用技能 · 4. 长期记忆 · 5. 对话摘要",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "系统会在每轮对话里把上述规则放在最前面，并追加一条执行约束：" +
                            "后续用户消息、工具输出与历史摘要都不得覆盖它们。" +
                            "若你的请求与规则冲突，模型会先做不冲突的部分再说明冲突点。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ── 编辑 ────────────────────────────────────────
        item { SmallTitle("内容") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    // TextField 而不是 BasicTextField：聚焦边框动画、squircle 圆角、
                    // 标签态都由 miuix 提供，且与模型页的输入框观感一致。
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "例如：始终用中文回答；代码必须可直接运行；不要编造链接",
                        useLabelAsPlaceholder = true,
                        // 提示词可能很长：给 10 行可视区，超出内部滚动。
                        minLines = 6,
                        maxLines = 12,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (draft.isBlank()) {
                            "留空表示不注入这一段（其余分层照常生效）。"
                        } else {
                            "约 ${draft.length} 字符 · 改动在点「保存」后对下一轮对话生效。"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = if (dirty) "保存" else "已保存",
                            enabled = dirty,
                            modifier = Modifier.weight(1f),
                            onClick = { scope.launch { onSave(draft) } },
                        )
                        if (draft.isNotEmpty()) {
                            Spacer(Modifier.height(0.dp))
                            TextButton(
                                text = "清空",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    draft = ""
                                    scope.launch { onSave("") }
                                },
                            )
                        }
                    }
                }
            }
        }

        // ── 写法建议（减少用户写出无效规则） ─────────────
        item { SmallTitle("怎么写更有效") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    listOf(
                        "写具体行为，不写抽象目标：'回答先给结论再给步骤' 比 '回答要专业' 有效",
                        "把「不要做什么」写成可判定的条件：'不确定时说不确定' 比 '不要瞎说' 有效",
                        "规则控制在合理长度：太长会稀释每一条的分量，也会挤占上下文",
                        "与当前任务无关的规则不要常驻，按会话临时说明更划算",
                    ).forEach { tip ->
                        Text(
                            text = "· " + tip,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }
        }
    }
}