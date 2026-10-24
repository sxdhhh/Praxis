package dev.praxis.feature.settings.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.settings.ColorSource
import dev.praxis.core.model.settings.ThemeSettings
import dev.praxis.ui.widgets.ChatDisplayStyle
import kotlinx.coroutines.launch

/**
 * 外观页的接线层：读主题设置 + 六个写入口。
 *
 * ## 为什么从 `SettingsHost.kt` 搬出来
 *
 * 上游 InstallerX 的分包是「一个页面一个子目录」（`settings/preferred/theme/`、
 * `settings/preferred/about/`、`settings/preferred/lab/` …），每个目录里放
 * 页面、Widgets、ViewModel、Action/State。Praxis 原来把这四个 Route 函数
 * 全塞在 `SettingsHost.kt` 里，结果是**分发器同时承担了四个页面的接线**，
 * 300 行里只有 30 行是分发。
 *
 * 搬出来后 `SettingsHost` 只剩「Route → 页面」的穷举表，
 * 加页面时改一处、读代码时不必翻过别人的接线。
 *
 * ## 为什么六个回调都在这里 `scope.launch`
 *
 * `SettingsRepository` 的写接口是 `suspend`（要落 DataStore）。
 * 页面不持有 scope，否则每页都要自己 `rememberCoroutineScope`，
 * 组合销毁时挂起的写会被取消。放在接线层，写操作跟随页面组合生命周期，
 * 语义与「点了就写」一致。
 */
@Composable
fun AppearanceRoute(
    repository: SettingsRepository,
    modifier: Modifier,
) {
    val settings by repository.themeSettings.collectAsState(initial = ThemeSettings())
    // 对话显示风格与主题设置分开读：它不在 `ThemeSettings` 里
    // （那个模型在 `core:model`，不能引用 `ui:widgets` 的 UI 枚举）。
    val chatStyleRaw by repository.chatDisplayStyle.collectAsState(initial = "cursor")
    val scope = rememberCoroutineScope()

    AppearanceScreen(
        settings = settings,
        onModeChange = { scope.launch { repository.setThemeMode(it) } },
        onColorSourceChange = { scope.launch { repository.setColorSource(it) } },
        // 色卡选中：写 seed AND 切到 Custom。
        //
        // ## 为什么一次写两个键
        //
        // 只写 seed 不改 colorSource 时，若用户原本是「跟随壁纸」，
        // 色卡网格根本不可见（AnimatedVisibility 收起），此路径走不到。
        // 但反过来：用户点色卡的瞬间一定是想用自定义色，
        // 若某天网格改成常显，只写 seed 会留下「选了色但不生效」的困惑。
        // 两次写都在同一个协程里，DataStore 的事务性足够。
        onSeedColorChange = { argb ->
            scope.launch {
                repository.setSeedColor(argb)
                repository.setColorSource(ColorSource.Custom)
            }
        },
        onPaletteChange = { scope.launch { repository.setPaletteStyle(it) } },
        onColorSpecChange = { scope.launch { repository.setColorSpec(it) } },
        onPredictiveBackChange = { scope.launch { repository.setPredictiveBackAnimation(it) } },
        onPredictiveBackDirectionChange = {
            scope.launch { repository.setPredictiveBackExitDirection(it) }
        },
        chatDisplayStyle = ChatDisplayStyle.fromStorage(chatStyleRaw),
        onChatDisplayStyleChange = {
            scope.launch { repository.setChatDisplayStyle(it.toStorage()) }
        },
        modifier = modifier,
    )
}
