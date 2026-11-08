package dev.praxis.core.gateway

/**
 * 模型管理页的一行（`gateway_model` 的展示视图）。
 *
 * ## 为什么不让页面直接用 Room 实体
 *
 * 实体在 `:core:data`（Android 模块），而页面在 `:feature:gateway` —— 依赖方向是
 * `app → feature → ui → core`，feature 不该认识 Room 实体。这一层纯数据让两边都干净。
 *
 * 只带页面真正要显示/操作的字段：能力位那些留在实体里，页面不展示也不猜。
 */
data class GatewayModelRow(
    val provider: String,
    val id: String,
    val name: String,
    val kind: String,
    val enabled: Boolean,
)

/**
 * 新建一个模型条目的算出来的默认值（文档 §9.2）。
 *
 * ## 为什么能力位留 null 而不是猜 true/false
 *
 * 手动添加时我们**不知道**这个模型支不支持工具调用/图片/推理。写 true 会让选路
 * 把它当成多模态模型发出去（然后被上游 400）；写 false 又会让本来支持的功能用不上。
 * null = 未知，调用方据此「不据此判断」——这是唯一诚实的取值。
 */
object GatewayModelEntryDefaults {

    /** 手动添加的条目来源标记（与导入/内置区分）。 */
    const val SOURCE_MANUAL: String = "manual"

    /** 默认模型种类。绝大多数上游的默认条目都是纯对话模型。 */
    const val DEFAULT_KIND: String = "chat"

    /** 已知的输入模态：手动添加时我们只知道「它至少接受文本」。 */
    const val DEFAULT_INPUT_MODALITIES: String = "text"

    /** 没有推理等级（空串 = 空列表，不是「有但叫空」）。 */
    const val NO_REASONING_EFFORTS: String = ""

    /** 名称留空时用 id 顶上 —— 空名字在列表里就是一行空白。 */
    fun displayName(id: String, name: String): String {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) id.trim() else trimmed
    }
}
