package dev.praxis.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.ThemeSettings
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置域入口：把 SETTINGS 域的路由分发到具体页面。
 *
 * 只在 `:app` 里被调用一次。`entryKey` 就是路由里的 arg，与 [SettingsEntry.key] 对应，
 * 这样加页面不需要改导航层。
 */
@Composable
fun SettingsHost(
    entryKey: String?,
    repository: SettingsRepository,
    versionName: String,
    versionCode: Int,
    toolSpecs: List<dev.praxis.core.tool.ToolSpec>,
    currentTier: dev.praxis.core.tool.PermissionTier,
    isToolEnabled: (String) -> Boolean,
    onTierChange: (dev.praxis.core.tool.PermissionTier) -> Unit,
    onToolToggle: (String, Boolean) -> Unit,
    onOpen: (SettingsEntry) -> Unit,
    /** 记忆存储。由 :app 注入，与 repository 同口径。 */
    memoryStore: dev.praxis.core.memory.MemoryStore,
    modifier: Modifier = Modifier,
) {
    val entry = SettingsEntry.byKey(entryKey)
    if (entry == null) {
        SettingsScreen(onOpen = onOpen, modifier = modifier)
        return
    }

    when (entry) {
        SettingsEntry.Appearance -> AppearanceRoute(repository, modifier)

        SettingsEntry.Model -> ModelRoute(repository, modifier)

        SettingsEntry.Memory -> MemoryRoute(memoryStore, modifier)

        SettingsEntry.Tools -> dev.praxis.feature.settings.tools.ToolsScreen(
            tools = toolSpecs,
            currentTier = currentTier,
            isEnabled = isToolEnabled,
            onToggle = onToolToggle,
            modifier = modifier,
        )

        SettingsEntry.Permission -> dev.praxis.feature.settings.tools.PermissionScreen(
            currentTier = currentTier,
            allTools = toolSpecs,
            onSelect = onTierChange,
            modifier = modifier,
        )
        SettingsEntry.About -> AboutScreen(
            versionName = versionName,
            versionCode = versionCode,
            modifier = modifier,
        )

        // 其余条目已登记但页面还没实现：显示明确说明，不假装能用。
        else -> SettingsPlaceholder(entry, modifier)
    }
}

@Composable
private fun AppearanceRoute(
    repository: SettingsRepository,
    modifier: Modifier,
) {
    val settings by repository.themeSettings.collectAsState(initial = ThemeSettings())
    val scope = rememberCoroutineScope()

    AppearanceScreen(
        settings = settings,
        onModeChange = { scope.launch { repository.setThemeMode(it) } },
        onColorSourceChange = { scope.launch { repository.setColorSource(it) } },
        onPaletteChange = { scope.launch { repository.setPaletteStyle(it) } },
        modifier = modifier,
    )
}

@Composable
private fun SettingsPlaceholder(entry: SettingsEntry, modifier: Modifier) {
    Column(modifier = modifier.padding(24.dp)) {
        Text(
            text = entry.title,
            style = MiuixTheme.textStyles.title2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = "此设置项尚未实现。功能落地后会自动出现在这里。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 模型配置路由：读设置 + 保存。 */
@Composable
private fun ModelRoute(repository: SettingsRepository, modifier: Modifier) {
    val modelSettings by repository.modelSettings.collectAsState(initial = dev.praxis.core.model.settings.ModelSettings())
    val scope = rememberCoroutineScope()
    // 密钥单独读一次（不进 data class，避免跟随配置对象到处流）。
    val keyMasked = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        keyMasked.value = maskApiKey(repository.apiKey())
    }

    ModelScreen(
        settings = modelSettings,
        apiKeyMasked = keyMasked.value,
        onSave = { baseUrl, model, apiKey ->
            scope.launch {
                repository.setBaseUrl(baseUrl)
                repository.setModel(model)
                // 留空表示不修改密钥，避免用户只是想改模型时把 key 清掉。
                if (apiKey.isNotBlank()) {
                    repository.setApiKey(apiKey)
                    keyMasked.value = maskApiKey(apiKey)
                }
            }
        },
        modifier = modifier,
    )
}
