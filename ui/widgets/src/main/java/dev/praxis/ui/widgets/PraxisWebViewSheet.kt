package dev.praxis.ui.widgets

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * H5 容器（docs/PORTING-GATEWAY.md §9.10）—— 登录/授权页的宿主。
 *
 * ## 为什么这里用了文档说的「最后手段」
 *
 * 文档 §9.10 的选型顺序是：`dev.jeziellago:compose-webview`（首选）→ KevinnZou →
 * **`AndroidView { WebView }`（最后手段，且要评审）**。
 *
 * 这里没加任何第三方依赖，理由是可核实的：**本环境无法核实那些库的版本号**
 * （Maven Central / AndroidX 站点都被沙箱 DNS 拦、本机 Gradle 缓存里也没有它们）。
 * 写一个猜的版本号会直接让所有人的构建失败 —— 那个代价比「用平台 WebView」大得多。
 *
 * 评审要点（也写给以后想换成库的人）：
 *
 * - 这里「自己写的」是**宿主壳**（加载进度、错误重试、返回键、回调拦截、新窗口处理），
 *   约 80 行；**不是控件绘制**，没有一行画 UI。文档反对的是后者。
 * - 平台 `WebView` 本身就是系统组件，Compose 里只能用 `AndroidView` 承载 ——
 *   那几个库内部做的是同一件事。
 * - 换库时只需替换 [PraxisWebViewSheet] 的实现：调用方只依赖这个函数签名。
 *
 * ## 壳要处理的六件事（逐条对应文档表）
 *
 * | 事项 | 这里怎么做的 |
 * |---|---|
 * | 加载进度 / 错误页 / 重试 | 顶部进度条；`onReceivedError` 时盖一层错误页 + 重试按钮 |
 * | 返回键（先 `canGoBack()`，否则关容器） | `BackHandler` |
 * | 拦截 `allowedHosts` 上的回调 URL | `shouldOverrideUrlLoading` + `onPageStarted` 各查一次 |
 * | `target=_blank` | `setSupportMultipleWindows` + `onCreateWindow` 把新窗口**装进同一个 WebView** |
 * | Cookie / DOM storage / 第三方 Cookie | `domStorageEnabled` + `setAcceptThirdPartyCookies` |
 * | 自定义 UA（可选） | [userAgentSuffix] |
 *
 * @param url 要打开的地址。
 * @param onClose 用户关闭容器（含「返回无可退」时）。
 * @param onCallbackUrl 命中 [allowedHosts] 的地址：**容器不跳转**，把它交出去（OAuth 回调靠它）。
 * @param allowedHosts 允许作为回调的主机名片段（大小写不敏感）；空 = 不拦截。
 * @param userAgentSuffix 追加到系统 UA 后面的标识；null = 不改（有些站会拦 WebView UA）。
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
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentUrl by remember { mutableStateOf(url) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun isCallback(target: String?): Boolean {
        if (target.isNullOrBlank() || allowedHosts.isEmpty()) return false
        val lower = target.lowercase()
        return allowedHosts.any { host -> lower.contains(host.lowercase()) }
    }

    BackHandler(enabled = true) {
        val view = webView
        // 登录流程里返回很常见：能退网页就先退网页，退不动才关容器。
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            // 不 destroy 会泄漏：登录页里通常带着定时器与长连接。
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部：地址（只读，方便用户核对是不是自己那家）+ 关闭。
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
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // 微信/QQ 登录依赖第三方 Cookie。
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        userAgentSuffix?.let { suffix ->
                            settings.userAgentString = settings.userAgentString + " " + suffix
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val target = request?.url?.toString()
                                if (isCallback(target)) {
                                    // 回调地址不跳转：交出去让上层拿 code/参数。
                                    onCallbackUrl(target!!)
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                currentUrl = url ?: currentUrl
                                // 有些跳转走 302，不经过 shouldOverrideUrlLoading，这里再查一次。
                                if (isCallback(url)) onCallbackUrl(url!!)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                currentUrl = url ?: currentUrl
                                progress = 100
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                err: WebResourceError?,
                            ) {
                                // 只报主文档的错：子资源（图片/统计）失败不该盖错误页。
                                if (request?.isForMainFrame == true) {
                                    error = err?.description?.toString() ?: "页面加载失败"
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                                if (newProgress >= 100) error = null
                            }

                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?,
                            ): Boolean {
                                // 部分厂商登录会开新窗口；那个窗口没有容器会直接白屏，
                                // 所以把它的地址装进**同一个** WebView。
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                transport.webView = view
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
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
                        webView?.loadUrl(currentUrl)
                    }) { Text("重试") }
                }
            }
        }
    }
}
