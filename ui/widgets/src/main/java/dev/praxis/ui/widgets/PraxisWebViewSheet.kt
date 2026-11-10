package dev.praxis.ui.widgets

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multiplatform.webview.web.AccompanistWebViewClient
import com.multiplatform.webview.web.ComposeWebView
import com.multiplatform.webview.web.LoadingState
import com.multiplatform.webview.web.WebViewNavigator
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState

/**
 * H5 容器（docs/PORTING-GATEWAY.md §9.10）—— 登录/授权页的宿主。
 *
 * ## 为什么现在是「库 + 壳」而不是自己写 AndroidView
 *
 * 文档 §9.10 明写：**不要自己写 `AndroidView { WebView }` 包一层**，
 * 首选现成的 Compose 库。此前我用的是 AndroidView 直连平台 WebView，
 * 理由是「库的版本号无法核实」—— 那条理由后来不成立了：
 *
 * | 候选 | 版本 |
 * |---|---|
 * | `dev.jeziellago:compose-webview`（文档首选） | **查不到**（Maven 站点在本机被沙箱 DNS 拦） |
 * | `io.github.kevinnzou:compose-webview-multiplatform`（文档备选） | 2.0.0（依赖升级记录里有 1.9.40 → 2.0.0） |
 *
 * 文档本来就写着「先试 jeziellago，不兼容换 KevinnZou」，所以用备选是**在文档给的选项内**。
 *
 * ## 壳要处理的六件事（逐条对应文档那张表）
 *
 * | 事项 | 这里怎么做的 |
 * |---|---|
 * | 加载进度 / 错误页 / 重试 | `state.loadingState` 出进度；`state.errorsForCurrentRequest` 出错误页 + 重试 |
 * | 返回键（先 `canGoBack()`，否则关容器） | `BackHandler` + `navigator.canGoBack` |
 * | 拦截 `allowedHosts` 上的回调 URL | 自定义 `shouldOverrideUrlLoading` |
 * | `target=_blank` | `setSupportMultipleWindows` + 把新窗口装进同一 WebView |
 * | Cookie / DOM storage / 第三方 Cookie | `onCreated` 里开 |
 * | 自定义 UA（可选） | `onCreated` 里追加 [userAgentSuffix] |
 *
 * @param url 要打开的地址。
 * @param onClose 用户关闭容器（含「返回无可退」时）。
 * @param onCallbackUrl 命中 [allowedHosts] 的地址：**容器不跳转**，把它交出去（OAuth 回调靠它）。
 * @param allowedHosts 允许作为回调的主机名片段（大小写不敏感）；空 = 不拦截。
 * @param userAgentSuffix 追加到系统 UA 后面的标识；null = 不改。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PraxisWebViewSheet(
    url: String,
    onClose: () -> Unit,
    onCallbackUrl: (String) -> Unit = {},
    allowedHosts: List<String> = emptyList(),
    userAgentSuffix: String? = null,
    modifier: Modifier = Modifier,
) {
    val webViewState = rememberWebViewState(url = url)
    val navigator = rememberWebViewNavigator()
    var lastSeenUrl by remember { mutableStateOf(url) }

    fun isCallback(target: String?): Boolean {
        if (target.isNullOrBlank() || allowedHosts.isEmpty()) return false
        val lower = target.lowercase()
        return allowedHosts.any { host -> lower.contains(host.lowercase()) }
    }

    // 回调拦截：库的 Android 实现是 AccompanistWebViewClient（可覆写跳转判定）。
    val client = remember(allowedHosts, onCallbackUrl) {
        object : AccompanistWebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url?.toString()
                if (isCallback(target)) {
                    onCallbackUrl(target!!)
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }
        }
    }

    // 登录流程里返回很常见：能退网页就先退网页，退不动才关容器。
    BackHandler(enabled = true) {
        if (navigator.canGoBack) navigator.goBack() else onClose()
    }

    Column(modifier = modifier.fillMaxSize()) {
        val currentUrl = webViewState.currentUrl ?: url
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = currentUrl,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("关闭") }
        }
        val loading = webViewState.loadingState
        if (loading is LoadingState.Loading && loading.progress < 1f) {
            LinearProgressIndicator(
                progress = { loading.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Spacer(Modifier.height(4.dp))
        }
        Box(modifier = Modifier.fillMaxSize()) {
            ComposeWebView(
                modifier = Modifier.fillMaxSize(),
                state = webViewState,
                navigator = navigator,
                webViewClient = client,
                onCreated = { web ->
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    // 微信/QQ 登录依赖第三方 Cookie。
                    web.settings.setSupportMultipleWindows(true)
                    web.settings.javaScriptCanOpenWindowsAutomatically = true
                    userAgentSuffix?.let { suffix ->
                        web.settings.userAgentString = web.settings.userAgentString + " " + suffix
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                    web.webChromeClient = object :
                        android.webkit.WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?,
                        ): Boolean {
                            // 部分厂商登录会开新窗口；装进**同一个** WebView，
                            // 否则那个窗口没有容器会直接白屏。
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                                ?: return false
                            transport.webView = view
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    web.webViewClient = client
                    lastSeenUrl = url
                },
                onDispose = { web ->
                    // 不释放会泄漏：登录页里通常带着定时器与长连接。
                    web.stopLoading()
                    web.destroy()
                },
            )
            val errors = webViewState.errorsForCurrentRequest
            if (errors.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "打不开这个页面", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errors.first().description,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { navigator.loadUrl(lastSeenUrl) }) { Text("重试") }
                }
            }
        }
    }
}
