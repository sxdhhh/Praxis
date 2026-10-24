package dev.praxis.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 全部导航目的地。
 *
 * ## 为什么从「两个参数化 Route」改成「十五个具名 Route」（2026-10-22 导航重构）
 *
 * 旧实现只有两个类：
 *
 * ```
 * Home(domainId)               ← 10 个域全靠这个字符串区分
 * Detail(domainId, arg, title) ← 14 个设置页 + 各域子页全靠两个字符串区分
 * ```
 *
 * 于是所有「这一页到底是哪一页」的判断都退化成**运行期字符串查表**：
 *
 *  - `SettingsHost` 用 `SettingsEntry.byKey(route.arg)` 查，查不到就渲染兜底页。
 *    真实事故：域首页清单里有 `advanced`、枚举里没有 → 点「高级」渲染的是
 *    同一页 → 用户看到「按钮点了没反应」，还连带把 Linux 环境 / 系统提示词 /
 *    功能模型全部挡住（它们只在那一页里）。
 *  - 标题要从 `blockOf(arg)` 反查，反查不到就用路由自带字符串兜底。
 *  - `FeatureHost` 的 `when (route.domain)` 里 `else ->` 分支承载了 5 个**没有任何
 *    入口**的域（ASSISTANT / TOOLS / TOOLBOX / PACKAGES / ABOUT，全局引用计数各 1，
 *    仅存在于清单里），也就是「定义了却进不去」。
 *
 * 这些错误的共同点是：**它们都不是编译错误**。字符串写错、枚举少一项、
 * 清单与分发不同步，全都编得过、跑起来才发现，表现为「点了没反应 / 打开是错的页 /
 * 二级页互相篡位」。
 *
 * 改成具名 `data object` 之后，`NavDisplay` 的 `entry<Route.X>` 与这里的声明
 * 必须一一对应 —— 少写一个 entry 就是编译错误，写错名字也是编译错误。
 * 「篡页」这一整类缺陷从**构造上**消失。
 *
 * ## 为什么不再有 `domainId` / `title`
 *
 * 标题改由页面自己给出（`stringResource` 或页面内的常量）。路由只负责「去哪」，
 * 不携带展示文案 —— 旧实现让标题跟着路由序列化进 Bundle，清单改名后旧栈恢复出
 * 的标题会和内容对不上，而内容是对的，排查时极难看出是标题的问题。
 *
 * ## 序列化
 *
 * `rememberNavBackStack` 用 kotlinx.serialization 把整栈编成字符串存 Bundle，
 * 所以每个 Route 必须 `@Serializable`。`data object` 序列化后只是一个类型标识，
 * 不含字段，天然向前兼容（新增 Route 不影响旧栈恢复）。
 */
@Serializable
sealed interface PraxisRoute : NavKey {

    /**
     * 唯一的常驻根目的地：底栏 + 四个 Tab 的 Pager 壳。
     *
     * ## 为什么 Tab 不再是栈里的元素（2026-10-22 导航重构的核心）
     *
     * 旧实现在这里放 `Home(domainId)`，切 Tab 靠改栈顶。为了让「切 Tab 有平移
     * 动画」不得不把替换改成真 push/pop —— 因为 miuix-nav 的转场**只由栈深驱动**
     * （`NavDisplay` 把 `backStack.lastIndex` 作为动画目标，同级替换时
     * `lastIndex` 不变 → 位移恒为 0 → 永不产生动画）。
     *
     * 那条路走下去的代价是连锁的：栈深封顶两层、`showBottomBar` 判据两次改、
     * `onBack` 判据加例外、方向要在调用处手算四种组合并塞给 `TabSlideTransition`。
     * 换来的仍然只是「模拟」Pager 的位移，没有手势横滑、没有 Tab 位置持久化、
     * 而且 Tab 变成栈元素后**按返回会退 Tab 而不是退层级**。
     *
     * 现在 Tab 归 `HorizontalPager`（见 `MainShell`），栈只装真正的层级页面。
     * 切 Tab 不产生栈深度变化，所以「切 Tab 没有动画」这个 bug 的存在前提消失了。
     */
    @Serializable
    data object Main : PraxisRoute

    // ── 对话域 ────────────────────────────────────────────────

    /** 会话列表（从对话页的侧边栏改为独立二级页，返回即回对话）。 */
    @Serializable
    data object ChatSessions : PraxisRoute

    // ── 设置域：一层一个具名 Route ──────────────────────────────

    @Serializable
    data object SettingsModel : PraxisRoute

    @Serializable
    data object SettingsFunctionModel : PraxisRoute

    @Serializable
    data object SettingsSystemPrompt : PraxisRoute

    /**
     * 角色卡列表。
     *
     * 与 [SettingsSystemPrompt] 是两个不同目的地：
     * 系统提示词是「一段全局规则」，本页是「多张可切换的人设卡」。
     * 旧实现把两者混为一页，用户改完提示词再想换人设时会以为要覆盖同一段文本。
     */
    @Serializable
    data object SettingsCharacters : PraxisRoute

    /**
     * 角色卡编辑。
     *
     * 带 `cardId` 参数：`null` 表示新建。
     *
     * 为什么做成参数化 Route 而不是「列表页内联编辑」：
     * 编辑页有十几个字段（人设、开场白、标签、白名单…），
     * 内联编辑会让列表页变成一个巨型表单，且返回时滚动位置丢失。
     * 参数化 Route 让编辑成为独立层级，返回即回列表，符合用户预期。
     */
    @Serializable
    data class SettingsCharacterEdit(val cardId: String? = null) : PraxisRoute

    /** 提示词标签管理（可复用片段库）。 */
    @Serializable
    data object SettingsPromptTags : PraxisRoute

    @Serializable
    data object SettingsAppearance : PraxisRoute

    @Serializable
    data object SettingsTools : PraxisRoute

    @Serializable
    data object SettingsPermission : PraxisRoute

    @Serializable
    data object SettingsMemory : PraxisRoute

    @Serializable
    data object SettingsWorkflow : PraxisRoute

    @Serializable
    data object SettingsSpeech : PraxisRoute

    @Serializable
    data object SettingsLinux : PraxisRoute

    /**
     * 终端设置（字号 / 回滚行数 / 首屏字符画）。
     *
     * 与 [SettingsLinux] 是两个不同目的地：Linux 环境管容器 / SSH / FRP，
     * 本页只管终端长什么样。旧实现里终端操作条的「环境配置」胶囊与齿轮
     * 都跳 Linux 环境页，齿轮看起来是个假按钮（第 12 项缺陷）。
     */
    @Serializable
    data object SettingsTerminal : PraxisRoute

    @Serializable
    data object SettingsPackages : PraxisRoute

    @Serializable
    data object SettingsLog : PraxisRoute

    @Serializable
    data object SettingsAbout : PraxisRoute

    /**
     * 开源许可明细（从「关于」进入）。
     *
     * ## 为什么单独一个 Route 而不是在关于页里塞一块长文本
     *
     * 上游 InstallerX 就是独立页（`settings/preferred/about/ossLicensePage/`
     * 里的 `MiuixOpenSourceLicensePage.kt` + `LibrariesContainer.kt`）。
     * 放在关于页里会让关于页的主职责（应用身份 + 三个入口）被几百行许可淹没，
     * 而且许可列表本身是一个需要滚动、需要分组、需要跳转的独立界面。
     *
     * ## 为什么它不是 `SettingsEntry` 的成员
     *
     * `SettingsEntry` 是**设置首页的入口清单**。这一页只能从关于页进入，
     * 放进首页会让「关于 → 开源许可」变成「设置 → 开源许可」的平级入口，
     * 与上游层级不一致。
     */
    @Serializable
    data object SettingsOpenSourceLicense : PraxisRoute
}

/**
 * 底栏的四个 Tab。
 *
 * ## 为什么是枚举而不是 `PraxisDomain`
 *
 * 底栏顺序 = Pager 页序，两者必须是同一个来源，否则「点第 3 个 tab 却翻到第 2 页」
 * 这类错位会反复出现。枚举的 `ordinal` 直接就是 Pager 的 page index。
 *
 * 只列 4 个域：其余域（助手 / 工具 / 工具箱 / 软件包 / 关于）通过设置页进入，
 * 不再各占一个 Tab —— 旧实现在 `PraxisDomain` 里定义了 10 个域，但底栏只有 4 个、
 * 又没有「全部域」的列表入口，其中 5 个域实际不可达。
 */
enum class MainTab {
    Chat,
    Terminal,
    Memory,
    Settings,
    ;

    companion object {
        /** Pager 页序 → Tab。越界时回落第一个，避免恢复出脏 index 时崩。 */
        fun of(index: Int): MainTab = entries.getOrElse(index) { Chat }

        /** Tab → Pager 页序。 */
        fun indexOf(tab: MainTab): Int = tab.ordinal
    }
}
