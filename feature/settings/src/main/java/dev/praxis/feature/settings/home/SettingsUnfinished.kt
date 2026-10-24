package dev.praxis.feature.settings.home

import dev.praxis.feature.settings.common.SettingsList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.navigation.PraxisRoute
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「这一项还没做」的说明页。
 *
 * ## 为什么要说清「为什么没做」而不是一句「尚未实现」
 *
 * 用户点进来是为了用它。只说「尚未实现」等于把问题丢回给用户，
 * 他既不知道该不该等，也不知道是不是坏了。所以每个入口带一句具体原因，
 * 由调用方传入（`SettingsHost` 里那两个常量就是这些原因）。
 *
 * ## 顶部避让
 *
 * 用 [SettingsList] 而不是 `Column(padding(24.dp))`：
 * 后者不读 `LocalTopBarInset`，标题会被顶栏压住。
 * `SettingsList` 是全项目统一的滚动容器，内含顶栏避让与底栏滚动限位。
 */
@Composable
internal fun SettingsUnfinished(
    title: String,
    reason: String,
    modifier: Modifier = Modifier,
) {
    SettingsList(modifier = modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(24.dp)) }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = reason,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * 「传错了 Route」的显式错误页。
 *
 * ## 为什么需要它，以及它为什么不是兜底页
 *
 * 旧实现的兜底页长这样：`entryKey` 查不到就**再渲染一遍设置首页**。
 * 于是「接线错误」被伪装成「按钮点了没反应」—— 用户报「点『高级』没反应」，
 * 真实原因是清单里写了 `advanced`、枚举里没有。这类 bug 只能靠猜。
 *
 * 现在把状态分成三种，各自显示不同内容：
 *
 * | 状态 | 显示 | 用途 |
 * |---|---|---|
 * | 功能未实现 | [SettingsUnfinished]（带具体原因） | 用户知道该不该等 |
 * | 环境未装配 | 同上（原因写「缺注入」） | 用户知道是构建问题 |
 * | **Route 传错** | 本页（显示收到的具体 Route） | 维护者一眼定位 |
 *
 * 第三种只应在开发期出现（用户看不到）。页面上直接印出收到的
 * `PraxisRoute` 类型名，比任何一句「出错了」都有用。
 */
@Composable
internal fun SettingWrongRoute(
    route: PraxisRoute,
    modifier: Modifier = Modifier,
) {
    SettingsList(modifier = modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(24.dp)) }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = "这一页不属于设置",
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = "收到的是 $route。设置子页只应传 SettingsXxx 类型的路由；" +
                        "出现这个页面说明上层分发把非设置页投到了 SettingsHost。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}