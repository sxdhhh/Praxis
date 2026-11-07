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
 * 待执行的脚本及其在包内的路径。
 *
 * ## 为什么必须带路径
 *
 * 真实包是多文件产物：`dist/main.js` 里写 `require("./ui/x.js")`。
 * 不给出「当前模块是谁」，宿主就无法把相对路径解析成包内路径 ——
 * 只能猜，而猜错的表现是「注册阶段第一步就抛错」，作者完全无从判断。
 *
 * 路径语义与 [PluginArchive.normalizePath] 一致：包根相对、`/` 分隔。
 */
data class PluginScriptSource(
    val path: String,
    val text: String,
)

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
     * 装载一个包：执行主入口与启用的子包，收集它们注册的工具与入口。
     *
     * @param packageId 包 id（用于日志与隔离）
     * @param main 主入口脚本（**带路径**：脚本里的 require 是相对自身目录的）
     * @param subpackages 启用的子包：id → 脚本（同样带路径）
     * @param resourceResolver 资源 key → 字节（图标、模板等按需取）
     * @param readFile 包内相对路径 → 文本；供脚本 require 用。
     *        路径一律是**包根相对**、`/` 分隔。返回 null 表示该文件不存在。
     * @return 注册结果；失败时返回错误而不是抛（一个包坏了不该影响别的）
     */
    suspend fun load(
        packageId: String,
        main: PluginScriptSource,
        subpackages: List<Pair<String, PluginScriptSource>>,
        resourceResolver: (String) -> ByteArray?,
        readFile: (String) -> String?,
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

    /**
     * 取某个 UI 入口的渲染树（compose_dsl）。
     *
     * ## 为什么返回原始 JSON 而不是已解析的树
     *
     * 解析（[ComposeDslParser]）是纯逻辑，放 core:plugin 里能单测；端口只负责
     * 「让脚本跑起来、把 JSON 拿回来」。混在一起会让运行时依赖 UI 模块。
     *
     * @param entryId 注册时给出的入口 id
     * @return 脚本返回的 { tree, state, memo }；失败时 Result.failure
     */
    suspend fun render(packageId: String, entryId: String): Result<String>

    /** 卸载一个包：销毁它的运行上下文，释放 WebView。 */
    suspend fun unload(packageId: String)
}

/** 装载结果。 */
data class PluginLoadOutcome(
    val tools: List<PluginToolDeclaration>,
    /** 注册过程中出现的错误（JS 抛异常、脚本语法错等）。 */
    val errors: List<String> = emptyList(),
    /**
     * 脚本注册的 UI 入口（registerUiRoute / registerNavigationEntry 等）。
     *
     * ## 与 tools 是**两条独立的注册线**
     *
     * Operit 的 examples/sidebar_sillytavern 这类包**只注册入口、不注册工具**。
     * 所以判断「这次装载有没有产出」不能只看 tools，否则这类包会被当成空载。
     */
    val entryPoints: List<PluginEntryPoint> = emptyList(),
) {
    val isUsable: Boolean
        get() = errors.isEmpty() || tools.isNotEmpty() || entryPoints.isNotEmpty()
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
     * 包 id → 该包捕获到的 UI 入口（运行时注册的）。
     *
     * 与 [registeredTools] 同理：卸载时只能拿到包 id，所以注册时就要记账 ——
     * 否则停用后入口会永远留在「兼容插件入口」列表里，用户点了必然失败。
     */
    private val registeredEntries = mutableMapOf<String, List<PluginEntryPoint>>()

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

        // 只装启用中的子包。路径一起带上：脚本里的 require 相对自身目录。
        //
        // 用**已安装插件**的 manifest 而不是 content 的：启停状态记录在
        // 安装态上（disabledSubpackageIds），而 content 只是这次从归档里
        // 读到的内容 —— 两者在「用户关掉某个子包」时会不一致。
        val enabledSubpackages = plugin.manifest.subpackages
            .filter { plugin.isSubpackageEnabled(it.id) }
            .mapNotNull { sub ->
                val text = content.subpackageScripts[sub.id] ?: return@mapNotNull null
                sub.id to PluginScriptSource(path = sub.entry, text = text)
            }

        val outcome = runtime.load(
            packageId = plugin.id,
            main = PluginScriptSource(path = plugin.manifest.main, text = mainScript),
            subpackages = enabledSubpackages,
            resourceResolver = { key -> content.resourceBytes[key] },
            // 路径先归一化再查表：`./a.js` 与 `a.js` 是同一个文件，
            // 而表的键是归一化后的形态。
            readFile = { raw -> PluginArchive.normalizePath(raw)?.let { content.textFiles[it] } },
        )

        // ## 工具来自子包脚本的 METADATA（2026-11-08，R7）
        //
        // 文档 §3.2.3：工具属于子包，注册名 <subpackage_id>:<tool_name>，
        // 由脚本开头的 METADATA 块**静态声明**。registerToolPkg() 只声明
        // UI 与钩子（§3.2.5 的示例里一个工具都没有）。
        //
        // 之前 Praxis 假设存在一个 registerTool 运行时 API —— 它并不存在，
        // 所以 registeredTools 一直是空的，模型看不到任何插件工具。
        val metadataErrors = mutableListOf<String>()
        val metadataTools = enabledSubpackages.flatMap { (subId, source) ->
            val parsed = ScriptMetadataParser.parse(source.text)
            when {
                // 没有 METADATA 不算错误：不是每个子包都提供工具。
                parsed == null -> emptyList()
                parsed.isFailure -> {
                    metadataErrors += "子包 " + subId + " 的 METADATA 解析失败：" +
                        (parsed.exceptionOrNull()?.message ?: "未知原因")
                    emptyList()
                }
                else -> ScriptMetadataParser.toToolDeclarations(subId, parsed.getOrThrow())
            }
        }
        val allTools = metadataTools + outcome.tools

        // ## 入口先记账，工具后注册（2026-11-08）
        //
        // 旧实现在 tools 为空时直接 return —— 而 sidebar_sillytavern 那类包
        // 只注册 UI 入口、一个工具都不注册，于是入口被整个丢掉，
        // 表现就是「装了包但兼容插件入口页是空的」。
        //
        // 入口与工具是两条独立的线，各自记账，互不依赖。
        if (outcome.entryPoints.isNotEmpty()) {
            registeredEntries[plugin.id] = outcome.entryPoints.toList()
        }

        if (allTools.isNotEmpty()) {
            // 转成 Tool 并注册（允许替换：更新场景）。
            val toolObjects = allTools.map { declaration ->
                PluginBackedTool(
                    packageId = plugin.id,
                    declaration = declaration,
                    runtime = runtime,
                )
            }
            registry.registerOrReplaceAll(toolObjects)
            registeredTools[plugin.id] = allTools.map { it.name }.toMutableList()
        }

        // 只要脚本真的产出过东西（工具或入口），就算已装载 ——
        // 卸载时要据此释放运行时。只注册入口的包同样持有运行上下文。
        if (allTools.isNotEmpty() || outcome.entryPoints.isNotEmpty()) {
            loadedPackageIds.add(plugin.id)
        }

        // 返回的 outcome 要反映**实际注册了什么**：调用方据此展示
        // 「这个包提供了几个工具」，报旧值会让界面与注册表对不上。
        return outcome.copy(
            tools = allTools,
            errors = outcome.errors + metadataErrors,
        )
    }

    /**
     * 卸载一个插件：移除它的全部工具并销毁运行时。
     *
     * 幂等：对没装载过的包调用不做事。
     */
    suspend fun unload(packageId: String) {
        registeredEntries.remove(packageId)
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

    /** 某个包捕获到哪些入口（运行时注册的）。 */
    fun entriesOf(packageId: String): List<PluginEntryPoint> =
        registeredEntries[packageId].orEmpty()

    /**
     * 全部包捕获到的入口（包 id → 入口）。
     *
     * 返回副本：调用方合并时不应该改动宿主内部的记账表。
     */
    fun capturedEntries(): Map<String, List<PluginEntryPoint>> =
        registeredEntries.mapValues { it.value.toList() }

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