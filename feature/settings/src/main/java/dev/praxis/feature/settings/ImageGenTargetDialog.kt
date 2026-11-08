package dev.praxis.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.imagegen.ImageGenTarget
import dev.praxis.core.imagegen.ImageModel
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 生图目标的选择弹窗（docs/PORTING-IMAGEGEN.md §7.3）。
 *
 * ## 为什么是「两个分组」而不是一个列表
 *
 * 本地模型 id 与服务商的 (provider, model) 是两种语义：一个叫 flux 的本地模型和
 * 一条远端 flux-schnell 放在同一个列表里，用户分不清自己选的是哪条路。
 * 分组的标题本身就是那层说明。
 *
 * ## 为什么「服务商」这一组现在只有一句说明
 *
 * 远端走 `/v1/images/generations` 的客户端还没写（工具目前只认本地引擎）。
 * 摆一堆点了没反应的服务商模型，就是「假入口」。等客户端就位再放出来。
 *
 * ## 点击即生效
 *
 * 与功能模型页的单选弹窗同一取舍：用户心里的动作是「选它」，不是「标记它」。
 * 多一步确认反而让人以为没选中。
 */
@Composable
fun ImageGenTargetDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    localModels: List<ImageModel>,
    current: ImageGenTarget?,
    onSelect: (ImageGenTarget) -> Unit,
) {
    MiuixAnimatedDialog(visible = visible, onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text = "选择生图目标", style = MiuixTheme.textStyles.body1)
                Text(
                    text = "点击即生效；生成时按这里选的走",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "本地模型",
                    style = MiuixTheme.textStyles.body2,
                )
                if (localModels.isEmpty()) {
                    Text(
                        text = "还没有已下载的模型 —— 先去「模型库」下一个",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    localModels.forEach { model ->
                        val selected = (current as? ImageGenTarget.Local)?.modelId == model.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(ImageGenTarget.Local(model.id))
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Image,
                                contentDescription = null,
                                tint = if (selected) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                                modifier = Modifier.padding(end = 10.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = model.name, style = MiuixTheme.textStyles.body2)
                                Text(
                                    text = model.approximateSize + " · " + if (model.runOnCpu) "CPU" else "NPU",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            if (selected) {
                                Text(
                                    text = "当前",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
                Spacer(Modifier.height(8.dp))
                Text(text = "服务商生图模型", style = MiuixTheme.textStyles.body2)
                Text(
                    text = "还没接：远端 /v1/images/generations 的客户端未写，现在只有本地引擎这条路",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
