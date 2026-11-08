package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.gateway.GatewayImportReport
import dev.praxis.ui.widgets.MiuixBlockingLoadingDialog
import dev.praxis.ui.widgets.MiuixConfirmDialog
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 导入导出页（文档 9.7）。
 *
 * ## 本轮只做「从剪贴板导入」这一个入口
 *
 * 9.7 列了三个入口：选择文件、从剪贴板、备份包。先做剪贴板这一条，原因是
 * **它不需要 ActivityResult**：仓库里 LogScreen 已经记过一次教训 ——
 * 设置子树跑在 :app 的导航容器里，注册时机不稳定。剪贴板读取没有这个问题，
 * 而且用户从网关面板复制一段 JSON 就是最自然的路径。
 *
 * 另两个入口**明确未实现**，界面上不出现「点了没反应」的按钮。
 *
 * ## 四段报告用对话框展示（9.7）
 *
 * 报告在**落库之前**给用户看（预检），确认之后才写。顺序不能反：写完了再报，
 * 用户就没得选了。
 */
@Composable
fun GatewayImportScreen(
    /** 解析与预检是否进行中。 */
    busy: Boolean,
    /** 预检结果；非空时弹出对话框等用户确认。 */
    report: GatewayImportReport?,
    /** 落库后的简短结果（例如「已写入 2 个账号」）。 */
    resultText: String?,
    /** 出错时的文案（读剪贴板为空、JSON 坏了等）。 */
    errorMessage: String?,
    onImportFromClipboard: () -> Unit,
    onConfirmImport: () -> Unit,
    onDismissDialog: () -> Unit,
    /** 导出是否进行中（写盘是阻塞的，与导入共用同一套弹窗观感）。 */
    exportBusy: Boolean = false,
    /** 导出结果：路径 + 写了几个文件 + 未包含项。 */
    exportText: String? = null,
    /** 导出失败时的文案。 */
    exportError: String? = null,
    /** 触发导出；true = 含凭证（先弹确认）。 */
    onExport: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dialogVisible: MutableState<Boolean> = remember { mutableStateOf(false) }
    // 含凭证导出要先确认一次：它是唯一会把明文密钥写到磁盘的路径。
    val exportConfirmVisible: MutableState<Boolean> = remember { mutableStateOf(false) }
    var lastReport by remember { mutableStateOf<GatewayImportReport?>(null) }
    if (report != null && report !== lastReport) {
        lastReport = report
        dialogVisible.value = true
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "__import_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Restore,
                title = "导入",
                subtitle = "把网关那边配好的账号搬过来",
            )
        }

        item(key = "__import_clipboard__") {
            GatewayCard {
                MiuixNavigationItemWidget(
                    title = "从剪贴板导入",
                    description = "在网关面板导出账号文件，复制内容后点这里",
                    onClick = onImportFromClipboard,
                )
            }
        }

        item(key = "__export_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Description,
                title = "导出",
                subtitle = "把 Praxis 这边的账号与映射交回网关",
            )
        }

        item(key = "__export_config_only__") {
            GatewayCard {
                MiuixNavigationItemWidget(
                    title = "导出（仅配置）",
                    description = "凭证写成 ***，可以放心传到别的设备",
                    onClick = { onExport(false) },
                )
            }
        }

        item(key = "__export_with_secrets__") {
            GatewayCard {
                MiuixNavigationItemWidget(
                    title = "导出（含凭证）",
                    description = "明文密钥，文件名带 -secrets；导出前会再确认一次",
                    onClick = { exportConfirmVisible.value = true },
                )
            }
        }

        item(key = "__export_hint__") {
            GatewayCard {
                Text(
                    text = "导出到应用私有外部目录 exports/（Android/data/<包名>/files/exports），" +
                        "卸载应用会一起清掉。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "路由/重试/超时配置与网关 Key 暂时导不出去（Praxis 还没做），" +
                        "导出结果里会逐条列出未包含项。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item(key = "__import_hint__") {
            GatewayCard {
                Text(
                    text = "同一处也能粘 modelRules（带 mappings 的那段 JSON），会自动识别并存下来；" +
                        "存进去之后「模型映射」立即生效。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "提示：内置家的账号暂时会被跳过（理由会写在报告里）—— " +
                        "Praxis 还没有这些家的地址，要等上游实现接进来。自定义家可以正常导入。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    MiuixBlockingLoadingDialog(
        visible = busy,
        text = "正在解析…",
    )

    val message = when {
        errorMessage != null -> errorMessage
        report != null -> formatReport(report)
        else -> ""
    }
    MiuixConfirmDialog(
        showState = dialogVisible,
        title = if (errorMessage != null) "导入失败" else "确认导入",
        message = message,
        confirmText = if (errorMessage != null) "知道了" else "写入",
        dismissText = "取消",
        destructive = false,
        onConfirm = {
            dialogVisible.value = false
            if (errorMessage == null) onConfirmImport() else onDismissDialog()
        },
    )

    if (resultText != null) {
        val resultVisible = remember { mutableStateOf(true) }
        MiuixConfirmDialog(
            showState = resultVisible,
            title = "导入完成",
            message = resultText,
            confirmText = "知道了",
            dismissText = "关闭",
            destructive = false,
            onConfirm = {
                resultVisible.value = false
                onDismissDialog()
            },
        )
    }

    // 含凭证导出的二次确认：这是唯一会把明文密钥写到磁盘的路径。
    MiuixConfirmDialog(
        showState = exportConfirmVisible,
        title = "含凭证导出",
        message = "接下来写出的账号文件里是明文密钥，文件名会带 -secrets。" +
            "请不要通过聊天工具转发。确定继续？",
        confirmText = "导出",
        dismissText = "取消",
        destructive = true,
        onConfirm = {
            exportConfirmVisible.value = false
            onExport(true)
        },
    )

    if (exportText != null) {
        // key 用内容：两次导出结果不同时重建弹窗，而不是沿用上一次的可见状态。
        val visible = remember(exportText) { mutableStateOf(true) }
        MiuixConfirmDialog(
            showState = visible,
            title = "导出完成",
            message = exportText,
            confirmText = "知道了",
            dismissText = "关闭",
            destructive = false,
            onConfirm = { visible.value = false },
        )
    }

    if (exportError != null) {
        val visible = remember(exportError) { mutableStateOf(true) }
        MiuixConfirmDialog(
            showState = visible,
            title = "导出失败",
            message = exportError,
            confirmText = "知道了",
            dismissText = "关闭",
            destructive = false,
            onConfirm = { visible.value = false },
        )
    }

    MiuixBlockingLoadingDialog(
        visible = exportBusy,
        text = "正在导出…",
    )
}

/**
 * 四段报告的文案。
 *
 * 段序固定「新增 / 更新 / 跳过 / 失败」—— 与文档 8.6 一致。理由只对后两段有意义，
 * 所以前两段不显示原因（省得用户以为「没有原因」是缺信息）。
 */
internal fun formatReport(report: GatewayImportReport): String {
    val lines = mutableListOf<String>()
    lines += "新增 " + report.added.size
    lines += "更新 " + report.updated.size
    lines += "跳过 " + report.skipped.size
    lines += "失败 " + report.failed.size
    val detail = report.skipped + report.failed
    if (detail.isNotEmpty()) {
        lines += ""
        detail.take(10).forEach { item ->
            lines += "· " + item.name + "：" + (item.reason ?: "未说明")
        }
        if (detail.size > 10) lines += "…还有 " + (detail.size - 10) + " 条"
    }
    return lines.joinToString("\n")
}
