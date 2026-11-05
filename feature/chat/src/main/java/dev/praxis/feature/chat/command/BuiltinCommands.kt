package dev.praxis.feature.chat.command

/**
 * Praxis 的内置命令集。
 *
 * ## 首批为什么是这五条
 *
 * 挑选标准：**都是「用户已经在做、但当前要点三次以上」的操作**。
 * 命令的价值在于把常用动作压缩成一次输入，而不是把菜单搬一份。
 *
 * | 命令 | 现状成本 | 收益 |
 * |---|---|---|
 * | `/compact` | 要滚到摘要区点按钮 | 一次输入 |
 * | `/model` | 点模型胶囊 → 展开分组 → 选 | 一次输入 |
 * | `/new` | 打开侧边栏 → 新建 | 一次输入 |
 * | `/clear` | 无入口 | **新增能力** |
 * | `/export` | 无入口 | **新增能力** |
 *
 * ## 为什么没有 `/help`
 *
 * 输入框打 `/` 就弹出完整列表，「help」是多余的一层。
 * 界面上再提供一个只能看列表的命令，反而让列表看起来像「需要帮助才能看到」。
 *
 * ## `submitsToModel` 的取值依据
 *
 * 全部为 false：这五条都是**本地操作**（改界面状态 / 落盘 / 导文件），
 * 没有一条会向模型提交新的用户消息。这是 Harness 「命令不进入模型历史」
 * 约定的直接体现 —— 用户打 `/compact` 不该让 AI 看到「用户说了 /compact」。
 */
object BuiltinCommands {

    val COMPACT = CommandDefinition(
        name = "compact",
        description = "压缩当前对话为摘要",
    )

    val MODEL = CommandDefinition(
        name = "model",
        description = "切换模型",
        inputHint = "可选：模型名",
    )

    val NEW = CommandDefinition(
        name = "new",
        description = "新建对话",
    )

    val CLEAR = CommandDefinition(
        name = "clear",
        description = "清空当前对话的消息（不可撤销）",
    )

    val EXPORT = CommandDefinition(
        name = "export",
        description = "导出当前对话为文本",
    )

    val all: List<CommandDefinition> = listOf(COMPACT, MODEL, NEW, CLEAR, EXPORT)
}
