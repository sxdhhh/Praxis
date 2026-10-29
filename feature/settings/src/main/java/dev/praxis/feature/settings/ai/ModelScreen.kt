package dev.praxis.feature.settings.ai

import dev.praxis.feature.settings.common.SettingsList
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.praxis.core.model.settings.ThinkingEffort
import dev.praxis.core.model.settings.ApiProvider
import dev.praxis.core.model.settings.ModelSettings
import dev.praxis.ui.widgets.MiuixBadge
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import dev.praxis.core.model.settings.CustomHeader
import dev.praxis.core.model.settings.ModelAdvancedSettings
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable

/**
 * API 配置页（对齐 Operit `ModelApiSettingsSection` 的信息架构，控件全部 miuix）。
 *
 * ## 从 Operit 照搬的核心体验：选服务商 → 自动回填
 *
 * Operit 的配置页最值得抄的不是「字段多」，而是这一条流程：
 *
 * ```kotlin
 * fun getDefaultModelName(providerTypeId: String) = ...
 * fun getEndpointOptions(providerTypeId: String) = ...
 * ```
 *
 * 选了服务商就把**默认端点**与**默认模型**填进去。用户从「翻文档抄两个字符串」
 * 变成「选一项 + 贴 Key」。抄错一个字符的失败（404 / model not found）
 * 基本被消灭了 —— 这是配置页最关键的价值。
 *
 * ## 字段顺序对齐 Operit
 *
 * ```
 * 服务商   （预设下拉，选完自动回填下面两项）
 * 接口地址  （可改；改过就标「已自定义」）
 * API Key  （本地推理服务不需要）
 * 模型     （**模型池**：可手填逗号分隔多个，也可「拉取」多选）
 * 当前使用  （池里多于一个时才出现：选实际发请求用哪个）
 * 参数     （温度滑块）
 * 保存
 * ```
 *
 * ## 哪些 Operit 字段**没有**抄，以及为什么
 *
 * Operit 的 `ModelConfigData` 有 30+ 字段，但其中相当一部分在 Praxis 是无处落地的：
 *
 * | Operit 字段 | 为什么不做 |
 * |---|---|
 * | 多 API Key 池 + 轮询模式 | 需要 Key 池 UI + 轮换器 + 每个 Key 的失效统计；Praxis 单 Key 已够用 |
 * | `topP` / `topK` / `presencePenalty` / `frequencyPenalty` / `repetitionPenalty` | `OpenAiCompatProvider` 的请求体只写 `model/stream/temperature/max_tokens`，加进来就是「能填但没用」 |
 * | 上下文长度 / 总结阈值 / 总结规则 | Praxis 的上下文管理是 `AiContextBudget`，按 token 预算自动丢弃，没有「总结」这一层 |
 * | llama.cpp / MNN 本地引擎参数 | Praxis 不做本地推理，只做 OpenAI 兼容 HTTP |
 * | 自定义请求头 / 思考规则 JSON | 需要解析与注入管线；当前 `OpenAiCompatProvider` 不接受自定义头 |
 * | Codex OAuth / 区域告警 / 各家专有搜索开关 | 绑定特定服务商与协议，Praxis 只实现 OpenAI Chat 一种 |
 *
 * 这一版的判断标准是：**只放"填了会真的影响请求"的字段**。
 * 放一个不生效的滑块比如实告诉用户"这项还不支持"更糟 —— 用户会以为调了有用。
 *
 * ## 温度滑块为什么这一版才敢给
 *
 * 上一版这里只有一行只读文字。原因不是控件缺，而是
 * **`SettingsBackedProvider` 当初没把 `settings.temperature` 写进 `ChatRequest`** ——
 * 加个滑块调了也白调。本次同时修掉了那一处（`request.copy(model, temperature)`），
 * 滑块才成为真控件。
 *
 * ## 与 Operit 的另一处差异：保存按钮
 *
 * Operit 是**边改边自动保存**（`ModelConfigAutoSaveSupport`）。Praxis 保持**显式保存**：
 * `baseUrl` 是半截字符串（`https://api.x.`），自动落盘会把半截地址存进库并触发请求。
 * 服务商选择同样**只改草稿**，随保存一起落盘 —— 避免「选了没保存，下次进来又变回去」
 * 这类状态不一致。
 *
 * ## 模型列表拉取与多选（1:1 对齐 Operit 的「模型名右侧取列表」按钮）
 *
 * 点「拉取」→ `ModelCatalogFetcher` 请求 `/models` → 弹窗**多选** →
 * 确认后把勾选项按**服务端返回顺序**拼成逗号串写进模型池。
 *
 * ### 为什么多选是**真**功能（而不是假控件）
 *
 * 这里曾经是单选，理由是「`ModelSettings.model` 只有一个字符串」。
 * 但那只是当时的**数据结构**限制，不是产品判断 —— 上游 Operit 的
 * `ModelConfigData.modelName` 一直是逗号分隔的池子，配 `modelIndex`
 * 指向当前使用项（`getModelByIndex` / `getValidModelIndex`）。
 *
 * 本轮把同一套补齐了：池子存在 `ModelSettings.model`、
 * 索引用 `ModelSettings.modelIndex`、真正发请求时由
 * `ModelSettings.activeModel` 取单个。于是多选有了实际作用 ——
 * 用户可以把常用的几个模型存进池子，再切「当前使用」而不用重填。
 *
 * ### 顺序为什么必须稳定
 *
 * `modelIndex` 是「池里的第几个」。若拼接顺序随用户点击顺序变化，
 * 下次打开设置时「当前使用」就指到了另一个模型 —— 那是看起来像
 * 随机行为的 bug。所以弹窗按 `options` 原序拼（见
 * `ModelPickerDialog.onConfirm`）。
 *
 * @param modelCatalogFetcher 模型列表拉取的端口（`:app` 注入 OkHttp 实现）。
 *
 * 为 null 时**不显示**「拉取」按钮 —— 与 [onClearApiKey] 同一原则：
 * 能力未装配时不给一个点了没反应的按钮。单测 / @Preview 可以传假实现。
 */
@Composable
fun ModelScreen(
    settings: ModelSettings,
    apiKeyMasked: String,
    onSave: (
        baseUrl: String,
        /**
         * 模型**池**（逗号分隔，可空 = 未配置）。
         *
         * ## 为什么这里传的是池子而不是单个模型（2026-10-24）
         *
         * 对齐 Operit：`ModelConfigData.modelName` 存的就是池子，
         * 当前用哪个由 `modelIndex` 表达。设置页负责编辑池子与索引，
         * 发请求时由 `ModelSettings.activeModel` 取单个。
         */
        model: String,
        /** 当前使用池里第几个（0 起），与 [model] 一起落盘。 */
        modelIndex: Int,
        apiKey: String,
        provider: ApiProvider,
        temperature: Double,
        /** 上下文上限（token）。0 = 未知。 */
        contextLimit: Int,
        /** 压缩阈值（0..1）。 */
        compactThreshold: Float,
        /** 思考强度。 */
        thinkingEffort: ThinkingEffort,
        /** 自定义请求头（完整列表，不是增量）。 */
        customHeaders: List<CustomHeader>,
        /** 每分钟最大请求数。0 = 不限。 */
        requestLimitPerMinute: Int,
        /** 最大并发请求数。0 = 不限。 */
        maxConcurrentRequests: Int,
    ) -> Unit,
    /**
     * 进阶配置（自定义请求头 + 请求队列）。
     *
     * ## 为什么是顶层参数而不是 onSave 的入参
     *
     * 它只有一个来源（[ModelRoute] 从 `repository.modelAdvancedSettings` 读），
     * 页内也不需要把它回传给调用方 —— 回传走 [onSave] 的三个独立参数。
     * 放在顶层还能给默认值，让 @Preview / 单测不必构造完整对象。
     */
    advanced: ModelAdvancedSettings = ModelAdvancedSettings(),
    modifier: Modifier = Modifier,
    /**
     * 清除已保存的密钥。
     *
     * 为什么必须有这个显式动作：`onSave` 里密钥留空表示「不修改」
     * （否则用户只想改模型时会把 Key 清掉）。于是「想删 Key」没有表达方式 ——
     * 填一个空格也不行（`isNotBlank()` 会把它拦掉）。
     * 靠用户猜「填空格」既不可发现，也是错的。
     */
    onClearApiKey: () -> Unit = {},
    /** 拉取模型列表的端口；null 表示该能力未装配。 */
    modelCatalogFetcher: dev.praxis.core.ai.ModelCatalogFetcher? = null,
    /**
     * 已保存的**明文**密钥，仅供「拉取模型」这一次请求使用。
     *
     * ## 为什么需要它（而不是复用 [apiKeyMasked]）
     *
     * 拉取列表要真的发一次带认证的请求，打码值（`sk-abc****xyz`）发出去就是 401。
     * 而草稿里的 [apiKey] 在用户没改过时是空串（界面不回填明文，见下）。
     *
     * 于是「已保存但这次没重新输入」这个最常见的场景，必须有一个真实值来源。
     *
     * ## 为什么它不显示在任何地方
     *
     * 只作为 [modelCatalogFetcher] 的入参，不参与 UI。界面上展示的永远是
     * [apiKeyMasked]。这样「明文不进 UI 层」这条约束仍然成立 —— 只是在
     * **执行拉取**这一处需要它，与 `SettingsBackedProvider` 里读密钥同性质。
     */
    savedApiKey: String = "",
) {
    val scope = rememberCoroutineScope()

    // ── 草稿态 ────────────────────────────────────────────────────
    // 都用 `remember(settings.xxx)`：外部保存成功后 settings 变，
    // 草稿会重置为已保存值（否则会出现"保存了但输入框还是旧内容"）。
    var provider by remember(settings.provider) { mutableStateOf(settings.provider) }
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    // 当前使用池里第几个。与 `model` 成对出现：只改池子不改索引，
    // 或者只改索引不改池子，都会出现「池里有 X 但索指引到 Y」的错位。
    var modelIndex by remember(settings.modelIndex) { mutableIntStateOf(settings.modelIndex) }
    // 草稿模型池（把输入框里的逗号串切开）。
    //
    // ## 为什么叫 `draftPool` 而不是 `savedPool`
    //
    // 它来自**草稿** `model`，不是已保存值。名字必须反映这一点：
    // 输入框里每敲一个字符它都会变（决定「当前使用」列表要不要出现、
    // 弹窗里哪些已勾选）。叫 `savedPool` 会让后来者以为它跟着
    // `settings.model` 走，从而在排查「为什么勾选状态不对」时找错方向。
    //
    // ## 为什么不用 `remember(model)` 包一层
    //
    // `parseModelList` 只是一次 split + trim + filter，输入框每次变更
    // 都要重算 —— 那是必须的，缓存反而会读到旧值。包 remember 在这个
    // 场景下没有收益（没有任何「不随 model 变化的重复计算」被省下）。
    val draftPool = dev.praxis.core.model.settings.parseModelList(model)
    // ── 上下文相关草稿 ────────────────────────────────────────
    //
    // 上下文上限用「千 token」为单位编辑，因为用户脑子里记的是「128k」，
    // 不是「131072」。内部存的是 token 数，界面乘 1000 换算。
    var contextLimitK by remember(settings.contextLimit) {
        mutableFloatStateOf((settings.contextLimit / 1000).toFloat().coerceIn(0f, 2000f))
    }
    var compactThreshold by remember(settings.compactThreshold) {
        mutableFloatStateOf(settings.compactThreshold.coerceIn(0.1f, 0.95f))
    }
    var thinkingEffort by remember(settings.thinkingEffort) {
        mutableStateOf(settings.thinkingEffort)
    }

    var temperature by remember(settings.temperature) {
        // coerceIn：历史库里可能存过 >2 的值（旧版没有范围限制）。
        // 直接交给 Slider 会画到轨道外面，且 `steps` 的刻度计算也会错位。
        mutableFloatStateOf(settings.temperature.toFloat().coerceIn(0f, 2f))
    }
    // 密钥不回填（只显示打码结果）：回填明文等于把它摆到界面上。
    var apiKey by remember { mutableStateOf("") }

    // 地址被改过（与所选服务商的默认值不同）→ 标「已自定义」。
    // 这不是装饰：中转站用户改了地址后，需要确认"我确实覆盖了预设"。
    val endpointCustomized = provider.defaultEndpoint.isNotBlank() &&
        baseUrl != provider.defaultEndpoint

    val dirty = baseUrl != settings.baseUrl ||
        model != settings.model ||
        // 索引也算改动：用户可能只是把「当前使用」从 A 换成 B（池子没变），
        // 那也是需要落盘的配置变化，按钮不能显示「已保存」。
        modelIndex != settings.modelIndex ||
        provider != settings.provider ||
        // 浮点比较必须带容差：滑块给出的值可能是 0.70000005，
        // 直接 `!=` 会让「已保存」永远不显示，用户以为每次都有未保存改动。
        kotlin.math.abs(temperature - settings.temperature.toFloat()) > 0.001f ||
        contextLimitK.toInt() * 1000 != settings.contextLimit ||
        kotlin.math.abs(compactThreshold - settings.compactThreshold) > 0.001f ||
        thinkingEffort != settings.thinkingEffort ||
        // 进阶配置也要参与「有未保存改动」的判定，否则用户改完请求头
        // 会看到按钮仍是「已保存」，以为已经落盘。
        headerRows.map { it.first.trim() to it.second }
            .filter { it.first.isNotEmpty() } != advanced.customHeaders ||
        (rpmDraft.toIntOrNull() ?: 0) != advanced.requestLimitPerMinute ||
        (concurrencyDraft.toIntOrNull() ?: 0) != advanced.maxConcurrentRequests ||
        apiKey.isNotEmpty()

    // 本地推理（Ollama / LM Studio / llama.cpp）不需要 Key。
    // 逼用户填一个假 Key 会让"保存"按钮逻辑变得莫名其妙。
    val keyRequired = provider.requiresApiKey

    // ── 模型列表拉取（1:1 对齐 Operit 的「取列表」按钮）───────────────
    //
    // 三个状态都要显式持有：
    //  - `modelsLoading`：按钮转圈 + 防连点；
    //  - `modelsError`：失败原因**留在弹窗里**，不弹 Snackbar（弹窗会盖住它）；
    //  - `models`：拉到的列表，清空后才重新拉。
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<dev.praxis.core.ai.AiModelOption>>(emptyList()) }
    var showModelPicker by remember { mutableStateOf(false) }

    // 认证方式：本地推理不带认证头，OpenRouter 要两个额外标识头。
    // 判定放这里而不是 fetcher 里 —— fetcher 在 :core:ai，不该认识 ApiProvider。
    val providerKind = when {
        !provider.requiresApiKey -> dev.praxis.core.ai.ModelCatalogFetcher.ProviderKind.NoAuth
        provider == ApiProvider.OpenRouter -> dev.praxis.core.ai.ModelCatalogFetcher.ProviderKind.OpenRouter
        else -> dev.praxis.core.ai.ModelCatalogFetcher.ProviderKind.Bearer
    }

    /** 拉一次列表。**先填好 baseUrl 才能拉** —— 否则算出来的 URL 是空串。 */
    fun fetchModels() {
        val fetcher = modelCatalogFetcher ?: return
        if (modelsLoading) return
        modelsLoading = true
        modelsError = null
        scope.launch {
            // 用**草稿态**的 baseUrl 与 apiKey：用户刚填完还没保存就点「拉取」，
            // 这时候拿已保存的值去请求会拉到另一台服务器上。
            // 密钥留空时退回已保存的（打码值不可用，所以要额外读一次真实值）。
            val result = fetcher.fetch(
                baseUrl = baseUrl,
                apiKey = apiKey.ifBlank { savedApiKey },
                providerKind = providerKind,
            )
            modelsLoading = false
            result.fold(
                onSuccess = { list ->
                    models = list
                    showModelPicker = true
                },
                onFailure = { error ->
                    val reason = (error as? dev.praxis.core.ai.ModelCatalog.ParseFailure)
                        ?.reason?.description
                        ?: error.message
                        ?: "未知错误"
                    modelsError = reason
                    // 失败也打开弹窗：错误要显示在**弹窗内**（列表位置），
                    // 而弹窗里带「重试」按钮。不打开的话用户只看到按钮闪了一下。
                    showModelPicker = true
                },
            )
        }
    }

    SettingsList(
        modifier = modifier.fillMaxSize(),
    ) {
        // 首个卡片自己带 top padding（下面 .padding(top = 12.dp)），
        // 这里不再补一个 Spacer —— 两道加起来是 24dp，与其余页面的 12dp 不一致。
        // ── 服务商 ──────────────────────────────────────────────────
        //
        // 旧实现这里**不加**分组标题，理由是「卡片里只有一行，行标题已写着
        // 「服务商」」。但那之后卡片里多出了说明文字与本地服务商提示，
        // 且整页七个分区里只有它没有标题 —— 扫视时会显得缺了一级。
        // 现在统一补上 SettingsSectionHeader（1:1 对齐 Operit：每个分区
        // 都有图标 + 标题 + 副标题）。
        item {
            SettingsSectionHeader(
                icon = Icons.Outlined.Cloud,
                title = "服务商",
                subtitle = "选一个预置服务商可自动填好地址与模型",
            )
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                ProviderSpinner(
                    current = provider,
                    onSelect = { picked ->
                        provider = picked
                        // ★ 对齐 Operit 的核心：选完立刻回填默认端点与默认模型。
                        //
                        // 只回填**非空**的默认值：`Custom` / `LmStudio` / `LlamaCpp`
                        // 的默认值是空串，若也回填就会把用户已经填好的地址清空 ——
                        // 那是破坏性操作。空值时保持用户原输入不动。
                        if (picked.defaultEndpoint.isNotBlank()) baseUrl = picked.defaultEndpoint
                        if (picked.defaultModel.isNotBlank()) model = picked.defaultModel
                    },
                )
                Text(
                    text = if (provider.hasFullDefaults) {
                        "已填入 ${provider.label} 的默认地址与模型，只需补 API Key。"
                    } else {
                        "该服务商没有默认地址，请手动填写。中转站也选这一项。"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
                if (provider == ApiProvider.Ollama ||
                    provider == ApiProvider.LmStudio ||
                    provider == ApiProvider.LlamaCpp
                ) {
                    Text(
                        // 必须说清：默认地址里的 127.0.0.1 是**手机自己**。
                        // 绝大多数人把 Ollama 装在电脑上，填默认值必然连不上，
                        // 而错误是「连接被拒绝」，看不出是地址写错了哪一半。
                        text = "注意：默认地址里的 127.0.0.1 指手机本机。" +
                            "服务跑在电脑上时，要改成电脑的局域网 IP（如 http://192.168.1.10:11434/v1）。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }

        // ── 接口地址 ────────────────────────────────────────────────
        item {
            SettingsSectionHeader(
                icon = Icons.Outlined.Link,
                title = "接口地址",
                subtitle = "服务商的 API 根地址，三种写法都接受",
            )
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    TextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = "Base URL",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    if (endpointCustomized) {
                        Spacer(Modifier.height(8.dp))
                        MiuixBadge(
                            text = "已自定义",
                            textColor = MiuixTheme.colorScheme.onSurface,
                            containerColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // 三种写法都能填（`endpoint()` 已做容错），
                        // 但文案要说清 —— 用户照抄完整端点曾导致过
                        // `/v1/chat/completions/v1/chat/completions` → 404。
                        text = "三种写法都行：\n" +
                            "· https://api.deepseek.com\n" +
                            "· https://api.deepseek.com/v1\n" +
                            "· https://api.deepseek.com/v1/chat/completions",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // 已放开明文 HTTP（见 AndroidManifest 的长注释）。
                        // 放开就必须提示代价，否则用户把 Key 填到公网 http 上不知情。
                        text = "支持 http://（本地 / 局域网服务）。" +
                            "公网地址务必用 https，否则 API Key 会明文过网。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ── API Key ────────────────────────────────────────────────
        item {
            SettingsSectionHeader(
                icon = Icons.Outlined.Key,
                title = "API Key",
                subtitle = "加密保存在本机，不上传任何服务器",
            )
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (keyRequired) {
                        TextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = "API Key",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (apiKeyMasked.isEmpty()) {
                                "尚未设置密钥。"
                            } else {
                                "当前已保存：$apiKeyMasked（留空则不修改）"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (apiKeyMasked.isNotEmpty()) {
                            // 有 key 才给清除入口：没有 key 时给一个「清除」按钮
                            // 是纯粹的困惑源（按了没反应）。
                            ClearApiKeyButton(onClearApiKey)
                        }
                    } else {
                        // 本地服务：不给输入框。
                        // 给一个可以留空的输入框，用户会犹豫「要不要填」，
                        // 而正确答案是"不用填"。直接说明比留空输入框清楚。
                        Text(
                            text = "${provider.label} 默认不校验 API Key，无需填写。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        if (apiKeyMasked.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                // 之前配过云端服务、现在切到本地的场景：Key 还在库里。
                                // 不清空（用户可能还会切回去），但要让用户知道它还在。
                                text = "库里仍保存着一份密钥（$apiKeyMasked），切回云端服务时可用。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            // 承载一条**反直觉**的事实：切到本地不会删掉云端 Key。
                            // 用户有权知道 —— 否则会以为"切到本地就等于清掉了"。
                            text = "切到本地服务不会自动删除已保存的密钥，切回云端仍会使用它。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                        if (apiKeyMasked.isNotEmpty()) {
                            ClearApiKeyButton(onClearApiKey)
                        }
                    }
                }
            }
        }

        // ── 模型 ────────────────────────────────────────────────────
        item {
            SettingsSectionHeader(
                icon = Icons.Outlined.DataObject,
                title = "模型",
                subtitle = "从服务商拉取，或手动填写模型 ID",
            )
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    // ── 输入行 + 「拉取」按钮 ────────────────────────
                    //
                    // 布局照抄 Operit 的 `SettingsTextField(trailingContent = IconButton)`：
                    // 输入框占满宽度，按钮贴在它右端。这样「这个按钮是给这个框用的」
                    // 一目了然 —— 放到卡片底部单独一行会让人不确定它拉的是哪个字段。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = model,
                            onValueChange = {
                                // ## 只做换行/回车清理，**不过滤逗号**（2026-10-24）
                                //
                                // 逗号是模型池的分隔符（对齐 Operit 的
                                // `modelNameInput`），这里正是用户手动维护池子的
                                // 地方。过滤逗号会让「手填两个模型」变得不可能。
                                //
                                // 换行/回车必须清掉：它们会混进模型名里，
                                // 发请求时是非法字符（上游同样处理）。
                                model = it.replace("\n", "").replace("\r", "")
                            },
                            // 标签从「模型 ID」改成「模型」：现在这里存的是**池子**。
                            // 仍叫「ID」会让人以为只能填一个。
                            label = "模型（可用逗号填多个）",
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        if (modelCatalogFetcher != null) {
                            // 与上游 `canRequestModelList` 同一套判据：
                            // 地址非空 +（服务商需要 key 时）确实有 key。
                            //
                            // 少了 key 也允许点，会得到一个 401 —— 那条错误对用户
                            // 是"密钥不对"的误导，实际是"还没填"。所以禁用，并
                            // 在下面用一句话说清缺什么。
                            val hasKey = apiKey.isNotBlank() || savedApiKey.isNotBlank()
                            val canFetch = baseUrl.isNotBlank() &&
                                !modelsLoading &&
                                (!provider.requiresApiKey || hasKey)
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                text = if (modelsLoading) "拉取中…" else "拉取",
                                enabled = canFetch,
                                onClick = { fetchModels() },
                            )
                        }
                    }
                    // 缺什么就说什么：这两句是「拉取」按钮灰掉时的唯一解释。
                    if (modelCatalogFetcher != null) {
                        val hasKey = apiKey.isNotBlank() || savedApiKey.isNotBlank()
                        val missing = when {
                            baseUrl.isBlank() -> "请先填写上面的接口地址。"
                            provider.requiresApiKey && !hasKey -> "请先填写 API Key 再拉取模型列表。"
                            else -> null
                        }
                        if (missing != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = missing,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    // ── 当前使用哪个（池子里多于一个时才出现）──────
                    //
                    // ## 为什么需要这一块（对齐 Operit 的 `modelIndex`）
                    //
                    // 多选之后池里可能有 3 个模型，但每次请求只能发一个。
                    // 上游用 `modelIndex` 表达「用第几个」，并在
                    // `MultiServiceManager.createServiceFromConfig` 里
                    // `getModelByIndex(config.modelName, actualIndex)` 取值。
                    //
                    // 这里给用户一个可见可改的入口。池里只有一个模型时
                    // **不显示**：那时没有可选的余地，显示出来只是噪音。
                    if (draftPool.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "当前使用",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(4.dp))
                        // 用竖排单选项而不是下拉：候选通常只有 2~4 个，
                        // 铺开比多一次点击更省事；也让「索引」这件事可见
                        // （用户能看懂「第一个」就是索引 0）。
                        draftPool.forEachIndexed { index, id ->
                            val isActive = index == modelIndex.coerceIn(0, draftPool.lastIndex)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = isActive,
                                        // `Role.RadioButton`：读屏会念「单选按钮，
                                        // 已选中」，比「按钮」信息量大。
                                        role = Role.RadioButton,
                                        onClick = { modelIndex = index },
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isActive,
                                    onClick = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = id,
                                    style = MiuixTheme.textStyles.body2,
                                    color = if (isActive) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (provider.defaultModel.isNotBlank()) {
                            "例如 ${provider.defaultModel}。必须是该服务商支持的模型名。"
                        } else {
                            "必须填服务商支持的模型名，写错会在对话时报错。" +
                                "没有默认值的服务商可以点「拉取」从 /models 里选（可多选）。"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    // 拉取失败的**摘要**也留在页面上：用户关掉弹窗后还能看到
                    // 上次为什么失败，不用再点一次去复现。
                    val pageError = modelsError
                    if (pageError != null && !showModelPicker) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = pageError,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ── 参数 ────────────────────────────────────────────────────
        //
        // 只做 temperature：它是 `OpenAiCompatProvider` 真正写进请求体的四个参数
        // 之一（model / stream / temperature / max_tokens）。
        // topP / topK / penalty 在请求体里没有对应字段，放进来就是假的。
        item {
            SettingsSectionHeader(
                icon = Icons.Outlined.Tune,
                title = "参数",
                subtitle = "采样温度等影响输出风格的项",
            )
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SliderPreference(
                    value = temperature,
                    onValueChange = { temperature = it },
                    title = "温度",
                    summary = "越低越确定、越高越发散",
                    valueText = String.format(Locale.ROOT, "%.1f", temperature),
                    // OpenAI 兼容协议的温度取值范围是 0..2。
                    // steps = 19 表示两端之间还有 19 个刻度 → 共 21 档，步长 0.1。
                    valueRange = 0f..2f,
                    steps = 19,
                )
                Text(
                    text = "0.7 适合大多数对话；需要稳定输出（代码、格式化）时调到 0.2 以下。" +
                        "改动在点「保存修改」后生效。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }

        // ── 上下文 ─────────────────────────────────────────────────
        //
        // ## 为什么上限要用户自己填
        //
        // 同一个模型 ID 在不同服务商那里上限不同（128k / 32k / 按套餐）。
        // 内置表必然过时，且会给出一条「系统认为你超了但其实能发」的假告警。
        // 填 0 表示未知 —— 此时只显示已用量，不画比例，也不做压缩判断。
        item {
            SettingsSectionHeader(
                icon = Icons.Outlined.Storage,
                title = "上下文",
                subtitle = "上下文长度上限，以及超限时的压缩阈值",
            )
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SliderPreference(
                    value = contextLimitK,
                    onValueChange = { contextLimitK = it },
                    title = "最大上下文",
                    summary = "填 0 表示未知（不画比例、不自动压缩）",
                    valueText = if (contextLimitK < 1f) {
                        "未知"
                    } else {
                        contextLimitK.toInt().toString() + "k tokens"
                    },
                    // 0..2000k：覆盖从 8k 小模型到 2M 长上下文模型。
                    // steps 取 39 → 40 档，每档 50k；小值区间靠拖动精度够用，
                    // 而真正需要细调的是 128k/200k 这一带，50k 一格偏粗 ——
                    // 所以下面额外给了一个「常用值」快捷行。
                    valueRange = 0f..2000f,
                    steps = 39,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 常用值：比让用户在 2000 档滑块上精确拖到 128 现实得多。
                    listOf(0 to "未知", 32 to "32k", 128 to "128k", 200 to "200k")
                        .forEach { (k, label) ->
                            TextButton(
                                text = label,
                                onClick = { contextLimitK = k.toFloat() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                }
                SliderPreference(
                    value = compactThreshold,
                    onValueChange = { compactThreshold = it },
                    title = "压缩阈值",
                    summary = "占用达到该比例时，先把历史压成摘要再发送",
                    valueText = (compactThreshold * 100).toInt().toString() + "%",
                    // 0.1..0.95：低于 0.1 会每轮都压缩（每次多一次模型调用），
                    // 高于 0.95 会「要压缩的那次请求自己就超限」。
                    valueRange = 0.1f..0.95f,
                    steps = 16,
                )
                Text(
                    text = "压缩用的是「功能模型」里「上下文摘要」配置的模型；" +
                        "未配置时跟随当前模型。工具执行期间不会触发压缩。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }

        // ── 思考 ───────────────────────────────────────────────────
        //
        // ## 为什么是三档滑块而不是开关
        //
        // 各家参数名与取值域都不同（`reasoning_effort` / `thinking_budget` /
        // `enable_thinking`）。抽象成 None/Low/Medium/High 四档由 provider 翻译，
        // 用户换服务商时不用重配，也不会因为填错值直接 400。
        item {
            SettingsSectionHeader(
                icon = Icons.Outlined.Psychology,
                title = "思考",
                subtitle = "推理强度。越高越慢越贵，复杂任务才需要",
            )
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SliderPreference(
                    value = thinkingEffort.ordinal.toFloat(),
                    onValueChange = { v ->
                        thinkingEffort = ThinkingEffort.entries[
                            v.toInt().coerceIn(0, ThinkingEffort.entries.lastIndex),
                        ]
                    },
                    title = "思考强度",
                    summary = "越高越愿意先推理再回答，耗时与费用也更高",
                    valueText = when (thinkingEffort) {
                        ThinkingEffort.None -> "关闭"
                        ThinkingEffort.Low -> "低"
                        ThinkingEffort.Medium -> "中"
                        ThinkingEffort.High -> "高"
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                )
                Text(
                    text = "不支持思考参数的模型会自动忽略这一项，不会导致请求失败。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }

        // ── 自定义请求头（1:1 对齐 Operit CustomHeadersSettingsSection）─────
        //
        // ## 为什么默认折叠
        //
        // 绝大多数用户不需要自定义头（0 项）。默认展开会在一页设置里塞一份
        // 空表单，把「保存」按钮挤到屏幕外。折叠后只在标题右侧显示已配数量。
        item {
            SettingsSectionHeader(
                icon = Icons.Outlined.Article,
                title = "自定义请求头",
                subtitle = "中转站或企业网关要求的额外 HTTP 头",
            )
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    // 标题行本身就是展开开关（与 Operit 一致：整行可点）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { headersExpanded = !headersExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (headersExpanded) "收起" else "展开",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        val configured = headerRows.count { it.first.isNotBlank() }
                        Text(
                            text = if (configured > 0) "已配置 $configured 项" else "未配置",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (headersExpanded) {
                        Spacer(Modifier.height(12.dp))
                        headerRows.forEachIndexed { index, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextField(
                                    value = row.first,
                                    onValueChange = { v ->
                                        // ## 为什么这里允许任意字符进草稿
                                        //
                                        // 过滤放在发送前（`OpenAiCompatProvider`
                                        // 的 `isValidHeaderName`），不放输入时：
                                        // 用户输到一半必然出现非法中间态，
                                        // 输入时拦会让「输不进去」变成常态。
                                        headerRows = headerRows.toMutableList().also {
                                            it[index] = v.replace("\n", "").replace("\r", "") to row.second
                                        }
                                    },
                                    label = "头名",
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                                Spacer(Modifier.width(8.dp))
                                TextField(
                                    value = row.second,
                                    onValueChange = { v ->
                                        headerRows = headerRows.toMutableList().also {
                                            it[index] = row.first to v.replace("\n", "").replace("\r", "")
                                        }
                                    },
                                    label = "值",
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                                Spacer(Modifier.width(4.dp))
                                TextButton(
                                    text = "删",
                                    onClick = {
                                        val next = headerRows.toMutableList()
                                        next.removeAt(index)
                                        // 删到空时必须留一行空框，否则整个列表
                                        // 消失、用户找不到「添加」入口。
                                        headerRows = next.ifEmpty { listOf("" to "") }
                                    },
                                )
                            }
                        }
                        TextButton(
                            text = "添加一行",
                            onClick = { headerRows = headerRows + ("" to "") },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            // 说清「内置头不会被覆盖」：用户最容易问的就是
                            // 「我填了 Authorization 为什么还带了原来的」。
                            text = "键为空的行会被忽略。这里加的头会与内置的 " +
                                "Authorization / Content-Type 同时存在，不互相覆盖。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        // ── 请求队列（1:1 对齐 Operit AdvancedSettingsSection 的队列部分）──
        //
        // ## 为什么不搬 API Key 池
        //
        // Operit 的 Advanced 里有「多 Key 轮询」与「Key 可用性测试」。
        // Praxis 的密钥是**单个加密值**（`SettingsRepository.setApiKey`），
        // 搬过来得先改存储为列表 —— 那是另一轮量级。宁可这轮只做真正
        // 能生效的两项（限流 + 并发），也不摆一组存不进去的控件。
        item {
            SettingsSectionHeader(
                icon = Icons.Outlined.Speed,
                title = "请求队列",
                subtitle = "撞到 429 或额度紧张时才需要填",
            )
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    TextField(
                        value = rpmDraft,
                        onValueChange = { v ->
                            // 只收数字：这里填的是次数，混进字母会让
                            // 换算成 Int 时静默变 0（等于「不限流」），
                            // 而用户以为自己已经配好了。
                            rpmDraft = v.filter { it.isDigit() }.take(6)
                        },
                        label = "每分钟最大请求数（留空 = 不限）",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = concurrencyDraft,
                        onValueChange = { v ->
                            concurrencyDraft = v.filter { it.isDigit() }.take(3)
                        },
                        label = "最大并发请求数（留空 = 不限）",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "两个限制分工不同：前者管每分钟总量，后者管同一瞬间在飞的个数。" +
                            "Praxis 同时最多只有对话、摘要、标题、记忆四类请求，一般不需要填。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ── 保存 ────────────────────────────────────────────────────
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    TextButton(
                        text = if (dirty) "保存修改" else "已保存",
                        enabled = dirty,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                onSave(
                                    baseUrl,
                                    model,
                                    // 池子与索引必须一起传：只传一个会让
                                    // 「池里 3 个模型但索指引向第 5 个」这种
                                    // 错位落进库里。
                                    modelIndex,
                                    apiKey,
                                    provider,
                                    temperature.toDouble(),
                                    // 换算回 token 数。0 表示「不知道」，此时进度条不画比例。
                                    contextLimitK.toInt() * 1000,
                                    compactThreshold,
                                    thinkingEffort,
                                    // 自定义头传**整份列表**：只传增量的话，
                                    // 删掉的行永远删不掉（库里是覆盖写）。
                                    customHeaders = headerRows
                                        .map { it.first.trim() to it.second }
                                        .filter { it.first.isNotEmpty() },
                                    requestLimitPerMinute = rpmDraft.toIntOrNull() ?: 0,
                                    maxConcurrentRequests = concurrencyDraft.toIntOrNull() ?: 0,
                                )
                                // 保存后清空密钥草稿，避免它一直留在内存里。
                                apiKey = ""
                            }
                        },
                    )
                    if (!settings.isConfigured) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "尚未完成配置。地址与模型都填好之后才能开始对话。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    // ── 模型列表弹窗（1:1 对齐 Operit 的 showModelsDialog）─────────────
    //
    // ## 为什么渲染在 SettingsList **之外**
    //
    // `SettingsList` 是 LazyColumn。把 Dialog 放进 item 里，滚出屏幕时该 item
    // 被回收 → Dialog 跟着消失（或反过来，滚动时被反复重建导致闪）。
    // Dialog 走独立 window，位置无所谓，放在外层最省事，也与 `ChatScreen`
    // 里「编辑器 / 信息弹窗放在根 Column 末尾」是同一套判断。
    //
    // ## 选中后的行为
    //
    // 只写**草稿** `model`，不落盘：与页面的「显式保存」口径一致。
    // 落盘由下面「保存修改」按钮统一做。这样不会出现「在弹窗里点了一下，
    // 保存按钮还是灰的，但库里已经变了」这种状态错位。
    // ## ⚠️ 常驻挂载，不能写成 `if (showModelPicker) { ... }`（2026-10-24）
    //
    // 旧写法把 `ModelPickerDialog` 包在 `if (showModelPicker) { }` 里。
    // 那样 `showModelPicker` 一变 false，**整个组件就从组合里被移除**，
    // 弹窗的退场动画根本没有机会播放 —— 它会在同一帧里消失。
    //
    // 现在弹窗内部没有任何可见性判断（`MiuixAnimatedDialog(visible = true)`），
    // 可见性由 `MiuixAnimatedDialog` 自己带进带出：它在退场动画播完后
    // 才拆 window。所以这一层必须**无条件调用**。
    //
    // 代价：弹窗关闭后组件仍然在组合树里（只是不挂 window），
    // 那点开销远小于「动画播不出来」。
    ModelPickerDialog(
        options = models,
        // 可见性交给组件自己带进带出（见上面的说明）。
        visible = showModelPicker,
        // 把已保存的池子传进去（弹窗要据此显示哪些勾上了）。
        selectedIds = draftPool.toSet(),
        loading = modelsLoading,
        error = modelsError,
        onRefresh = { fetchModels() },
        onConfirm = { picked ->
            // ## 只写**草稿**，不落盘（与页面的「显式保存」口径一致）
            //
            // 落盘由下面「保存修改」按钮统一做。这样不会出现
            // 「在弹窗里点了一下，保存按钮还是灰的，但库里已经变了」
            // 这种状态错位。
            //
            // `picked` 已经由弹窗按 `options` 原序排好（见
            // `ModelPickerDialog.onConfirm` 的说明），所以这里直接拼。
            model = dev.praxis.core.model.settings.joinModelList(picked)
            // ## 索引要跟着池子一起收敛
            //
            // 勾选可能让池子变短（例如原来 3 个、现在只勾 1 个），
            // 原索引就可能越界。这里夹一次，避免「保存了一个越界索引、
            // 下次打开显示不出选中项」。
            //
            // 为什么不保留原索引：用户重新勾选多半就是想换一套，
            // 夹到合法范围比死守旧值更符合预期。`activeModel` 在
            // 运行时还会再兜底一次（越界回落 0），两道保险。
            modelIndex = modelIndex.coerceIn(0, (picked.size - 1).coerceAtLeast(0))
            showModelPicker = false
        },
        onDismiss = { showModelPicker = false },
    )
}

/**
 * 服务商选择器（miuix `OverlaySpinnerPreference`）。
 *
 * 用 Overlay 版而不是 Window 版：Window 版内部开 `Dialog`（新 Window），
 * 在 `NavDisplay` 的 entry 里与宿主 Window 抢焦点 —— 这正是外观页此前闪退的原因。
 *
 * `renderInRootScaffold = true`：画到根 Scaffold 的 overlay 层，
 * 否则会被 `SettingsList` 的 LazyColumn clip 裁掉。
 */
@Composable
private fun ProviderSpinner(
    current: ApiProvider,
    onSelect: (ApiProvider) -> Unit,
) {
    val options = ApiProvider.entries.toList()
    val items = remember(options) { options.map { DropdownItem(title = it.label) } }
    // coerceAtLeast(0)：indexOf 找不到时返回 -1，直接传给 miuix 会显示空值。
    val selectedIndex = remember(current, options) {
        options.indexOf(current).coerceAtLeast(0)
    }
    OverlaySpinnerPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = "服务商",
        renderInRootScaffold = true,
        onSelectedIndexChange = { index ->
            options.getOrNull(index)?.takeIf { it != current }?.let(onSelect)
        },
    )
}

/**
 * 「清除已保存的密钥」按钮。
 *
 * ## 为什么需要它
 *
 * `onSave` 里密钥留空表示「**不修改**」—— 否则用户只想改模型时会把 Key 清掉。
 * 于是「我想删掉这个 Key」就没有任何表达方式：填一个空格也不行
 * （`SettingsRepository.setApiKey` 用 `isNotBlank()` 拦掉了空白）。
 * 让用户去猜「填空格」既不可发现，本身也是错的。
 *
 * ## 为什么不做二次确认
 *
 * 清掉密钥的代价是「下次对话报未配置」，重新填一次即可，不是不可逆的数据损失。
 * 这个页面上真正不可逆的操作一个都没有，为它加一层弹窗只会拖慢真实的配置流程。
 */
@Composable
private fun ClearApiKeyButton(onClear: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    TextButton(
        text = "清除已保存的密钥",
        onClick = onClear,
    )
}

/** 密钥打码：只露头尾各 3 个字符。 */
fun maskApiKey(key: String): String = when {
    key.isEmpty() -> ""
    key.length <= 8 -> "****"
    else -> key.take(3) + "****" + key.takeLast(3)
}