package dev.praxis.feature.settings.info

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.praxis.feature.settings.common.SettingsList
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 开源许可明细。对齐 InstallerX 的
 * `settings/preferred/about/ossLicensePage/`（`MiuixOpenSourceLicensePage` +
 * `LibrariesContainer`，后者是「分组 + 可点行」的容器）。
 *
 * ## 为什么是独立页而不是关于页里的一段长文本
 *
 * 上游就是这个层级。而且许可数据本身的形态决定了它需要一个列表：
 * 每条都要「库名 / 许可类型 / 可跳到上游」三件事，长度远超一屏。
 * 塞进关于页会把 hero 之后的内容全挤掉，主次颠倒。
 *
 * ## 数据为什么写在文件里而不是从构建产物读
 *
 * 正确做法是 Gradle 的 license 插件生成清单（上游用 `LibrariesContainer`
 * 二次封装的就是这份数据）。Praxis 尚未接入该插件，这里手写一份
 * **与 `feature/settings/build.gradle.kts` 的 `api(...)` 一一对应**的清单。
 *
 * 手写清单的失效风险是「加了依赖忘登记」，所以：
 *  - 分组顺序按「基础控件 → 运行库 → 参考实现」，新增依赖归到对应组；
 *  - 每一条都写清**为什么用它**，忘登记时看代码也知道该不该加。
 *
 * ## 为什么用 `MiuixNavigationItemWidget` 而不是纯文本行
 *
 * 这一页的每一行都**应该可点**（跳到上游仓库看许可全文），
 * 与关于页那层「纯摘要」不同。上游同样如此：摘要层不可点，明细层可点。
 */
@Composable
fun OpenSourceLicenseScreen(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current

    SettingsList(modifier = modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(12.dp)) }

        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                InfoCard(
                    title = "许可与义务",
                    body = "本应用以 GPL-3.0-only 的参照实现为基础重写，自身代码与" +
                        "其保持一致。下列第三方组件按各自的许可协议分发；" +
                        "重新分发本应用时必须保留这些声明。",
                )
            }
        }

        item { SmallTitle("UI 与设计") }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixNavigationItemWidget(
                    title = "miuix (compose-miuix-ui)",
                    description = "Apache-2.0 · 全项目唯一的 UI 引擎",
                    onClick = { uriHandler.openUri("https://github.com/miuix-kotlin-multiplatform/miuix") },
                )
                MiuixNavigationItemWidget(
                    title = "Kyant0/AndroidLiquidGlass",
                    description = "Apache-2.0 · 液态玻璃（backdrop）实现",
                    onClick = { uriHandler.openUri("https://github.com/Kyant0/AndroidLiquidGlass") },
                )
                MiuixNavigationItemWidget(
                    title = "cn.apixiaoyuan.app",
                    description = "仅参考实现（未包含其代码）· 顶部 scaffold 与按钮",
                    onClick = { },
                )
            }
        }

        item { SmallTitle("参照实现") }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixNavigationItemWidget(
                    title = "InstallerX-Revived",
                    description = "GPL-3.0-only · 控件、页面结构、导航与转场",
                    onClick = { uriHandler.openUri("https://github.com/wxxsfxyzm/InstallerX-Revived") },
                )
            }
        }

        item { SmallTitle("运行库") }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                MiuixNavigationItemWidget(
                    title = "AndroidX / Jetpack Compose",
                    description = "Apache-2.0 · Compose 运行时、导航、生命周期",
                    onClick = { uriHandler.openUri("https://developer.android.com/jetpack/androidx") },
                )
                MiuixNavigationItemWidget(
                    title = "Koin",
                    description = "Apache-2.0 · 依赖注入",
                    onClick = { uriHandler.openUri("https://github.com/InsertKoinIO/koin") },
                )
                MiuixNavigationItemWidget(
                    title = "OkHttp",
                    description = "Apache-2.0 · HTTP 客户端",
                    onClick = { uriHandler.openUri("https://github.com/square/okhttp") },
                )
                MiuixNavigationItemWidget(
                    title = "Room",
                    description = "Apache-2.0 · 本地数据库（会话 / 消息 / 记忆）",
                    onClick = { uriHandler.openUri("https://developer.android.com/jetpack/androidx/releases/room") },
                )
                MiuixNavigationItemWidget(
                    title = "kotlinx.serialization",
                    description = "Apache-2.0 · 路由栈与配置序列化",
                    onClick = { uriHandler.openUri("https://github.com/Kotlin/kotlinx.serialization") },
                )
                MiuixNavigationItemWidget(
                    title = "Timber",
                    description = "Apache-2.0 · 日志门面",
                    onClick = { uriHandler.openUri("https://github.com/JakeWharton/timber") },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * 说明型卡片（标题 + 正文）。
 *
 * 不用 `MiuixNavigationItemWidget`：它带右箭头，而这里没有下一层。
 */
@Composable
private fun InfoCard(title: String, body: String) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}