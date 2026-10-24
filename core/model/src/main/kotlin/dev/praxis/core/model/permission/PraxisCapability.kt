package dev.praxis.core.model.permission

/**
 * Praxis 需要的**运行时授权项**。
 *
 * ## 这一层此前完全不存在（2026-10-22 修）
 *
 * 用户原话：「权限管理 tmd 就跟没有一样连个正经的授权检测都没有」。
 *
 * 属实。此前的「权限页」只有 [dev.praxis.core.tool.PermissionTier] 三档
 * （STANDARD / DEBUGGER / ROOT），那只是**决定把哪些工具描述发给模型**的策略上限，
 * 和 Android 的真实授权毫无关系。`PermissionScreen` 自己的注释里也写着
 * 「当前没有实现任何真实的提权检测」，等于把缺失摊在明面上而没有补。
 *
 * 后果是具体可验的：
 *  - manifest 里只有 INTERNET 等 4 个普通权限，**一个运行时权限都没声明**；
 *  - 用户把档位切到 ROOT，界面上写着「已解锁 Root 级能力」，
 *    但应用连 `/sdcard` 都没被授予，工具一跑就是 `PermissionDenied`；
 *  - 界面上没有任何地方能告诉用户「你还缺什么、点哪里去补」。
 *
 * ## 为什么按「能力」而不是按 Android 权限名字建模型
 *
 * `android.permission.*` 是平台概念，`core:model` 是纯 Kotlin 模块，
 * 引 Android 常量会把这一层绑死在 Android 上（本项目已确立：
 * core 不得依赖 ui，纯 JVM 模块不放 Android 类型）。所以这里用**能力**建模，
 * 由 `core:data` 的实现类负责把能力翻译成具体权限。
 *
 * 另一个好处：同一个能力在不同 API 级别需要的权限不同（例如存储：
 * API ≥ 30 是 `MANAGE_EXTERNAL_STORAGE` 的 `isExternalStorageManager()`，
 * API ≤ 29 是 `WRITE_EXTERNAL_STORAGE` 的运行时授予），
 * 这层差异必须留在实现里，不该泄漏到模型。
 */
enum class PraxisCapability(
    /** 界面上显示的名字。 */
    val label: String,
    /** 一句话说明「不给会怎样」——比「需要存储权限」有用得多。 */
    val rationale: String,
    /** 是否必需：缺了核心功能直接不可用，UI 上要标红。 */
    val required: Boolean,
) {
    /** 通知：前台服务、长任务进度、工具执行结果回显都要它。 */
    NOTIFICATION(
        label = "通知",
        rationale = "缺少时前台服务不会显示进度，长任务被系统杀掉也看不到提示。",
        required = false,
    ),

    /** 存储：读写 /sdcard，导入导出、读取用户选的文件都要它。 */
    STORAGE(
        label = "存储访问",
        rationale = "缺少时无法读写手机文件，只能访问应用私有目录。",
        required = true,
    ),

    /**
     * 命令执行通道（Shizuku / Root）。
     *
     * 这不是 Android 运行时权限，而是「能不能以更高身份执行命令」。
     * 单独列出来是因为：没有它，[dev.praxis.core.tool.PermissionTier.ROOT]
     * 这一档就是空壳 —— 用户以为提权了，实际什么也做不了。
     */
    PRIVILEGED_SHELL(
        label = "高级执行通道",
        rationale = "缺少时只能以普通应用身份运行命令，系统级操作（安装、改设置）不可用。",
        required = false,
    ),

    /**
     * 悬浮窗（显示在其他应用上层）。
     *
     * ## 为什么要单列（2026-10-24 对齐 Operit）
     *
     * 上游 `PermissionGuideViewModel.checkPermissions()` 里有这一项
     * （`Settings.canDrawOverlays`）。Praxis 此前完全没有 —— 于是
     * 「工具执行时显示悬浮进度」「截图取色浮层」这类能力要么没做，
     * 要么做了也弹不出来，且用户在权限页看不到「差什么」。
     *
     * 检测方式与存储同属「不能用 checkSelfPermission」的一类：
     * 它根本不在运行时权限体系里，只能问 `Settings.canDrawOverlays`。
     */
    OVERLAY(
        label = "悬浮窗",
        rationale = "缺少时无法在其他应用之上显示浮层（执行进度、取色器）。",
        required = false,
    ),

    /**
     * 电池优化豁免。
     *
     * 上游同样有这一项（`PowerManager.isIgnoringBatteryOptimizations`）。
     *
     * 对 Praxis 这类「后台跑长任务」的应用，它决定长任务会不会被
     * Doze 掐断 —— 用户报「跑一半没了」时，这里是第一个要查的开关。
     */
    BATTERY_EXEMPTION(
        label = "电池优化豁免",
        rationale = "缺少时长时间运行的任务可能被系统休眠掐断，表现为「跑到一半没了」。",
        required = false,
    ),

    /**
     * 无障碍服务。
     *
     * ## 这一项对应 Operit 的 `AndroidPermissionLevel.ACCESSIBILITY`
     *
     * 它是唯一「不是权限、而是一个用户手动开启的服务」的能力，
     * 检测方式是读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
     * 里有没有我们自己的服务名（见 `CapabilityDetector`）。
     *
     * 不能申请、只能跳系统设置页让用户手动开 —— 与悬浮窗同类。
     */
    ACCESSIBILITY(
        label = "无障碍服务",
        rationale = "缺少时无法跨应用读取界面与模拟点击（自动化类工具不可用）。",
        required = false,
    ),

    /**
     * 精确定位。
     *
     * 上游的 `hasLocationPermission` 是 **FINE 或 COARSE 任一** 即算授予，
     * 这里沿用同一判据（见 `CapabilityDetector.detectLocation`）：
     * 只给 COARSE 的用户也该算「能用」，卡在「必须精确」会让
     * 一个本来能用的功能显示成不可用。
     */
    LOCATION(
        label = "位置",
        rationale = "缺少时与地点相关的工具拿不到坐标（只能拿最后一次已知位置）。",
        required = false,
    ),

    /**
     * 设备管理员。
     *
     * 对应 Operit 的 `AndroidPermissionLevel.ADMIN`。
     *
     * 与其它项的关键差别：**它在被授予的那一刻就拥有了锁屏/擦除数据
     * 之类的真实权力**。所以默认值必须是「不申请」——
     * 这一项只在用户自己点「去授予」时才走
     * `ACTION_ADD_DEVICE_ADMIN`，不在引导页里推送。
     */
    DEVICE_ADMIN(
        label = "设备管理员",
        rationale = "缺少时无法执行锁屏、清空数据这类设备级操作。",
        required = false,
    ),
}

/**
 * 权限层级 —— 1:1 对齐 Operit 的 `AndroidPermissionLevel`。
 *
 * ## 为什么在「能力」之外还要有「层级」（2026-10-24）
 *
 * 这两个是**正交**的两件事，此前混成了一件事，才出现「档位切到 ROOT
 * 但连 /sdcard 都读不了」的割裂状态：
 *
 * | 概念 | 回答的问题 | 谁决定 |
 * |---|---|---|
 * | [PraxisCapability] | 「这一项系统权限有没有？」 | 用户去系统里点 |
 * | [PermissionTier] | 「把哪些工具描述发给模型？」 | 用户在设置里选 |
 * | [PraxisPermissionLevel] | 「本机/本应用能到什么程度？」 | 两侧共同决定 |
 *
 * `PraxisPermissionLevel` 是**结论**，不是开关：它由「用户选的档位」
 * 与「真实检测到的能力」共同推导出来，用于在界面上说清
 * 「你现在实际处于哪一级」。
 *
 * ## 为什么是这五级
 *
 * 照抄 Operit 的 `AndroidPermissionLevel`（STANDARD / ACCESSIBILITY /
 * DEBUGGER / ADMIN / ROOT），因为它那五级正对应五种不同的**取权方式**：
 *
 * - STANDARD：什么都不用做（安装即得）；
 * - ACCESSIBILITY：用户手动开一个服务；
 * - DEBUGGER：装 Shizuku / 开 USB 调试；
 * - ADMIN：激活设备管理员；
 * - ROOT：授予 Root。
 *
 * 换一套分法就会丢掉这个「一级对一种取权途径」的性质，
 * 而权限页的引导文案正是按这个性质组织的。
 */
enum class PraxisPermissionLevel(
    /** 界面上的名字。 */
    val label: String,
    /** 一句话说明「到这一级意味着什么」。 */
    val summary: String,
) {
    /** 基础：只有安装即授予的普通权限。 */
    STANDARD(
        label = "基础",
        summary = "仅普通应用权限，可读写应用私有目录、联网。",
    ),

    /** 无障碍：用户手动开启了无障碍服务。 */
    ACCESSIBILITY(
        label = "无障碍",
        summary = "可跨应用读取界面并模拟点击（通过无障碍服务）。",
    ),

    /** 调试：Shizuku 在跑，可用 ADB 级权限执行命令。 */
    DEBUGGER(
        label = "调试",
        summary = "可通过 Shizuku 以 ADB 身份执行命令，能装卸应用、改部分系统设置。",
    ),

    /** 管理员：已激活设备管理员。 */
    ADMIN(
        label = "设备管理员",
        summary = "可执行锁屏、清空数据等设备级操作。",
    ),

    /** Root：已授予 Root。 */
    ROOT(
        label = "Root",
        summary = "以 root 身份执行命令，可读写整个文件系统。",
    ),
    ;

    companion object {
        /**
         * 由「真实能力快照」推导当前实际所处层级。
         *
         * ## 为什么从高往低判、命中即返回
         *
         * 五级是**包含关系**（有 Root 的机器通常也装了 Shizuku）。
         * 逐级向上判会让用户看到「Root 可用，当前层级=调试」这种
         * 自相矛盾的结论。从最高级往下判，第一个满足的就是真实上限。
         *
         * ## 为什么读 [GrantChannel] 而不是 `detail` 文案
         *
         * 先前版本用 `detail.contains("Root")` 判断。那是脆的 ——
         * 改文案、做本地化都会让它失效，而失效时**不报错**，
         * 只是界面上的层级悄悄变成「调试」。改成读枚举后，
         * 检测侧必须显式声明来源，漏写会退回 [GrantChannel.NONE]，
         * 表现是层级偏低（保守），不会偏向高估。
         *
         * ## 为什么 DEVICE_ADMIN 排在 ROOT/DEBUGGER 之后
         *
         * 设备管理员是一个**独立能力**，不与提权通道互斥，也不蕴含
         * 更强的执行能力 —— 它只是多了几个设备管理 API。
         * 放在提权通道之后，是为了让「有 Root」比「有设备管理员」
         * 显示得更强（符合直觉），同时不丢掉「两者都有」的事实
         * （那一项在能力列表里各自可见）。
         */
        fun deriveFrom(snapshot: CapabilitySnapshot): PraxisPermissionLevel {
            val granted = snapshot.states.filter { it.isGranted }.map { it.capability }.toSet()
            val shellChannel = snapshot.of(PraxisCapability.PRIVILEGED_SHELL)?.channel
            return when {
                PraxisCapability.PRIVILEGED_SHELL in granted &&
                    shellChannel == GrantChannel.ROOT -> ROOT
                PraxisCapability.PRIVILEGED_SHELL in granted -> DEBUGGER
                PraxisCapability.DEVICE_ADMIN in granted -> ADMIN
                PraxisCapability.ACCESSIBILITY in granted -> ACCESSIBILITY
                else -> STANDARD
            }
        }
    }
}

/**
 * 单个能力的**检测结果**。
 *
 * 三态而不是布尔：`GRANTED` / `DENIED` 之外必须有 `UNSUPPORTED` ——
 * 在 Android 12 上问通知权限是没有意义的（该版本没有 POST_NOTIFICATIONS），
 * 报「未授予」会让用户去一个根本不存在的开关里找。
 */
enum class CapabilityStatus {
    /** 已授予，功能可用。 */
    GRANTED,

    /** 未授予，但本机支持，可以去申请。 */
    DENIED,

    /**
     * 本机不支持，或检测方式不适用（例如 API 12 上的通知权限、
     * 没有装 Shizuku 的机器上的 Shizuku 通道）。
     *
     * 与 [DENIED] 分开的关键作用：UI 上「不支持」不该出现「去授予」按钮，
     * 否则用户点了没反应，会认为按钮坏了。
     */
    UNSUPPORTED,
}

/**
 * 这个能力是**靠什么**满足的。
 *
 * ## 为什么需要它（2026-10-24）
 *
 * 只靠 [CapabilityState.detail]（一段给人看的文案）去区分「Root」与
 * 「Shizuku」是脆的：改一个字、做一次本地化，判定就失效，
 * 而且失效时**不报错**，只是界面上的层级悄悄变成错的。
 *
 * `PRIVILEGED_SHELL` 这一项同时覆盖两种完全不同的通道，
 * 而层级推导必须知道到底是哪一种 —— 所以把来源显式建模。
 */
enum class GrantChannel {
    /** 不需要任何通道（系统直接给的，或该项不适用）。 */
    NONE,

    /** 由 Root 满足。 */
    ROOT,

    /** 由 Shizuku（ADB 身份）满足。 */
    SHIZUKU,

    /** 由系统设置里的开关满足（悬浮窗 / 无障碍 / 电池豁免这类）。 */
    SYSTEM,
}

/**
 * 一个能力的完整状态（检测结果 + 如何补）。
 *
 * @param capability 能力
 * @param status 当前状态
 * @param detail 补充说明（例如「Shizuku 已安装但未授权」/「未检测到 Shizuku」），
 *   直接显示给用户，让「为什么不行」一眼可见。
 * @param channel 靠什么满足的（见 [GrantChannel] 的说明：层级推导要用它，
 *   而不是解析 [detail] 的文案）。
 */
data class CapabilityState(
    val capability: PraxisCapability,
    val status: CapabilityStatus,
    val detail: String = "",
    val channel: GrantChannel = GrantChannel.NONE,
) {
    /** 是否真的可用（UI 判断用，避免各处写 `status == GRANTED`）。 */
    val isGranted: Boolean get() = status == CapabilityStatus.GRANTED

    /** 是否该显示「去授予」入口：只有 DENIED 才显示。 */
    val canRequest: Boolean get() = status == CapabilityStatus.DENIED
}

/**
 * 授权状态的完整快照。
 *
 * 用快照而不是 `StateFlow<Map>`：界面一次读一份完整的，
 * 不会出现「通知已更新、存储还是旧值」的中间态闪烁。
 */
data class CapabilitySnapshot(
    val states: List<CapabilityState>,
) {
    fun of(capability: PraxisCapability): CapabilityState? =
        states.firstOrNull { it.capability == capability }

    /** 必需能力里还缺几个 —— 首页/权限页顶上那句提示用它。 */
    val missingRequired: List<CapabilityState>
        get() = states.filter { it.capability.required && !it.isGranted }

    /**
     * 是否全部必需能力都到位。
     *
     * 这不是「能不能用」的总开关 —— 缺存储时应用仍能跑，只是文件工具不可用。
     * 它只用于决定要不要在界面上提示。
     */
    val allRequiredGranted: Boolean get() = missingRequired.isEmpty()

    companion object {
        /**
         * 初始值：全部 UNSUPPORTED。
         *
         * 不猜「可能已授予」——猜错会让界面在真机上短暂显示「已授予」再变红，
         * 比一开始就显示「检测中」更让人困惑。检测是异步的，先给中性值。
         */
        val Unknown: CapabilitySnapshot = CapabilitySnapshot(
            states = PraxisCapability.entries.map {
                CapabilityState(it, CapabilityStatus.UNSUPPORTED, "尚未检测")
            },
        )
    }
}