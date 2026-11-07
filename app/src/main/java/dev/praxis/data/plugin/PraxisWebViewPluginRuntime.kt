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
import dev.praxis.core.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 * 包之间的全局变量必须隔离：`ToolPkg`、`require` 的模块缓存、脚本自己的
 * 模块级状态都挂在全局上。共用一个 WebView 会让 A 包的 `require('x')`
 * 拿到 B 包的缓存。
 *
 * ## 与 Operit 的差距（如实记录）
 *
 * Operit 的 JsEngine 有 2767 行，含工具调用桥、Compose DSL 宿主、图片池、
 * 路由网关。这里只做**注册阶段**：跑脚本、收注册结果、能按名调用函数。
 * 宿主能力（Tools.*）尚未暴露 —— 也就是说插件脚本现在还调不到任何系统能力。
 */
class PraxisWebViewPluginRuntime(
    private val context: Context,
) : PluginScriptRuntime {

    /** 一个包的运行上下文。 */
    private class Engine(
        val web: WebView,
        /** 包内可读文件（相对路径 → 正文）。当前来自 content.resourceBytes。 */
        val files: Map<String, String>,
    )

    private val engines = ConcurrentHashMap<String, Engine>()

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun load(
        packageId: String,
        mainScript: String,
        subpackageScripts: Map<String, String>,
        resourceResolver: (String) -> ByteArray?,
    ): PluginLoadOutcome = withContext(Dispatchers.Main) {
        // 重装同一包时先释放旧上下文：不然旧 WebView 的全局变量还在，
        // 新脚本会拿到上次的 ToolPkg 注册残留。
        releaseEngine(packageId)

        val files = buildFileTable()
        val web = createWebView(packageId, files)
        val engine = Engine(web, files)
        engines[packageId] = engine

        val errors = mutableListOf<String>()
        try {
            awaitPageReady(web)
            eval(web, PluginRuntimeBootstrapScript.JS)

            // 子包脚本先装：主脚本可能在注册阶段就要用到它们的导出。
            for ((subId, script) in subpackageScripts) {
                val failure = eval(web, wrapModule(script))
                if (failure != "null") {
                    errors += "子包 " + subId + " 装载失败：" + failure
                }
            }

            val mainFailure = eval(web, wrapModule(mainScript))
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

            PluginLoadOutcome(
                tools = parsed.tools,
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

        val payload = escapeForJsString(argumentsJson)
        val raw = unwrapJsString(
            eval(engine.web, "__praxis_invoke(" + quoteJs(entryFunction) + ", \"" + payload + "\")"),
        )

        val json = runCatching { JSONTokener(raw).nextValue() as? org.json.JSONObject }
            .getOrNull()
        when {
            json == null -> ToolResult(content = "插件返回了无法解析的结果：" + raw, isError = true)
            json.has("error") -> ToolResult(
                content = "插件工具 " + toolName + " 执行失败：" + json.optString("error"),
                isError = true,
            )
            else -> {
                val result = json.opt("result")
                ToolResult(
                    content = if (result == null || result == org.json.JSONObject.NULL) {
                        "(插件未返回内容)"
                    } else {
                        result.toString()
                    },
                )
            }
        }
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
    private fun createWebView(packageId: String, files: Map<String, String>): WebView {
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        // 包脚本只在本地执行，不需要网络与文件系统访问。
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.addJavascriptInterface(FileBridge(files), BRIDGE_NAME)
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
     * 把脚本包成 CommonJS 模块。
     *
     * 不加这一层的话，`exports.registerToolPkg = ...` 会直接抛
     * 「exports is not defined」，而作者从界面只看到「没反应」。
     */
    private fun wrapModule(source: String): String =
        "(function(){var exports={};var module={exports:exports};" +
            "try{" + source + "\n;globalThis.exports=exports;return null;}" +
            "catch(e){return String(e&&e.message?e.message:e);}})()"

    /**
     * 包内文件表（相对路径 → 正文），给 require 用。
     *
     * ## 现在是空的，这是明确的不支持，不是偷懒
     *
     * `PluginScriptRuntime.load` 只拿到 mainScript / subpackageScripts /
     * 按 key 索引的资源字节，**没有「包内相对路径 → 正文」这个能力**。
     * 而真实包是打包产物：sidebar_sillytavern 的 dist/main.js 会
     * require 同包的 dist/ui/.../index.ui.js。
     *
     * 编一份假的文件表只会让 require 在某些包上碰巧成功、在另一些包上
     * 静默失败。所以这里返回空表，require 直接抛「module not found」，
     * 错误信息会出现在 PluginLoadOutcome.errors 里。
     *
     * 解法是给端口加文件读取能力（下一轮第一件事）。
     */
    private fun buildFileTable(): Map<String, String> = emptyMap()

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
    private class FileBridge(private val files: Map<String, String>) {

        @JavascriptInterface
        fun readFile(path: String): String? = files[path] ?: files[path.removePrefix("./")]
    }

    private companion object {
        const val BRIDGE_NAME = "PraxisHost"
        const val EMPTY_PAGE = "<html><body></body></html>"

    }
}
