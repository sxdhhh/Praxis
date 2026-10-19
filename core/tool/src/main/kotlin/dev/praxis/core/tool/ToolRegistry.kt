package dev.praxis.core.tool

/**
 * 工具注册表。
 *
 * 只做三件事：登记、查、按当前可见性导出声明。
 * **不做执行** —— 执行要带权限判定与结果格式化，拆到 [ToolExecutor]，
 * 这样注册表可以纯数据、能被单测穷举。
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
