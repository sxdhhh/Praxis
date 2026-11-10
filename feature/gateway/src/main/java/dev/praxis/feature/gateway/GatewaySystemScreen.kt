package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.widgets.MiuixIntNumberPickerWidget
import dev.praxis.ui.widgets.MiuixSwitchWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → 网关 → 系统设置（docs/PORTING-GATEWAY.md §5.18）。
 *
 * ## 只摆「真的接上了消费者」的项
 *
 * 文档 §5.18 列了 18 条端点，其中大部分是服务端专属（CORS / captcha / sidebar-layout /
 * storage / system-info / health —— 文档自己也标了不移植）。
 * 这里只放「各自有一个明确读取点」的项，改了立刻有效果：
 *
 * - 超时 → OkHttp 客户端（改了要重建客户端，见 PraxisApp）
 * - 换号上限 → `FailoverBudget.maxSwitches`
 * - debug → provider 记请求/响应**摘要**（不含对话内容）
 * - 排队等待上限 → `RequestThrottle.tryAcquire`（等不到就报「排队超时」，
 *   而不是无限挂着）
 *
 * 其余（图片压缩阈值、脱敏规则、提示词模式、自动续写、客户端识别）**不放进来**：
 * 摆一个没人读的开关，比没有更糟 —— 用户调了没反应，会以为整个设置页坏了。
 */
@Composable
fun GatewaySystemScreen(
    settings: dev.praxis.core.gateway.GatewaySystemSettings =
        dev.praxis.core.gateway.GatewaySystemSettings(),
    onChange: (dev.praxis.core.gateway.GatewaySystemSettings) -> Unit = {},
    /** 出站脱敏（文档 §5.18）：开关 + 规则。 */
    sanitize: dev.praxis.core.ai.SanitizeSettings = dev.praxis.core.ai.SanitizeSettings(),
    onSanitizeChange: (dev.praxis.core.ai.SanitizeSettings) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current
    // 规则文本框的本地态：直接绑到 settings.rules 会在每次按键时重建列表，
    // 光标也会随重组抖动（用户正在输入正则）。
    var sanitizeRulesText by remember(sanitize.rules) {
        mutableStateOf(dev.praxis.core.ai.SanitizeRuleLines.toText(sanitize.rules))
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = topInset,
            bottom = 24.dp + scrollLimit,
        ),
    ) {
        item(key = "__system_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Tune,
                title = "系统设置",
                subtitle = "超时、换号与排障；改完立刻生效",
            )
        }

        item(key = "__system_note__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "只列**真的会被读到**的项。文档 §5.18 里的图片压缩/脱敏规则/" +
                            "提示词模式/自动续写/客户端识别还没接 —— 那几项各自要改一条数据通路，" +
                            "没接上之前不摆开关。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item(key = "__system_connect__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixIntNumberPickerWidget(
                    title = "建连超时",
                    description = "连上上游要多久；网络差的地方可调大",
                    value = settings.connectTimeoutSeconds,
                    startInt = dev.praxis.core.gateway.GatewaySystemSettings.MIN_CONNECT_SECONDS,
                    endInt = dev.praxis.core.gateway.GatewaySystemSettings.MAX_CONNECT_SECONDS,
                    valueSuffix = " 秒",
                    onValueChange = { seconds ->
                        onChange(settings.copy(connectTimeoutSeconds = seconds))
                    },
                )
            }
        }
        item(key = "__system_read__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixIntNumberPickerWidget(
                    title = "读取超时",
                    description = "两次数据之间的上限；流式回复会一直读，所以这**不是**整轮上限",
                    value = settings.readTimeoutSeconds,
                    startInt = dev.praxis.core.gateway.GatewaySystemSettings.MIN_READ_SECONDS,
                    endInt = dev.praxis.core.gateway.GatewaySystemSettings.MAX_READ_SECONDS,
                    valueSuffix = " 秒",
                    onValueChange = { seconds ->
                        onChange(settings.copy(readTimeoutSeconds = seconds))
                    },
                )
            }
        }
        item(key = "__system_switches__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixIntNumberPickerWidget(
                    title = "一次请求最多换几个账号",
                    description = "文档 §7.4：换号次数上限默认 3（越换越差时及时收手）",
                    value = settings.maxSwitches,
                    startInt = dev.praxis.core.gateway.GatewaySystemSettings.MIN_SWITCHES,
                    endInt = dev.praxis.core.gateway.GatewaySystemSettings.MAX_SWITCHES,
                    valueSuffix = " 次",
                    onValueChange = { count -> onChange(settings.copy(maxSwitches = count)) },
                )
            }
        }
        item(key = "__system_queue__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixIntNumberPickerWidget(
                    title = "排队等待上限",
                    description = "并发名额一直被占时最多等多久；**0 = 不限**（会一直等）",
                    value = settings.queueWaitSeconds,
                    startInt = dev.praxis.core.gateway.GatewaySystemSettings.MIN_QUEUE_WAIT_SECONDS,
                    endInt = dev.praxis.core.gateway.GatewaySystemSettings.MAX_QUEUE_WAIT_SECONDS,
                    valueSuffix = " 秒",
                    onValueChange = { seconds ->
                        onChange(settings.copy(queueWaitSeconds = seconds))
                    },
                )
            }
        }
        item(key = "__img_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Image,
                title = "图片压缩",
                subtitle = "发出去的图先缩一缩",
            )
        }
        item(key = "__img_switch__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixSwitchWidget(
                    title = "启用图片压缩",
                    description = "关掉时原图原样发出去",
                    checked = settings.imageCompress.enabled,
                    onCheckedChange = { enabled ->
                        onChange(
                            settings.copy(imageCompress = settings.imageCompress.copy(enabled = enabled)),
                        )
                    },
                )
            }
        }
        item(key = "__img_max_edge__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixIntNumberPickerWidget(
                    title = "长边上限",
                    description = "照片通常 4000+，缩到这个值才像「消息里的图」",
                    value = settings.imageCompress.maxEdge,
                    startInt = dev.praxis.core.ai.ChatImageCompression.MIN_MAX_EDGE,
                    endInt = dev.praxis.core.ai.ChatImageCompression.MAX_MAX_EDGE,
                    valueSuffix = " 像素",
                    onValueChange = { value ->
                        onChange(
                            settings.copy(imageCompress = settings.imageCompress.copy(maxEdge = value)),
                        )
                    },
                )
            }
        }
        item(key = "__img_quality__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixIntNumberPickerWidget(
                    title = "JPEG 质量",
                    description = "越低越小越糊",
                    value = settings.imageCompress.quality,
                    startInt = dev.praxis.core.ai.ChatImageCompression.MIN_QUALITY,
                    endInt = dev.praxis.core.ai.ChatImageCompression.MAX_QUALITY,
                    valueSuffix = "",
                    onValueChange = { value ->
                        onChange(
                            settings.copy(imageCompress = settings.imageCompress.copy(quality = value)),
                        )
                    },
                )
            }
        }
        item(key = "__img_threshold__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixIntNumberPickerWidget(
                    title = "体积阈值",
                    description = "小于这个体积的图**不动它**（再压只是多掉一层画质）；0 = 不豁免",
                    value = settings.imageCompress.thresholdKb,
                    startInt = dev.praxis.core.ai.ChatImageCompression.MIN_THRESHOLD_KB,
                    endInt = dev.praxis.core.ai.ChatImageCompression.MAX_THRESHOLD_KB,
                    valueSuffix = " KB",
                    onValueChange = { value ->
                        onChange(
                            settings.copy(
                                imageCompress = settings.imageCompress.copy(thresholdKb = value),
                            ),
                        )
                    },
                )
            }
        }
        item(key = "__sanitize_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Security,
                title = "出站脱敏",
                subtitle = "发送前把命中规则的片段替换掉",
            )
        }
        item(key = "__sanitize_switch__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixSwitchWidget(
                    title = "启用脱敏",
                    description = "关掉时规则保留、只是不生效（不用重写一遍）",
                    checked = sanitize.enabled,
                    onCheckedChange = { enabled ->
                        onSanitizeChange(sanitize.copy(enabled = enabled))
                    },
                )
            }
        }
        item(key = "__sanitize_rules__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "规则（一行一条正则；# 开头是注释）",
                        style = MiuixTheme.textStyles.body2,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = sanitizeRulesText,
                        onValueChange = { text ->
                            sanitizeRulesText = text
                            onSanitizeChange(
                                sanitize.copy(
                                    rules = dev.praxis.core.ai.SanitizeRuleLines.fromText(text),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "例如 \\d{11}",
                        useLabelAsPlaceholder = true,
                        minLines = 3,
                    )
                    // 坏正则单独报出来（它们不会让发送失败，只是那一条不生效）。
                    val broken = dev.praxis.core.ai.OutboundSanitizer.compile(sanitize.rules).broken
                    if (broken.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "有 " + broken.size + " 条正则写错了，会被跳过：" +
                                broken.joinToString("、"),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item(key = "__system_debug__") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixSwitchWidget(
                    title = "把请求/响应摘要写进日志",
                    description = "只记模型、条数、字节数与 HTTP 状态 —— **不含对话内容与密钥**" +
                        "（日志页承诺过的）",
                    checked = settings.debugRawTraffic,
                    onCheckedChange = { enabled ->
                        onChange(settings.copy(debugRawTraffic = enabled))
                    },
                )
            }
        }
    }
}
