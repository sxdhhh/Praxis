package dev.praxis.feature.settings.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.plugin.InstalledEntryPoint
import dev.praxis.feature.settings.common.SettingsList
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 兼容插件入口。
 *
 * ## 为什么单独一页，而不是塞进「扩展包」
 *
 * Operit 的包可以在主脚本里注册 UI 入口：
 *
 * | 注册函数 | 产物 |
 * |---|---|
 * | registerToolPkgNavigationEntry | 导航入口 |
 * | registerToolPkgUiRoute | 一个完整页面 |
 * | registerToolPkgToolboxUiModule | 工具箱卡片 |
 * | registerToolPkgDesktopWidget | 桌面组件 |
 *
 * 这些是**每个包自己的界面**。混进「扩展包」列表会让那一页变成
 * 「包信息 + 一堆第三方按钮」——装卸包与使用入口是两件事，
 * 混在一起两边都读不清。
 *
 * ## 为什么停用的包也列出来
 *
 * 「我装了但没出现」是这类功能最常见的问题。把停用包的入口一并列出并标注，
 * 用户一眼能分清「没启用」和「包坏了」—— 后者才是需要他去处理的。
 *
 * ## 为什么现在没有「打开」按钮
 *
 * 打开入口要把它挂到运行时宿主（WebView 里跑包脚本）上，那是独立一步。
 * 在这一步之前放一个点了没反应的按钮，比不放更糟：用户会以为功能坏了。
 * 所以这一页现在只回答「有哪些入口、来自谁、脚本在哪」。
 */
@Composable
fun CompatPluginEntryScreen(
    points: List<InstalledEntryPoint>,
    modifier: Modifier = Modifier,
) {
    SettingsList(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "__title__") {
            SmallTitle("兼容插件入口（${points.size}）")
        }

        if (points.isEmpty()) {
            item(key = "__empty__") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "还没有插件声明 UI 入口。\n\n" +
                            "在包的清单里加一段 entries 即可被收进来：\n" +
                            "entries: [\n" +
                            "  { id: my_panel, kind: navigation, title: 我的面板, entry: ui/panel.js }\n" +
                            "]\n\n" +
                            "kind 支持 navigation / ui_route / toolbox / widget。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        items(points, key = { it.key }) { point ->
            EntryRow(point, modifier = Modifier.animateItem())
        }
    }
}

/** 单个入口一行。左列是「入口是谁」，右列只在不可用时出现。 */
@Composable
private fun EntryRow(
    point: InstalledEntryPoint,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = point.title,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = point.pluginTitle + " · " + point.point.kind.label,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = point.point.entry,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (!point.enabled) {
                Text(
                    text = "未启用",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
