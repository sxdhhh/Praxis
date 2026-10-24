// SPDX-License-Identifier: GPL-3.0-only
package dev.praxis.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Praxis 的图标集合：全项目**唯一**的图标来源。
 *
 * ## 为什么从 `:app` 搬到独立模块
 *
 * 旧位置是 `app/src/main/java/dev/praxis/ui/icons/`。`:app` 是组装层，
 * feature 模块不能反过来依赖它 —— 但 feature 又是真正画图标的地方
 * （底栏在 `:app`，而「工具」「关于」这类图标在 feature 页里要用）。
 * 图标留在 `:app` 就直接把「谁都能用」变成了「只有 `:app` 能用」。
 *
 * 与 InstallerX 的 `ui/icons` 同位：图标是纯资源映射，无状态、无依赖，
 * 单独一个模块零成本。
 *
 * ## 载体
 *
 * 统一用 outlined 变体：miuix 的控件外观是细线风格，实心图标放在
 * 同一行里会显得过重。命名用语义名（`Chat` / `Document`）而不是
 * 图形名（`ChatBubbleOutline`），换载体时调用处不用改。
 */
object PraxisIcons {

    // ── 底栏 Tab ────────────────────────────────────────────
    val Chat: ImageVector = Icons.Outlined.ChatBubbleOutline
    val Terminal: ImageVector = Icons.Outlined.Terminal
    val Memory: ImageVector = Icons.Outlined.Memory
    val Settings: ImageVector = Icons.Outlined.Settings

    // ── 关于页 ──────────────────────────────────────────────
    /** 「关于 Praxis」信息块。 */
    val Info: ImageVector = Icons.Outlined.Info

    /** 开源许可。 */
    val Document: ImageVector = Icons.Outlined.Description

    // ── 其它域 ──────────────────────────────────────────────
    val Tools: ImageVector = Icons.Outlined.Build

    /**
     * Praxis 自己的单色 Logo（关于页 hero 用）。
     *
     * ## 为什么是手写 `ImageVector` 而不是引资源
     *
     * 关于页在 `:feature:settings`（库模块），它的 `R` 里**没有** `app` 模块的
     * `drawable/ic_launcher_foreground`（Gradle 的 `R` 按模块隔离）。
     * 三条路里只有这条没有代价：
     *
     *  - 在 `feature/settings` 新建 `res/drawable` 拷一份 → 同一图形存两份，
     *    改一处要改两处；
     *  - 给 `:ui:icons` 加 res 目录 → 为了一个图标引入资源管线，且它现在
     *    全是 `ImageVector`，加 res 反而破坏一致性；
     *  - **手写 `ImageVector`** → 零资源、零重复，与本文其余条目同构。
     *
     * 图形与 `app/src/main/res/drawable/ic_launcher_foreground.xml` 的 path 完全一致
     * （那个 path 本身就是个「P」形，正好是 Praxis 的首字母），
     * 语义对应上游 `/root/ref-InstallerX` 的 `ic_launcher_monochrome`。
     *
     * `by lazy`：`ImageVector` 构建要解析 path 字符串，不该在每次读属性时重做。
     */
    val Logo: ImageVector by lazy {
        ImageVector.Builder(
            name = "PraxisLogo",
            defaultWidth = 108.dp,
            defaultHeight = 108.dp,
            viewportWidth = 108f,
            viewportHeight = 108f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    "M38,30h18c9,0 15,5 15,13c0,6 -3,10 -8,12l9,17h-11l-8,-15h-4v15h-11z" +
                        "M49,39v10h7c3,0 4,-2 4,-5c0,-3 -1,-5 -4,-5z",
                ).toNodes(),
                // 白色是「单色图标」的约定色：实际渲染色由 `Image`/`Icon` 的
                // `tint`（或 textureBlur 的 blend）决定，这里只是 mask 的填充。
                fill = SolidColor(Color.White),
            )
        }.build()
    }
}
