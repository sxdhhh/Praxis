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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * H5 容器（docs/PORTING-GATEWAY.md §9.10）—— 登录/授权页的宿主。
 *
 * ## 这是文档里那条「最后手段」，而且试过两次库都失败了
 *
 * 文档 §9.10：`jeziellago`（首选）→ KevinnZou → **`AndroidView { WebView }`（最后手段，且要评审）**。
 * 两次试库的记录（都留证据，不是「懒得试」）：
 *
 * | 尝试 | 结果 |
 * |---|---|
 * | 首选 jeziellago | **版本号查不到**（Maven Central 在本机被沙箱 DNS 拦、Gradle 缓存也没有） |
 * | 备选 KevinnZou 2.0.0（依赖能解析 ✓） | `WebView` composable 在，但 `onCreated` / `onDispose` / `webViewClient` 参数与实际签名不符，`AccompanistWebViewClient` 也不暴露 Android `WebViewClient` 的覆写点 |
 *
 * 阻断点：**本环境读不到该库的 API 面**。换库需要先拿到 API（CI 里打印 artifact 签名，或贴一份 README）。
 *
 * ## 两个真实 bug 的修法来自另一个项目（用户提供）
 *
 * `cn.apixiaoyuan.app` 的 H5 容器（同样是 AndroidView 路线）踩过并解决了这两条：
 *
 * ### ① 容器限位：顶部让出状态栏 + 那条带子用页面底色填充
 *
 * 根因（他的原话）：「App 开了 `enableEdgeToEdge()`，内容区一直画到屏幕最顶端，
 * **状态栏是浮在内容之上的**」，而容器 `Column` 没有 background → 露出窗口底色 →
 * **一条白带子压在深色页面上**。
 *
 * 修法两条：让出状态栏高度（[applyTopInset]），并用**页面自己的底色**填那一条。
 *
 * ### ② 页面底色自适应（[WebPageColor]）
 *
 * 不问就不猜：向 H5 要 `getComputedStyle(html).backgroundColor` → `body` →
 * `<meta name=theme-color>`（优先级同他的实现，理由见那边的注释：html 的背景会铺满视口）。
 *
 * **与他的实现有一处不同**：他在 Kotlin 侧解析 CSS 色值，我让 JS 直接算成 ARGB 整数
 * 回来 —— Kotlin 侧少一个解析器，也就少一处能出错的地方。
 *
 * @param url 要打开的地址。
 * @param onClose 用户关闭容器（含「返回无可退」时）。
 * @param onCallbackUrl 命中 [allowedHosts] 的地址：**容器不跳转**，把它交出去（OAuth 回调靠它）。
 * @param allowedHosts 允许作为回调的主机名片段（大小写不敏感）；空 = 不拦截。
 * @param userAgentSuffix 追加到系统 UA 后面的标识；null = 不改。
 * @param applyTopInset 是否让出状态栏高度。
 *  - **全屏页面**（路由承载的容器）应当传 true：edge-to-edge 下不让出就会被状态栏压住；
 *  - **弹窗里的容器**（当前用法，授权窗）传 false：外层的对话框已经处理了 insets，
 *    再让一次会多出一条空白。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PraxisWebViewSheet(
    url: String,
    onClose: () -> Unit,
    onCallbackUrl: (String) -> Unit = {},
    allowedHosts: List<String> = emptyList(),
    userAgentSuffix: String? = null,
    applyTopInset: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentUrl by remember { mutableStateOf(url) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 页面底色：探测到之前是 null（用主题色兜底），探测到之后铺满整页。
    var pageColor by remember { mutableStateOf<Color?>(null) }

    val density = LocalDensity.current
    val statusBarTop = if (applyTopInset) {
        WindowInsets.statusBars.getTop(density)
    } else {
        0
    }

    fun isCallback(target: String?): Boolean {
        if (target.isNullOrBlank() || allowedHosts.isEmpty()) return false
        val lower = target.lowercase()
        return allowedHosts.any { host -> lower.contains(host.lowercase()) }
    }

    // 返回键：能退网页就先退网页，退不动才关容器（登录流程里返回很常见）。
    BackHandler(enabled = true) {
        val view = webView
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

    // 整页背景用页面底色：那条状态栏带子与页面浑然一体（见文件头 ①）。
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(pageColor ?: MaterialTheme.colorScheme.surface),
    ) {
        if (statusBarTop > 0) {
            Spacer(Modifier.height(with(density) { statusBarTop.toDp() }))
        }
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
                        // 页面底色：透明窗口会让带子露出下一层，所以先给个不透明底。
                        setBackgroundColor(android.graphics.Color.WHITE)
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
                                // 每次加载完成都探一次：SPA 内部导航会换「页面」，底色未必相同。
                                view?.let { web ->
                                    web.evaluateJavascript(PAGE_COLOR_PROBE) { raw ->
                                        parsePageColor(raw)?.let { color -> pageColor = color }
                                    }
                                }
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

/**
 * 问页面「你的底色是什么」，直接返回 **ARGB 整数**（`null` = 问不出来）。
 *
 * 优先级 `html` → `body` → `<meta theme-color>`：`html` 的背景会铺满整个视口
 * （CSS 的背景传播规则），是「页面实际看起来的底色」；`body` 常常是 transparent；
 * `theme-color` 是页面给浏览器壳的提示色，作最后兜底。
 *
 * 算成整数再回来，是为了让 Kotlin 侧不需要一个 CSS 色值解析器（少一处能出错的地方）。
 */
private const val PAGE_COLOR_PROBE = """
(function(){
  try{
    function bg(el){ try{ return (el && getComputedStyle(el).backgroundColor) || ''; }catch(e){ return ''; } }
    function argb(c){
      if(!c || c==='transparent') return -1;
      var m = String(c).match(/^#([0-9a-fA-F]{6})$/);
      if(m) return (0xff000000 | parseInt(m[1],16)) | 0;
      var r = String(c).match(/rgba?\(([^)]+)\)/);
      if(!r) return -1;
      var p = r[1].split(',').map(function(x){ return parseFloat(x); });
      if(p.length < 3 || isNaN(p[0])) return -1;
      var a = p.length > 3 ? p[3] : 1;
      if(!a) return -1;
      return ((Math.round(a*255) << 24) | (p[0] << 16) | (p[1] << 8) | p[2]) | 0;
    }
    var h = argb(bg(document.documentElement));
    var b = argb(bg(document.body));
    var t = -1;
    try{
      var meta = document.querySelector('meta[name="theme-color"]');
      if(meta) t = argb(meta.getAttribute('content') || '');
    }catch(e){}
    var v = h !== -1 ? h : (b !== -1 ? b : t);
    return v === -1 ? 'null' : String(v);
  }catch(e){ return 'null'; }
})()
"""

/** 解析 [PAGE_COLOR_PROBE] 的返回值（`evaluateJavascript` 会给字符串加引号）。 */
internal fun parsePageColor(raw: String?): Color? {
    val text = raw?.trim()?.trim('"') ?: return null
    if (text.isEmpty() || text == "null") return null
    val argb = text.toLongOrNull() ?: return null
    return Color(argb.toInt())
}
