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
    /** 当前生效的人格文本（用户改过就是用户的，否则是出厂人格）。 */
    personaCurrent: String = "",
    /** 用户是否改过人格（决定显示「恢复出厂」还是「保存」）。 */
    personaEdited: Boolean = false,
    /** 出厂人格原文，用于「恢复出厂」与占位提示。 */
    personaBuiltin: String = "",
    /** 保存人格；空串 = 恢复出厂人格。 */
    onSavePersona: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // 草稿：跟随外部值重建（保存成功后外部值变，草稿与之一致）。
    var draft by remember(current) { mutableStateOf(current) }
    val dirty = draft != current

    // 人格草稿：初值取「当前生效」的那份（改过就用改过的，否则是出厂）。
    // 这样用户点进来看得到实际在用的文本，而不是一片空白。
    var personaDraft by remember(personaCurrent) { mutableStateOf(personaCurrent) }
    val personaDirty = personaDraft != personaCurrent

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

        // ── 内置人格（第二层）────────────────────────────
        //
        // 用户的要求：「可以修改内置人格的系统提示词，就放在系统提示词下面的」。
        // 所以这一节紧跟在用户规则之后，且**明确说明它是第二优先级** ——
        // 否则用户会以为自己改的是最高优先级那份。
        item { SmallTitle("内置人格（第二优先级）") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "这是 Praxis 的默认身份描述。改它只影响「它怎么介绍自己」，" +
                            "不会动上面那份最高优先级规则。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = personaDraft,
                        onValueChange = { personaDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "例如：你是 Praxis，一个务实的 Android AI 助手……",
                        useLabelAsPlaceholder = true,
                        minLines = 4,
                        maxLines = 10,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (personaEdited) {
                            "当前用的是你改过的版本（${personaDraft.length} 字符）。"
                        } else {
                            "当前用的是出厂人格。改动会在下一轮对话生效。"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = if (personaDirty) "保存" else "已保存",
                            enabled = personaDirty,
                            modifier = Modifier.weight(1f),
                            onClick = { scope.launch { onSavePersona(personaDraft) } },
                        )
                        // ## 为什么「恢复出厂」要单独一个按钮
                        //
                        // 人格与用户规则分开存（见 SystemPromptSettings.persona），
                        // 所以「清空人格」不会误清用户规则；反过来，
                        // 用户想把人格改回原文时，不该逼他自己去删干净 ——
                        // 一键写回出厂文本更明确，也不会留下半截残留。
                        if (personaEdited) {
                            TextButton(
                                text = "恢复出厂",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    personaDraft = personaBuiltin
                                    scope.launch { onSavePersona("") }
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