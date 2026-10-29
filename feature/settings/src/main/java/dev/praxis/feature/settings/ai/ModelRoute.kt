package dev.praxis.feature.settings.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.ModelAdvancedSettings
import dev.praxis.core.model.settings.ModelSettings
import kotlinx.coroutines.launch

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

    ModelScreen(
        settings = modelSettings,
        advanced = advanced,
        apiKeyMasked = keyMasked.value,
        savedApiKey = keyPlain.value,
        modelCatalogFetcher = modelCatalogFetcher,
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
}
