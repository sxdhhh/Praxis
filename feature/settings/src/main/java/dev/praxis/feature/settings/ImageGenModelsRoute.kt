package dev.praxis.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.feature.settings.common.SettingsList
import dev.praxis.core.imagegen.ImageDownloadState
import dev.praxis.core.imagegen.ImageModel
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模型库（docs/PORTING-IMAGEGEN.md §7.5）：内置清单 + 下载。
 *
 * ## 为什么下载状态是「一行一个来源」
 *
 * [state] 是全局的（同一时刻只允许一个下载，见 `ImageModelDownloadManager` 的单飞）。
 * 界面据此决定：正在下的那一行显示进度与取消，其余行显示「另一个模型正在下载」而**不是**
 * 悄悄禁用 —— 用户点下去没反应比看到原因更糟。
 *
 * ## 列表项必须给稳定 key
 *
 * 下载进度每 64KB 刷新一次；没有 key 时整行重建，进度条会抖（文档 §7 的纪律 ②）。
 */
@Composable
fun ImageGenModelsRoute(
    models: List<ImageModel>,
    installedIds: Set<String>,
    state: ImageDownloadState,
    onDownload: (ImageModel) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val runningId = (state as? ImageDownloadState.Running)?.modelId

    SettingsList(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "__header__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.PhotoLibrary,
                title = "模型库",
                subtitle = "下载到本机后即可离线出图",
            )
        }

        item(key = "__state__") {
            StateCard(state = state, onCancel = onCancel)
        }

        items(models, key = { model -> model.id }) { model ->
            val installed = model.id in installedIds
            val isThisOne = runningId == model.id
            val busyElsewhere = runningId != null && !isThisOne
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                // 显式包一层 Column：Card 的 content 作用域来自 AAR，不假设它是 ColumnScope，
                // 否则两个子节点会叠在同一个位置（视觉 bug，编译期看不出来）。
                Column {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = model.name,
                        style = MiuixTheme.textStyles.body2,
                    )
                    Text(
                        text = model.description + " · " + model.approximateSize + " · " + backendLabel(model),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    when {
                        installed -> Text(
                            text = "已安装",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )

                        isThisOne -> Text(
                            text = progressLine(state),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )

                        busyElsewhere -> Text(
                            text = "另一个模型正在下载，完成后再试",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                if (!installed && !isThisOne && !busyElsewhere) {
                    MiuixNavigationItemWidget(
                        title = "下载",
                        description = "约 " + model.approximateSize + "，下载前会先检查空间",
                        onClick = { onDownload(model) },
                    )
                }
                if (isThisOne) {
                    MiuixNavigationItemWidget(
                        title = "取消",
                        description = "已经下载的部分会被清掉",
                        onClick = onCancel,
                    )
                }
                }
            }
        }

        item(key = "__footer__") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "清单里只有几条种子模型。完整清单是数据补充，不加代码；" +
                            "模型权重另有许可（SD1.5 是 CreativeML OpenRAIL-M、SDXL 是 OpenRAIL++），" +
                            "Praxis 不随包分发权重。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/** 全局下载状态的提示条：完成/失败/取消各一句，正在下载时不重复（行内已经有进度）。 */
@Composable
private fun StateCard(state: ImageDownloadState, onCancel: () -> Unit) {
    val message = when (state) {
        ImageDownloadState.Idle -> null
        is ImageDownloadState.Running -> null
        is ImageDownloadState.Completed -> buildString {
            append("下载完成：")
            append(state.modelId)
            if (state.spaceWasUnknown) append("（下载前无法预估空间，已尽量尝试）")
            state.leftoverZipName?.let { name ->
                append("；压缩包 " + name + " 没能删掉，可在模型目录手动清理")
            }
        }
        is ImageDownloadState.Failed -> when (state.reason) {
            ImageDownloadState.Failed.Reason.InsufficientSpace -> "空间不足：" + state.detail
            ImageDownloadState.Failed.Reason.Transport -> "下载失败：" + state.detail
        }
        is ImageDownloadState.Cancelled -> "已取消，残留已清理"
    } ?: return

    val isFailure = state is ImageDownloadState.Failed
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.footnote1,
            color = if (isFailure) {
                MiuixTheme.colorScheme.error
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            modifier = Modifier.padding(16.dp),
        )
    }
}

private fun progressLine(state: ImageDownloadState): String {
    val progress = (state as? ImageDownloadState.Running)?.progress
        ?: return "正在准备…"
    val fraction = progress.fraction
    return if (fraction == null) {
        "已下载 " + formatBytes(progress.bytesDownloaded) + "（总量未知）"
    } else {
        "下载中 " + (fraction * 100).toInt() + "% · " + formatBytes(progress.bytesDownloaded)
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(java.util.Locale.US, "%.0f MB", mb)
    }
}

private fun backendLabel(model: ImageModel): String = when {
    model.runOnCpu -> "CPU"
    model.isSdxl -> "NPU · SDXL"
    model.isAnima -> "NPU · Anima"
    else -> "NPU"
}
