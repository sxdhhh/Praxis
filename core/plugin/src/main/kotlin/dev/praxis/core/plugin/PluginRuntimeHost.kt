package dev.praxis.core.plugin

import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolRegistry
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import dev.praxis.core.tool.PermissionTier

/**
 * 插件声明的工具（由脚本在运行时注册）。
 *
 * ## 为什么参数 schema 是字符串
 *
 * 脚本侧注册工具时传的是 JS 对象（`parameters: { type: "object", ... }`）。
 * 宿主把它序列化成 JSON 字符串交给 [ToolSpec] —— 与内置工具一致
 * （见 `ToolContract` 的说明：schema 方言太杂，手写字符串最可控）。
 */
data class PluginToolDeclaration(
    val name: String,
    val description: String,
    val parametersJson: String = EMPTY_SCHEMA,
    /** 建议的执行入口（脚本里的函数名）。空表示用工具名。 */
    val entryFunction: String = "",
) {
    companion object {
        /** 无参工具的规范 schema。不能省略 `parameters` 字段。 */
        const val EMPTY_SCHEMA: String = """{"type":"object","properties":{}}"""
    }
}

/**
 * 脚本执行端口。
 *
 * ## 为什么这层必须抽象
 *
 * 脚本在 WebView 里跑（与 Operit 一致），但那意味着：
 *
 * - 执行是**异步**的（`evaluateJavascript` 回调，还要等桥接回传）；
 * - 需要主线程（WebView 只能在有 Looper 的线程建）；
 * - 每个插件可能需要独立引擎（互不干扰的全局变量）。
 *
 * `core:plugin` 是纯 JVM，不能出现 `android.webkit`。所以把「跑一段 JS 并
 * 拿回结果」抽成这个端口，`:app` 用 WebView 实现，单测用假实现
 * （直接返回预设的注册列表）。
 *
 * ## 与 Operit 的对应
 *
 * Operit 的 `JsEngine` 有 2767 行，包含工具调用桥、Compose DSL 宿主、
 * 图片池、路由网关等。这一版只抽「执行 + 注册回调」最小面：
 * 先把包能装上、工具能注册、能调用打通，其余能力按需追加。
 */
interface PluginScriptRuntime {

    /**
     * 装载一个包：执行主入口与启用的子包，收集它们注册的工具。
     *
     * @param packageId 包 id（用于日志与隔离）
     * @param mainScript 主入口脚本正文
     * @param subpackageScripts 子包 id → 脚本正文（只含启用的）
     * @param resourceResolver 资源 key → 字节（图标、模板等按需取）
     * @return 注册结果；失败时返回错误而不是抛（一个包坏了不该影响别的）
     */
    suspend fun load(
        packageId: String,
        mainScript: String,
        subpackageScripts: Map<String, String>,
        resourceResolver: (String) -> ByteArray?,
    ): PluginLoadOutcome

    /**
     * 调用一个已注册的插件工具。
     *
     * @param argumentsJson 模型给的参数（**原样**传，不预解析）
     */
    suspend fun invoke(
        packageId: String,
        toolName: String,
        entryFunction: String,
        argumentsJson: String,
    ): ToolResult

    /** 卸载一个包：销毁它的运行上下文，释放 WebView。 */
    suspend fun unload(packageId: String)
}

/** 装载结果。 */
data class PluginLoadOutcome(
    val tools: List<PluginToolDeclaration>,
    /** 注册过程中出现的错误（JS 抛异常、脚本语法错等）。 */
    val errors: List<String> = emptyList(),
) {
    val isUsable: Boolean get() = errors.isEmpty() || tools.isNotEmpty()
}

/**
 * 插件运行时宿主：把「插件」变成「工具」。
 *
 * ## 它解决的核心问题：工具的生命周期
 *
 * 内置工具是「注册一次，活到进程结束」。插件工具不同：
 *
 * | 动作 | 工具会发生什么 |
 * |---|---|
 * | 启用插件 | 创建 |
 * | 停用插件 | **必须消失**（否则模型会一直尝试调用不存在的工具） |
 * | 更新插件 | 替换（同名工具换成新实现） |
 * | 卸载插件 | 消失 + 释放运行时 |
 *
 * [ToolRegistry] 原本只有「注册（重名抛错）」，所以这一轮补了
 * `registerOrReplaceAll` / `removeWhere` —— 就是为这里服务的。
 *
 * ## 为什么要记录「每个插件注册了哪些工具」
 *
 * 卸载时只能拿到包 id，工具名是插件自己起的。所以**注册时必须记账**，
 * 否则停用插件后那些工具会永远留在注册表里，模型能看到、调用必失败。
 */
class PluginRuntimeHost(
    private val registry: ToolRegistry,
    private val runtime: PluginScriptRuntime,
) {

    /** 包 id → 该包注册的工具名（按注册顺序）。 */
    private val registeredTools = mutableMapOf<String, MutableList<String>>()

    /**
     * 已装载的包 id。
     *
     * ## 为什么只记 id 不记工具详情
     *
     * 工具详情（描述、schema）已经在 `ToolRegistry` 里（通过 `PluginBackedTool`），
     * 在这里再存一份就是第二份真相 —— 更新时容易只改一边。
     * 这里只需要知道「哪些包装载过」，用于判断 unload 是否需要调 runtime。
     */
    private val loadedPackageIds = mutableSetOf<String>()

    /**
     * 装载一个已安装且启用的插件。
     *
     * ## 顺序：先卸载旧版本，再装新的
     *
     * 更新插件时如果不先卸，旧工具会残留（尤其是新版本删掉了某个工具 ——
     * 那个工具会一直留在注册表里，调用时指向已卸载的运行时）。
     *
     * ## 失败也要记账
     *
     * 脚本可能装载失败（语法错、API 版本不符）。此时不注册任何工具，
     * 但要把错误返回给上层展示。**不抛异常**：一个包坏了不该让
     * 整个启动流程崩掉，用户就没法进应用去删它了。
     */
    suspend fun load(plugin: InstalledPlugin, content: PluginArchiveContent): PluginLoadOutcome {
        unload(plugin.id)

        if (!plugin.enabled) {
            return PluginLoadOutcome(emptyList(), listOf("插件未启用，跳过装载"))
        }
        val fatal = plugin.failureText
        if (fatal != null) {
            return PluginLoadOutcome(emptyList(), listOf("清单有问题：$fatal"))
        }

        val mainScript = content.mainScript
            ?: return PluginLoadOutcome(emptyList(), listOf("主入口脚本 '${content.manifest.main}' 读取失败"))

        // 只装启用中的子包。
        val enabledSubpackageScripts = content.subpackageScripts
            .filterKeys { plugin.isSubpackageEnabled(it) }
            .mapNotNull { (id, script) -> script?.let { id to it } }
            .toMap()

        val outcome = runtime.load(
            packageId = plugin.id,
            mainScript = mainScript,
            subpackageScripts = enabledSubpackageScripts,
            resourceResolver = { key -> content.resourceBytes[key] },
        )

        if (outcome.tools.isEmpty()) {
            return outcome
        }

        // 转成 Tool 并注册（允许替换：更新场景）。
        val toolObjects = outcome.tools.map { declaration ->
            PluginBackedTool(
                packageId = plugin.id,
                declaration = declaration,
                runtime = runtime,
            )
        }
        registry.registerOrReplaceAll(toolObjects)

        registeredTools[plugin.id] = outcome.tools.map { it.name }.toMutableList()
        loadedPackageIds.add(plugin.id)
        return outcome
    }

    /**
     * 卸载一个插件：移除它的全部工具并销毁运行时。
     *
     * 幂等：对没装载过的包调用不做事。
     */
    suspend fun unload(packageId: String) {
        val names = registeredTools.remove(packageId).orEmpty()
        if (names.isNotEmpty()) {
            registry.removeAll(names)
        }
        if (loadedPackageIds.remove(packageId)) {
            runCatching { runtime.unload(packageId) }
        }
    }

    /** 某个包现在贡献了哪些工具。 */
    fun toolsOf(packageId: String): List<String> = registeredTools[packageId].orEmpty().toList()

    /** 全部插件贡献的工具名（调试与 UI 统计用）。 */
    fun allPluginToolNames(): Set<String> = registeredTools.values.flatten().toSet()

    /** 已装载的包数。 */
    val loadedCount: Int get() = loadedPackageIds.size
}

/**
 * 由插件提供的工具。
 *
 * ## 每次执行都转发到脚本
 *
 * 不在宿主侧缓存任何脚本状态：插件的工具可能读写自己的模块级变量，
 * 必须由**同一个**脚本上下文处理（见 [PluginScriptRuntime.invoke] 的
 * packageId 参数）。宿主只是个路由器。
 */
private class PluginBackedTool(
    private val packageId: String,
    private val declaration: PluginToolDeclaration,
    private val runtime: PluginScriptRuntime,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = declaration.name,
        description = declaration.description,
        parametersJson = declaration.parametersJson.ifBlank {
            PluginToolDeclaration.EMPTY_SCHEMA
        },
        // ## 为什么插件工具一律 STANDARD
        //
        // 插件脚本跑在 WebView 沙盒里，它能做的事由**它调用的宿主工具**
        // 的档位决定（脚本里调 `Tools.Files.write` 时，那次调用会走
        // ToolExecutor 的权限判定）。给插件工具本身定 DEBUGGER/ROOT
        // 是重复且错误的：那会让「一个只读文件列表的插件」也要求 ROOT。
        tier = PermissionTier.STANDARD,
        // 但风险等级按 Dangerous 标：插件是第三方代码，
        // UI 上该提示用户「这会执行外部脚本」。
        risk = ToolRisk.Dangerous,
    )

    override suspend fun execute(argumentsJson: String, context: dev.praxis.core.tool.ToolContext): ToolResult =
        try {
            runtime.invoke(
                packageId = packageId,
                toolName = declaration.name,
                entryFunction = declaration.entryFunction.ifBlank { declaration.name },
                argumentsJson = argumentsJson,
            )
        } catch (error: Throwable) {
            // 工具失败是**正常业务结果**（脚本里抛异常也是），
            // 要作为可读文本回传给模型，而不是让整轮对话崩掉。
            ToolResult(
                content = "插件工具 '${declaration.name}' 执行失败：${error.message ?: error.javaClass.simpleName}",
                isError = true,
            )
        }
}