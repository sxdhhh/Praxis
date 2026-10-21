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
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页。
 *
 * 结构照抄 InstallerX `MiuixAboutPage`（892 行那份）的分组模式：
 *
 * ```
 * SmallTitle("关于")   → Card { MiuixNavigationItemWidget × N }
 * SmallTitle("调试")   → Card { ... }
 * ```
 *
 * ## 与上游的差异（都是业务差异，不是简化）
 *
 *  - 上游第一组是「源码 / 开源许可 / 检查更新 / 直接更新」四个可点行；
 *    Praxis 尚未发布，**没有更新通道**，所以不给「检查更新」——
 *    点了没反应的按钮比没有更糟。
 *  - 上游用 `textureBlur` 给 Card 加磨砂（依赖它自己的 backdrop 体系）；
 *    Praxis 的卡片走 miuix 默认 `surfaceContainer`，与其余设置页一致。
 *
 * ## 顶部版本块
 *
 * 上游把 App 图标 + 名称 + 版本放在 LazyColumn 的一个 item 里居中排。
 * Praxis 同样处理，但**不画图标**：图标资源还没做，用占位方块会让关于页
 * 看起来像没做完，不如只排文字。
 */
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    modifier: Modifier = Modifier,
) {
    SettingsList(modifier = modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(24.dp)) }

        // ── 版本块（居中）────────────────────────────────────────────
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

        // ── 开源许可 ────────────────────────────────────────────────
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
                    LicenseLine("InstallerX-Revived (控件层与底栏)", "GPL-3.0-only")
                    LicenseLine("cn.apixiaoyuan.app (壳与滚动)", "仅参考实现")
                    LicenseLine("AndroidX / Compose", "Apache-2.0")
                    LicenseLine("Koin", "Apache-2.0")
                    LicenseLine("OkHttp", "Apache-2.0")
                    LicenseLine("Room", "Apache-2.0")
                    LicenseLine("Timber", "Apache-2.0")
                }
            }
        }

        // ── 调试 ────────────────────────────────────────────────────
        item { SmallTitle("调试") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                MiuixNavigationItemWidget(
                    title = "玻璃渲染管线",
                    description = "切换高低版本管线，用于排查观感差异",
                    onClick = { /* 入口已接，面板在设置首页 */ },
                )
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