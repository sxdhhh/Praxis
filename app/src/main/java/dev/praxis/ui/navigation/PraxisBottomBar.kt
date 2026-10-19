package dev.praxis.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.domain.model.PraxisDomain
import dev.praxis.ui.icons.PraxisIcons
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 浮层底栏。
 *
 * 硬约束（既有项目踩过的坑）：
 *  - 底栏是浮层，内容从下方穿过；不能靠给内容区加 padding 抬高来「让位」。
 *  - 模糊/液态玻璃只在 API 33+ 启用（见 PraxisCapabilities），低版本不挂 backdrop。
 *
 * miuix 0.9.4 真实签名（javap 核对，勿凭印象写）：
 *  - FloatingNavigationBar(modifier, color, cornerRadius, horizontalAlignment, ...)
 *  - FloatingNavigationBarItem(selected, onClick, icon: ImageVector, label: String, ...)
 *    注意 label 是 String，不是 composable 槽。
 */
@Composable
fun PraxisBottomBar(
    selected: PraxisDomain,
    onSelect: (PraxisDomain) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        PraxisDomain.CHAT to PraxisIcons.Chat,
        PraxisDomain.TOOLS to PraxisIcons.Tools,
        PraxisDomain.MEMORY to PraxisIcons.Memory,
        PraxisDomain.SETTINGS to PraxisIcons.Settings,
    )

    FloatingNavigationBar(
        modifier = modifier.padding(bottom = 12.dp),
        color = MiuixTheme.colorScheme.surface,
    ) {
        items.forEach { (domain, icon) ->
            FloatingNavigationBarItem(
                selected = domain == selected,
                onClick = { onSelect(domain) },
                icon = icon,
                label = domain.title,
            )
        }
    }
}