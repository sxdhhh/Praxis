package dev.praxis.feature.settings.appearance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.model.settings.ColorSource
import dev.praxis.core.model.settings.ColorSpecVersion
import dev.praxis.core.model.settings.PaletteStyle
import dev.praxis.core.model.settings.PredictiveBackAnimation
import dev.praxis.core.model.settings.PredictiveBackExitDirection
import dev.praxis.core.model.settings.ThemeMode
import dev.praxis.core.model.settings.ThemeSettings
import dev.praxis.feature.settings.common.SettingsList
import dev.praxis.ui.widgets.ChatDisplayStyle
import dev.praxis.ui.widgets.MiuixSwitchWidget
import dev.praxis.ui.widgets.SeedColorGrid
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 外观设置页。**结构 1:1 照 InstallerX `MiuixThemeSettingsPage.kt`（632 行）**。
 *
 * ## 与上游的分组对照
 *
 * | 上游分组 | Praxis | 理由 |
 * |---|---|---|
 * | UI 引擎（miuix / M3） | 不做 | 全项目只有 miuix 一个引擎，一选一的开关是假 UI |
 * | Apple 浮动底栏 | 不做 | 底栏已固定为 FloatingBottomBar，没有第二种可选 |
 * | miuix UI：主题模式 / 模糊 / 自定义颜色 / 动态取色 / 调色板 / 颜色规范 / 图标取色 | **做**（见下） | 全部真实生效 |
 * | 主题色（色卡网格） | **做** | 上游最核心的一块，Praxis 之前完全没有 |
 * | 预测性返回（动画 / 退场方向） | **做** | Praxis 的返回转场可切换档位 |
 * | 应用图标 | 不做 | Praxis 无图标包体系 |
 *
 * ## 必须照抄的三个版式细节
 *
 *  1. **`AnimatedVisibility` 用 `FastOutSlowInEasing` + 300/400 与 250/350 两组时长**
 *     （进入比退出慢）。默认的 `tween()` 是 LinearEasing，展开会显得「弹」。
 *  2. **色卡放在独立的 `item { AnimatedVisibility { Column { SmallTitle; Card } } }`**，
 *     不是包在上一张 Card 里 —— 上游如此，因为色卡网格很高，混在同组会撑爆卡片圆角。
 *  3. **`OverlaySpinnerPreference` 而不是 `WindowSpinnerPreference`**：前者在根
 *     Scaffold 的 overlay 层同层渲染，后者开新 Window。Praxis 是单 Activity +
 *     NavDisplay 结构，开新 Window 会与宿主抢焦点 → 真机闪退。
 *
 * ## 色卡为什么不是「只显示当前色」
 *
 * 旧版这一行只读展示 seed 并注明「取色器待做」。但「自定义色」一旦选中，
 * 生效的就是某个 seed，用户必须能**改**它 —— 只显示不给改，等于这一整组设置
 * 只能靠默认值。这一轮补上色卡网格（[SeedColorGrid]，算法见其 KDoc）。
 */
@Composable
fun AppearanceScreen(
    settings: ThemeSettings,
    onModeChange: (ThemeMode) -> Unit,
    onColorSourceChange: (ColorSource) -> Unit,
    onSeedColorChange: (Long) -> Unit,
    onPaletteChange: (PaletteStyle) -> Unit,
    onColorSpecChange: (ColorSpecVersion) -> Unit,
    onPredictiveBackChange: (PredictiveBackAnimation) -> Unit,
    onPredictiveBackDirectionChange: (PredictiveBackExitDirection) -> Unit,
    onChatDisplayStyleChange: (ChatDisplayStyle) -> Unit,
    /**
     * 当前对话显示风格。
     *
     * 单独一个参数而不是塞进 [settings]（`AppearanceSettings`）：
     * 那个模型在 `core:model`，而 [ChatDisplayStyle] 在 `ui:widgets`。
     * 让 core 模型去引用 UI 枚举会造成 core → ui 的反向依赖。
     */
    chatDisplayStyle: ChatDisplayStyle,
    modifier: Modifier = Modifier,
) {
    val followWallpaper = settings.colorSource == ColorSource.Wallpaper
    // 色卡用亮档还是暗档：跟随当前主题模式，而不是固定亮色。
    // 上游固定亮档（isDarkForPreview = false），在深色主题下会刺眼。
    val dark = when (settings.mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    SettingsList(modifier = modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(12.dp)) }

        // ── 主题模式 ────────────────────────────────────────────────
        item { SmallTitle("miuix 界面") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SpinnerRow(
                    title = "主题模式",
                    current = settings.mode,
                    options = ThemeMode.entries.toList(),
                    labelOf = { it.displayName },
                    onSelect = onModeChange,
                )

                // 「跟随壁纸取色」是开关而不是两个选项：
                // miuix 的 `ThemeController.currentColors()` 在 keyColor == null 时
                // 读壁纸，传了 keyColor 就永远不读（见 PraxisTheme 的注释）。
                // 所以这个开关实际决定 keyColor 是否为 null。
                MiuixSwitchWidget(
                    title = "跟随壁纸取色",
                    description = "关闭后使用下方选定的品牌色",
                    checked = followWallpaper,
                    onCheckedChange = { follow ->
                        onColorSourceChange(
                            if (follow) ColorSource.Wallpaper else ColorSource.Custom,
                        )
                    },
                )

                // ── 仅自定义取色时可见 ──────────────────────────────────
                // 曲线与时长照抄上游（FastOutSlowInEasing，进 300/400、出 250/350），
                // 比默认 tween 慢一点，观感更稳。
                AnimatedVisibility(
                    visible = !followWallpaper,
                    enter = fadeIn(
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                    ) + expandVertically(
                        animationSpec = tween(400, easing = FastOutSlowInEasing),
                    ),
                    exit = fadeOut(
                        animationSpec = tween(250, easing = FastOutSlowInEasing),
                    ) + shrinkVertically(
                        animationSpec = tween(350, easing = FastOutSlowInEasing),
                    ),
                ) {
                    Column {
                        SpinnerRow(
                            title = "调色板",
                            current = settings.paletteStyle,
                            options = PaletteStyle.entries.toList(),
                            labelOf = { it.displayName },
                            onSelect = onPaletteChange,
                        )
                        SpinnerRow(
                            title = "颜色规范",
                            current = settings.colorSpec,
                            options = ColorSpecVersion.entries.toList(),
                            labelOf = { it.displayName },
                            onSelect = onColorSpecChange,
                        )
                        Hint(
                            "颜色规范仅对色调点缀 / 中性 / 鲜艳 / 表现力四种调色板生效，" +
                                "其余调色板会自动回落到 2021 规范。",
                        )
                    }
                }
            }
        }

        // ── 主题色：色卡网格（独立 item，不混入上面的 Card）────────────
        item {
            AnimatedVisibility(
                visible = !followWallpaper,
                enter = fadeIn(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ) + expandVertically(
                    animationSpec = tween(400, easing = FastOutSlowInEasing),
                ),
                exit = fadeOut(
                    animationSpec = tween(250, easing = FastOutSlowInEasing),
                ) + shrinkVertically(
                    animationSpec = tween(350, easing = FastOutSlowInEasing),
                ),
            ) {
                Column {
                    SmallTitle("主题色")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SeedColorGrid(
                            selectedArgb = settings.seedColorArgb,
                            dark = dark,
                            onSelect = onSeedColorChange,
                        )
                    }
                }
            }
        }

        // ── 预测性返回 ──────────────────────────────────────────────
        item { SmallTitle("预测性返回") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SpinnerRow(
                    title = "返回动画",
                    current = settings.predictiveBackAnimation,
                    options = PredictiveBackAnimation.entries.toList(),
                    labelOf = { it.displayName },
                    onSelect = onPredictiveBackChange,
                )
                // 退场方向只对 Scale 有意义：AOSP 由手势方向决定，
                // Classic 是固定轨迹，Miuix 是整页平移。给了也没用 → 隐藏。
                AnimatedVisibility(
                    visible = settings.predictiveBackAnimation == PredictiveBackAnimation.Scale,
                    enter = fadeIn(
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                    ) + expandVertically(
                        animationSpec = tween(400, easing = FastOutSlowInEasing),
                    ),
                    exit = fadeOut(
                        animationSpec = tween(250, easing = FastOutSlowInEasing),
                    ) + shrinkVertically(
                        animationSpec = tween(350, easing = FastOutSlowInEasing),
                    ),
                ) {
                    SpinnerRow(
                        title = "退场方向",
                        current = settings.predictiveBackExitDirection,
                        options = PredictiveBackExitDirection.entries.toList(),
                        labelOf = { it.displayName },
                        onSelect = onPredictiveBackDirectionChange,
                    )
                }
                Hint(predictiveBackHint(settings.predictiveBackAnimation))
            }
        }

        // ── 对话样式 ────────────────────────────────────────────────
        //
        // 对标 Operit 的 `chat_style`（主题设置 → 对话）。它的两种风格
        // 在形态上完全不同，不只是配色：
        //
        //  - Cursor：用户消息占满整行（8dp 圆角 + 「提问」标签），
        //            助手消息**无容器**直接铺在页面底色上（「回复」标签）；
        //  - Bubble：用户右对齐蓝底、助手左对齐灰底，都是 18dp 圆角气泡。
        //
        // 放在「外观」页而不是「模型」页：它改变的是渲染形态，与模型无关。
        item { SmallTitle("对话样式") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SpinnerRow(
                    title = "显示风格",
                    current = chatDisplayStyle,
                    options = ChatDisplayStyle.entries.toList(),
                    labelOf = { it.label },
                    onSelect = onChatDisplayStyleChange,
                )
                Hint(chatDisplayStyleHint(chatDisplayStyle))
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * 风格的中文名。
 *
 * 文案放在设置页而不是枚举里：`ui:widgets` 的枚举要同时被聊天页与设置页引用，
 * 枚举里写死中文会让它变成「带界面的模型」，而模型不该管某个页面怎么称呼它。
 */
private val ChatDisplayStyle.label: String
    get() = when (this) {
        ChatDisplayStyle.Cursor -> "Cursor（Operit 默认）"
        ChatDisplayStyle.Bubble -> "气泡"
    }

/**
 * 每种风格的说明。
 *
 * 必须写清「助手消息有没有容器」这一条 —— 那是两种风格唯一的实质差别，
 * 而用户在设置页只看名字（Cursor / 气泡）是猜不出来的。
 */
private fun chatDisplayStyleHint(style: ChatDisplayStyle): String = when (style) {
    ChatDisplayStyle.Cursor ->
        "用户消息占满整行并标注「提问」，助手回复直接铺在页面底色上、不套容器。\n" +
            "长回复与代码块不会被气泡底色挤压，适合读长内容。"
    ChatDisplayStyle.Bubble ->
        "传统聊天气泡：你的消息右对齐蓝底，助手回复左对齐灰底。\n" +
            "每条消息边界更清楚，但长代码块在气泡里换行较多。"
}

/**
 * 一行「标题 + 当前值 + 可点展开」的选择器。
 *
 * ## 为什么统一包一层（上游做法）
 *
 * `OverlaySpinnerPreference` 收 `DropdownItem` 列表 + **索引**，而调用方手里是枚举。
 * 每个调用点各写一遍「枚举 ↔ 索引」映射，就会有人漏掉 `indexOf` 返回 -1 的兜底
 * （-1 会让 miuix 显示空白下拉）。包一层后索引计算与越界兜底只写一次。
 *
 * ## 为什么必须 `renderInRootScaffold = true`
 *
 * 不设时浮层画在组件自身位置附近，会被 `LazyColumn` 的 clip 边界裁掉 ——
 * 长列表里越往下越必然遇到（下拉弹不出来 / 只弹出半截）。
 */
@Composable
private fun <T> SpinnerRow(
    title: String,
    current: T,
    options: List<T>,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val items = remember(options) { options.map { DropdownItem(title = labelOf(it)) } }
    // coerceAtLeast(0)：indexOf 找不到时返回 -1，直接传给 miuix 会显示空值。
    val selectedIndex = remember(current, options) {
        options.indexOf(current).coerceAtLeast(0)
    }

    OverlaySpinnerPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        renderInRootScaffold = true,
        onSelectedIndexChange = { newIndex ->
            options.getOrNull(newIndex)?.takeIf { it != current }?.let(onSelect)
        },
    )
}

/** 一行说明文字。上游同样放在 Card 末尾，用 footnote1 + 次要色。 */
@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
    )
}

// ---------------------------------------------------------------------------
//  文案（不写进 core 枚举，避免 core 层夹带 UI 文案）
// ---------------------------------------------------------------------------

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

internal val ColorSpecVersion.displayName: String
    get() = when (this) {
        ColorSpecVersion.Spec2021 -> "2021 规范"
        ColorSpecVersion.Spec2025 -> "2025 规范"
    }

internal val PredictiveBackAnimation.displayName: String
    get() = when (this) {
        PredictiveBackAnimation.Aosp -> "系统（AOSP）"
        PredictiveBackAnimation.Miuix -> "miuix 默认"
        PredictiveBackAnimation.Scale -> "缩放移出"
        PredictiveBackAnimation.Classic -> "经典缩放"
    }

internal val PredictiveBackExitDirection.displayName: String
    get() = when (this) {
        PredictiveBackExitDirection.FollowGesture -> "跟随手势"
        PredictiveBackExitDirection.AlwaysRight -> "总是往右"
        PredictiveBackExitDirection.AlwaysLeft -> "总是往左"
    }

/** 每一档的一句话说明（写「看起来会怎样」，不写参数名）。 */
private fun predictiveBackHint(animation: PredictiveBackAnimation): String = when (animation) {
    PredictiveBackAnimation.Aosp ->
        "与系统返回一致：手指拖动时页面随手势缩小、让出边缘，松手带回弹。"
    PredictiveBackAnimation.Miuix ->
        "miuix 库自带转场：整页平移。与系统观感差别较大。"
    PredictiveBackAnimation.Scale ->
        "页面以手指位置为轴缩小并移出，露出被盖住的页面。方向可单独设置。"
    PredictiveBackAnimation.Classic ->
        "被盖住的页面从侧面盖上来的经典效果（早期 KernelSU 管理器风格）。"
}