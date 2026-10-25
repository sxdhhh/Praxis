package dev.praxis.feature.settings.ai

import dev.praxis.feature.settings.common.SettingsList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.ApiProvider
import dev.praxis.core.model.settings.FunctionModelOverride
import dev.praxis.core.model.settings.FunctionModelSettings
import dev.praxis.core.model.settings.FunctionSlot
import dev.praxis.core.model.settings.ModelSettings
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.praxis.ui.widgets.FoldableContent

/**
 * 功能模型配置页（对齐 Operit「功能模型配置」的信息架构）。
 *
 * ## 抄的是结构，不是那 2530 行
 *
 * Operit 的 `ModelConfigScreen.kt` 是 2530 行，含「配置档案列表 + 每条 30 多个参数 +
 * API Key 池 + 思考档位 + 连接测试 + 影响面提示」。Praxis 当前的模型层只有
 * `baseUrl / model / temperature / provider` 四个字段，没有 Key 池、没有多档案。
 *
 * 所以只抄它的**信息架构**：
 *
 * ```
 * [全局配置]  全局：DeepSeek / deepseek-chat
 * [功能模型]
 *   对话              跟随全局（deepseek-chat）   ← 点开可覆盖
 *   上下文总结         跟随全局（deepseek-chat）
 *   AI 总结标题        当前：qwen-turbo            ← 已覆盖，主色高亮
 *   ...
 * [全部恢复为跟随全局]
 * ```
 *
 * 展开后：说明 + 模型 ID 输入框 + 服务商切换 + 恢复跟随全局。
 *
 * **不做假的参数面板**：Praxis 的请求体里只有 temperature 一个可选参数，
 * 逐功能再配一遍温度没有真实消费方 —— 做了就是假 UI。
 *
 * ## 为什么「跟随全局」要带具体模型名
 *
 * 只写「跟随全局」用户不知道实际会用什么，还得回上一页看。
 * 写成「跟随全局（deepseek-chat）」把结果摊开，省掉一次往返。
 */
@Composable
fun FunctionModelRoute(
    repository: SettingsRepository,
    modifier: Modifier = Modifier,
) {
    val global by repository.modelSettings.collectAsState(initial = ModelSettings())
    val functions by repository.functionModelSettings.collectAsState(initial = FunctionModelSettings())
    val scope = rememberCoroutineScope()

    // 同时只展开一个：一次只改一处，避免多个输入框同时可编辑造成「改了哪个」的困惑。
    var expanded by remember { mutableStateOf<FunctionSlot?>(null) }

    SettingsList(modifier = modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(8.dp)) }

        item {
            SmallTitle("全局配置")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    text = if (global.model.isBlank()) {
                        "尚未配置全局模型。所有「跟随全局」的功能位都会没有模型可用，" +
                            "请先回上一页配置服务商与模型。"
                    } else {
                        "全局：${global.provider.label} / ${global.model}"
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        item {
            SmallTitle("功能模型")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                FunctionSlot.entries.forEachIndexed { index, slot ->
                    FunctionSlotRow(
                        slot = slot,
                        current = functions[slot],
                        resolvedModel = functions.resolve(slot, global).model,
                        expanded = expanded == slot,
                        onToggleExpand = { expanded = if (expanded == slot) null else slot },
                        onModelChange = { newModel ->
                            scope.launch {
                                val prev = functions[slot]
                                repository.setFunctionModel(
                                    slot,
                                    prev.copy(model = newModel.trim().ifBlank { null }),
                                )
                            }
                        },
                        onProviderChange = { provider ->
                            scope.launch {
                                val prev = functions[slot]
                                repository.setFunctionModel(
                                    slot,
                                    prev.copy(
                                        provider = provider,
                                        // 换服务商必须同步换地址，否则会拼出
                                        // 「A 家的地址 + B 家的模型」这种必然 404 的组合。
                                        baseUrl = provider.defaultEndpoint.ifBlank { null },
                                    ),
                                )
                            }
                        },
                        onReset = {
                            scope.launch {
                                repository.setFunctionModel(slot, FunctionModelOverride())
                                expanded = null
                            }
                        },
                    )
                    if (index != FunctionSlot.entries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MiuixTheme.colorScheme.dividerLine,
                        )
                    }
                }
            }
        }

        item {
            TextButton(
                text = "全部恢复为跟随全局",
                onClick = {
                    scope.launch { repository.clearFunctionModels() }
                    expanded = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 一个功能位：折叠头一行，展开后是编辑区。 */
@Composable
private fun FunctionSlotRow(
    slot: FunctionSlot,
    current: FunctionModelOverride,
    resolvedModel: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onModelChange: (String) -> Unit,
    onProviderChange: (ApiProvider) -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = slot.title,
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (current.isOverridden) {
                        "当前：$resolvedModel"
                    } else {
                        "跟随全局（${resolvedModel.ifBlank { "未配置" }}）"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (current.isOverridden) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // ## 展开必须有动画（2026-10-25，同 PackageGroupsCard）
        //
        // 旧写法 `if (expanded) { Column(...) }` 是一帧内塞入布局。
        // 改用项目统一的 `FoldableContent`，常驻调用、只翻 `visible`。
        FoldableContent(visible = expanded) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = slot.description,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextField(
                    value = current.model.orEmpty(),
                    onValueChange = onModelChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    label = "模型 ID（留空 = 跟随全局）",
                    singleLine = true,
                )
                // 服务商切换只在「已经覆盖模型」时给：没覆盖时 provider 跟着全局走，
                // 单独改 provider 会得到一个「地址变了但模型还是全局那个」的错配。
                if (current.isOverridden) {
                    val provider = current.provider ?: ApiProvider.Custom
                    TextButton(
                        text = "服务商：${provider.label}（点击切换）",
                        onClick = {
                            val all = ApiProvider.entries
                            val idx = all.indexOf(provider)
                            onProviderChange(all[(idx + 1) % all.size])
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        textStyle = MiuixTheme.textStyles.footnote1,
                    )
                    TextButton(
                        text = "恢复跟随全局",
                        onClick = onReset,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MiuixTheme.textStyles.footnote1,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}