package dev.praxis.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.model.settings.ColorSource
import dev.praxis.core.model.settings.PaletteStyle
import dev.praxis.core.model.settings.ThemeMode
import dev.praxis.core.model.settings.ThemeSettings
import dev.praxis.ui.widgets.MiuixDropdown
import dev.praxis.ui.widgets.MiuixSwitchWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 外观设置页。
 *
 * 结构照抄 InstallerX 的 `MiuixThemeSettingsPage`（632 行那份）：
 *
 * ```
 * SmallTitle(分组) → Card(h=12, b=12) { 控件直排 }
 * ```
 * 关键细节都按上游来：
 *  - **行与行之间不加分隔线**：miuix 的 BasicComponent 自带行距与按压反馈，
 *    再画 `HorizontalDivider` 会出现双线；
 *  - **条件项用 `AnimatedVisibility` 展开/收起**，不是直接出现/消失：
 *    上游「开启自定义取色」后才显示动态取色那一行，用的就是
 *    `fadeIn() + expandVertically()` / `fadeOut() + shrinkVertically()`；
 *  - **选择器用内联下拉**（上游 `WindowSpinnerPreference`），不弹全屏对话框：
 *    设置页里弹窗会丢失上下文，改完不知道自己在哪一项。
 *
 * ## 三个维度为什么这样排
 *
 *  - 亮暗模式：最高频，放第一组；
 *  - 取色源：决定 keyColor 是否为 null（「跟随壁纸」必须 null），影响面最大；
 *  - 调色板：只有取色源确定后才有意义，且默认折叠在前者之下。
 *
 * 调色板是 9 种不是 10 种：miuix 0.9.4 的 `ThemePaletteStyle` 无 Cmf 系。
 */
@Composable
fun AppearanceScreen(
    settings: ThemeSettings,
    onModeChange: (ThemeMode) -> Unit,
    onColorSourceChange: (ColorSource) -> Unit,
    onPaletteChange: (PaletteStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)
    val palettes = PaletteStyle.entries.toList()
    val followWallpaper = settings.colorSource == ColorSource.Wallpaper

    SettingsList(modifier = modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(12.dp)) }

        // ── 显示模式 ────────────────────────────────────────────────
        item { SmallTitle("显示模式") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixDropdown(
                    items = modes.map { it.displayName },
                    selectedIndex = modes.indexOf(settings.mode).coerceAtLeast(0),
                    onSelectedIndexChange = { index -> onModeChange(modes[index]) },
                )
            }
        }

        // ── 取色 ────────────────────────────────────────────────────
        item { SmallTitle("取色") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixSwitchWidget(
                    title = "跟随壁纸取色",
                    description = "关闭后使用自定义品牌色",
                    checked = followWallpaper,
                    onCheckedChange = { follow ->
                        onColorSourceChange(
                            if (follow) ColorSource.Wallpaper else ColorSource.Custom,
                        )
                    },
                )
                // 只有「自定义」时调色板才有意义：跟随壁纸时系统按壁纸算调色板，
                // 手动选会被覆盖 —— 直接隐藏比「选了但没效果」诚实。
                AnimatedVisibility(
                    visible = !followWallpaper,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    MiuixDropdown(
                        items = palettes.map { it.displayName },
                        selectedIndex = palettes.indexOf(settings.paletteStyle).coerceAtLeast(0),
                        onSelectedIndexChange = { index -> onPaletteChange(palettes[index]) },
                    )
                }
            }
        }
    }
}

/** 用户可见文案（不写进 core 枚举，避免 core 层夹带 UI 文案）。 */
internal val ThemeMode.displayName: String
    get() = when (this) {
        ThemeMode.System -> "跟随系统"
        ThemeMode.Light -> "浅色"
        ThemeMode.Dark -> "深色"
    }

internal val ColorSource.displayName: String
    get() = when (this) {
        ColorSource.Wallpaper -> "跟随壁纸"
        ColorSource.Custom -> "自定义色"
    }

internal val PaletteStyle.displayName: String
    get() = when (this) {
        PaletteStyle.TonalSpot -> "色调点缀"
        PaletteStyle.Neutral -> "中性"
        PaletteStyle.Vibrant -> "鲜艳"
        PaletteStyle.Expressive -> "表现力"
        PaletteStyle.Content -> "内容"
        PaletteStyle.Fidelity -> "保真"
        PaletteStyle.Monochrome -> "单色"
        PaletteStyle.Rainbow -> "彩虹"
        PaletteStyle.FruitSalad -> "水果沙拉"
    }