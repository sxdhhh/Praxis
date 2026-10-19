// 结构与数值移植自 InstallerX-Revived ui/page/miuix/widgets/MiuixDialog.kt（GPL-3.0-only）。
//
// 移植说明：原文件 645 行、9 个对话框，全部绑定安装器业务（卸载、Root 实现选择、
// GitHub 更新通道、代理 URL、授权器），Praxis 一个都用不上。因此本次只保留
// **照原式样重建的通用对话框**，并把原文里靠注释约束的硬规矩固化成代码结构：
//
//  1. 按钮与内容之间固定 Spacer(24.dp) —— 原文件 9 处全部如此，不给单处特例；
//  2. 双按钮 Row 用 Arrangement.spacedBy(8.dp)，两个按钮都 weight(1f)（等宽）；
//  3. 确认按钮必须带 ButtonDefaults.textButtonColorsPrimary()，否则视觉上分不出主次；
//  4. 底部弹层关闭一律走 LocalDismissState（走完关闭动画），不直接调 onDismissRequest，
//     否则会跳过动画、弹层瞬间消失。
package dev.praxis.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 通用确认对话框（替代原文件里 6 个「标题 + 正文 + 取消/确认」的重复实现）。
 *
 * @param showState 控制显示；由调用方持有，便于跨重组保留。
 * @param title 标题。
 * @param message 正文。
 * @param confirmText 确认按钮文案。
 * @param dismissText 取消按钮文案。
 * @param destructive 确认动作是否具破坏性（true 时确认按钮用错误色）。
 */
@Composable
fun MiuixConfirmDialog(
    showState: MutableState<Boolean>,
    title: String,
    message: String,
    confirmText: String = "确认",
    dismissText: String = "取消",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
) {
    // 关闭一律走 dismissState：直接改 showState 会跳过关闭动画。
    val dismissState = LocalDismissState.current
    val close = { dismissState?.invoke() ?: run { showState.value = false } }

    WindowDialog(
        show = showState.value,
        title = title,
        onDismissRequest = {
            showState.value = false
        },
        content = {
            Column {
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )

                // 硬规矩 1：内容与按钮之间固定 24dp，不给单处特例。
                Spacer(Modifier.height(24.dp))

                // 硬规矩 2：双按钮等宽、间距 8dp。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = dismissText,
                        onClick = close,
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = confirmText,
                        // 硬规矩 3：确认按钮必带 primary（破坏性动作改用 danger）。
                        // miuix 0.9.4 没有 textButtonColorsDanger()；危险色就是把
                        // textButtonColorsPrimary 的 color 换成 error。
                        colors = if (destructive) {
                            ButtonDefaults.textButtonColorsPrimary(
                                color = MiuixTheme.colorScheme.error,
                            )
                        } else {
                            ButtonDefaults.textButtonColorsPrimary()
                        },
                        onClick = {
                            onConfirm()
                            close()
                        },
                    )
                }
            }
        },
    )
}

/**
 * 列表型选择对话框（替代原文件里的 Root 实现选择 / 更新通道选择）。
 *
 * `insideMargin` 传 `DpSize(0.dp, 24.dp)`：列表项自己带水平内边距，
 * 外层再补水平边距会让选中高亮条左右缩进不一致（原文件注释里踩过）。
 */
@Composable
fun <T> MiuixSelectionDialog(
    showState: MutableState<Boolean>,
    title: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    confirmText: String = "确认",
    dismissText: String = "取消",
) {
    val dismissState = LocalDismissState.current
    val close = { dismissState?.invoke() ?: run { showState.value = false } }
    // 显式标注类型：selected 是 T?，靠推断再交给 by 委托时泛型消解不稳定（会报 has no method setValue）。
    val pending: MutableState<T?> = remember(showState.value) { mutableStateOf(selected) }

    WindowDialog(
        show = showState.value,
        title = title,
        onDismissRequest = { showState.value = false },
        insideMargin = DpSize(0.dp, 24.dp),
        content = {
            Column {
                options.forEach { option ->
                    MiuixSelectableRow(
                        text = optionLabel(option),
                        isSelected = option == pending.value,
                        onClick = { pending.value = option },
                    )
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = dismissText,
                        onClick = close,
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = confirmText,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            pending.value?.let(onSelect)
                            close()
                        },
                    )
                }
            }
        },
    )
}
