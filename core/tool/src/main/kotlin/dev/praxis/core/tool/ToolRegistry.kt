package dev.praxis.core.tool

/**
 * 工具注册表。
 *
 * 只做三件事：登记、查、按当前可见性导出声明。
 * **不做执行** —— 执行要带权限判定与结果格式化，拆到 [ToolExecutor]，
 * 这样注册表可以纯数据、能被单测穷举。
 *
 * ## 两套登记语义，按调用方区分
 *
 * | 方法 | 重名时 | 谁用 |
 * |---|---|---|
 * | [register] / [registerAll] | **抛异常** | 内置工具（重名一定是笔误） |
 * | [registerOrReplaceAll] / [replace] | **覆盖** | 插件（重名 = 同一插件的新旧版本） |
 *
 * 插件还有卸载需求（[removeWhere] / [removeAll]），因为「关掉一个插件」
 * 必须让它注册的工具从模型可见列表里消失 —— 否则模型会一直尝试调用
 * 一个已经不可用的工具，每次都失败，白烧 token。
 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, Tool>()

    /**
     * 登记一个工具。
     *
     * 重名直接抛异常而不是覆盖：重名一般是复制粘贴漏改，静默覆盖会让
     * 排查变成「明明注册了却调到了旧实现」。
     */
    fun register(tool: Tool) {
        val name = tool.spec.name
        if (tools.containsKey(name)) {
            throw ToolException("duplicate tool name: $name")
        }
        tools[name] = tool
    }

    fun registerAll(vararg toolList: Tool) = toolList.forEach(::register)

    /**
     * 批量登记，**允许替换**同名工具。
     *
     * ## 为什么插件必须用这个而不是 [register]
     *
     * 插件是「可卸载、可重装、可更新」的。重装一个已装过的插件时，
     * 它的工具名与上次完全一样 —— 用 [register] 会抛
     * `duplicate tool name`，于是「更新插件」直接失败。
     *
     * ## 为什么替换要单独一个方法而不是放宽 [register]
     *
     * 内置工具之间重名几乎一定是笔误（复制粘贴漏改），那种情况应该崩，
     * 好让开发期立刻发现。只有插件这条路径上「同名 = 同一个插件的
     * 新旧版本」是正常的，所以只有它走替换。
     *
     * @return 被替换掉的旧工具名（便于调用方记日志：更新了哪几个）
     */
    fun registerOrReplaceAll(toolList: List<Tool>): List<String> {
        val replaced = mutableListOf<String>()
        toolList.forEach { tool ->
            val name = tool.spec.name
            if (tools.containsKey(name)) replaced += name
            tools[name] = tool
        }
        return replaced
    }

    /**
     * 卸载插件注册的全部工具（按判定函数筛选）。
     *
     * ## 为什么要按判定函数而不是「按包 id」
     *
     * 工具名是插件自己起的，宿主没有强制前缀。卸载时我们手里只有包 id，
     * 所以需要调用方自己决定「哪些名字属于这个包」——
     * 通常是插件在注册时自己记下的一份名单（见 `PluginRuntimeHost`）。
     *
     * 如果宿主反过来强制「所有插件工具必须叫 `pkgid__tool`」，
     * 确实能按前缀精确卸载，但会破坏与 operit 生态的兼容
     * （那边工具名是完全自由的），所以不做。
     *
     * @return 实际移除的工具名
     */
    fun removeWhere(predicate: (String) -> Boolean): List<String> {
        val removed = tools.keys.filter(predicate)
        removed.forEach { tools.remove(it) }
        return removed
    }

    /** 精确移除若干工具。@return 实际存在的（被移除的）名字 */
    fun removeAll(names: Collection<String>): List<String> {
        val removed = names.filter { tools.containsKey(it) }
        removed.forEach { tools.remove(it) }
        return removed
    }

    /** 替换单个工具（插件热更新单个工具时用）。 */
    fun replace(tool: Tool) {
        tools[tool.spec.name] = tool
    }

    fun find(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    fun names(): Set<String> = tools.keys.toSet()

    /**
     * 按权限档位导出可用工具声明（直接喂给 core:ai 的 ChatRequest.tools）。
     *
     * 过滤规则：只暴露调用者当前档位**能实际执行**的工具。
     * 把无权使用的工具发给模型是坏主意 —— 它会反复尝试，每次都被拒，
     * 白烧 token 还让用户觉得「这 AI 很笨」。
     */
    fun descriptorsFor(tier: PermissionTier): List<ToolSpec> =
        tools.values.map { it.spec }.filter { tier.allows(it.tier) }
}

/**
 * 档位是否覆盖目标档位。
 *
 * 严格按「够用即可」：ROOT 能用 STANDARD/DEBUGGER/ROOT，反之不行。
 * 用 `ordinal >=` 而不是手写 when，保证加档位时不会漏（虽然现在只有三档）。
 */
fun PermissionTier.allows(required: PermissionTier): Boolean =
    this.ordinal >= required.ordinal

/**
 * 默认权限策略。
 *
 * 设计取向：**宁可多问一次，不可默认放行**。
 *  - 已授予的档位覆盖 → ALLOW
 *  - 差一档（缺 DEBUGGER 或 ROOT）→ ASK（用户可当场提权）
 *  - 差两档以上（STANDARD 想跑 ROOT 工具）→ FORBID
 *
 * 为什么不用「统一 ASK」：每次都问会让用户条件反射点允许，
 * 反而比 FORBID 更不安全。
 */
object DefaultPermissionPolicy {

    fun decide(granted: PermissionTier, required: PermissionTier): PermissionDecision = when {
        granted.allows(required) -> PermissionDecision.ALLOW
        required.ordinal - granted.ordinal == 1 -> PermissionDecision.ASK
        else -> PermissionDecision.FORBID
    }
}
