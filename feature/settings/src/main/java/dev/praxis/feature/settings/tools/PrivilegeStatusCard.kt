// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
// 结构照抄 InstallerX-Revived:
//   app/src/main/java/com/rosan/installer/ui/page/miuix/settings/home/MiuixHomePage.kt
//   MiuixStatusGrid 的大状态卡（第 272–350 行）
package dev.praxis.feature.settings.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 大状态卡（**结构照抄 InstallerX 主页的 `MiuixStatusGrid` 大卡**）。
 *
 * ## 为什么值得单独抽成控件
 *
 * apixiaoyuan / InstallerX 的「状态卡」是同一套视觉语言的产物，后面还会
 * 在别处复用（工作区域状态、模型连通状态）。写成控件而不是各页复制，
 * 是为了让「状态」在全 App 看起来是同一个东西 —— 用户对颜色的记忆
 * 比对齐像素更可靠。
 *
 * ## 从上游照搬的四件事（都不能省）
 *
 * 1. **一整个背景图标**：`170dp`，`offset(50dp, 38dp)` 贴着右下角出血。
 *    这是这张卡最容易被忽略、但最决定「像不像」的一处：没有它，卡片
 *    只是一块带文字的色板；有它，面积感与「状态」语义立刻成立。
 * 2. **状态色带**：启用 → `secondaryContainer`；未启用 → `errorContainer`。
 *    两套色不是「好看/难看」，而是「正常/需要注意」。
 * 3. **三段文字层级**：20sp SemiBold（结论）→ 14sp Medium α0.8（解释）
 *    → 14sp Medium α0.8（细节）。上游用 `descTextColor = onX.copy(alpha=0.8f)`
 *    做次级层级，比换字号更稳，因为字号变化会让中文行高跳动。
 * 4. **按压 Tilt**：`pressFeedbackType = PressFeedbackType.Tilt` + `showIndication`。
 *    miuix 自带的「倾斜」反馈，比水波纹更接近原版。
 *
 * ## 与上游的差异
 *
 *  - 上游 `Icon` 用的是它自己的 `AppIcons.Active` / `Icons.Rounded.ErrorOutline`；
 *    Praxis 由调用方传 [statusIcon]，不绑定具体图标集。
 *  - 上游第三段（Authorizer 类型）可能整段不显示（系统安装器场景）。
 *    Praxis 用可空 [statusDetail] 表达同一件事。
 *
 * ## 为什么圆角/内边距固定
 *
 * 这张卡是「页面级」元素，与 `Card` 默认圆角（16dp）一致即可；
 * 内边距 16dp 与上游相同，保证图标出血量与文字左边距的比例不变。
 *
 * @param active 是否处于「已获得」状态，决定整卡色带与图标色
 * @param title 结论句（20sp）
 * @param description 解释句（14sp）
 * @param statusIcon 右下角大图标（建议 170dp 视觉尺寸的矢量图）
 * @param statusDetail 第三段细节；null 表示不显示（上游同款行为）
 * @param onClick 点击动作；null 表示不可点（不可点时也不显示按压反馈）
 */
@Composable
fun PrivilegeStatusCard(
    active: Boolean,
    title: String,
    description: String,
    statusIcon: ImageVector,
    modifier: Modifier = Modifier,
    statusDetail: String? = null,
    onClick: (() -> Unit)? = null,
) {
    // ── 状态色带（照抄上游的三元选择，但只保留「动态取色」一支）────────
    //
    // 上游有两种：动态取色下用 secondaryContainer/errorContainer，
    // 非动态色（固定苹果绿/粉红）下用硬编码 0xFFDFFAE4 / 0xFFFAEEEE。
    // Praxis 全项目只有 miuix 一个引擎且始终走主题语义色（R28 决策），
    // 所以**只保留语义色分支** —— 那也正好是「跟随莫奈取色」的效果。
    val containerColor = if (active) {
        MiuixTheme.colorScheme.secondaryContainer
    } else {
        MiuixTheme.colorScheme.errorContainer
    }
    val textContentColor = if (active) {
        MiuixTheme.colorScheme.onSecondaryContainer
    } else {
        MiuixTheme.colorScheme.onErrorContainer
    }
    // 第三段比第一段浅一档：α 而不是换字号（换字号会让中文行高跳动）。
    val descTextColor = textContentColor.copy(alpha = 0.8f)
    val iconTint = if (active) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
    } else {
        MiuixTheme.colorScheme.error.copy(alpha = 0.8f)
    }

    // 按压时的轻微缩放：`PressFeedbackType.Tilt` 已提供倾斜，
    // 但 miuix 的 Tilt 只影响卡片**表面**（surface 绘制层），
    // 我们叠在外面的 content 与 170dp 图标不会跟着「压下去」。
    //
    // ## 为什么不用 interactionSource
    //
    // miuix `Card` 的 `interactionSource` 是**内部创建**的，没有对外参数
    // （查源码 Card.kt:99 `val interactionSource = remember { MutableInteractionSource() }`）。
    // 所以外部无法观察它的按压态，必须自己维护。
    // 用 `pointerInput` + `awaitPointerEventScope` 是最小侵入的做法：
    // 只在按下/抬起/取消时改一个布尔，不拦截事件（不改写任何 event，纯观察）。
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "privilege_card_press",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            // 纯观察按下态，不消费事件（不调 consume），否则会抢掉 Card 自己的点击。
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                pressed = event.changes.any { it.pressed }
                            }
                        }
                    }
                },
            ),
        colors = CardDefaults.defaultColors(color = containerColor),
        insideMargin = PaddingValues(0.dp),
        onClick = onClick,
        showIndication = onClick != null,
        pressFeedbackType = if (onClick != null) {
            PressFeedbackType.Tilt
        } else {
            PressFeedbackType.None
        },
    ) {
        Box(Modifier.fillMaxWidth()) {
            // ── 背景图标（右下角出血）────────────────────────────────
            // matchParentSize + offset：图标不参与测量，卡片高度完全由文字决定。
            // 若用普通 size()，170dp 的图标会把卡片撑高 —— 上游这里正是
            // matchParentSize，不能改成固定尺寸。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 50.dp, y = 38.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    modifier = Modifier.size(170.dp),
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = iconTint,
                )
            }

            // ── 三段文字 ────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textContentColor,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = description,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = descTextColor,
                )
                // 36dp 是上游的固定间隔：给背景图标留出视觉呼吸区。
                // 不写 Spacer，直接靠 Column 的 arrangement，会让
                // 「有 detail / 无 detail」两种形态的高度差变得不受控。
                Spacer(Modifier.height(36.dp))
                if (statusDetail != null) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = statusDetail,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = descTextColor,
                    )
                } else {
                    // 上游在「无第三段」时补 16dp，避免卡片高度塌下去太多。
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * 小统计卡（照抄 InstallerX `MiuixStatCard`）：一行标题 + 一个大数字。
 *
 * 与 [PrivilegeStatusCard] 配套使用：大卡给「结论」，小卡给「两个可量化的细节」。
 * 上游配色：标题 `onSurfaceVariantSummary` 15sp Medium，数值 `onSurface` 26sp SemiBold。
 */
@Composable
fun PrivilegeStatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
        showIndication = onClick != null,
        pressFeedbackType = if (onClick != null) {
            PressFeedbackType.Tilt
        } else {
            PressFeedbackType.None
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = title,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 并排两个小卡（照抄上游第二行布局：等宽、等高、12dp 间距）。 */
@Composable
fun PrivilegeStatRow(
    leftTitle: String,
    leftValue: String,
    rightTitle: String,
    rightValue: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrivilegeStatCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            title = leftTitle,
            value = leftValue,
        )
        PrivilegeStatCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            title = rightTitle,
            value = rightValue,
        )
    }
}