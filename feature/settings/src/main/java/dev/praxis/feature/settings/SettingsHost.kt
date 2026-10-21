package dev.praxis.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
    /** 工作流引擎。由 :app 注入。 */
    workflowEngine: dev.praxis.core.workflow.WorkflowEngine,
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

        SettingsEntry.Workflow -> WorkflowRoute(workflowEngine, modifier)

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
        SettingsEntry.Log -> LogScreen(modifier)
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

/**
 * 尚未实现的设置项。
 *
 * ## 为什么要说清「为什么没做」而不是一句「尚未实现」
 *
 * 用户点进来是为了用它。只说「尚未实现」等于把问题丢回给用户，
 * 他既不知道该不该等，也不知道是不是坏了。所以每个入口带一句具体原因。
 *
 * ## 顶部避让
 *
 * 这里原先用 `Column(padding(24.dp))`，不读 `LocalTopBarInset` ——
 * 而 `AppScaffold` 故意把顶栏高度**不**放进容器 padding（否则内容永远到不了
 * 顶栏下面，渐变模糊会退化成实色）。结果是标题被顶栏压住。
 * 现在改成 `SettingsList`（已经是全项目统一的滚动容器，内含顶栏避让）。
 */
@Composable
private fun SettingsPlaceholder(entry: SettingsEntry, modifier: Modifier) {
    SettingsList(modifier = modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(24.dp)) }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = entry.title,
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = placeholderReason(entry),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** 每个未实现入口的具体原因（不写笼统的「尚未实现」）。 */
private fun placeholderReason(entry: SettingsEntry): String = when (entry) {
    SettingsEntry.Speech ->
        "语音（TTS / 语音输入）已列入主动放弃：要适配多个厂商引擎，" +
            "半成品只会出现「以为能说话、实际报错」。等有确定引擎再回来做。"
    SettingsEntry.Packages ->
        "扩展包需要沙箱内嵌 JS 运行时（ToolPkg），风险量级与普通工具完全不同。" +
            "当前工具注册表已覆盖内置能力，暂不开放第三方包。"
    else -> "此设置项尚未实现。"
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
