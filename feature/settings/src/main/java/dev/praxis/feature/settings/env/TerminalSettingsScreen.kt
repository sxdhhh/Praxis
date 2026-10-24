package dev.praxis.feature.settings.env

import dev.praxis.feature.settings.SettingsEntry
import dev.praxis.feature.settings.common.SettingsList
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.model.settings.TerminalSettings
import dev.praxis.core.linux.LinuxDiskUsage
import dev.praxis.core.model.linux.VirtualKeyboardLayoutCodec
import dev.praxis.ui.widgets.MiuixSwitchWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 终端设置页（第 12 项缺陷）。
 *
 * ## 这一页存在的理由
 *
 * 用户报的是「终端环境配置与设置指向同一界面、入口重复」。
 *
 * 现场取证（`TerminalScreen.TerminalActionBar`）：
 * 操作条右侧两个控件 —— 「环境配置」胶囊与齿轮图标 —— 都传
 * `onOpenEnvConfig`，而 `:app` 把它接成 `onSettingsEntry(SettingsEntry.Linux)`。
 * 也就是说这两个按钮点下去是**同一页**。齿轮在这个布局里的语义显然是
 * 「终端自己的设置」，跳到 Linux 环境页就是错的；更糟的是没传回调时
 * 齿轮 fallback 到 `onClear`（`onClick = onOpenEnvConfig ?: onClear`），
 * 点齿轮会**清屏**，看起来像误触。
 *
 * 于是分工改成：
 *  - 「环境配置」胶囊 → Linux 环境页（`SettingsEntry.Linux`，容器/镜像/SSH/FRP）；
 *  - 齿轮 → **本页**（字号 / 回滚行数 / 首屏 banner）。
 *
 * ## 为什么这三项而不是更多
 *
 * 这三项是渲染层**真的会读**的值（`TerminalScreen.TerminalTypography`、
 * `TerminalBuffer.maxScrollback`、`TerminalViewModel.writeBanner`）。
 * 加一个没有对应的设置项等于给用户一个假开关 —— 本项目的既有纪律是
 * 「设置项必须接线，否则不给入口」。
 */
@Composable
fun TerminalSettingsScreen(
    settings: TerminalSettings,
    onFontSizeChange: (Float) -> Unit,
    onScrollbackChange: (Int) -> Unit,
    onShowBannerChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 存储管理相关状态。
     *
     * ## 为什么收成一个数据类而不是再加 9 个参数
     *
     * 这一页的参数已经 5 个，再加「占用 / 统计中 / 是否安装 / 忙 / 运行中 /
     * 共享 tmp / 刷新 / 清空 / 重置」会到 13 个 —— 调用点会变成一屏
     * 无法核对的实参列表，漏传一个就是「按钮永远灰着」。
     *
     * 收成一个有默认值的对象后：未装配（单测、预览）时整块存储区
     * 自动隐藏，不需要调用点写 9 个空实现。
     */
    storage: TerminalStorageState = TerminalStorageState(),
) {
    SettingsList(modifier = modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(12.dp)) }

        // ── 存储管理 ────────────────────────────────────────────────
        //
        // 放在最前面（Operit 的顺序也是存储管理在前）。理由：用户点进终端
        // 设置，最常见的两个目的是「终端字太小/太大」和「环境占了多大」。
        // 后者是带数字的、需要立刻看到的东西，压在底部要滑半天。
        if (storage.available) {
            item { SmallTitle("存储管理") }
            item {
                StorageManagementCard(
                    usage = storage.usage,
                    calculating = storage.calculatingUsage,
                    installed = storage.installed,
                    busy = storage.busy,
                    running = storage.running,
                    sharedTmp = storage.sharedTmp,
                    onRefresh = storage.onRefreshUsage,
                    onClearTmp = storage.onClearSharedTmp,
                    onReset = storage.onResetEnvironment,
                )
            }
            item { SmallTitle("临时目录") }
            item {
                SharedTmpCard(
                    enabled = storage.sharedTmp,
                    running = storage.running,
                    onCheckedChange = storage.onSharedTmpChange,
                )
            }
            item { SmallTitle("虚拟键盘") }
            item {
                VirtualKeyboardCard(
                    labels = storage.keyboardLabels,
                    onSave = storage.onSaveKeyboardLabels,
                    onReset = storage.onResetKeyboardLabels,
                )
            }
        }

        // ── 显示 ────────────────────────────────────────────────────
        item { SmallTitle("显示") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                // ## 为什么用 SliderPreference 而不是自己拼 Slider
                //
                // miuix 的 `SliderPreference` = 标题 + 摘要 + 值文本 + 滑杆，
                // 且滑杆放在 `BasicComponent` 的 bottomAction 区（行高、内边距、
                // 按压反馈都与同页其它设置行一致）。
                // 自己拿 `Slider` 拼会得到「标题一行、滑杆一行、间距靠猜」的割裂版。
                //
                // steps：从 9 到 20 共 12 个整数档 → 中间 10 个分割点。
                // 给 steps 后滑杆会**吸附**到整数值，不会出现 13.7sp 这种
                // 用户看不出差别、却让行高按小数算的中间值。
                SliderPreference(
                    value = settings.fontSizeSp,
                    onValueChange = onFontSizeChange,
                    title = "字号",
                    summary = "终端输出的等宽字体大小",
                    valueText = "${settings.fontSizeSp.roundToInt()} sp",
                    valueRange = TerminalSettings.MIN_FONT_SIZE_SP..TerminalSettings.MAX_FONT_SIZE_SP,
                    steps = (TerminalSettings.MAX_FONT_SIZE_SP -
                        TerminalSettings.MIN_FONT_SIZE_SP).toInt() - 1,
                )

                MiuixSwitchWidget(
                    title = "首屏字符画",
                    description = "新建会话时显示 PRAXIS 字符画与提示",
                    checked = settings.showBanner,
                    onCheckedChange = onShowBannerChange,
                )
            }
        }

        // ── 缓冲 ────────────────────────────────────────────────────
        item { SmallTitle("缓冲") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                // 回滚行数按 100 行一档：这个参数的取值只影响「能往上翻多久」，
                // 差 50 行用户完全感知不到，按 100 分档更好拖。
                //
                // steps 的算法：(上限-下限)/每档 - 1。这里 (5000-100)/100 - 1 = 48。
                val step = 100
                val stepCount =
                    (TerminalSettings.MAX_SCROLLBACK_LINES - TerminalSettings.MIN_SCROLLBACK_LINES) / step
                SliderPreference(
                    value = settings.scrollbackLines.toFloat(),
                    onValueChange = { v ->
                        // 吸附到 100 的整数倍：steps 已保证滑杆只落在档位上，
                        // 这里再取整是为了防御「手改 DataStore 后的非整档值」被写回去。
                        val snapped = (v / step).roundToInt() * step
                        onScrollbackChange(snapped)
                    },
                    title = "回滚行数",
                    summary = "超出后最旧的行被丢弃；行数越多占内存越多",
                    valueText = "${settings.scrollbackLines} 行",
                    valueRange = TerminalSettings.MIN_SCROLLBACK_LINES.toFloat()..
                        TerminalSettings.MAX_SCROLLBACK_LINES.toFloat(),
                    steps = stepCount - 1,
                )
            }
        }

        // ── 恢复默认 ────────────────────────────────────────────────
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = "字号、回滚行数与首屏字符画都恢复为默认值。" +
                        "只影响终端页，不动 Linux 环境（容器 / SSH / FRP）的配置。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
                TextButton(
                    text = "恢复默认",
                    onClick = onReset,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/**
 * 终端设置页里「存储 / 临时目录 / 虚拟键盘」三块的状态与回调。
 *
 * ## 为什么 `available` 是**总开关**
 *
 * 这三块能力都依赖 `LinuxEnvPort`（`:app` 注入）。未装配时（单测、
 * `@Preview`、或 DI 没接上）不能显示一堆死按钮 —— 那等于让用户以为
 * 点了会有反应。`available = false` 时整块区域**不渲染**，
 * 页面只剩原有的显示/缓冲设置，与加这个功能之前完全一致。
 *
 * ## 为什么回调都给默认空实现
 *
 * 与 `available` 呼应：没有能力就没有回调，调用点不必写 9 个 `{}`
 * 只为满足签名。默认空实现让「只关心字号」的调用点完全不用改。
 *
 * ## 为什么 `usage` 可空而不是给零值
 *
 * 「没统计过」与「统计出来是 0」是两件事。前者该显示「点刷新统计」，
 * 后者该显示「0 B」。混成 0 会让用户看到「0 B」然后以为环境坏了。
 *
 * @param available 是否装配了 Linux 环境端口。false 时整块隐藏。
 * @param usage 占用快照；null = 未统计。
 * @param calculatingUsage 正在统计。
 * @param installed 是否已安装环境。未安装时存储卡只显示一句说明。
 * @param busy 有长任务在进行（安装 / 启动 / 清理 / 重置）。
 * @param running 容器是否在跑。跑着时清空 tmp 与重置都禁用。
 * @param sharedTmp 共享临时目录是否开启（决定 tmp 行是否显示、清空按钮是否可用）。
 * @param keyboardLabels 当前生效的键帽标签（14 个拍平）。
 * @param onSaveKeyboardLabels 保存自定义标签。
 * @param onResetKeyboardLabels 恢复默认标签。
 */
data class TerminalStorageState(
    val available: Boolean = false,
    val usage: LinuxDiskUsage.EnvironmentUsage? = null,
    val calculatingUsage: Boolean = false,
    val installed: Boolean = false,
    val busy: Boolean = false,
    val running: Boolean = false,
    val sharedTmp: Boolean = false,
    val onRefreshUsage: () -> Unit = {},
    val onClearSharedTmp: () -> Unit = {},
    val onResetEnvironment: () -> Unit = {},
    val onSharedTmpChange: (Boolean) -> Unit = {},
    /**
     * 键帽标签。
     *
     * 默认值取自 `VirtualKeyboardLayoutCodec.DEFAULT_LABELS` 而不是空列表：
     * `TerminalStorageState()` 的默认构造会被单测/预览用到，
     * 那时卡片应显示默认布局而不是一个 0 格键盘。
     */
    val keyboardLabels: List<String> = VirtualKeyboardLayoutCodec.DEFAULT_LABELS,
    val onSaveKeyboardLabels: (List<String>) -> Unit = {},
    val onResetKeyboardLabels: () -> Unit = {},
)