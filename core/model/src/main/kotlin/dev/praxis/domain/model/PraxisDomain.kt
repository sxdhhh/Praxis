package dev.praxis.domain.model

/**
 * Praxis 的目标：把 Operit 原有的功能面用更现代的 UI 重新组织。
 *
 * 这里刻意不复用 Operit 的包名与类名 —— Praxis 是独立应用，不注入宿主进程，
 * 只承接 Operit 的「功能清单」这一契约。功能域的划分直接对齐 Operit
 * ui/features 与 core 下的真实目录，便于后续逐块移植。
 * （注意：注释里不要写路径通配，Kotlin 块注释支持嵌套，
 *   多一个斜杠星号会开启嵌套注释，导致整段注释吞掉后面的代码。）
 */
enum class PraxisDomain(
    val id: String,
    val title: String,
    val subtitle: String,
) {
    CHAT("chat", "对话", "与 AI 的多轮会话、消息与会话历史"),
    ASSISTANT("assistant", "助手", "常驻助手、浮窗与语音入口"),
    TOOLS("tools", "工具", "工具调用、工具箱与权限授权"),
    TOOLBOX("toolbox", "工具箱", "内置工具包与自定义 JS 工具"),
    WORKFLOW("workflow", "工作流", "可视化编排与自动化触发"),
    TERMINAL("terminal", "终端", "命令行执行、ANSI 渲染与会话历史"),
    MEMORY("memory", "记忆", "长期记忆、向量检索与记忆整理"),
    PACKAGES("packages", "软件包", "包管理、模块安装与依赖"),
    SETTINGS("settings", "设置", "模型、外观、权限与实验项"),
    ABOUT("about", "关于", "版本、致谢与开源许可"),
    ;

    companion object {
        fun byId(id: String?): PraxisDomain? = entries.firstOrNull { it.id == id }
    }
}
