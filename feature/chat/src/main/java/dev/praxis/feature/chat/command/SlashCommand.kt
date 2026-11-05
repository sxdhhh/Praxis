package dev.praxis.feature.chat.command

/**
 * 解析后的 `"/命令 参数"`。
 *
 * @param name 去掉前导斜杠的命令名（已校验为 `[a-z][a-z0-9_-]*`）。
 * @param rawInput 命令名之后的**原始**文本，**保留分隔符与后缀**。
 *
 *   ## 为什么保留前导空格
 *
 *   Harness 的 `CommandInvocation.rawInput` 明确是「紧随命令名之后的文本，
 *   包含分隔空白」。保留它，handler 才能区分：
 *
 *   | 输入 | rawInput | 语义 |
 *   |---|---|---|
 *   | `/plan` | `""` | 无参数 |
 *   | `/plan review` | `" review"` | 参数是 review |
 *   | `/plan  review` | `"  review"` | 用户多打了一个空格 |
 *
 *   第三行看起来没用，但如果这里 `trim()` 了，handler 就再也无法还原
 *   「用户到底打了什么」——而命令结果需要能被用户核对。
 */
data class ParsedCommand(
    val name: String,
    val rawInput: String,
)

/**
 * `/` 命令解析（规则逐条对齐 DeepSeek Harness `dsh-commands`）。
 *
 * ## 为什么规则要「严」而不是「宽容」
 *
 * 这是整个设计里最关键的一个取舍。宽容解析（trim、转小写、容错字符）看起来
 * 更友好，但会带来一个严重问题：**把普通文本误判成命令**。
 *
 * 例如用户真的想发一句「/usr/bin 这个路径怎么改」，若解析器接受前导空格或
 * 奇怪字符，这句话会被当成命令吃掉，用户的消息就再也发不出去。
 *
 * Harness 的取舍是：宁可让用户重新打一遍，也不误吞一条正常消息。
 * 这里完全照抄。
 *
 * ## 具体规则
 *
 * | 输入 | 结果 | 理由 |
 * |---|---|---|
 * | `"/plan"` | `plan`, `""` | 正常 |
 * | `"/plan review"` | `plan`, `" review"` | 保留分隔空白 |
 * | `" /plan"` | **null** | 前导空格不认（可能是缩进/引用） |
 * | `"/Plan"` | **null** | 大写不认（避免大小写歧义） |
 * | `"/plan?"` | **null** | 非法字符不认 |
 * | `"/usr/bin"` | **null** | 含斜杠，是路径不是命令 |
 * | `"//plan"` | **null** | 双斜杠 |
 * | `"/"` | **null** | 只有斜杠，没有名字 |
 * | `"hi /plan"` | **null** | 不在开头 |
 */
object SlashCommand {

    /** 命令名的合法字符集：小写字母开头，其余小写字母 / 数字 / 下划线 / 连字符。 */
    private val NAME_REGEX = Regex("^[a-z][a-z0-9_-]*$")

    /**
     * 解析一行输入。
     *
     * 返回 null 表示「这不是命令」——调用方应当把它当作**普通消息**处理。
     *
     * ## 为什么不做「未知命令」判断
     *
     * 这里只判断**语法**是否像命令。是否已注册是另一件事（见 [find]）。
     * 两者分开的理由：语法解析是纯函数、可穷举测试；而注册表随时可变。
     */
    fun parse(input: String): ParsedCommand? {
        // 不以斜杠开头：不是命令。注意**不做 trim** —— 见类文档。
        if (!input.startsWith('/')) return null
        val body = input.substring(1)
        if (body.isEmpty()) return null
        // 取命令名：直到第一个空白为止。
        val nameEnd = body.indexOfFirst { it == ' ' || it == '\t' || it == '\n' }
        val name = if (nameEnd < 0) body else body.substring(0, nameEnd)
        if (!NAME_REGEX.matches(name)) return null
        // rawInput = 名称之后的全部原始文本（**包含**分隔空白）。
        val raw = if (nameEnd < 0) "" else body.substring(nameEnd)
        return ParsedCommand(name = name, rawInput = raw)
    }

    /**
     * 从注册表中找生效定义。
     *
     * ## 为什么未知命令不自动转成模型输入
     *
     * 见类文档里「为什么规则要严」：反过来，若未知命令（`/foo`）被当成普通
     * 消息发给模型，用户会看到模型一本正经地回答一个根本不存在的命令，
     * 而不是「没有这个命令」这种可操作的提示。
     *
     * 所以未知命令的处理是**明确报错**，由 UI 显示，不发网络请求。
     */
    fun find(parsed: ParsedCommand, registry: List<CommandDefinition>): CommandDefinition? =
        registry.firstOrNull { it.name == parsed.name }
}

/**
 * 一条命令的注册定义。
 *
 * ## 为什么 `handler` 不在这里
 *
 * Harness 把「给 UI 的 descriptor」与「含 handler 的定义」分开：
 * UI 适配器拿到的是不含 handler 的只读描述符，天然不可能绕过注册表执行。
 *
 * 这里把执行入口放在 ViewModel，定义只描述「叫什么、做什么、接受什么输入」，
 * 同一思想：定义是数据，执行是行为。
 */
data class CommandDefinition(
    /** 命令名（不含斜杠，小写）。必须满足 [SlashCommand.NAME_REGEX]。 */
    val name: String,
    /** 一行说明，显示在命令列表里。 */
    val description: String,
    /** 输入占位提示（为空表示该命令不接受参数）。 */
    val inputHint: String = "",
    /**
     * 该命令是否真的对模型可见地提交工作。
     *
     * ## 为什么需要这个标记
     *
     * Harness 的核心约定是「命令和结果本身不会变成模型消息」——
     * 处理器若要提交模型可见的工作，必须**显式调用 agent**。
     *
     * 有了这个标记，UI 就能诚实地告诉用户：「这条命令只会改变界面/设置，
     * AI 看不到」还是「这条会让 AI 产生新一轮回复」。
     */
    val submitsToModel: Boolean = false,
)
