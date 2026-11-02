package dev.praxis.feature.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 附件面板中的一项。
 *
 * [enabled] = false 的项显示为禁用态且**不可点**。
 * 这比「隐藏」好：用户能看到「有这个能力」，也就知道该去哪提需求；
 * 藏起来会让人以为产品没这个设计。
 */
private data class AttachmentCell(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

/**
 * 附件选择菜单。
 *
 * ## 为什么从「自建 Card + 九宫格」改成 miuix 下拉（2026-11-03）
 *
 * 用户原话：「加号的菜单应该和模型和功能菜单的样式一致而不是最后一张图片的那种」。
 *
 * 旧实现是**自建的一整套**：`MiuixAnimatedPopup` + 满屏 Box + `Card` + 4 列 grid。
 * 结果是同一个输入栏上三个按钮弹出三种不同的东西：
 *
 * | 按钮 | 组件 | 观感 |
 * |---|---|---|
 * | 模型 / 功能 | miuix `OverlayDropdownPopup` | 圆角面板、逐项动画、贴着触发区 |
 * | 加号 | 自建 Card + 九宫格 | 尺寸/圆角/阴影/动画全不一样 |
 *
 * 现在统一成同一套组件，三块浮层的宽度、右边距、圆角、阴影、动画全部由
 * miuix 自己保证 —— 不再靠三处硬编码的 padding 去「对齐」，那种对齐方式
 * 之前已经出过一次错（见 AttachmentPanel 的历史注释：三块板底边不在一条线上）。
 *
 * ## 图标怎么保留
 *
 * `DropdownItem` 的第 5 个参数是 `icon: @Composable ((Modifier) -> Unit)?` ——
 * 官方下拉**原生支持带图标**（javap 从 Maven Central 的 aar 核实过）。
 * 所以附件这 8 项不用为了图标另做布局。
 *
 * ## 禁用项为什么不隐藏
 *
 * 8 项里只有「图片」「文件」有真实通路，其余 6 项缺底层能力。
 * 做成禁用态（灰、不可点）而不是藏起来：用户能看出「有这功能但还没做」，
 * 藏起来会让人以为产品压根没设计。
 */
@Composable
fun AttachmentPanel(
    visible: Boolean,
    onPickImage: (() -> Unit)?,
    onPickFile: (() -> Unit)?,
    onAttachLocation: (() -> Unit)? = null,
    onAttachNotifications: (() -> Unit)? = null,
    /** 屏幕内容：MediaProjection 一次性抓帧。 */
    onCaptureScreen: (() -> Unit)? = null,
    /** 拍照：调系统相机，零新依赖（FileProvider + TakePicture）。 */
    onTakePhoto: (() -> Unit)? = null,
    onAttachMemory: (() -> Unit)? = null,
    onAttachPackages: (() -> Unit)? = null,
    /**
     * 点面板外关闭。
     *
     * null = 不提供（预览场景），此时浮层只能靠再加号关闭。
     */
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cells = remember(
        onPickImage, onPickFile, onAttachLocation, onAttachNotifications,
        onCaptureScreen, onTakePhoto, onAttachMemory, onAttachPackages,
    ) {
        listOf(
            AttachmentCell(Icons.Default.Image, "图片", onPickImage != null) {
                onPickImage?.invoke()
            },
            AttachmentCell(Icons.Default.PhotoCamera, "拍照", onTakePhoto != null) {
                onTakePhoto?.invoke()
            },
            AttachmentCell(Icons.Default.Memory, "记忆", onAttachMemory != null) {
                onAttachMemory?.invoke()
            },
            AttachmentCell(Icons.Default.Description, "文件", onPickFile != null) {
                onPickFile?.invoke()
            },
            AttachmentCell(Icons.Default.ScreenshotMonitor, "屏幕", onCaptureScreen != null) {
                onCaptureScreen?.invoke()
            },
            AttachmentCell(Icons.Default.Notifications, "通知", onAttachNotifications != null) {
                onAttachNotifications?.invoke()
            },
            AttachmentCell(Icons.Default.LocationOn, "位置", onAttachLocation != null) {
                onAttachLocation?.invoke()
            },
            AttachmentCell(Icons.Default.AutoAwesome, "智能包", onAttachPackages != null) {
                onAttachPackages?.invoke()
            },
        )
    }

    // 单个 DropdownEntry 承载 8 项。`selected = false` 是有意的：
    // 这是「动作菜单」不是「选择器」，没有当前选中值这回事。
    val entry = remember(cells) {
        DropdownEntry(
            items = cells.map { cell ->
                DropdownItem(
                    text = cell.label,
                    enabled = cell.enabled,
                    selected = false,
                    onClick = {
                        cell.onClick()
                        onDismiss?.invoke()
                    },
                    // 第 5 个参数就是 icon slot（javap 核实过签名）。
                    icon = { iconModifier ->
                        Icon(
                            imageVector = cell.icon,
                            contentDescription = null,
                            tint = if (cell.enabled) {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            } else {
                                MiuixTheme.colorScheme.disabledOnSecondaryVariant
                            },
                            modifier = iconModifier.size(20.dp),
                        )
                    },
                )
            },
        )
    }

    OverlayDropdownPopup(
        entries = listOf(entry),
        show = visible,
        onDismiss = { onDismiss?.invoke() },
        onDismissFinished = { onDismiss?.invoke() },
        maxHeight = null,
        dropdownColors = DropdownDefaults.dropdownColors(),
        // 动作菜单：点完就收起。
        collapseOnSelection = true,
    )
}
