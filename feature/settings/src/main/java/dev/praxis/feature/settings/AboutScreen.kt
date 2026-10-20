package dev.praxis.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页。
 *
 * 只放**能确认的信息**：版本号由 :app 传入，许可清单写实际用到的库。
 * 不写「即将推出」「敬请期待」这类占位话术。
 */
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    SettingsList(
        modifier = modifier.fillMaxSize(),
    ) {
        item { Spacer(Modifier.height(24.dp)) }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Praxis",
                    style = MiuixTheme.textStyles.title1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = "思行 · 把思考付诸实践",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "$versionName ($versionCode)",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        item { SmallTitle("开源许可") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Praxis 使用了以下开源项目。各项目的完整许可文本随构建产物一同分发。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                    LicenseLine("miuix (compose-miuix-ui)", "Apache-2.0")
                    LicenseLine("Kyant0/AndroidLiquidGlass", "Apache-2.0")
                    LicenseLine("InstallerX-Revived (控件层)", "GPL-3.0-only")
                    LicenseLine("AndroidX / Compose", "Apache-2.0")
                    LicenseLine("Koin", "Apache-2.0")
                    LicenseLine("OkHttp", "Apache-2.0")
                    LicenseLine("Room", "Apache-2.0")
                    LicenseLine("Timber", "Apache-2.0")
                }
            }
        }
    }
}

@Composable
private fun LicenseLine(name: String, license: String) {
    Text(
        text = "$name  ·  $license",
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
