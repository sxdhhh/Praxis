package dev.praxis.core.plugin

/**
 * 插件加载顺序：依赖优先的拓扑排序。
 *
 * ## 为什么必须有这一层
 *
 * 插件之间可以声明 `requires`。被依赖的包必须先注册它的工具，
 * 否则依赖方在入口脚本里调用 `Tools.Xxx` 时拿到 `undefined` ——
 * 报错是 `undefined is not a function`，完全看不出是加载顺序问题。
 *
 * ## 循环依赖必须报错而不是「随便挑一个先加载」
 *
 * 两个包互相依赖时，无论先加载哪个都会有一个拿到 undefined。
 * 静默挑一个的后果是「有时能用、有时不能用」，取决于目录遍历顺序 ——
 * 这是最难复现的一类 bug（用户重装一次就好了，下次又坏）。
 * 所以：检测到环就明确列出参与的包名，让用户去删其中一个。
 *
 * ## 稳定排序
 *
 * 同一层（无依赖关系）的包按 [InstalledPlugin.id] 字典序输出。
 * 不这样做的话顺序取决于 `HashMap` 迭代顺序，同一份插件集在
 * 不同设备上加载顺序不同 —— 「我这边好的，用户那边坏」的经典来源。
 */
object PluginLoadOrder {

    /** 排序结果。 */
    data class Ordering(
        /** 可以加载的包，依赖在前。 */
        val ordered: List<InstalledPlugin>,
        /**
         * 被跳过的包：依赖缺失或版本不满足。
         * 键是包 id，值是可读原因。
         */
        val skipped: Map<String, String>,
        /** 检测到的依赖环。每项是一个环上的 id 列表（首尾相同）。 */
        val cycles: List<List<String>>,
    ) {
        /** 是否有包因为依赖问题被跳过。 */
        val hasProblems: Boolean get() = skipped.isNotEmpty() || cycles.isNotEmpty()
    }

    /**
     * 排序。
     *
     * @param plugins 全部已安装包（含未启用的 —— 未启用的会被排除在加载外，
     *   但仍参与「依赖是否满足」的判断：一个被禁用的依赖包
     *   会导致依赖它的包也无法加载，这必须如实报出来）
     */
    fun resolve(plugins: List<InstalledPlugin>): Ordering {
        val byId = plugins.associateBy { it.id }
        val skipped = LinkedHashMap<String, String>()

        // ── 1. 先按「包自身可用性」筛 ──────────────────────────
        val candidates = plugins.filter { plugin ->
            when {
                plugin.problems.any { it is ManifestProblem.Fatal } -> {
                    val fatal = plugin.problems.first { it is ManifestProblem.Fatal }
                        as ManifestProblem.Fatal
                    skipped[plugin.id] = "清单有问题（${fatal.field}）：${fatal.message}"
                    false
                }
                !plugin.enabled -> {
                    // 未启用不算「跳过错误」，只是不加载。不放进 skipped（那是错误清单）。
                    false
                }
                else -> true
            }
        }

        // ── 2. 校验依赖是否满足 ───────────────────────────────
        //
        // ## 为什么要迭代到不动点，而不是一遍过
        //
        // 依赖可能成链：A 依赖 B，B 依赖 C，而 C 的清单坏了。
        // 一遍过时 A 看到的 B「存在且已启用」，于是 A 判定通过；
        // 但 B 随后因为 C 被跳过，A 就变成「依赖了一个加载不了的包」，
        // 而它已经在 eligible 里了 —— 结果是 A 加载时 B 的工具不存在，
        // 报的错是 `undefined is not a function`，与真正原因隔了三层。
        //
        // 所以循环剔除，直到没有新的失败者。链长最多等于包数，开销可忽略。
        var eligible = candidates
        var changed = true
        while (changed) {
            changed = false
            val stillEligible = eligible.filter { plugin ->
                val failure = firstDependencyFailure(plugin, byId, eligible)
                if (failure != null) {
                    skipped[plugin.id] = failure
                    changed = true
                    false
                } else {
                    true
                }
            }
            eligible = stillEligible
        }

        // ── 3. 拓扑排序（Kahn，带稳定次序）────────────────────
        val eligibleIds = eligible.map { it.id }.toSortedSet()
        val inDegree = eligible.associate { it.id to 0 }.toMutableMap()
        val dependents = LinkedHashMap<String, MutableList<String>>()

        eligible.forEach { plugin ->
            plugin.manifest.requires.forEach { requirement ->
                // 只统计「在 eligible 里」的依赖边：
                // 依赖一个未启用的包，在上面已经被 skipped 掉，这里不会有它。
                if (requirement.id in eligibleIds) {
                    inDegree[plugin.id] = (inDegree[plugin.id] ?: 0) + 1
                    dependents.getOrPut(requirement.id) { mutableListOf() }.add(plugin.id)
                }
            }
        }

        val ordered = mutableListOf<InstalledPlugin>()
        // TreeSet 保证同层按 id 字典序，产出稳定顺序。
        val ready = java.util.TreeSet<String>()
        inDegree.filterValues { it == 0 }.keys.forEach { ready.add(it) }

        while (ready.isNotEmpty()) {
            val id = ready.pollFirst()!!
            byId[id]?.let { ordered.add(it) }
            dependents[id].orEmpty().forEach { child ->
                val next = (inDegree[child] ?: 0) - 1
                inDegree[child] = next
                if (next == 0) ready.add(child)
            }
        }

        // ── 4. 剩下的就是环 ───────────────────────────────────
        val unresolved = eligibleIds - ordered.map { it.id }.toSet()
        val cycles = if (unresolved.isEmpty()) {
            emptyList()
        } else {
            listOf(findCycle(unresolved, eligible))
        }

        return Ordering(ordered = ordered, skipped = skipped, cycles = cycles)
    }

    /**
     * 检查一个包的依赖是否都满足。
     *
     * 三种失败：
     * 1. 依赖的包根本没装
     * 2. 依赖的包装了但**未启用**（用户关了它）
     * 3. 版本不满足约束
     */
    private fun firstDependencyFailure(
        plugin: InstalledPlugin,
        byId: Map<String, InstalledPlugin>,
        eligible: List<InstalledPlugin>,
    ): String? {
        val eligibleIds = eligible.map { it.id }.toSet()
        plugin.manifest.requires.forEach { requirement ->
            val target = byId[requirement.id]
            when {
                target == null -> return "缺少依赖包 '${requirement.id}'" +
                    (requirement.constraintText?.let { "（要求 $it）" } ?: "")
                !target.enabled -> return "依赖包 '${requirement.id}' 已被禁用，本包无法启用"
                requirement.id !in eligibleIds && target.problems.any {
                    it is ManifestProblem.Fatal
                } -> return "依赖包 '${requirement.id}' 自身清单有问题"
                else -> requirement.validate(target.manifest.version)?.let { return it }
            }
        }
        return null
    }

    /**
     * 在剩余的（未排序成功的）节点里找出一个环。
     *
     * 用 DFS 找环而不是只报「这些包互相依赖」：用户需要知道**具体哪几个**。
     * 把整个剩余集合一股脑列出来时，用户面对 20 个包无法判断该删哪个。
     */
    private fun findCycle(
        remaining: Set<String>,
        eligible: List<InstalledPlugin>,
    ): List<String> {
        val dependencies = eligible.associate { plugin ->
            plugin.id to plugin.manifest.requires.map { it.id }.filter { it in remaining }
        }

        val visited = mutableSetOf<String>()
        val stack = mutableListOf<String>()
        val onStack = mutableSetOf<String>()

        fun dfs(node: String): List<String>? {
            if (node in onStack) {
                // 找到环：从栈里截出环的部分，首尾闭合便于显示。
                val start = stack.indexOf(node)
                return stack.subList(start, stack.size).toList() + node
            }
            if (node in visited) return null
            visited.add(node)
            onStack.add(node)
            stack.add(node)
            dependencies[node].orEmpty().forEach { next ->
                dfs(next)?.let { return it }
            }
            stack.removeAt(stack.lastIndex)
            onStack.remove(node)
            return null
        }

        remaining.sorted().forEach { node ->
            dfs(node)?.let { return it }
        }
        // 理论上不可达（剩余节点必然含环），兜底返回剩余集合本身。
        return remaining.sorted()
    }
}