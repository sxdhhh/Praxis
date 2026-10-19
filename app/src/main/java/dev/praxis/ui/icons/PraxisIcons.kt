package dev.praxis.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Praxis 的图标集合：统一用 outlined 变体，避免与 miuix 的实心图标风格冲突。 */
object PraxisIcons {
    val Chat: ImageVector = Icons.Outlined.ChatBubbleOutline
    val Tools: ImageVector = Icons.Outlined.Build
    val Memory: ImageVector = Icons.Outlined.Memory
    val Settings: ImageVector = Icons.Outlined.Settings
}