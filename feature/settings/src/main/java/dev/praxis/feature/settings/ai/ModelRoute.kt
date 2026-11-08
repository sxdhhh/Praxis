package dev.praxis.feature.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.ModelAdvancedSettings
import dev.praxis.core.model.settings.ModelSettings
import kotlinx.coroutines.launch

/**
 * 配置弹窗的两种模式。
 *
 * ## 为什么共用一个状态
 *
 * 「新建」与「重命名」的界面完全一样：一个标题 + 一个输入框 + 确定/取消。
 * 分成两个 `mutableStateOf` 会让「同时打开两个弹窗」在状态上成为可能 ——
 * 那是永远不会发生的场景，却要每个渲染分支都判一遍。
 */
private sealed interface ProfileDialog {
    /** 新建（没有初始名）。 */
    data object New : ProfileDialog

    /** 重命名（带初始名与目标 id）。 */
    data class Rename(val id: String, val initial: String) : ProfileDialog
}


/**
 * 模型配置路由：读设置 + 保存。
 *
 * ## 从 `SettingsHost.kt` 搬到 `ai/` 子包
 *
 * 与 [AppearanceRoute] 同理：上游 InstallerX 的
 * `settings/preferred/installer/` 这类子目录里放的是**这一页自己的**
 * 页面 + 接线，分发器不承担接线。Praxis 原来把四个 Route 函数塞在
 * `SettingsHost` 里，300 行中只有 30 行是分发。
 *
 * ## 密钥为什么不进 `ModelSettings`
 *
 * `apiKey` 走独立加密存取（见 `SettingsRepository.apiKey()` / `setApiKey`），
 * 不放进 data class：一旦进了配置对象，它会跟着调试日志、状态快照、
 * 单测 fixture 到处流。这里只持有**掩码后的展示值**。
 */
@Composable
fun ModelRoute(
    repository: SettingsRepository,
    modifier: Modifier,
    /**
     * 模型列表拉取端口（`:app` 注入 OkHttp 实现）。
     *
     * 可空：没有注入时页面不显示「拉取」按钮。这样设置页在单测 / @Preview
     * 里不必拖进 OkHttp。
     */
    modelCatalogFetcher: dev.praxis.core.ai.ModelCatalogFetcher? = null,
    /** 网关对外模型清单；:app 从账号池与 modelRules 算出（见 GatewayModelCatalog）。 */
    gatewayModels: List<dev.praxis.core.ai.AiModelOption> = emptyList(),
) {
    val modelSettings by repository.modelSettings.collectAsState(
        initial = ModelSettings(),
    )
    // 进阶配置（自定义请求头 + 请求队列）。单独一条流，理由见
    // `ModelAdvancedSettings` 的 KDoc：里面的值可能含令牌，不与模型参数混在一起。
    val advanced by repository.modelAdvancedSettings.collectAsState(
        initial = ModelAdvancedSettings(),
    )
    val scope = rememberCoroutineScope()
    // 密钥单独读一次（不进 data class，避免跟随配置对象到处流）。
    val keyMasked = remember { mutableStateOf("") }
    /**
     * 密钥**明文**，只用于「拉取模型」这一次请求。
     *
     * ## 为什么需要它
     *
     * 拉列表要发带认证的真实请求。`keyMasked` 打码后发出去必然 401；
     * 而界面草稿在用户没改过时是空串（故意不回填明文）。
     * 所以「已保存但这次没重新输入」这个最常见场景必须有一个真实值来源。
     *
     * ## 为什么与 keyMasked 分开
     *
     * `keyMasked` 会进 Composable 参数、进重组快照；明文不应参与任何 UI 状态。
     * 分开存，明文只在 `onSave` 与「拉取」两处被读。
     */
    val keyPlain = remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        keyPlain.value = repository.apiKey()
        keyMasked.value = maskApiKey(keyPlain.value)
    }

    // 多配置：列表 + 当前生效项。
    //
    // `initial` 给空集：首帧还没读到 DataStore 时 UI 只画「新建」按钮，
    // 不会闪一个空选择器（见 ProfileSpinner 的空列表处理）。
    val profiles by repository.modelProfiles.collectAsState(
        initial = dev.praxis.core.model.settings.ModelProfileSet(),
    )
    // 新建/重命名用的输入框（null = 不显示弹窗）。
    // 两个动作共用一个弹窗状态：它们的 UI 完全一样，只有提交逻辑不同。
    val profileDialog = remember { mutableStateOf<ProfileDialog?>(null) }

    // 切换配置后要重读密钥：每套配置的 key 是独立的。
    // 不重读的后果是「切到配置 B，输入框里还是配置 A 的掩码」，
    // 而保存时又把 A 的 key 写进 B —— 这是最坏的一种串味。
    LaunchedEffect(profiles.activeId) {
        keyPlain.value = repository.apiKey()
        keyMasked.value = maskApiKey(keyPlain.value)
    }

    ModelScreen(
            gatewayModels = gatewayModels,
        settings = modelSettings,
        advanced = advanced,
        apiKeyMasked = keyMasked.value,
        savedApiKey = keyPlain.value,
        modelCatalogFetcher = modelCatalogFetcher,
        profiles = profiles,
        onSelectProfile = { id ->
            scope.launch { repository.setActiveProfile(id) }
        },
        onNewProfile = { profileDialog.value = ProfileDialog.New },
        onRenameProfile = {
            profiles.active?.let { active ->
                profileDialog.value = ProfileDialog.Rename(active.id, active.name)
            }
        },
        onDeleteProfile = {
            profiles.active?.let { active ->
                scope.launch { repository.deleteProfile(active.id) }
            }
        },
        onSave = { baseUrl, model, modelIndex, apiKey, provider, temperature,
            contextLimit, compactThreshold, thinkingEffort,
            customHeaders, requestLimitPerMinute, maxConcurrentRequests ->
            scope.launch {
                repository.setBaseUrl(baseUrl)
                // 上下文三项一起写：它们决定进度条与压缩行为，
                // 分开写会让中途失败留下「上限改了但阀值没改」的半截配置。
                repository.setContextLimit(contextLimit)
                repository.setCompactThreshold(compactThreshold)
                repository.setThinkingEffort(thinkingEffort)
                // ## 模型池与索引必须**一起**写（2026-10-24）
                //
                // 分成两次 `put` 意味着中间有一瞬「池子换了、索引还是旧的」。
                // 那一瞬若有请求发出，`activeModel` 会按旧索引去新池里取，
                // 取到的是错的模型（或者越界回落 0）。
                //
                // 严格来说 DataStore 的两次 `put` 不是原子的；但两者都是
                // 同一个 DataStore 实例的串行编辑，窗口极小，且
                // `activeModel` 的越界兜底保证不会崩。要做到真原子得把
                // 两个值合成一个 JSON 字段 —— 那会让老版本兼容性变差
                // （见 `KEY_MODEL` 的 KDoc），不值得。
                repository.setModel(model)
                repository.setModelIndex(modelIndex)
                // 服务商与温度这次一起落盘：
                // 之前选了服务商只改了草稿、没保存，退出再进来又变回「自定义」，
                // 用户会以为选择没生效。
                repository.setModelProvider(provider)
                repository.setTemperature(temperature)
                // 留空表示不修改密钥，避免用户只是想改模型时把 key 清掉。
                if (apiKey.isNotBlank()) {
                    repository.setApiKey(apiKey)
                    keyPlain.value = apiKey
                    keyMasked.value = maskApiKey(apiKey)
                }
                // 进阶配置三项一起写：分开写会出现「头更新了但限流没更新」
                // 的半截状态，而这三项在请求路径上是一起被读的。
                repository.setCustomHeaders(customHeaders)
                repository.setRequestLimitPerMinute(requestLimitPerMinute)
                repository.setMaxConcurrentRequests(maxConcurrentRequests)
            }
        },
        onClearApiKey = {
            scope.launch {
                // 传空串即清除（`setApiKey` 对空串直接写空值）。
                repository.setApiKey("")
                keyPlain.value = ""
                keyMasked.value = ""
            }
        },
        modifier = modifier,
    )

    // ── 新建 / 重命名弹窗 ────────────────────────────────────────
    //
    // 常驻挂载（不是 `if (visible) {}`）：退场动画要 160ms 才播完，
    // 条件挂载会在可见性变 false 的瞬间把整棵树拆掉，动画来不及播。
    // 与 `ModelPickerDialog` 同一条纪律。
    val dialog = profileDialog.value
    ProfileNameDialog(
        visible = dialog != null,
        title = if (dialog is ProfileDialog.Rename) "重命名配置" else "新建配置",
        initial = (dialog as? ProfileDialog.Rename)?.initial.orEmpty(),
        onDismiss = { profileDialog.value = null },
        onConfirm = { name ->
            when (val d = dialog) {
                is ProfileDialog.Rename -> scope.launch { repository.renameProfile(d.id, name) }
                else -> scope.launch {
                    // 新建时把**当前配置**的参数复制过去：
                    // 用户点「新建」通常是想「在同一家的基础上改个模型」，
                    // 从空白开始意味着 baseUrl 与 key 都要重填一遍。
                    // 而 key 是独立存的，这里只复制参数（不含密钥）。
                    val created = repository.createProfile(
                        name = name,
                        settings = modelSettings,
                    )
                    repository.setActiveProfile(created)
                }
            }
            profileDialog.value = null
        },
    )
}

/**
 * 「新建 / 重命名配置」弹窗：一个标题 + 一个输入框 + 确定/取消。
 *
 * ## 为什么两个动作共用一个组件
 *
 * 界面完全一样，只有提交逻辑不同（由调用方在 [onConfirm] 里分支）。
 * 拆成两个组件会得到两份几乎逐字相同的布局代码 —— 改一处忘另一处。
 *
 * ## 为什么用 `MiuixAnimatedDialog`
 *
 * 项目里所有弹窗都走它（进场 240ms / 退场 160ms）。
 * 直接 `Dialog {}` 是挂上就出现、关掉就整块消失，没有过渡。
 */
@Composable
private fun ProfileNameDialog(
    visible: Boolean,
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 草稿：取消就等于没输入过。
    // `remember(visible)` 在重新打开时重置成 initial —— 重命名场景下
    // 输入框里应该预填当前名字，而不是上次编辑到一半的残值。
    var draft by remember(visible) { mutableStateOf(initial) }

    MiuixAnimatedDialog(
        visible = visible,
        onDismissRequest = onDismiss,
    ) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body1,
                )
                androidx.compose.foundation.layout.Spacer(
                    Modifier.padding(top = 8.dp),
                )
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "配置名称",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                    )
                    TextButton(
                        text = "确定",
                        // 空名字禁用：存一个空名字只会让下拉里出现一个空白项。
                        enabled = draft.isNotBlank(),
                        onClick = { onConfirm(draft.trim()) },
                    )
                }
            }
        }
    }
}
