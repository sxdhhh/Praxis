package dev.praxis.feature.settings

import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.CloudDownload
import dev.praxis.ui.widgets.MiuixSwitchWidget
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import top.yukonga.miuix.kmp.basic.TextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.feature.settings.common.SettingsList
import dev.praxis.core.imagegen.ChipsetTier
import dev.praxis.core.imagegen.EngineAvailability
import dev.praxis.core.imagegen.ImageGenEngineSource
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 探活的两种瞬时状态（页面自己管，因为「正在探」不属于持久配置）。 */
private sealed interface ProbeUi {
    data object Probing : ProbeUi
    data class Done(val result: EngineAvailability) : ProbeUi
}

/**
 * 设置 → 本地生图（docs/PORTING-IMAGEGEN.md §7.1、§7.4）。
 *
 * ## 这一版能做什么、不能做什么
 *
 * **真的**：引擎探活（连不上会给出具体原因）、本机芯片档位、已安装模型列表（读文件系统）。
 * **还没有**：模型库页（下载/导入）、画布/采样/性能/输出/诊断这些配置项。
 *
 * 没做的部分**写在页面上**而不是藏在心里：这是唯一能让用户分清「还没做」与「做了但坏了」的办法。
 * 也不摆不能点的假开关 —— 那种界面比空白更让人困惑。
 *
 * ## 为什么探活放在页面里而不是 :app
 *
 * 「正在探 / 探到什么」是一次性状态，进页面就该重新探一次（引擎可能刚被用户启动）。
 * 放进 :app 的全局状态反而要处理「什么时候清掉」。探活函数从上面注入，页面只负责调用。
 */
@Composable
fun ImageGenRoute(
    /** 引擎探活（:app 注入）。失败也算结果，不抛异常。 */
    probeEngine: suspend () -> EngineAvailability,
    /** 本机芯片型号原值（`Build.SOC_MODEL`，API < 31 回落 `Build.HARDWARE`）。 */
    socModel: String,
    /** 已安装的模型（选择器要用 id）。 */
    installedModels: List<dev.praxis.core.imagegen.ImageModel>,
    /** 当前选中的生图目标；null = 还没选。 */
    currentTarget: dev.praxis.core.imagegen.ImageGenTarget? = null,
    /** 选完写回 imagegen_target。 */
    onSelectTarget: (dev.praxis.core.imagegen.ImageGenTarget) -> Unit = {},
    /** 模型目录的绝对路径（只读展示，方便用户自己放文件）。 */
    modelsRootPath: String,
    /** 下载设置（文档 §7.2）：下载源 + 仅 Wi-Fi。 */
    downloadSettings: dev.praxis.core.imagegen.ImageDownloadSettings =
        dev.praxis.core.imagegen.ImageDownloadSettings(),
    onDownloadSettingsChange: (dev.praxis.core.imagegen.ImageDownloadSettings) -> Unit = {},
    /** 生图默认参数（画布 / 采样）。模型没说这些参数时按它走。 */
    imageDefaults: dev.praxis.core.imagegen.ImageGenDefaults =
        dev.praxis.core.imagegen.ImageGenDefaults(),
    onImageDefaultsChange: (dev.praxis.core.imagegen.ImageGenDefaults) -> Unit = {},
    /** 输出设置（文档 §7.1）：自动存相册。 */
    imageOutput: dev.praxis.core.imagegen.ImageOutputSettings =
        dev.praxis.core.imagegen.ImageOutputSettings(),
    onImageOutputChange: (dev.praxis.core.imagegen.ImageOutputSettings) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 组合局部变量只能在组合期读一次、闭包捕获；写进 onClick 的 lambda 里会在点击时越界
    // 到非组合上下文（CI 报过：@Composable invocations can only happen from ...）。
    val navigator = dev.praxis.ui.navigation.LocalNavigator.current

    var probeTick by remember { mutableStateOf(0) }
    var probe by remember { mutableStateOf<ProbeUi>(ProbeUi.Probing) }
    var targetPickerVisible by remember { mutableStateOf(false) }
    var sourceDialogVisible by remember { mutableStateOf(false) }
    var defaultsDialogVisible by remember { mutableStateOf(false) }

    // 进页面探一次；点「重新检测」把 tick 加一即重探。
    LaunchedEffect(probeTick) {
        probe = ProbeUi.Probing
        probe = ProbeUi.Done(probeEngine())
    }

    SettingsList(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "__intro__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Image,
                title = "本地生图",
                subtitle = "在手机上出图，不经过云端",
            )
        }
        item(key = "__intro_card__") {
            BareCard {
                Text(
                    text = "Praxis 自己不做推理：它连一个跑在 127.0.0.1:8081 的引擎，" +
                        "把提示词发给它、把进度和成图收回来。所以要先有一个能应答的引擎。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // ── 引擎状态（§7.4 三态）──
        item(key = "__engine_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Memory,
                title = "引擎",
                subtitle = "8081 端口上有没有人在应答",
            )
        }
        item(key = "__engine_card__") {
            BareCard {
                when (val current = probe) {
                    ProbeUi.Probing -> Text(
                        text = "正在检测 127.0.0.1:8081 ……",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )

                    is ProbeUi.Done -> when (val result = current.result) {
                        is EngineAvailability.Available -> Column {
                            Text(
                                text = "已连接",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            Text(
                                text = sourceLine(result) + "。" + healthLine(result),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }

                        is EngineAvailability.Unavailable -> Column {
                            Text(
                                text = "未检测到引擎",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                            )
                            Text(
                                text = reasonLine(result),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Text(
                                text = "三条路（都还没做进 Praxis，先手动准备）：" +
                                    "① 装 Local Dream 并打开它的 host 模式；" +
                                    "② 在 Praxis 的 Linux 容器里跑引擎；" +
                                    "③ 自用构建时把引擎随包内置。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        item(key = "__engine_retry__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "重新检测",
                    description = "引擎刚启动？点这里再探一次",
                    onClick = { probeTick += 1 },
                )
            }
        }

        // ── 模型 ──
        item(key = "__models_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.PhotoLibrary,
                title = "模型",
                subtitle = "已下载的权重",
            )
        }
        item(key = "__target_card__") {
            BareCard {
                Text(
                    text = "当前目标",
                    style = MiuixTheme.textStyles.body2,
                )
                Text(
                    text = targetLine(currentTarget, installedModels),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item(key = "__target_pick__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "更换目标",
                    description = if (installedModels.isEmpty()) {
                        "还没有已下载的模型，先去模型库下一个"
                    } else {
                        "从已下载的本地模型里选"
                    },
                    onClick = { targetPickerVisible = true },
                )
            }
        }
        item(key = "__models_library__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "模型库",
                    description = "内置清单 · 下载 · 导入",
                    onClick = {
                        navigator.push(dev.praxis.ui.navigation.PraxisRoute.SettingsImageGenModels)
                    },
                )
            }
        }
        item(key = "__models_card__") {
            BareCard {
                Text(
                    text = if (installedModels.isEmpty()) {
                        "还没有已安装的模型。"
                    } else {
                        "已安装 " + installedModels.size + " 个：" +
                            installedModels.joinToString("、") { model -> model.name }
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "本机芯片：" + socModel + "（下载档位 " + ChipsetTier.downloadSuffix(socModel) + "）",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "模型目录：" + modelsRootPath,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ── 还没做的部分 ──
        // ── 画布与采样（§7.1）──
        //
        // **偏离文档一处**：文档把它们拆成「画布」「采样」两个分区，这里合成一个。
        // 理由：五个参数（宽/高/步数/CFG/负向提示词）在同一张弹窗里编辑，
        // 拆成两个分区意味着用户要在两个弹窗之间来回改同一组相关参数。
        item(key = "__canvas_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Tune,
                title = "画布与采样",
                subtitle = "AI 没说这些参数时按这里的走",
            )
        }
        item(key = "__canvas_row__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "默认参数",
                    description = imageDefaults.width.toString() + "×" +
                        imageDefaults.height.toString() + " · " + imageDefaults.steps + " 步 · CFG " +
                        formatCfg(imageDefaults.cfg) +
                        if (imageDefaults.negative.isBlank()) "" else " · 负向已设",
                    onClick = { defaultsDialogVisible = true },
                )
            }
        }

        // ── 输出（§7.1）──
        item(key = "__output_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Save,
                title = "输出",
                subtitle = "生成完之后要不要额外做点什么",
            )
        }
        item(key = "__output_autosave__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixSwitchWidget(
                    title = "生成后自动存相册",
                    description = "存到相册的 Pictures/Praxis；Android 9 及以下不支持" +
                        "（那需要存储权限，Praxis 还没接那套流程）",
                    checked = imageOutput.autoSave,
                    onCheckedChange = { enabled ->
                        onImageOutputChange(imageOutput.copy(autoSave = enabled))
                    },
                )
            }
        }
        item(key = "__output_note__") {
            BareCard {
                Text(
                    text = "存相册失败不会影响出图：图已经落盘、也已经进了对话，只是相册里没有。" +
                        "这类结果只会写进 App 日志（设置 → 日志）。「显示每步预览」还没做 ——" +
                        "要引擎推中间帧，不是加个开关的事。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // ── 诊断（§7.1 的诊断分区，可做的那部分）──
        //
        // 只列**这一刻确实是事实**的东西。文档原来还画了「上次启动耗时」与
        // 「引擎日志尾巴」，两者都拿不到：引擎是外部 App 起的（形态 A），
        // 它的日志在它自己那边，Praxis 没有渠道去读。
        item(key = "__diag_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.BugReport,
                title = "诊断",
                subtitle = "出问题时先看这几项",
            )
        }
        item(key = "__diag_card__") {
            BareCard {
                DiagnosticRow("引擎地址", "127.0.0.1:" + 8081)
                DiagnosticRow("模型目录", modelsRootPath)
                DiagnosticRow(
                    "App 日志",
                    dev.praxis.core.data.log.PraxisLogger.logsDir?.absolutePath
                        ?: "不可用",
                )
                DiagnosticRow(
                    "引擎日志",
                    "拿不到 —— 引擎是外部 App 起的，日志在它那边（等形态 B/C）",
                )
            }
        }

        // ── 下载源（§7.2）──
        item(key = "__download_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.CloudDownload,
                title = "下载源",
                subtitle = "从哪下模型、什么时候允许下",
            )
        }
        item(key = "__download_source__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "下载源",
                    description = downloadSettings.baseUrl + "（点这里更换）",
                    onClick = { sourceDialogVisible = true },
                )
            }
        }
        item(key = "__download_wifi__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixSwitchWidget(
                    title = "仅 Wi-Fi 下载",
                    description = "几 GB 的包走流量代价不小；关掉后移动网络也能下",
                    checked = downloadSettings.wifiOnly,
                    onCheckedChange = { enabled ->
                        onDownloadSettingsChange(downloadSettings.copy(wifiOnly = enabled))
                    },
                )
            }
        }
        item(key = "__download_note__") {
            BareCard {
                Text(
                    text = "文档那页还画了「并发下载数」与「代理」，这两项目前读不到：" +
                        "下载器是逐个文件顺序下的（改并发要先解决多 .part 同时写与进度合并），" +
                        "而 Praxis 还没有全局代理设置。所以不放这两个开关。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item(key = "__plan_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Tune,
                title = "还没做完",
                subtitle = "写在这里，免得你以为是自己没找到",
            )
        }
        item(key = "__plan_card__") {
            BareCard {
                PLAN_LINES.forEachIndexed { index, line ->
                    Text(
                        text = line,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp),
                    )
                }
            }
        }
    }

    if (defaultsDialogVisible) {
        GenerationParamsDialog(
            defaults = imageDefaults,
            onDismiss = { defaultsDialogVisible = false },
            onConfirm = { updated ->
                onImageDefaultsChange(updated)
                defaultsDialogVisible = false
            },
        )
    }

    if (sourceDialogVisible) {
        DownloadSourceDialog(
            settings = downloadSettings,
            onDismiss = { sourceDialogVisible = false },
            onConfirm = { updated ->
                onDownloadSettingsChange(updated)
                sourceDialogVisible = false
            },
        )
    }

    ImageGenTargetDialog(
        visible = targetPickerVisible,
        onDismiss = { targetPickerVisible = false },
        localModels = installedModels,
        current = currentTarget,
        onSelect = onSelectTarget,
    )
}

/** 当前目标的一句话说明（本地显示模型名，而不是 id）。 */
private fun targetLine(
    current: dev.praxis.core.imagegen.ImageGenTarget?,
    installed: List<dev.praxis.core.imagegen.ImageModel>,
): String = when (current) {
    null -> "还没选。装好第一个模型后会自动选中它。"
    is dev.praxis.core.imagegen.ImageGenTarget.Local -> {
        val name = installed.firstOrNull { it.id == current.modelId }?.name ?: current.modelId
        "本地：" + name
    }
    is dev.praxis.core.imagegen.ImageGenTarget.Remote ->
        "服务商：" + current.provider + " / " + current.model
}

@Composable
private fun BareCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

private fun sourceLine(result: EngineAvailability.Available): String = when (result.source) {
    null -> "来源尚未识别（只知道 8081 有人应答）"
    ImageGenEngineSource.External -> "来自外部应用"
    ImageGenEngineSource.Container -> "来自 Praxis 的容器"
    ImageGenEngineSource.Bundled -> "随包内置"
}

private fun healthLine(result: EngineAvailability.Available): String {
    val raw = result.health.raw.trim()
    return if (raw.isEmpty()) "引擎返回了空响应" else "响应：" + raw.take(80)
}

private fun reasonLine(result: EngineAvailability.Unavailable): String {
    val reason = when (result.reason) {
        EngineAvailability.Unavailable.Reason.NotRunning -> "8081 端口没有人应答"
        EngineAvailability.Unavailable.Reason.HttpError -> "应答了但状态码不对"
        EngineAvailability.Unavailable.Reason.MalformedHealth -> "8081 上有东西在跑，但不是我们的引擎"
    }
    val detail = result.detail?.takeIf { it.isNotBlank() }
    return if (detail == null) "原因：" + reason else "原因：" + reason + "（" + detail + "）"
}

/** 还没实现的分区。和 `GatewayRoute` 同一个做法：写在页面上。 */
private val PLAN_LINES = listOf(
    "模型库：内置清单 + 下载（单 zip / 多文件）+ 导入",
    "模型提供方 · 并发数：**已可用**（1~4，默认 2；进度按所有文件求和，不会来回跳）",
    "模型提供方 · 代理：要等 Praxis 先有全局代理设置",
    "性能（预热 / 后端选择）：形态 A 下不适用 —— Praxis 不启动引擎，只是连一个已经在跑的引擎，" +
        "启动参数由那个外部 App 决定；等形态 B/C 落地才有东西可调",
    "输出 · 每步预览：要引擎在采样过程中推中间帧 + 一条缩略图管线（自动存相册已可用）",
    "诊断 · 引擎日志与启动耗时：引擎是外部 App 起的，日志与启动时刻都在它那边，Praxis 拿不到（等形态 B/C）",
)

/**
 * 下载源弹窗（文档 §7.2）。
 *
 * ## 校验用 core 的规则，不在页面里写一遍
 *
 * `ImageDownloadSettingsRules` 已经覆盖「必须有 http/https、尾部斜杠归一化」
 * （9 例单测）。页面再写一份必然与它漂移 —— 那种漂移的表现是「页面说没问题、下载却拼错地址」。
 */
@Composable
private fun DownloadSourceDialog(
    settings: dev.praxis.core.imagegen.ImageDownloadSettings,
    onDismiss: () -> Unit,
    onConfirm: (dev.praxis.core.imagegen.ImageDownloadSettings) -> Unit,
) {
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var concurrency by remember { mutableStateOf(settings.concurrency.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    MiuixAnimatedDialog(visible = true, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "下载源", style = MiuixTheme.textStyles.body1)
                Text(
                    text = "内置清单里的路径会拼在它后面；换镜像站就改这里",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "https://github.com/",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = concurrency,
                    onValueChange = { concurrency = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "同时下载几个文件（1~4）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                error?.let { text ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                MiuixNavigationItemWidget(
                    title = "保存",
                    description = "地址要以 http:// 或 https:// 开头；并发数超出 1~4 会夹到边界",
                    onClick = {
                        if (!dev.praxis.core.imagegen.ImageDownloadSettingsRules
                                .isValidBaseUrl(baseUrl)
                        ) {
                            error = "地址要以 http:// 或 https:// 开头"
                        } else {
                            val parsed = concurrency.trim().toIntOrNull()
                            if (parsed == null) {
                                // 并发数不是用户精确输入（是 1~4 的选择），但「填了字」要如实拦下。
                                error = "并发数要填数字（1~4）"
                            } else {
                                onConfirm(
                                    settings.copy(
                                        baseUrl = dev.praxis.core.imagegen.ImageDownloadSettingsRules
                                            .normalizeBaseUrl(baseUrl),
                                        concurrency = dev.praxis.core.imagegen
                                            .ImageDownloadSettingsRules
                                            .normalizeConcurrency(parsed),
                                    ),
                                )
                            }
                        }
                    },
                )
                MiuixNavigationItemWidget(
                    title = "恢复默认",
                    description = dev.praxis.core.imagegen.ImageDownloadSettings.DEFAULT_BASE_URL,
                    onClick = {
                        baseUrl = dev.praxis.core.imagegen.ImageDownloadSettings.DEFAULT_BASE_URL
                        error = null
                    },
                )
            }
        }
    }
}

/** CFG 显示成 7 而不是 7.0（整数就省掉小数点）。 */
private fun formatCfg(cfg: Float): String =
    if (cfg == cfg.toInt().toFloat()) cfg.toInt().toString() else cfg.toString()

/**
 * 默认参数弹窗（文档 §7.1 的画布 + 采样）。
 *
 * ## 校验用 core 的规则
 *
 * `ImageGenDefaultsRules` 与 `generate_image` 工具读的是同一套边界（256~1024 /
 * 1~60 / 1~20）。页面自己再判一遍必然漂移，表现是「设置页说能存、生成时被拒」。
 */
@Composable
private fun GenerationParamsDialog(
    defaults: dev.praxis.core.imagegen.ImageGenDefaults,
    onDismiss: () -> Unit,
    onConfirm: (dev.praxis.core.imagegen.ImageGenDefaults) -> Unit,
) {
    var width by remember { mutableStateOf(defaults.width.toString()) }
    var height by remember { mutableStateOf(defaults.height.toString()) }
    var steps by remember { mutableStateOf(defaults.steps.toString()) }
    var cfg by remember { mutableStateOf(formatCfg(defaults.cfg)) }
    var negative by remember { mutableStateOf(defaults.negative) }
    var error by remember { mutableStateOf<String?>(null) }

    MiuixAnimatedDialog(visible = true, onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text = "默认生成参数", style = MiuixTheme.textStyles.body1)
                Text(
                    text = "AI 没在工具参数里指定时按这里的值走；它自己指定了就听它的",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = width,
                    onValueChange = { width = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "宽（256~1024）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = height,
                    onValueChange = { height = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "高（256~1024）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = steps,
                    onValueChange = { steps = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "步数（1~60）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = cfg,
                    onValueChange = { cfg = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "CFG（1~20）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = negative,
                    onValueChange = { negative = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "负向提示词（可留空）",
                    useLabelAsPlaceholder = true,
                    minLines = 2,
                    maxLines = 4,
                )
                error?.let { text ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                MiuixNavigationItemWidget(
                    title = "保存",
                    description = "越界不会保存（不静默夹到边界）",
                    onClick = {
                        val candidate = dev.praxis.core.imagegen.ImageGenDefaults(
                            width = width.trim().toIntOrNull() ?: -1,
                            height = height.trim().toIntOrNull() ?: -1,
                            steps = steps.trim().toIntOrNull() ?: -1,
                            cfg = cfg.trim().toFloatOrNull() ?: Float.NaN,
                            negative = negative,
                            scheduler = defaults.scheduler,
                        )
                        val invalid = dev.praxis.core.imagegen.ImageGenDefaultsRules
                            .validate(candidate)
                        if (invalid != null) {
                            error = invalid
                        } else {
                            onConfirm(candidate)
                        }
                    },
                )
            }
        }
    }
}

/**
 * 诊断里的一行：左标题、右取值。取值可长可短，所以单独一行而不是挤在标题里。
 *
 * 用 `SelectionContainer` 包着：这些路径用户往往要复制去别处用。
 */
@Composable
private fun DiagnosticRow(title: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
