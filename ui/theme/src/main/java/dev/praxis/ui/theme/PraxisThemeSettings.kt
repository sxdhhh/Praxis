package dev.praxis.ui.theme

// 类型已下沉到 core:model（core 层不得依赖 ui 层，而设置仓储在 core:data）。
// 这里只做 Compose 侧的转换，不再重复定义枚举。

import androidx.compose.ui.graphics.Color
import dev.praxis.core.model.settings.ColorSource
import dev.praxis.core.model.settings.ThemeSettings

/** 需要传给 miuix 取色器的 seed；跟随壁纸时为 null（否则永远不读系统壁纸）。 */
val ThemeSettings.keyColorOrNull: Color?
    get() = if (colorSource == ColorSource.Custom) Color(seedColorArgb) else null
