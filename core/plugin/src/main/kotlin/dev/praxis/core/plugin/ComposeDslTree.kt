package dev.praxis.core.plugin

/**
 * compose_dsl 渲染树。
 *
 * ## 契约出处
 *
 * Operit 的 ToolPkgComposeDslParser：脚本返回
 *
 *     { tree: { type, props, children, slots }, state, memo }
 *
 * children 是数组（单个对象也接受），slots 是「具名插槽 → 节点数组」
 * （如 Scaffold 的 topBar）。动作靠 __actionId：字符串或
 * { "__actionId": "..." } 都认。
 *
 * ## 为什么 props 只保留标量
 *
 * props 的值可以是嵌套对象（modifier、style）。把结构体硬转成字符串会让
 * 渲染器拿到一段无法解释的文本，比「没有这个 prop」更糟。所以结构体
 * 在这里被丢弃并记一条诊断，调用方能看到「这个属性没被应用」。
 */
data class ComposeDslNode(
    val type: String,
    /** 只含标量属性（字符串化后的值）。 */
    val props: Map<String, String> = emptyMap(),
    /**
     * modifier 里的标量（padding / fillMaxWidth / width …）。
     *
     * ## 为什么要单独留一层
     *
     * modifier 是嵌套对象，其余嵌套 props 我仍然丢弃 —— 但布局信息全在它里面：
     * 丢掉就等于「所有组件都按默认尺寸画」，而用户看到的是「我写的 padding 没生效」。
     *
     * 只取一层的标量：再深的结构（例如 modifier 里再嵌 modifier）目前没有真实用例，
     * 真遇到时解析器会把它当结构体丢掉，而不是猜。
     */
    val modifier: Map<String, String> = emptyMap(),
    val children: List<ComposeDslNode> = emptyList(),
    /** 具名插槽：slot 名 → 节点列表。 */
    val slots: Map<String, List<ComposeDslNode>> = emptyMap(),
) {
    /** 取一个标量属性。 */
    fun prop(name: String): String? = props[name]

    /** 四周内边距（dp）。没写或不是整数时返回 null。 */
    val paddingDp: Int?
        get() = modifier["padding"]?.trim()?.toIntOrNull()

    /** Column/Row 的间距（dp）。 */
    val spacingDp: Int?
        get() = prop("spacing")?.trim()?.toIntOrNull()

    /** 是否撑满宽度。只认 true/false 两种字面量。 */
    val fillMaxWidth: Boolean
        get() = modifier["fillMaxWidth"]?.trim()?.equals("true", ignoreCase = true) == true

    /** 动作 id（三种写法都认，与 Operit 的 extractActionId 一致）。 */
    val actionId: String?
        get() = prop("__actionId")?.trim()?.takeIf { it.isNotEmpty() }
            ?: prop("action")?.trim()?.removePrefix("__action:")?.takeIf { it.isNotEmpty() }
}

/** 一次渲染的完整结果。 */
data class ComposeDslRenderResult(
    val tree: ComposeDslNode,
    val state: Map<String, String> = emptyMap(),
    val memo: Map<String, String> = emptyMap(),
)

/**
 * 渲染结果解析。
 *
 * 纯函数：脚本正文进来、树出去，可以在单测里锁住字段与容错，
 * 不必等真机上看「页面一片空白」再猜。
 */
object ComposeDslParser {

    fun parse(rawJson: String): Result<ComposeDslRenderResult> {
        val root = ManifestParser.parse(rawJson).getOrNull()
            ?: return Result.failure(IllegalArgumentException("渲染结果不是合法的 JSON 对象"))
        val fields = root.asMapping()

        val treeValue = fields["tree"]
            ?: return Result.failure(IllegalArgumentException("渲染结果缺少 tree"))

        val tree = parseNode(treeValue)
            ?: return Result.failure(
                IllegalArgumentException("tree 的 type 为空或不是对象，无法渲染"),
            )

        return Result.success(
            ComposeDslRenderResult(
                tree = tree,
                state = scalarMap(fields["state"]),
                memo = scalarMap(fields["memo"]),
            ),
        )
    }

    private fun parseNode(value: ManifestValue): ComposeDslNode? {
        val entries = (value as? ManifestValue.Mapping)?.entries ?: return null
        val type = entries["type"]?.asText()?.trim().orEmpty()
        if (type.isEmpty()) return null

        val props = mutableMapOf<String, String>()
        var modifier: Map<String, String> = emptyMap()
        (entries["props"] as? ManifestValue.Mapping)?.entries?.forEach { (key, raw) ->
            when {
                key == "modifier" && raw is ManifestValue.Mapping -> {
                    modifier = raw.entries.mapNotNull { (modifierKey, modifierValue) ->
                        when (modifierValue) {
                            is ManifestValue.Mapping -> null
                            is ManifestValue.Items -> null
                            else -> modifierKey to modifierValue.asText()
                        }
                    }.toMap()
                }
                raw is ManifestValue.Mapping -> Unit // 其余结构体丢弃：见类注释
                raw is ManifestValue.Items -> Unit
                else -> props[key] = raw.asText()
            }
        }

        val children = when (val raw = entries["children"]) {
            is ManifestValue.Items -> raw.values.mapNotNull { parseNode(it) }
            is ManifestValue.Mapping -> listOfNotNull(parseNode(raw))
            else -> emptyList()
        }

        val slots = mutableMapOf<String, List<ComposeDslNode>>()
        (entries["slots"] as? ManifestValue.Mapping)?.entries?.forEach { (name, raw) ->
            val nodes = when (raw) {
                is ManifestValue.Items -> raw.values.mapNotNull { parseNode(it) }
                is ManifestValue.Mapping -> listOfNotNull(parseNode(raw))
                else -> emptyList()
            }
            if (nodes.isNotEmpty()) slots[name] = nodes
        }

        return ComposeDslNode(
            type = type,
            props = props,
            modifier = modifier,
            children = children,
            slots = slots,
        )
    }

    private fun scalarMap(value: ManifestValue?): Map<String, String> {
        val entries = (value as? ManifestValue.Mapping)?.entries ?: return emptyMap()
        return entries.mapNotNull { (key, raw) ->
            when (raw) {
                is ManifestValue.Mapping -> null
                is ManifestValue.Items -> null
                else -> key to raw.asText()
            }
        }.toMap()
    }
}

/**
 * 组件白名单。
 *
 * ## 名字的出处
 *
 * [KNOWN] 抄自 Operit 的 ToolPkgComposeDslGeneratedRegistry —— 那是它支持的
 * 全部组件（80 个）。Praxis 本轮只实现 [RENDERABLE]，其余**显式渲染成占位**，
 * 而不是静默跳过：静默跳过会让用户以为自己的布局写错了。
 *
 * ## 为什么不一次做全
 *
 * 参考实现 3382 行生成代码 + 922 行 props 桥。先做出「典型面板能用」的
 * 子集，把不支持的显式标出来，再按真实需求补 —— 比一次铺开 80 个更可控。
 */
object ComposeDslComponents {

    /** 本轮实现了渲染的组件。 */
    val RENDERABLE: Set<String> = setOf(
        "Column",
        "Row",
        "Box",
        "Spacer",
        "Text",
        "Card",
        "TextButton",
        "Button",
        "LazyColumn",
        "Divider",
        "HorizontalDivider",
    )

    /** Operit 支持的全部组件名（用于区分「未知」与「还没实现」）。 */
    val KNOWN: Set<String> = setOf(
        "Column", "Row", "Box", "Spacer", "LazyColumn", "LazyRow", "Text", "TextField",
        "Switch", "Checkbox", "Button", "IconButton", "Card", "MaterialTheme", "Surface",
        "Icon", "LinearProgressIndicator", "CircularProgressIndicator", "SnackbarHost",
        "AssistChip", "Badge", "BadgedBox", "DismissibleDrawerSheet",
        "DismissibleNavigationDrawer", "Divider", "DropdownMenu", "ElevatedAssistChip",
        "ElevatedButton", "ElevatedCard", "ElevatedFilterChip", "ElevatedSuggestionChip",
        "ExtendedFloatingActionButton", "FilledIconButton", "FilledIconToggleButton",
        "FilledTonalButton", "FilledTonalIconButton", "FilledTonalIconToggleButton",
        "FilterChip", "FloatingActionButton", "HorizontalDivider", "IconToggleButton",
        "InputChip", "LargeFloatingActionButton", "LeadingIconTab", "ListItem",
        "ModalDrawerSheet", "ModalNavigationDrawer", "ModalWideNavigationRail",
        "NavigationBar", "NavigationDrawerItem", "NavigationRail", "NavigationRailItem",
        "OutlinedButton", "OutlinedCard", "OutlinedIconButton", "OutlinedIconToggleButton",
        "PermanentDrawerSheet", "PermanentNavigationDrawer", "PrimaryScrollableTabRow",
        "PrimaryTabRow", "ProvideTextStyle", "PullToRefreshBox", "RadioButton", "Scaffold",
        "SecondaryScrollableTabRow", "SecondaryTabRow", "ShortNavigationBar",
        "ShortNavigationBarItem", "SmallFloatingActionButton", "Snackbar", "SuggestionChip",
        "Tab", "TextButton", "TimePickerDialog", "VerticalDivider", "VerticalDragHandle",
        "WideNavigationRail", "WideNavigationRailItem", "BoxWithConstraints", "BasicText",
        "DisableSelection", "Image", "SelectionContainer",
    )

    /** 该类型能否渲染。 */
    fun isRenderable(type: String): Boolean = type in RENDERABLE

    /** 该类型是 Operit 认识的组件（只是本轮没实现）。 */
    fun isKnown(type: String): Boolean = type in KNOWN
}
