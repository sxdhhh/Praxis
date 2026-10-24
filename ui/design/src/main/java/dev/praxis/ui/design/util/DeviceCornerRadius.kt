package dev.praxis.ui.design.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 读取设备物理屏幕圆角半径。
 *
 * ## 为什么需要它
 *
 * `NavDisplayEffects.cornerClipRadius` 决定「转场中的页面按多大圆角裁切」。
 * 写死一个值会在两种设备上都错：
 *  - 圆角屏（主流现代机型）：写死 32dp 与真实屏幕角对不齐，边缘出现
 *    「页面圆角在屏幕圆角之内」的双层弧线；
 *  - 直角屏：写死非 0 会让滑入的页面凭空多出圆角，与静止状态不一致。
 *
 * 读真值后两种情况都自洽：圆角屏得到真实值，直角屏得到 [defaultRadius]（默认 0.dp）。
 *
 * ## 两条取值路径（照 InstallerX `CornerRadiusUtil`）
 *
 * 1. **API 31+**：公开 API `WindowInsets.getRoundedCorner(...)`，按左上/右上/
 *    左下/右下依次尝试（有的设备只报部分角）。
 * 2. **API 30 及以下**：没有公开 API，退回读系统资源
 *    `android:dimen/rounded_corner_radius_bottom`（小米文档给的口子），
 *    再退回 `rounded_corner_radius`。
 *
 * 两条都拿不到时返回 [defaultRadius]，不猜。
 *
 * @param defaultRadius 设备不报圆角时的兜底值。默认 0.dp（直角屏语义）。
 */
@Composable
fun rememberDeviceCornerRadius(defaultRadius: Dp = 0.dp): Dp {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current

    return remember(context, view, density, defaultRadius) {
        val radiusPx = deviceCornerRadiusPx(context, view)
        if (radiusPx > 0) {
            with(density) { radiusPx.toDp() }
        } else {
            defaultRadius
        }
    }
}

/** 像素值。两条路径依次尝试，拿不到返回 0。 */
private fun deviceCornerRadiusPx(context: Context, view: View): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val publicRadius = roundedCornerRadiusPx(view)
        if (publicRadius > 0) return publicRadius
    }
    return cornerRadiusFromResources(context)
}

/** API 31+ 的公开 API 路径。 */
private fun roundedCornerRadiusPx(view: View): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0

    val insets = view.rootWindowInsets ?: return 0

    // 四个角依次尝试：部分设备只上报其中几个（例如只报底部）。
    val corner = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        ?: insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
        ?: insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
        ?: insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)

    return corner?.radius ?: 0
}

/**
 * 低版本资源路径。
 *
 * `DiscouragedApi` 是因为 `getIdentifier` 按名字查系统资源 —— 系统改动名字
 * 就会失效。但这条路上没有更好的办法，且失效时会安全地返回 0。
 */
@SuppressLint("DiscouragedApi")
private fun cornerRadiusFromResources(context: Context): Int {
    val resources = context.resources

    val bottomId = resources.getIdentifier(
        "rounded_corner_radius_bottom",
        "dimen",
        "android",
    )
    if (bottomId > 0) return resources.getDimensionPixelSize(bottomId)

    val generalId = resources.getIdentifier(
        "rounded_corner_radius",
        "dimen",
        "android",
    )
    return if (generalId > 0) resources.getDimensionPixelSize(generalId) else 0
}