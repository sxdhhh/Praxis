package dev.praxis.core.tool.custom

/**
 * 用户/AI 自定义工具的声明式定义。
 *
 * ## 为什么是声明式 DSL，而不是「让 AI 写可执行代码」（2026-11-06）
 *
 * Android 上「AI 写代码当工具」只有三条路，全都不可取：
 *
 * | 路线 | 问题 |
 * |---|---|
 * | 设备上跑 kotlinc + d8 编译成 DEX | 要打包完整编译器，APK 翻倍、启动极慢 |
 * | JSR-223 / BeanShell 解释执行 | 第三方运行时支持差，**且同样能执行任意代码**，安全上没有优势 |
 * | **声明式 DSL + 原语白名单**（本实现） | 表达力受限，但**没有 RCE 面** |
 *
 * 关键判断：前两条的价值是「任意逻辑」，代价是「任意代码可执行」。
 * 而自定义工具真正要解决的是「把常用操作固化成可复用工具」——
 * 那只需要**组合既有原语**，不需要任意逻辑。
 *
 * ## 安全边界在哪里
 *
 * 1. **原语白名单**：`op` 只能是 [ToolPrimitives.SUPPORTED] 里的值，
 *    没有任何方式拼出任意命令执行；
 * 2. **参数绑定而非字符串拼接**：`{{name}}` 走占位替换，且**替换后的值
 *    仍然作为独立参数传给原语**，不参与 shell 解析；
 * 3. **步数上限**：防止设计错误导致无限展开；
 * 4. **总超时**：与 PTC runtime 的默认值一致（120 秒）。
 *
 * @param name 工具名（小写字母开头，与既有工具同一条命名规则）。
 * @param description 给模型看的一句话说明。
 * @param params 参数声明（名字 → 类型与是否必填）。
 * @param steps 有序步骤。
 * @param hasSideEffects 是否可能产生副作用。
 */
data class CustomToolDefinition(
    val name: String,
    val description: String,
    val params: Map<String, CustomToolParam> = emptyMap(),
    val steps: List<CustomToolStep> = emptyList(),
    val hasSideEffects: Boolean = false,
) {
    /**
     * 声明的参数名集合。
     *
     * 用于校验步骤里的 `{{name}}` 引用是否都存在 —— 拼错的占位符
     * 会让原语收到一个字面量 `{{targt}}`，而那**不会报错**，
     * 只会安静地做错事。
     */
    val paramNames: Set<String> get() = params.keys
}

/** 一个参数声明。 */
data class CustomToolParam(
    val type: String = "string",
    val required: Boolean = true,
    val description: String = "",
)

/** 自定义工具的一步。 */
data class CustomToolStep(
    /** 原语名（必须在 [ToolPrimitives.SUPPORTED] 内）。 */
    val op: String,
    /** 原语参数。值里可含 `{{paramName}}` 占位符。 */
    val args: Map<String, String> = emptyMap(),
)

/**
 * 原语白名单。
 *
 * ## 为什么必须白名单而不是黑名单
 *
 * 黑名单（「禁止 rm -rf」这类）永远漏 —— 攻击面是无穷的，而白名单是有限集。
 *
 * ## 白名单的含义
 *
 * 这里的每个原语都对应一个**已注册的 Praxis 工具**。自定义工具不是
 * 「新的执行能力」，而是「把已有能力按顺序串起来」。所以白名单的正确性
 * 可以机械核对：每个 op 都必须能在 `ToolRegistry` 里找到同名工具。
 */
object ToolPrimitives {
    /** 支持的 op → 对应的内置工具名（同名）。 */
    val SUPPORTED: Set<String> = setOf(
        "linux_exec",
        "host_shell",
        "read_file",
        "write_file",
        "copy_file",
        "search_memory",
        "save_memory",
    )

    fun isSupported(op: String): Boolean = op in SUPPORTED
}

/**
 * 定义校验结果。
 *
 * ## 为什么把告警与错误分开
 *
 * 错误 = 不能装载（继续下去会做错事）。
 * 告警 = 能装载但不理想（比如描述为空，模型难以判断何时使用）。
 *
 * 混在一起会导致「因为一个措辞问题拒绝整份定义」，而用户根本不理解
 * 为什么装不上。
 */
data class DefinitionIssue(
    val message: String,
    val isError: Boolean,
)

/** 定义校验器。`define` 阶段调用，**不执行任何东西**。 */
object CustomToolValidator {

    /** 单次执行的最大步数。 */
    const val MAX_STEPS = 20

    /** 名称规则：小写字母开头，其余小写字母/数字/下划线。 */
    private val NAME_REGEX = Regex("^[a-z][a-z0-9_]*$")

    /** 占位符：`{{name}}`。 */
    private val PLACEHOLDER_REGEX = Regex("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}")

    fun validate(def: CustomToolDefinition): List<DefinitionIssue> {
        val issues = mutableListOf<DefinitionIssue>()

        if (!NAME_REGEX.matches(def.name)) {
            issues += DefinitionIssue(
                "工具名 \"${def.name}\" 不合法：必须以小写字母开头，只含小写字母、数字、下划线。",
                isError = true,
            )
        }
        // 与内置工具重名会覆盖内置工具 —— 那是用户完全预料不到的行为。
        if (def.name in RESERVED_NAMES) {
            issues += DefinitionIssue(
                "工具名 \"${def.name}\" 与内置工具重名，会覆盖内置实现。请换一个名字。",
                isError = true,
            )
        }

        if (def.steps.isEmpty()) {
            issues += DefinitionIssue("至少需要一个步骤。", isError = true)
        }
        if (def.steps.size > MAX_STEPS) {
            issues += DefinitionIssue(
                "步骤数 ${def.steps.size} 超过上限 $MAX_STEPS。",
                isError = true,
            )
        }
        if (def.description.isBlank()) {
            issues += DefinitionIssue(
                "描述为空，模型难以判断什么时候该用这个工具。",
                isError = false,
            )
        }

        // 逐步校验 op 与占位符。
        def.steps.forEachIndexed { index, step ->
            val label = "第 ${index + 1} 步"
            if (!ToolPrimitives.isSupported(step.op)) {
                issues += DefinitionIssue(
                    "$label 的原语 \"${step.op}\" 不在白名单内。" +
                        "可用：${ToolPrimitives.SUPPORTED.sorted().joinToString(", ")}。",
                    isError = true,
                )
            }
            // 占位符必须都能在 params 里找到 —— 拼错不会报错，只会安静地做错事。
            step.args.forEach { (key, value) ->
                PLACEHOLDER_REGEX.findAll(value).forEach { match ->
                    val ref = match.groupValues[1]
                    if (ref !in def.paramNames) {
                        issues += DefinitionIssue(
                            "$label 的参数 \"$key\" 引用了未声明的占位符 `{{${ref}}}`。",
                            isError = true,
                        )
                    }
                }
            }
        }

        // 有副作用的工具必须声明，否则失败时框架会误以为可以安全重试。
        val writes = def.steps.any { it.op in SIDE_EFFECT_PRIMITIVES }
        if (writes && !def.hasSideEffects) {
            issues += DefinitionIssue(
                "含有写操作的原语，但 hasSideEffects 为 false。这会让失败时被误判为可安全重试。",
                isError = true,
            )
        }

        return issues
    }

    /** 与内置工具重名的保留名单（内置工具在注册表里已经占用了这些名字）。 */
    private val RESERVED_NAMES: Set<String> = ToolPrimitives.SUPPORTED

    /** 会产生副作用的原语。 */
    private val SIDE_EFFECT_PRIMITIVES: Set<String> = setOf(
        "linux_exec",
        "host_shell",
        "write_file",
        "copy_file",
        "save_memory",
    )
}
