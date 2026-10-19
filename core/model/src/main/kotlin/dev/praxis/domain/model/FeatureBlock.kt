package dev.praxis.domain.model

/**
 * 一个功能块 = 一个可进入的页面入口。
 *
 * `id` 是稳定标识（路由与后续持久化用），不要随意改名。
 */
data class FeatureBlock(
    val id: String,
    val title: String,
    val description: String,
)

/**
 * 域 → 功能块清单。
 *
 * 这份清单是 Praxis 与 Operit 的「功能契约」：条目按 Operit
 * ui/features 与 core 下的真实能力划分，Phase 2 逐块填充实现，
 * 不做「先造页面再想功能」。
 * （注释里不要写路径通配：Kotlin 块注释可嵌套，会吞掉后面代码。）
 */
val PraxisDomain.blocks: List<FeatureBlock>
    get() = when (this) {
        PraxisDomain.CHAT -> listOf(
            FeatureBlock("sessions", "会话列表", "多轮会话、置顶与归档"),
            FeatureBlock("composer", "输入与发送", "附件、语音、提示词快捷插入"),
            FeatureBlock("history", "消息历史", "分页加载、搜索与重新生成"),
        )

        PraxisDomain.ASSISTANT -> listOf(
            FeatureBlock("floating", "悬浮助手", "系统级浮窗入口"),
            FeatureBlock("voice", "语音", "语音输入与朗读"),
        )

        PraxisDomain.TOOLS -> listOf(
            FeatureBlock("permissions", "权限授权", "工具调用前的能力授权"),
            FeatureBlock("invocation", "调用记录", "工具调用链与结果查看"),
        )

        PraxisDomain.TOOLBOX -> listOf(
            FeatureBlock("pkg", "工具包", "内置工具包管理"),
            FeatureBlock("js", "自定义工具", "JS 工具脚本与调试"),
        )

        PraxisDomain.WORKFLOW -> listOf(
            FeatureBlock("editor", "编排", "节点式可视化编排"),
            FeatureBlock("trigger", "触发器", "定时、通知与外部事件"),
        )

        PraxisDomain.MEMORY -> listOf(
            FeatureBlock("store", "记忆库", "长期记忆条目"),
            FeatureBlock("search", "检索", "语义与关键词检索"),
        )

        PraxisDomain.PACKAGES -> listOf(
            FeatureBlock("installed", "已安装", "软件包与模块"),
            FeatureBlock("updates", "更新", "版本检查与升级"),
        )

        PraxisDomain.SETTINGS -> listOf(
            FeatureBlock("model", "模型", "服务商、密钥与参数"),
            FeatureBlock("appearance", "外观", "主题、取色与动效"),
            FeatureBlock("advanced", "高级", "实验项与日志"),
        )

        PraxisDomain.ABOUT -> listOf(
            FeatureBlock("version", "版本", "版本信息与构建号"),
            FeatureBlock("licenses", "开源许可", "依赖与许可清单"),
        )
    }

/** 按 id 取功能块（子页标题、路由还原都用它）。 */
fun PraxisDomain.blockOf(id: String): FeatureBlock? = blocks.firstOrNull { it.id == id }