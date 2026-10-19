package dev.praxis.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/** 设置首页可进入的条目。 */
enum class SettingsEntry(val key: String, val title: String, val summary: String) {
    Appearance("appearance", "外观", "主题、取色与调色板"),
    Tools("tools", "工具", "已注册工具与启用开关"),
    Model("model", "模型", "服务商、密钥与参数"),
    Speech("speech", "语音", "TTS 与语音输入"),
    Permission("permission", "权限", "工具调用的授权策略"),
    Packages("packages", "扩展包", "工具包与插件"),
    Log("log", "日志", "运行日志与诊断"),
    About("about", "关于", "版本、致谢与开源许可"),
    ;

    companion object {
        fun byKey(key: String?): SettingsEntry? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 设置首页。
 *
 * 分组用 `SmallTitle` + `Card`，与外观页一致（照抄 InstallerX 的设置页模式）。
 * 只列出**已经能进**的条目：外观现在有真实页面，其余先列出来但点击直达占位由上层处理，
 * 避免出现「点了没反应」的死入口。
 */
@Composable
fun SettingsScreen(
    onOpen: (SettingsEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        item { SmallTitle("通用") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = SettingsEntry.Appearance.title,
                    description = SettingsEntry.Appearance.summary,
                    onClick = { onOpen(SettingsEntry.Appearance) },
                )
                MiuixNavigationItemWidget(
                    title = SettingsEntry.Log.title,
                    description = SettingsEntry.Log.summary,
                    onClick = { onOpen(SettingsEntry.Log) },
                )
            }
        }

        item { SmallTitle("能力") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = SettingsEntry.Tools.title,
                    description = SettingsEntry.Tools.summary,
                    onClick = { onOpen(SettingsEntry.Tools) },
                )
                MiuixNavigationItemWidget(
                    title = SettingsEntry.Model.title,
                    description = SettingsEntry.Model.summary,
                    onClick = { onOpen(SettingsEntry.Model) },
                )
                MiuixNavigationItemWidget(
                    title = SettingsEntry.Speech.title,
                    description = SettingsEntry.Speech.summary,
                    onClick = { onOpen(SettingsEntry.Speech) },
                )
                MiuixNavigationItemWidget(
                    title = SettingsEntry.Permission.title,
                    description = SettingsEntry.Permission.summary,
                    onClick = { onOpen(SettingsEntry.Permission) },
                )
            }
        }

        item { SmallTitle("其他") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = SettingsEntry.Packages.title,
                    description = SettingsEntry.Packages.summary,
                    onClick = { onOpen(SettingsEntry.Packages) },
                )
                MiuixNavigationItemWidget(
                    title = SettingsEntry.About.title,
                    description = SettingsEntry.About.summary,
                    onClick = { onOpen(SettingsEntry.About) },
                )
            }
        }
    }
}
