package dev.praxis.core.plugin

/**
 * MCP 服务器配置。
 *
 * ## 字段与 Operit `MCPServerConfig` 对齐
 *
 * | 本字段 | Operit |
 * |---|---|
 * | [name] | `name` |
 * | [endpoint] | `endpoint` |
 * | [description] | `description` |
 * | [capabilities] | `capabilities` |
 * | [extraData] | `extraData` |
 *
 * 但额外加了 [transport] 与 [enabled]：Operit 把启用状态放在
 * PackageManager 的偏好里（与配置分离），那会让「导出配置」丢掉启停态。
 * 放在配置里更直观，导出即可迁移。
 */
data class McpServerConfig(
    val name: String,
    val endpoint: String,
    val description: String = "",
    /**
     * 传输方式。
     *
     * `stdio` 在 Android 上不可用（没有子进程模型），所以当前只支持
     * 网络类传输。保留该字段是为了读 Operit 导出的配置时能识别并给出
     * 明确提示，而不是静默失败。
     */
    val transport: McpTransport = McpTransport.StreamableHttp,
    val capabilities: List<String> = emptyList(),
    val extraData: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** 请求头（鉴权 token 等）。值可能含密钥，UI 上要遮蔽显示。 */
    val headers: Map<String, String> = emptyMap(),
) {
    /** 配置是否可用（缺 endpoint 或 stdio 都不可用）。 */
    val usability: String?
        get() = when {
            name.isBlank() -> "缺少名称"
            endpoint.isBlank() -> "缺少 endpoint"
            transport == McpTransport.Stdio ->
                "stdio 传输需要子进程，Android 上不可用；请改用 HTTP 或 SSE 端点"
            else -> null
        }
}

/**
 * MCP 传输方式。
 *
 * 三种是 MCP 规范定义的：`stdio`（本地子进程）、`sse`（旧版 HTTP 推送）、
 * `streamable-http`（新版）。Android 上没有稳定的子进程模型（`ProcessBuilder`
 * 只能起自带二进制），所以 stdio 只能拒绝并说明原因。
 */
enum class McpTransport(val storage: String, val label: String) {
    StreamableHttp("streamable-http", "Streamable HTTP"),
    Sse("sse", "SSE"),
    Stdio("stdio", "标准输入输出（不可用）"),
    ;

    companion object {
        fun fromStorage(value: String?): McpTransport = when (value?.trim()?.lowercase()) {
            "sse" -> Sse
            "stdio" -> Stdio
            // 空、http、streamable-http 都归到 StreamableHttp：
            // 这是新版默认，也是唯一在 Android 上稳定可用的。
            else -> StreamableHttp
        }
    }
}

/** MCP 工具（服务端声明的一个可调用工具）。 */
data class McpTool(
    val name: String,
    val description: String,
    val parameters: List<McpToolParameter> = emptyList(),
)

/** MCP 工具参数（由服务端 JSON Schema 摘要而来）。 */
data class McpToolParameter(
    val name: String,
    val description: String = "",
    /** JSON Schema 的 type：`string` / `number` / `boolean` / `object` / `array`。 */
    val type: String = "string",
    val required: Boolean = false,
)

/**
 * 把 MCP 服务器适配成统一扩展条目。
 *
 * ## 为什么不把 MCP 做成「插件包」
 *
 * Operit 的做法是把 MCP 服务器转成 `ToolPackage`（`MCPPackage.toToolPackage()`），
 * 混进同一套包列表。这带来一个问题：MCP 的「安装」其实是「连上并拉工具列表」，
 * 与「解压文件」完全不同 —— 混在一起后，卸载 MCP 会去删一个不存在的目录。
 *
 * Praxis 保留统一**视图**（用户看到一张列表），但存储与动作分开：
 * MCP 的启停就是「连不连」，没有文件操作。
 */
fun McpServerConfig.toExtensionEntry(toolCount: Int = -1): ExtensionEntry = ExtensionEntry(
    key = "${ExtensionEntry.Kind.McpServer.storage}:$name",
    kind = ExtensionEntry.Kind.McpServer,
    id = name,
    title = name,
    description = description.ifBlank { endpoint },
    version = "",
    authors = emptyList(),
    enabled = enabled,
    origin = PluginOrigin.Local,
    failure = usability,
    toolCount = toolCount,
)

/**
 * 把 Skill 适配成统一扩展条目。
 *
 * Skill 没有版本、没有作者 —— 它就是一个 Markdown 文件。
 * 强行填占位值（如 "1.0.0"）会让用户误以为可以更新。
 */
fun Skill.toExtensionEntry(enabled: Boolean): ExtensionEntry = ExtensionEntry(
    key = "${ExtensionEntry.Kind.Skill.storage}:$name",
    kind = ExtensionEntry.Kind.Skill,
    id = name,
    title = name,
    description = description.ifBlank { folder },
    version = "",
    authors = emptyList(),
    enabled = enabled,
    origin = PluginOrigin.Local,
    failure = null,
    toolCount = -1,
)

/**
 * 沙盒包（sandbox package）。
 *
 * ## 它和工具包有什么不同
 *
 * | | 工具包 | 沙盒包 |
 * |---|---|---|
 * | 目的 | 给 AI 增加**工具** | 给用户增加**界面**（Compose DSL 页面） |
 * | 入口 | `registerTool` | `registerUiRoute` / `registerScreen` |
 * | 谁看 | 模型 | 用户 |
 * | 触发 | 模型决定调用 | 用户点开 |
 *
 * Operit 里两者共用同一套 ToolPkg 装载机制（同一个 manifest、同一个 JS 引擎），
 * 区别只在注册的 API 不同。所以这里也复用 [PluginManifest]，只加一个
 * 标记位说明「这个包提供 UI」。
 *
 * ## 第一阶段的边界
 *
 * 能**识别、展示、启停**沙盒包，但 Compose DSL 渲染不在本轮：
 * 那需要一整套 DSL 解析 + 宿主组件映射（operit 的 `ToolPkgComposeDslParser`
 * 就有 1000+ 行）。先把包管理打通，UI 渲染作为独立能力后补。
 */
data class SandboxPackageInfo(
    val pluginId: String,
    val screens: List<SandboxScreen>,
)

/** 沙盒包声明的一个页面。 */
data class SandboxScreen(
    val id: String,
    val title: String,
    /** 清单里声明的路由（如 `my_tool/settings`）。 */
    val route: String,
    /** 是否常驻（离开页面不销毁运行时）。 */
    val keepAlive: Boolean = false,
)

/**
 * 从清单里挑出「这个包提供了哪些 UI 页面」。
 *
 * 判据：包里声明了 `subpackages` 之外的 UI 相关字段时无法从 manifest 直接看出 ——
 * Operit 的 UI 注册发生在**运行时**（JS 调 `registerUiRoute`），不在清单里。
 *
 * 所以这里只能给一个「可能提供 UI」的推断，真正的页面列表要在加载后由
 * 运行时回报。第一阶段把推断结果用于 UI 上的类型标签，不作为渲染来源。
 */
fun PluginManifest.providesUi(): Boolean =
    resources.any { it.mime.startsWith("text/html") || it.key.startsWith("ui.") } ||
        wasmModules.isNotEmpty()