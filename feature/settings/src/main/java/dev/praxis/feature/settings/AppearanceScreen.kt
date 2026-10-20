package dev.praxis.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.model.settings.ColorSource
import dev.praxis.core.model.settings.PaletteStyle
import dev.praxis.core.model.settings.ThemeMode
import dev.praxis.core.model.settings.ThemeSettings
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.MiuixSelectionDialog
import dev.praxis.ui.widgets.MiuixSwitchWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 外观设置页。
 *
 * 分组模式照抄 InstallerX 的 `MiuixThemeSettingsPage`：
 *  `SmallTitle` 当分组标题，`Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp))`
 *  包裹一组行，行与行之间不额外加分隔线（miuix 的 BasicComponent 自带行距与按压反馈）。
 *
 * 三个维度分开暴露：
 *  - 亮暗模式（跟随/亮/暗）—— 最高频；
 *  - 取色源（跟随壁纸/自定义）—— 直接决定 keyColor 是否为 null；
 *  - 调色板（9 种）—— 只有前两者确定后才有意义，放最后。
 *
 * 调色板是 9 种不是 10 种：miuix 0.9.4 的 ThemePaletteStyle 无 Cmf 系。
 */
@Composable
fun AppearanceScreen(
    settings: ThemeSettings,
    onModeChange: (ThemeMode) -> Unit,
    onColorSourceChange: (ColorSource) -> Unit,
    onPaletteChange: (PaletteStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showModeDialog by remember { mutableStateOf(false) }
    var showPaletteDialog by remember { mutableStateOf(false) }

    SettingsList(
        modifier = modifier.fillMaxSize(),
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        item { SmallTitle("显示模式") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "亮暗模式",
                    description = settings.mode.displayName,
                    onClick = { showModeDialog = true },
                )
            }
        }

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
                    checked = settings.colorSource == ColorSource.Wallpaper,
                    onCheckedChange = { follow ->
                        onColorSourceChange(
                            if (follow) ColorSource.Wallpaper else ColorSource.Custom,
                        )
                    },
                )
                MiuixNavigationItemWidget(
                    title = "调色板风格",
                    description = settings.paletteStyle.displayName,
                    onClick = { showPaletteDialog = true },
                )
            }
        }

        item { SmallTitle("当前配置") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = "模式：${settings.mode.displayName}\n" +
                        "取色源：${settings.colorSource.displayName}\n" +
                        "调色板：${settings.paletteStyle.displayName}\n" +
                        "规范：${settings.colorSpec.name}",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (showModeDialog) {
        val dialogState = remember { mutableStateOf(true) }
        MiuixSelectionDialog(
            showState = dialogState,
            title = "亮暗模式",
            options = ThemeMode.entries,
            selected = settings.mode,
            optionLabel = { it.displayName },
            onSelect = onModeChange,
        )
        if (!dialogState.value) showModeDialog = false
    }

    if (showPaletteDialog) {
        val dialogState = remember { mutableStateOf(true) }
        MiuixSelectionDialog(
            showState = dialogState,
            title = "调色板风格",
            options = PaletteStyle.entries,
            selected = settings.paletteStyle,
            optionLabel = { it.displayName },
            onSelect = onPaletteChange,
        )
        if (!dialogState.value) showPaletteDialog = false
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
