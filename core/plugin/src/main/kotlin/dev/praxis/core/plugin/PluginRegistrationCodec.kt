package dev.praxis.core.plugin

/**
 * 插件脚本注册结果的解析（纯函数，可纯 JVM 单测）。
 *
 * ## 它接的是谁
 *
 * :app 的 WebView 运行时跑完包的 main 脚本后，把脚本注册的东西
 * 收集成一个 JSON 交给这里。形状由注入的引导脚本决定
 * （见 [PluginRuntimeBootstrapScript]）：
 *
 *     {
 *       "uiRoutes":  [ { id, route, runtime, title, keepAlive, hasScreen } ],
 *       "navEntries":[ { id, route, surface, title, icon, order, action } ]
 *     }
 *
 * ## 这里为什么没有 tools
 *
 * 工具**不来自注册阶段**：文档 §3.2.3 写明工具属于子包，由脚本开头的
 * METADATA 块静态声明（见 [ScriptMetadataParser]）。registerToolPkg() 只
 * 声明 UI 与钩子 —— §3.2.5 的示例里一个工具都没有。
 *
 * 早先这里解析过一段 tools[]，那是照着「总该有个 registerTool」的直觉写的；
 * 文档查清后已删除，免得留着一条永远不会被填的通道。
 *
 * ## 为什么把这一步单独抽出来
 *
 * WebView 只能在真机/模拟器上跑，而「注册结果怎么变成模型」是纯逻辑。
 * 抽出来之后，字段映射与容错都能在单测里锁住 —— 否则插件注册出错时
 * 只能靠猜，因为设备上只有一句「没反应」。
 *
 * ## 容错策略与清单解析一致
 *
 * 注册来自第三方脚本：单条坏数据不该让整包失败，但要**留下原因**。
 * 所以逐条收集问题，能解析的照常产出。
 */
object PluginRegistrationCodec {

    /** 解析结果。 */
    data class Result(
        val entries: List<PluginEntryPoint> = emptyList(),
        /** 逐条问题（缺必填、类型不对等）。空表示全部解析成功。 */
        val errors: List<String> = emptyList(),
    )

    fun parse(packageId: String, rawJson: String): Result {
        val root = ManifestParser.parse(rawJson).getOrNull()
            ?: return Result(errors = listOf("注册结果不是合法的 JSON 对象"))
        val fields = root.asMapping()

        val errors = mutableListOf<String>()

        val routes = fields.listOfMappings("uiRoutes").mapIndexedNotNull { index, item ->
            val id = item.text("id").trim()
            if (id.isEmpty()) {
                errors += "uiRoutes[" + index + "] 缺少 id，已跳过"
                return@mapIndexedNotNull null
            }
            PluginEntryPoint(
                id = id,
                kind = PluginEntryPoint.Kind.UiRoute,
                // route 由脚本给；它没给就按 Operit 规则生成。
                routeId = item.text("route").trim().ifEmpty { PluginEntryPoint.routeIdOf(packageId, id) },
                runtime = item.text("runtime").trim().ifEmpty { PluginEntryPoint.RUNTIME_COMPOSE_DSL },
                // 运行时的 screen 是模块句柄，不是路径 —— 这里只记「有没有」。
                screen = if (item["has_screen"]?.asBooleanOrNull() == true) SCREEN_FROM_SCRIPT else "",
                title = LocalizedTextCodec.parse(item["title"], "uiRoutes[" + index + "].title"),
                keepAlive = item["keep_alive"]?.asBooleanOrNull() ?: false,
            )
        }

        val navEntries = fields.listOfMappings("navEntries").mapIndexedNotNull { index, item ->
            val id = item.text("id").trim()
            if (id.isEmpty()) {
                errors += "navEntries[" + index + "] 缺少 id，已跳过"
                return@mapIndexedNotNull null
            }
            val surface = item.text("surface").trim()
            if (surface.isEmpty()) {
                // 与 Operit 的校验一致：导航项必须有挂载面，否则不知道该放哪。
                errors += "导航入口「" + id + "」缺少 surface，已跳过"
                return@mapIndexedNotNull null
            }
            val route = item.text("route").trim().takeIf { it.isNotEmpty() }
            val action = parseAction(item)
            if (route == null && action == null) {
                errors += "导航入口「" + id + "」既没有 route 也没有 action，已跳过"
                return@mapIndexedNotNull null
            }
            PluginEntryPoint(
                id = id,
                kind = PluginEntryPoint.Kind.Navigation,
                routeId = route,
                surface = surface,
                title = LocalizedTextCodec.parse(item["title"], "navEntries[" + index + "].title"),
                icon = item.text("icon").trim().takeIf { it.isNotEmpty() },
                order = item.text("order").trim().toIntOrNull() ?: 0,
                action = action,
            )
        }

        return Result(
            entries = routes + navEntries,
            errors = errors,
        )
    }

    /** 脚本注册的页面：屏幕是模块句柄，没有路径可显示。 */
    const val SCREEN_FROM_SCRIPT: String = "(脚本模块)"

    private fun parseAction(item: Map<String, ManifestValue>): PluginActionHook? {
        val name = item.text("action_function").trim()
        if (name.isEmpty()) return null
        return PluginActionHook(
            function = name,
            functionSource = item.text("action_source").trim().takeIf { it.isNotEmpty() },
        )
    }

    // ── ManifestValue 便利读取（与 ManifestMapper 的同名助手等价）──

    private fun Map<String, ManifestValue>.text(key: String): String = this[key]?.asText().orEmpty()

    private fun Map<String, ManifestValue>.listOfMappings(key: String): List<Map<String, ManifestValue>> {
        val value = this[key] ?: return emptyList()
        return when (value) {
            is ManifestValue.Mapping -> listOf(value.entries)
            is ManifestValue.Items -> value.values.mapNotNull { (it as? ManifestValue.Mapping)?.entries }
            else -> emptyList()
        }
    }
}
