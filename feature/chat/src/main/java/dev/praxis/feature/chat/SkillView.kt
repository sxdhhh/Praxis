package dev.praxis.feature.chat

/**
 * 技能系统在聊天层的**最小视图**。
 *
 * ## 为什么不在 `:feature:chat` 直接引用 `:core:plugin`
 *
 * `feature:chat` 是纯 UI + 状态机，`core:plugin` 是扩展包体系（含文件扫描、
 * 市场、解析器）。让聊天层依赖整个插件体系，等于让「显示消息」这件事
 * 背上「读文件 + 解析 frontmatter」的实现细节 ——
 * 单测要起文件系统，改动插件层会牵连聊天层。
 *
 * 这里只声明聊天真正需要的两件事：名字 + 正文。
 * 由 `:app` 用适配器把 `Skill` 转过来（与 `PendingQueueStore`、
 * `MemoryPortQueryAdapter` 同一条分层纪律）。
 */
data class SkillView(
    /** 技能标识（`/技能名` 里的名字）。 */
    val name: String,

    /** 一句话说明。 */
    val description: String,

    /** Markdown 正文。`/技能名` 时作为一次性系统指令注入。 */
    val body: String,
)
