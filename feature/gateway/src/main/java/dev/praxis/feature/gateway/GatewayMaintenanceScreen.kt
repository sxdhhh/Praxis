package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Construction
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
 * 账号的真身在**网关**里（33 家插件的登录态都在那边），所以这里是把一份
 * 账号 JSON **推进网关**（POST /api/accounts/import），接受网关导出的原样文件。
 * 保活同理：配置读写都走 /api/checkin-keepalive，Praxis 只是编辑器。
 * 骨架与全站一致：LazyColumn + LocalTopBarInset + LocalScrollBottomLimit。
 */
@Composable
fun GatewayMaintenanceScreen(
    keepaliveJson: String = "",
    importResult: String? = null,
    importBusy: Boolean = false,
    keepaliveResult: String? = null,
    onImportAccounts: (String) -> Unit = {},
    onRefreshKeepalive: () -> Unit = {},
    onSaveKeepalive: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current
    var importText by remember { mutableStateOf("") }
    var keepaliveText by remember(keepaliveJson) { mutableStateOf(keepaliveJson) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topInset, bottom = 24.dp + scrollLimit),
    ) {
        item(key = "__maint_header__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Construction,
                title = "导入账号到网关",
                subtitle = "粘贴网关导出的账号 JSON（顶层 accounts 数组）",
            )
        }
        item(key = "__maint_import__") {
            TextField(
                value = importText,
                onValueChange = { importText = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                label = "账号 JSON",
                useLabelAsPlaceholder = true,
            )
        }
        item(key = "__maint_import_btn__") {
            MiuixNavigationItemWidget(
                title = if (importBusy) "导入中…" else "导入到网关",
                description = when {
                    importBusy -> "正在提交"
                    importText.isBlank() -> "先粘贴账号 JSON"
                    else -> "POST /api/accounts/import（原样转交，网关负责去重与合并）"
                },
                onClick = { if (!importBusy && importText.isNotBlank()) onImportAccounts(importText.trim()) },
            )
        }
        importResult?.let { text ->
            item(key = "__maint_import_result__") {
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
        item(key = "__maint_keepalive_header__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Autorenew,
                title = "保活配置",
                subtitle = "GET/POST /api/checkin-keepalive（网关按它维持日活）",
            )
        }
        item(key = "__maint_keepalive_field__") {
            TextField(
                value = keepaliveText,
                onValueChange = { keepaliveText = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                label = "保活 JSON",
                useLabelAsPlaceholder = true,
            )
        }
        item(key = "__maint_keepalive_reload__") {
            MiuixNavigationItemWidget(
                title = "重新从网关读取",
                description = "改了想丢弃时点这个",
                onClick = onRefreshKeepalive,
            )
        }
        item(key = "__maint_keepalive_save__") {
            MiuixNavigationItemWidget(
                title = "保存保活配置",
                description = if (keepaliveText.isBlank()) "内容为空，网关会拒绝" else "写回网关",
                onClick = { if (keepaliveText.isNotBlank()) onSaveKeepalive(keepaliveText.trim()) },
            )
        }
        keepaliveResult?.let { text ->
            item(key = "__maint_keepalive_result__") {
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
}