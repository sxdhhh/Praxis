package dev.praxis.ui.widgets

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multiplatform.webview.web.AccompanistWebViewClient
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState
import com.multiplatform.webview.web.WebView as ComposeWebView

/**
 * H5 容器（docs/PORTING-GATEWAY.md §9.10）—— 登录/授权页的宿主。
 *
 * ## 为什么是「库 + 壳」
 *
 * 文档 §9.10 明写：**不要自己写 `AndroidView { WebView }` 包一层**，首选现成的
 * Compose 库。此前我用 AndroidView，理由是「库版本无法核实」；那条理由不成立 ——
 * 文档的**备选** `io.github.kevinnzou:compose-webview-multiplatform` 有可用版本 2.0.0。
 *
 * ## 这里刻意只依赖「已被编译验证存在」的符号
 *
 * 第一次换库时 CI 报了 16 个错，但其中四条线索很有用：
 * `rememberWebViewState` / `rememberWebViewNavigator` / `AccompanistWebViewClient` /
 * `LoadingState` **都解析成功**，只有 composable 名、`navigator.goBack`、`state.currentUrl` 不对。
 * 所以这一版：
 *
 * 1. composable 用库的 `WebView`（import 时起别名，避免与平台 `WebView` 撞名）；
 * 2. **不用** `navigator.goBack` / `state.currentUrl` —— 自己在壳里记访问历史
 *    （`history` + `navigator.loadUrl`），前进后退与地址显示都不依赖库的可变 API；
 * 3. 进度 / 错误 / 新窗口全部在**平台的** `WebViewClient` / `WebChromeClient` 上覆写 ——
 *    那些是 Android 的稳定接口，与库版本无关。
 *
 * 壳要处理的六件事（文档那张表）逐条对应见各段注释。
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

    // 自己记历史：不依赖库的 goBack / currentUrl（它们的名字与版本相关）。
    var history by remember { mutableStateOf(listOf(url)) }
    var currentUrl by remember { mutableStateOf(url) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    fun isCallback(target: String?): Boolean {
        if (target.isNullOrBlank() || allowedHosts.isEmpty()) return false
        val lower = target.lowercase()
        return allowedHosts.any { host -> lower.contains(host.lowercase()) }
    }

    // 回调拦截 + 进度 + 错误：全部覆写平台接口（与库版本无关）。
    val client = remember(allowedHosts, onCallbackUrl) {
        object : AccompanistWebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url?.toString()
                if (isCallback(target)) {
                    onCallbackUrl(target!!)
                    return true
                }
                return false
            }

            override fun onPageStarted(
                view: android.webkit.WebView?,
                url: String?,
                favicon: Bitmap?,
            ) {
                if (url != null) {
                    currentUrl = url
                    history = history + url
                    // 有些跳转走 302，不经过 shouldOverrideUrlLoading，这里再查一次。
                    if (isCallback(url)) onCallbackUrl(url)
                }
            }

            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                if (url != null) currentUrl = url
                progress = 100
            }

            override fun onReceivedError(
                view: android.webkit.WebView?,
                request: WebResourceRequest?,
                err: WebResourceError?,
            ) {
                // 只报主文档的错：子资源（图片/统计）失败不该盖错误页。
                if (request?.isForMainFrame == true) {
                    error = err?.description?.toString() ?: "页面加载失败"
                }
            }
        }
    }

    // 返回键：能退就先退（用自记的历史），退不动才关容器。
    BackHandler(enabled = true) {
        val previous = history.dropLast(1).lastOrNull()
        if (previous != null) {
            history = history.dropLast(1)
            navigator.loadUrl(previous)
        } else {
            onClose()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
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
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
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
                    // 进度与新窗口：走平台的 chrome client（库不拦这两个）。
                    web.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                            progress = newProgress
                            if (newProgress >= 100) error = null
                        }

                        override fun onCreateWindow(
                            view: android.webkit.WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?,
                        ): Boolean {
                            // 部分厂商登录会开新窗口；装进**同一个** WebView，
                            // 否则那个窗口没有容器会直接白屏。
                            val transport = resultMsg?.obj as? android.webkit.WebView.WebViewTransport
                                ?: return false
                            transport.webView = view
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    web.webViewClient = client
                },
                onDispose = { web ->
                    // 不释放会泄漏：登录页里通常带着定时器与长连接。
                    web.stopLoading()
                    web.destroy()
                },
            )
            error?.let { message ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "打不开这个页面", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(text = message, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        error = null
                        navigator.loadUrl(currentUrl)
                    }) { Text("重试") }
                }
            }
        }
    }
}
