package dev.praxis.data.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.praxis.core.plugin.PluginLoadOutcome
import dev.praxis.core.plugin.PluginRegistrationCodec
import dev.praxis.core.plugin.PluginRuntimeBootstrapScript
import dev.praxis.core.plugin.PluginScriptRuntime
import dev.praxis.core.plugin.PluginScriptSource
import dev.praxis.core.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 基于 WebView 的插件脚本运行时。
 *
 * ## 为什么是 WebView
 *
 * Android 上没有内建的可嵌入 JS 引擎，引 quickjs/V8 都要额外的 native 依赖
 * （Operit 自带 quickjs，Praxis 没有）。WebView 是系统自带的 JS 环境，
 * 代价是只能在主线程创建、执行是异步回调。
 *
 * ## 一个包一个 WebView
 *
 * 包之间的全局变量必须隔离：ToolPkg、require 的模块缓存、脚本自己的
 * 模块级状态都挂在全局上。共用一个 WebView 会让 A 包的 require 拿到 B 包的缓存。
 *
 * ## 模块路径
 *
 * 每个脚本执行前都会把 globalThis.__praxis_current_module 设成它自己的
 * 包内路径，引导脚本里的 require 据此解析相对路径。没有这一步，
 * dist/main.js 里的 require("./ui/x.js") 会解析成 ui/x.js 而不是
 * dist/ui/x.js —— 而错误信息只是一句 module not found。
 */
class PraxisWebViewPluginRuntime(
    private val context: Context,
) : PluginScriptRuntime {

    /** 一个包的运行上下文。 */
    private class Engine(
        val web: WebView,
        /** 包内相对路径 → 文本。供 PraxisHost.readFile（require）使用。 */
        val readFile: (String) -> String?,
    )

    private val engines = ConcurrentHashMap<String, Engine>()

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun load(
        packageId: String,
        main: PluginScriptSource,
        subpackages: List<Pair<String, PluginScriptSource>>,
        resourceResolver: (String) -> ByteArray?,
        readFile: (String) -> String?,
    ): PluginLoadOutcome = withContext(Dispatchers.Main) {
        // 重装同一包时先释放旧上下文：不然旧 WebView 的全局变量还在，
        // 新脚本会拿到上次的 ToolPkg 注册残留。
        releaseEngine(packageId)

        val web = createWebView(readFile)
        engines[packageId] = Engine(web, readFile)

        val errors = mutableListOf<String>()
        try {
            awaitPageReady(web)
            eval(web, PluginRuntimeBootstrapScript.JS)

            // 子包脚本先装：主脚本可能在注册阶段就要用到它们的导出。
            for ((subId, source) in subpackages) {
                val failure = eval(web, wrapModule(source))
                if (failure != "null") {
                    errors += "子包 " + subId + " 装载失败：" + failure
                }
            }

            val mainFailure = eval(web, wrapModule(main))
            if (mainFailure != "null") {
                // 主脚本自己坏了：注册阶段跑不起来，直接返回错误，
                // 不要把「没注册任何东西」当成「包没有入口」。
                errors += "主脚本装载失败：" + mainFailure
                return@withContext PluginLoadOutcome(
                    tools = emptyList(),
                    errors = errors,
                    entryPoints = emptyList(),
                )
            }

            val ran = eval(web, "__praxis_run_registration()")
            if (ran != "true") {
                errors += "主脚本没有导出 registerToolPkg()，或执行时抛错"
            }

            val rawJson = unwrapJsString(eval(web, "__praxis_registrations_json()"))
            val parsed = PluginRegistrationCodec.parse(packageId, rawJson)
            errors += parsed.errors

            // 工具不从这里来：子包 METADATA 才是来源，由 PluginRuntimeHost
            // 在装载时合并进去（见 PluginRuntimeHost.load）。
            PluginLoadOutcome(
                tools = emptyList(),
                errors = errors,
                entryPoints = parsed.entries,
            )
        } catch (error: Throwable) {
            // 装载失败不抛给上层：一个包坏了不该让启动流程崩掉，
            // 用户还得进应用去删它。
            errors += "运行时异常：" + (error.message ?: error.javaClass.simpleName)
            PluginLoadOutcome(tools = emptyList(), errors = errors, entryPoints = emptyList())
        }
    }

    override suspend fun invoke(
        packageId: String,
        toolName: String,
        entryFunction: String,
        argumentsJson: String,
    ): ToolResult = withContext(Dispatchers.Main) {
        val engine = engines[packageId]
            ?: return@withContext ToolResult(
                content = "插件 " + packageId + " 的运行时已释放，无法调用 " + toolName,
                isError = true,
            )

        val raw = unwrapJsString(
            eval(
                engine.web,
                "__praxis_invoke(" + quoteJs(entryFunction) + ", " + quoteJs(argumentsJson) + ")",
            ),
        )

        val json = runCatching { JSONTokener(raw).nextValue() as? JSONObject }.getOrNull()
        when {
            json == null -> ToolResult(content = "插件返回了无法解析的结果：" + raw, isError = true)
            json.has("error") -> ToolResult(
                content = "插件工具 " + toolName + " 执行失败：" + json.optString("error"),
                isError = true,
            )
            else -> {
                val result = json.opt("result")
                ToolResult(
                    content = if (result == null || result == JSONObject.NULL) {
                        "(插件未返回内容)"
                    } else {
                        result.toString()
                    },
                )
            }
        }
    }

    override suspend fun render(packageId: String, entryId: String): Result<String> =
        withContext(Dispatchers.Main) {
            val engine = engines[packageId]
                ?: return@withContext Result.failure(
                    IllegalStateException("插件 " + packageId + " 的运行时已释放"),
                )

            val raw = unwrapJsString(
                eval(engine.web, "__praxis_render(" + quoteJs(entryId) + ")"),
            )
            val json = runCatching { JSONTokener(raw).nextValue() as? JSONObject }.getOrNull()
                ?: return@withContext Result.failure(
                    IllegalStateException("渲染返回了无法解析的结果：" + raw),
                )

            // 引导脚本把脚本侧异常转成 { error }：这里是它唯一的出口，
            // 吞掉就成了「页面空白、没人知道为什么」。
            val error = json.optString("error")
            if (error.isNotEmpty()) {
                return@withContext Result.failure(IllegalStateException(error))
            }
            Result.success(raw)
        }

    override suspend fun unload(packageId: String) = withContext(Dispatchers.Main) {
        releaseEngine(packageId)
    }

    // ── 内部 ──────────────────────────────────────────────────

    private fun releaseEngine(packageId: String) {
        val engine = engines.remove(packageId) ?: return
        runCatching {
            engine.web.removeJavascriptInterface(BRIDGE_NAME)
            engine.web.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(readFile: (String) -> String?): WebView {
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        // 包脚本只在本地执行，不需要网络与文件系统访问。
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.addJavascriptInterface(FileBridge(readFile), BRIDGE_NAME)
        return web
    }

    private suspend fun awaitPageReady(web: WebView) {
        suspendCancellableCoroutine { continuation ->
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            web.loadDataWithBaseURL(null, EMPTY_PAGE, "text/html", "utf-8", null)
        }
    }

    private suspend fun eval(web: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            web.evaluateJavascript(script) { value ->
                if (continuation.isActive) continuation.resume(value ?: "null")
            }
        }

    /**
     * 把脚本包成 CommonJS 模块，并给它一个「当前位置」。
     *
     * 不包 exports/module，脚本里的 exports.registerToolPkg = ... 会直接抛
     * 「exports is not defined」；不设当前模块路径，相对 require 会解析错。
     * 两种失败在界面上都只表现为「没反应」。
     */
    private fun wrapModule(source: PluginScriptSource): String {
        val path = quoteJs(source.path)
        return "(function(){" +
            "var exports={};var module={exports:exports};" +
            "globalThis.__praxis_current_module=" + path + ";" +
            "try{" + source.text + "\n;globalThis.exports=exports;return null;}" +
            "catch(e){return String(e&&e.message?e.message:e);}})()"
    }

    /** evaluateJavascript 的结果是 JSON 字面量；字符串会被再次转义。 */
    private fun unwrapJsString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return runCatching { JSONTokener(trimmed).nextValue() as? String ?: trimmed }
                .getOrDefault(trimmed)
        }
        return trimmed
    }

    private fun quoteJs(value: String): String = "\"" + escapeForJsString(value) + "\""

    private fun escapeForJsString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    /** 只读文件桥：给 require 用。 */
    private class FileBridge(private val readFile: (String) -> String?) {

        @JavascriptInterface
        fun readFile(path: String): String? = readFile.invoke(path)
    }

    private companion object {
        const val BRIDGE_NAME = "PraxisHost"
        const val EMPTY_PAGE = "<html><body></body></html>"
    }
}
