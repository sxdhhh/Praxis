package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 维护页：把账号导入网关 + 保活配置。
 *
 * ## 数据流为什么是这个方向
 *
 * 账号的真身在**网关**里（33 家插件的登录态都在那边）。所以这里是把一份
 * 账号 JSON **推进网关**（POST /api/accounts/import），而不是存进 Praxis 的库。
 * 接受的格式就是网关自己导出的原样文件 —— 用户从旧网关/面板导出的文件直接能进。
 *
 * 保活同理：配置读写都走 /api/checkin-keepalive，Praxis 只是编辑器。
 */
@Composable
fun GatewayMaintenanceScreen(
    /** 保活配置的当前 JSON（:app 从网关拉回来）。 */
    keepaliveJson: String = "",
    /** 导入结果/错误文案。 */
    importResult: String? = null,
    importBusy: Boolean = false,
    /** 保活配置的保存结果。 */
    keepaliveResult: String? = null,
    onImportAccounts: (String) -> Unit = {},
    onRefreshKeepalive: () -> Unit = {},
    onSaveKeepalive: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var importText by remember { mutableStateOf("") }
    var keepaliveText by remember(keepaliveJson) { mutableStateOf(keepaliveJson) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsSectionHeader(
            title = "导入账号到网关",
            subtitle = "粘贴网关导出的账号 JSON（顶层 accounts 数组）",
        )
        TextField(
            value = importText,
            onValueChange = { importText = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            label = "账号 JSON",
            useLabelAsPlaceholder = true,
        )
        Spacer(Modifier.height(8.dp))
        MiuixNavigationItemWidget(
            title = if (importBusy) "导入中…" else "导入到网关",
            description = when {
                importBusy -> "正在提交"
                importText.isBlank() -> "先粘贴账号 JSON"
                else -> "POST /api/accounts/import（原样转交，网关负责去重与合并）"
            },
            onClick = { if (!importBusy && importText.isNotBlank()) onImportAccounts(importText.trim()) },
        )
        importResult?.let { text ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    text = text,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SettingsSectionHeader(
            title = "保活配置",
            subtitle = "GET/POST /api/checkin-keepalive（网关按它维持日活）",
        )
        TextField(
            value = keepaliveText,
            onValueChange = { keepaliveText = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            label = "保活 JSON",
            useLabelAsPlaceholder = true,
        )
        Spacer(Modifier.height(8.dp))
        MiuixNavigationItemWidget(
            title = "重新从网关读取",
            description = "改了想丢弃时点这个",
            onClick = onRefreshKeepalive,
        )
        MiuixNavigationItemWidget(
            title = "保存保活配置",
            description = if (keepaliveText.isBlank()) "内容为空，网关会拒绝" else "写回网关",
            onClick = { if (keepaliveText.isNotBlank()) onSaveKeepalive(keepaliveText.trim()) },
        )
        keepaliveResult?.let { text ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    text = text,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}