package dev.praxis.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 附件面板中的一个格子。
 *
 * [enabled] = false 的格子显示为禁用态且**不可点**。
 * 这比「隐藏」好：用户能看到「有这个能力」，也就知道
 * 该去哪提需求；藏起来会让人以为产品没这个设计。
 */
private data class AttachmentCell(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

/**
 * 附件选择面板 —— 1:1 对齐 Operit `AttachmentSelectorPanel`
 * （`AttachmentSelector.kt:88`）。
 *
 * ## 布局与动画照抄上游
 *
 * | 项 | 上游 |
 * |---|---|
 * | 展开方向 | `expandVertically(expandFrom = Alignment.Bottom)` + `fadeIn` |
 * | 时长 | 200ms |
 * | 每行格子 | 4 个（`chunked(8)` 分页，Praxis 只有一页 8 项） |
 *
 * 从**底边向上**展开是必须的：面板贴在输入栏上方，若从顶边往下长，
 * 会先占位再填内容，看起来像「往下掉」。
 *
 * ## 为什么 6 项是禁用态
 *
 * Praxis 目前只有两条真实通路：
 *
 *  - **图片**：`ChatImagePreparer` + 系统 SAF（`GetContent`）；
 *  - **文件**：系统 SAF（`OpenDocument`）。
 *
 * 其余 6 项各自缺底层：
 *
 * | 项 | 缺什么 |
 * |---|---|
 * | 拍照 | 无 CameraX 依赖，也无 FileProvider 拍照落盘路径 |
 * | 记忆 | `MemoryPort` 只有 recall/remember，没有「把记忆作为附件插入」 |
 * | 屏幕内容 | 无 MediaProjection / 无障碍截图 |
 * | 通知 | 无 NotificationListenerService |
 * | 位置 | 无 LocationManager 封装与权限申请 |
 * | 智能包 | 无包解析（PackageManager 读 APK 元信息）通路 |
 *
 * 这些做成禁用态而不是假可用：点了没反应比灰着更糟。
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
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        // 从底边向上展开（上游 AttachmentSelector.kt:144 同一写法）。
        enter = expandVertically(
            animationSpec = tween(durationMillis = 200),
            expandFrom = Alignment.Bottom,
        ) + fadeIn(animationSpec = tween(durationMillis = 200)),
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = 160),
            shrinkTowards = Alignment.Bottom,
        ) + fadeOut(animationSpec = tween(durationMillis = 160)),
        modifier = modifier,
    ) {
        val cells = listOf(
            AttachmentCell(Icons.Default.Image, "图片", onPickImage != null) {
                onPickImage?.invoke()
            },
            AttachmentCell(
                Icons.Default.PhotoCamera,
                "拍照",
                onTakePhoto != null,
            ) { onTakePhoto?.invoke() },
            AttachmentCell(
                Icons.Default.Memory,
                "记忆",
                onAttachMemory != null,
            ) { onAttachMemory?.invoke() },
            AttachmentCell(Icons.Default.Description, "文件", onPickFile != null) {
                onPickFile?.invoke()
            },
            AttachmentCell(
                Icons.Default.ScreenshotMonitor,
                "屏幕",
                onCaptureScreen != null,
            ) { onCaptureScreen?.invoke() },
            AttachmentCell(
                Icons.Default.Notifications,
                "通知",
                onAttachNotifications != null,
            ) { onAttachNotifications?.invoke() },
            AttachmentCell(
                Icons.Default.LocationOn,
                "位置",
                onAttachLocation != null,
            ) { onAttachLocation?.invoke() },
            AttachmentCell(
                Icons.Default.AutoAwesome,
                "智能包",
                onAttachPackages != null,
            ) { onAttachPackages?.invoke() },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 4 列 grid。用 chunked + Row 而不是 LazyVerticalGrid：
            // 8 个固定格子不需要懒加载，Lazy 反而引入一个额外的滚动容器
            // （在可折叠面板里嵌可滚动容器会有嵌套滚动冲突）。
            cells.chunked(4).forEach { rowCells ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    rowCells.forEach { cell ->
                        AttachmentCellView(
                            cell = cell,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 不足 4 个时补空位，保持列对齐。
                    repeat(4 - rowCells.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentCellView(
    cell: AttachmentCell,
    modifier: Modifier = Modifier,
) {
    val tint = if (cell.enabled) {
        MiuixTheme.colorScheme.onSurface
    } else {
        // 禁用态：用 outline 而不是把 alpha 降到极低 —— alpha 太低会
        // 看不清图标形状，用户就不知道那是什么功能。
        MiuixTheme.colorScheme.outline
    }
    Column(
        modifier = modifier
            .clickable(enabled = cell.enabled) { cell.onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    color = if (cell.enabled) {
                        MiuixTheme.colorScheme.surfaceVariant
                    } else {
                        MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = cell.icon,
                contentDescription = cell.label,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = cell.label,
            fontSize = 11.sp,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
