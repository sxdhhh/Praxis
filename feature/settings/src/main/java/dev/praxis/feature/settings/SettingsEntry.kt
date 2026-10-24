package dev.praxis.feature.settings

import dev.praxis.ui.navigation.PraxisRoute

/**
 * 设置子页清单。
 *
 * ## 每个条目**直接持有目的地**，不再持有字符串 key（2026-10-22 导航重构）
 *
 * 旧实现是 `SettingsEntry(key = "model", ...)` + `SettingsEntry.byKey(route.arg)`：
 * 入口清单、路由参数、分发 `when` 三处各写一遍字符串，靠「人工保持一致」。
 * 真实事故：清单里有 `advanced`、枚举里没有 → `byKey` 返回 null →
 * 渲染兜底页 → 用户看到「点『高级』没反应」，且连带把 Linux 环境 /
 * 系统提示词 / 功能模型全部挡住（它们只在那一页里）。
 *
 * 现在 `route` 字段就是 `PraxisRoute` 的具名子类型：
 *
 * ```
 * SettingsEntry(Model, route = PraxisRoute.SettingsModel)
 * ```
 *
 * 三个好处都是编译期保证：
 *  1. **不存在「清单有、枚举没有」** —— 目的地就是类型，拼错就编译不过；
 *  2. **不存在「点进去是兜底页」** —— 分发是 `when (route)`，
 *     `PraxisNavDisplay` 里每个 Route 都有 entry，多余分支根本不存在；
 *  3. **不存在两处顺序不一致** —— 菜单直接遍历本枚举，没有第二份清单。
 *
 * ## 为什么还要保留 `title` / `summary`
 *
 * 它们是**展示文案**，属于设置页自己的知识（哪个分组、用什么措辞），
 * 不该塞进路由。路由只回答「去哪」，这里回答「叫什么叫什么用」。
 * 两者的对应关系由 `route` 字段一对一锁定。
 *
 * ## 顺序
 *
 * 从上到下是使用频率：模型 → 功能模型 → 系统提示词（这三项决定「AI 怎么说话」），
 * 然后外观（观感），再是工具/权限/记忆/工作流/语音（能力配置），
 * 最后 Linux/终端/扩展包/日志/关于（基础设施与信息）。
 */
enum class SettingsEntry(
    /** 目的地。具名 Route，与 `PraxisNavDisplay` 的 entry 一一对应。 */
    val route: PraxisRoute,
    val title: String,
    val summary: String,
) {
    Model(
        route = PraxisRoute.SettingsModel,
        title = "模型",
        summary = "服务商、密钥、参数与上下文",
    ),

    FunctionModel(
        route = PraxisRoute.SettingsFunctionModel,
        title = "功能模型",
        summary = "为不同功能单独指定模型",
    ),

    SystemPrompt(
        route = PraxisRoute.SettingsSystemPrompt,
        title = "系统提示词",
        summary = "自定义最高优先级规则",
    ),

    /**
     * 角色卡。
     *
     * ## 为什么与「系统提示词」分成两个入口
     *
     * 两者都影响「AI 怎么说话」，但语义不同：
     *
     * - 系统提示词 = **一段**全局规则（跨所有对话常驻）；
     * - 角色卡 = **多张**可切换的人设（同一时间只有一张生效）。
     *
     * 放在同一页会让用户以为「换人设 = 覆盖那段规则」，
     * 于是换卡后发现自己写的全局规则没了。
     *
     * ## 为什么排在系统提示词之后
     *
     * 用户先该定「不可违背的规则」，再考虑「当前扮演谁」。
     * 顺序反过来时，新手会一上来就被一堆人设字段劝退，
     * 而那段真正重要的规则始终没写。
     */
    Characters(
        route = PraxisRoute.SettingsCharacters,
        title = "角色卡",
        summary = "可切换的人设、开场白与标签",
    ),

    Appearance(
        route = PraxisRoute.SettingsAppearance,
        title = "外观",
        summary = "主题、取色与调色板",
    ),

    Tools(
        route = PraxisRoute.SettingsTools,
        title = "工具",
        summary = "已注册工具与启用开关",
    ),

    Permission(
        route = PraxisRoute.SettingsPermission,
        title = "权限",
        summary = "工具调用的授权策略",
    ),

    Memory(
        route = PraxisRoute.SettingsMemory,
        title = "记忆",
        summary = "长期记忆条目与引用次数",
    ),

    Workflow(
        route = PraxisRoute.SettingsWorkflow,
        title = "工作流",
        summary = "节点编排与试运行轨迹",
    ),

    Speech(
        route = PraxisRoute.SettingsSpeech,
        title = "语音",
        summary = "TTS 与语音输入",
    ),

    Linux(
        route = PraxisRoute.SettingsLinux,
        title = "Linux 环境",
        summary = "Ubuntu / Kali 容器、SSH 与内网穿透",
    ),

    /**
     * 终端页自己的设置（第 12 项缺陷）。
     *
     * 与 [Linux] 是两个**不同目的地**：Linux 环境管容器 / SSH / FRP，
     * 本项只管终端长什么样（字号、回滚行数、首屏字符画）。
     * 旧实现里终端操作条的「环境配置」胶囊与齿轮都跳 Linux 环境页，
     * 齿轮看起来是个假按钮 —— 用户报的「入口重复」就是这里。
     *
     * ## 为什么枚举名带 Prefs 后缀
     *
     * `core:model` 里已经有一个 data class `TerminalSettings`（真正存值的那个）。
     * 枚举项若同名，`SettingsHost` 里同时出现「枚举项」与类型名，
     * 读者与编译器都会混淆。加 Prefs 后缀，一眼能分出「入口」与「数据」。
     */
    TerminalPrefs(
        route = PraxisRoute.SettingsTerminal,
        title = "终端设置",
        summary = "字号、回滚行数与首屏字符画",
    ),

    Packages(
        route = PraxisRoute.SettingsPackages,
        title = "扩展包",
        summary = "工具包与插件",
    ),

    Log(
        route = PraxisRoute.SettingsLog,
        title = "日志",
        summary = "运行日志与诊断",
    ),

    About(
        route = PraxisRoute.SettingsAbout,
        title = "关于",
        summary = "版本、致谢与开源许可",
    ),
    ;

    companion object {
        /**
         * 目的地 → 条目。
         *
         * ## 为什么这个方向的反查是安全的
         *
         * 旧实现反查的是**字符串**（`byKey(route.arg)`），拼错、少项、
         * 清单改名全都编得过。这里反查的是**类型**：`route` 是枚举字段，
         * 由编译器强制每个枚举项都填；找不到时返回 null 也只会发生在
         * 「传进来一个不属于设置的 Route」这种明显的接线错误上，
         * 而那种情况在 `SettingsHost` 的 `when` 里根本走不到。
         */
        fun of(route: PraxisRoute): SettingsEntry? =
            entries.firstOrNull { it.route == route }
    }
}