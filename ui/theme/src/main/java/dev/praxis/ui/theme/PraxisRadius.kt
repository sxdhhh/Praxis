package dev.praxis.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 圆角令牌：全项目唯一的圆角数值来源。
 *
 * ## 为什么要有它（2026-11-03）
 *
 * 改造前全仓 67 处 `RoundedCornerShape(12.dp)` 这类硬编码，取值有
 * 1/2/3/4/6/8/10/12/16/18/20/24.dp 十二种。同一个「卡片」语义在不同页面
 * 用了 10、12、16 三种值 —— 用户看不出「哪个错了」，但整体观感是散的。
 *
 * 这与 [PraxisMotion] 是同一个问题（那边是时长/曲线，这边是圆角），
 * 解法也照它：收敛到一处，页面只引用名称。
 *
 * ## 为什么是这五档，不是更多
 *
 * 取值来自实测统计里出现最多的几档（12/16/10/8/20），并补一个 4 给
 * 「标签/徽章」这类极小元素。再多就会重新变成「随便挑一个」——
 * **令牌的价值在于档位少到能记住**。
 *
 * ## ⚠️ 不要为了「更圆」而调大这些值
 *
 * 圆角大小要与元素尺寸成比例。把一个 32dp 高的标签改成 16dp 圆角，
 * 它会变成胶囊；把 280dp 宽的菜单改成 8dp，它会显得方正。
 * 调值时按语义选档，不要按「好不好看」选。
 *
 * ## 与 G2 连续曲率（squircle）的关系 —— 这里刻意不用
 *
 * NexioSchedule 的圆角几乎全来自 miuix 的 `squircleSurface`（超椭圆），
 * 视觉上比正圆角更「顺」。但 Praxis **不能全局换过去**，原因见
 * `ToolCallCard.kt:87` 的实测结论：
 *
 * > `squircleSurface` 会为每个元素额外开一个离屏图层并跑 AGSL shader
 * > 逐像素求遮罩。一张两张看不出来，但列表里连续出现 5~10 张时，
 * > 滚动每帧要合成这么多离屏层 —— 掉帧非常明显。
 * > 用户原话：「翻文字很丝滑，滑到工具的时候就变卡了」。
 *
 * 所以本文件只做**数值收敛**，不引入 squircle。
 * squircle 只适合「静态、数量少」的容器（一级页面的大卡片、弹层），
 * 那类用法按需局部开启 `LocalSquircleEnabled`，不受本令牌约束。
 */
object PraxisRadius {

    /**
     * 微末元素：引用条、文本光标、1~3dp 的细装饰。
     *
     * ## 为什么补这一档（2026-11-03 复查）
     *
     * 首版只做了 4/8/12/16/20 五档，迁移到第 10 处后回头查剩余 47 处，
     * 发现实际用量里还有 1/2/3/6/10/18/24 dp 这些值没有对应档位。
     *
     * 1~3dp 的用途很集中：`MarkdownText` 的引用竖条（宽 3dp）、
     * `ChatContextUi` 的细装饰。这类元素的**尺寸本身只有 2~3dp**，
     * 给它们 4dp 圆角等于「整个元素都是圆的」，视觉上会变成圆点。
     *
     * 所以需要单独一档，而不是把 1~3 硬归到 4。
     */
    val Micro = 2.dp

    /** 极小元素：标签、徽章、行内代码块。 */
    val Tiny = 4.dp

    /** 小控件：输入框内衬、小按钮、图标底。 */
    val Small = 8.dp

    /** 常规控件：列表面板、搜索框、设置项。全项目用得最多的一档。 */
    val Medium = 12.dp

    /** 卡片与浮层：消息气泡、菜单容器、对话框。 */
    val Large = 16.dp

    /** 大容器：全屏抽屉、底部抽屉的顶角。 */
    val ExtraLarge = 20.dp

    /**
     * 超大面板：高度可达 560dp 的浮层（如 `ChatScrollNavigator` 的跳转面板）。
     *
     * 圆角要与元素尺寸成比例 —— 20dp 用在一个 560dp 高的面板上会显得方正，
     * 24dp 才有「大圆角浮层」的观感。
     */
    val Huge = 24.dp

    // ── 预置 Shape（避免调用点反复 new 同一个对象）──────────────────
    //
    // `RoundedCornerShape(dp)` 每次调用都会新建一个对象。放在 Composable
    // 的组合路径上（每帧可能执行）会产生可观的对象分配。这里直接持有实例。

    val MicroShape = RoundedCornerShape(Micro)
    val TinyShape = RoundedCornerShape(Tiny)
    val SmallShape = RoundedCornerShape(Small)
    val MediumShape = RoundedCornerShape(Medium)
    val LargeShape = RoundedCornerShape(Large)
    val ExtraLargeShape = RoundedCornerShape(ExtraLarge)
    val HugeShape = RoundedCornerShape(Huge)

    /** 抽屉这类「只圆一侧」的容器：右侧两角圆，左侧贴边。 */
    val SideSheetShape = RoundedCornerShape(topEnd = ExtraLarge, bottomEnd = ExtraLarge)
}
