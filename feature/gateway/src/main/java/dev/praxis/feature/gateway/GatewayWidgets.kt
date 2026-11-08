package dev.praxis.feature.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card

/**
 * 统一的卡片外壳：水平留白与其它设置页一致，内容统一 16dp 内边距。
 *
 * 放在这里而不是留在某个页面文件里：网关的几个页面（概览、账号、路由）
 * 都用同一个外壳，各写一份迟早会不一致。
 */
@Composable
internal fun GatewayCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            content()
        }
    }
}
