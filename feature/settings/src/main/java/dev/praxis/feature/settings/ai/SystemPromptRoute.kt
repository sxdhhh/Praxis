package dev.praxis.feature.settings.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.praxis.core.data.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * 系统提示词路由：读当前值 + 保存。
 *
 * 用 `collectAsState` 而不是 `remember { first() }`：保存后 Flow 会推新值，
 * 草稿层（`remember(current)`）随之重建，界面上「已保存 / 保存」的状态
 * 才能正确切换。若取首值快照，保存后草稿层不会重建，按钮永远显示「保存」，
 * 用户会重复点。
 */
@Composable
fun SystemPromptRoute(
    repository: SettingsRepository,
    modifier: Modifier,
) {
    val current by repository.systemPrompt.collectAsState(initial = "")
    // 人格：库里存的是「用户改写值」（空 = 没改过）。
    val personaStored by repository.personaPrompt.collectAsState(initial = "")
    val scope = rememberCoroutineScope()

    // 界面要显示的是**实际生效**的那份：没改过就显示出厂人格，
    // 否则输入框一片空白，用户无法基于原文微调、也看不出当前在用的是什么。
    val personaEffective = personaStored.takeIf { it.isNotBlank() }
        ?: dev.praxis.core.ai.SystemPromptSettings.BUILTIN_PERSONA

    SystemPromptScreen(
        current = current,
        onSave = { text -> scope.launch { repository.setSystemPrompt(text) } },
        personaCurrent = personaEffective,
        personaEdited = personaStored.isNotBlank(),
        personaBuiltin = dev.praxis.core.ai.SystemPromptSettings.BUILTIN_PERSONA,
        onSavePersona = { text -> scope.launch { repository.setPersonaPrompt(text) } },
        modifier = modifier,
    )
}