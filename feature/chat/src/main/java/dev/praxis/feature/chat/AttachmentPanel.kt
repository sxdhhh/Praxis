package dev.praxis.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.praxis.ui.widgets.MiuixAnimatedPopup

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
    /**
     * 点面板外关闭。
     *
     * ## 为什么改造为浮层后必须有它（2026-10-29）
     *
     * 旧实现是 `AnimatedVisibility` **内联**在输入区上方（展开时把底栏
     * 往下推）。它有自己的可见性开关，不需要「点外部关闭」—— 用户点
     * 加号或任意格子就消失了。
     *
     * 改成 `Popup` 浮层后，它盖在内容之上，必须有一条“点空白处关闭”的
     * 退路，否则用户只能再点一次加号（而那时加号可能已被遮住）。
     *
     * null = 不提供（预览），此时浮层不接收外部点击，只能靠加号关闭。
     */
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // ## 为什么改成浮层（2026-10-29）
    //
    // 旧实现是 `AnimatedVisibility` **内联**在输入区上方：展开时把底栏
    // 往下推、自己从底边向上长。它跟 Tune 的形态不一致 —— Tune 是
    // `Popup` 浮层（`ChatSettingsBar:661-685`），盖在内容之上、不挤布局。
    //
    // 同一个底栏里两个按钮，一个推布局一个浮起来，观感是错位的：
    // 点加号整页往上跳一下，点齿轮只是浮出一张卡。现在统一为浮层。
    //
    // ## 容器写法与 Tune 逐字一致
    //
    // `Popup(TopStart)` + 满屏 `Box`（自己接点击）+ `Card` 贴 `BottomEnd`。
    // 满屏 Box 是关键：Popup 的内容尺寸若随内容变化，窗口会每帧重排
    // （那正是 2026-10-25 修过的「一抖一抖」）。满屏固定尺寸就没这问题。
    MiuixAnimatedPopup(
        visible = visible,
        alignment = Alignment.TopStart,
        onDismissRequest = { onDismiss?.invoke() },
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            // 满屏 Box 已铺满，outside 区域不存在；开着它会让
            // 「点格子内部」也被判成 outside 而误关。
            dismissOnClickOutside = false,
        ),
    ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onDismiss?.invoke() },
                    ),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Card(
                    modifier = Modifier
                        // ## 尺寸与抬起量必须与「功能弹窗」一致（2026-10-29 修）
                        //
                        // 旧值：`bottom = 44.dp, end = 12.dp, widthIn(max=420dp) + fillMaxWidth`。
                        // 后果（截图对比）：这个面板比下面的功能面板**宽得多、也低得多**，
                        // 两者底边不在一条线上，视觉上像两个不相干的浮层。
                        //
                        // 三处浮层（模型选择 / 功能 / 附件）应当是**同一块板的三种内容**：
                        // 同宽、同右边距、同底边。基准取功能弹窗
                        // （`ChatSettingsBar` 的 `width(280.dp)` +
                        // `padding(bottom = 96.dp, end = 8.dp)`）。
                        //
                        // 为什么去掉 `fillMaxWidth()`：它与 `width(280.dp)` 互斥，
                        // 留着会按父级铺满，宽度又被撑回满屏。宽度只由 width 决定。
                        .padding(bottom = 96.dp, end = 8.dp)
                        .width(280.dp)
                        // 高度上限也与功能弹窗一致：8 个格子（2 行）远小于 420dp，
                        // 这里给上限只是为了让三块板的“最大高度”口径相同。
                        .heightIn(max = 420.dp)
                        // 卡片自己吃掉点击，否则点格子会被外层 Box 的 onClick 关掉。
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
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
