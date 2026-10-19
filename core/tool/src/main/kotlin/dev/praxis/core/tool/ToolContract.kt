package dev.praxis.core.tool

/**
 * 工具层契约。
 *
 * 纯 JVM：不得出现 android.* 或 androidx.*。
 * 真实实现（读写文件、跑 shell）在 core:tool:android，契约层只描述形状，
 * 这样工具注册表能在 JVM 单测里完整验证，不需要 Robolectric。
 */

/**
 * 权限档位。
 *
 * **只有三档**。Operit 原本有四档（多一个 admin），但实测 admin 的四个
 * override 文件共 1610 字节、全部零 override（`open class AdminX(ctx) : DebuggerX(ctx) { }`），
 * 即它不增加任何能力。多一档只会让用户多一次无意义的选择，所以砍掉。
 */
enum class PermissionTier {
    /** 普通能力：读写应用私有目录、网络、剪贴板。 */
    STANDARD,

    /** 调试能力：查看日志、读其他应用信息、无障碍。 */
    DEBUGGER,

    /** Root 能力：系统级文件、shell、包管理。 */
    ROOT,
}

/**
 * 单次调用的授权判定结果。
 *
 * [ASK] 不是「拒绝」，而是「需要先问用户」—— 调用方必须弹框，
 * 用户拒绝后回传 [ToolResult] 且 `isError = true`，让模型知道被拒而不是超时。
 */
enum class PermissionDecision {
    ALLOW,
    ASK,
    FORBID,
}

/** 工具声明的风险等级（用于默认策略与 UI 提示）。 */
enum class ToolRisk {
    /** 只读、无副作用。 */
    Safe,

    /** 写入应用自身数据。 */
    Moderate,

    /** 修改系统、执行命令、发网络请求。 */
    Dangerous,
}

/**
 * 工具的静态声明。
 *
 * [parametersJson] 是 JSON Schema 字符串，直接透传给模型；
 * 不在这里用 Kotlin 类型描述参数 —— 那需要一套从 Kotlin 类型生成 schema 的机制，
 * 而各家模型的 schema 方言并不统一，手写字符串最直白可控。
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
    val tier: PermissionTier = PermissionTier.STANDARD,
    val risk: ToolRisk = ToolRisk.Safe,
)

/** 工具执行结果。 */
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
)

/** 工具执行上下文（注入端口，不直接依赖 Android）。 */
interface ToolContext {

    /** 工具调用者当前的权限档位。 */
    val tier: PermissionTier
}

/**
 * 一个可执行工具。
 *
 * `execute` 是 suspend：真实工具会读文件、跑命令，必须能挂起。
 * 返回 [ToolResult] 而不抛异常：工具失败是**正常业务结果**（文件不存在、
 * 命令退出码非零），要作为可读文本回传给模型，让它自己决定怎么纠正。
 * 只有「工具本身写错了」才抛异常。
 */
interface Tool {

    val spec: ToolSpec

    suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult
}

/** 工具注册/执行失败（非业务失败）。 */
class ToolException(message: String, cause: Throwable? = null) : Exception(message, cause)
