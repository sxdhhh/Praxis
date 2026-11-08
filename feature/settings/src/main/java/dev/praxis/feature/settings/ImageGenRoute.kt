package dev.praxis.feature.settings

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
    /** 已安装的模型显示名。 */
    installedModels: List<String>,
    /** 模型目录的绝对路径（只读展示，方便用户自己放文件）。 */
    modelsRootPath: String,
    modifier: Modifier = Modifier,
) {
    var probeTick by remember { mutableStateOf(0) }
    var probe by remember { mutableStateOf<ProbeUi>(ProbeUi.Probing) }

    // 进页面探一次；点「重新检测」把 tick 加一即重探。
    LaunchedEffect(probeTick) {
        probe = ProbeUi.Probing
        probe = ProbeUi.Done(probeEngine())
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
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
                        dev.praxis.ui.navigation.LocalNavigator.current
                            .push(dev.praxis.ui.navigation.PraxisRoute.SettingsImageGenModels)
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
                        "已安装 " + installedModels.size + " 个：" + installedModels.joinToString("、")
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
    "模型提供方：下载源、仅 Wi-Fi、并发数、代理",
    "画布 / 采样 / 性能 / 输出：那些参数现在只能由 AI 通过工具改",
    "诊断：上次启动耗时、引擎日志尾巴",
)
