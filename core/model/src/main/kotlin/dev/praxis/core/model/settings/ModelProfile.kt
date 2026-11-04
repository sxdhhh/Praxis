package dev.praxis.core.model.settings

/**
 * 一个「模型配置」（= 一套 baseUrl + key + 模型池 + 参数）。
 *
 * ## 为什么需要多配置（2026-11-05）
 *
 * 用户原话：「模型配置页加一个选择模型配置」「也要接入主界面的模型选择，
 * 就类似于 operit 的那种」。
 *
 * 旧结构是**单配置**：`SettingsRepository` 里 `model_id` / `model_base_url` /
 * `model_index` 一堆扁平 key，只够存一家。而实际用起来必然要切换：
 *
 * ```
 *   公司中转站   https://workbuddy.xxx/v1   deepseek-v4-flash
 *   本地网关     http://192.168.1.80:7500    glm-5.2
 *   官方直连     https://api.deepseek.com    deepseek-chat
 * ```
 *
 * 换一家就要把 baseUrl、key、模型池全重填一遍 —— 这不是配置，是折腾。
 *
 * ## 为什么 id 是字符串而不是自增 Long
 *
 * 与 `ChatBubble.variantGroupId` 同一个理由：id 要在**插入前**就确定
 * （新建配置时还没落盘），`System.currentTimeMillis()` 的字符串形式
 * 最省事。它不对用户可见、不需要有序，只要唯一。
 *
 * ## 为什么 name 与 id 分开
 *
 * 用户会重命名（「默认配置」→「公司中转站」）。若用 name 当 key，
 * 重命名等于删掉旧键再建新键 —— 中间任何一步失败都会丢配置。
 * id 不变，只改显示名。
 *
 * ## 为什么 apiKey 不在这个类里
 *
 * 与 `ModelSettings` 同一条纪律：这个对象会进日志、进状态快照。
 * key 单独加密存取（见 `SettingsRepository.profileApiKey(id)`）。
 */
data class ModelProfile(
    /** 稳定标识（建配置时生成，重命名不变）。 */
    val id: String,
    /** 显示名。用户可改，可重名（不做唯一性校验 —— 那只会让用户困惑）。 */
    val name: String,
    /** 这套配置的全部参数。 */
    val settings: ModelSettings = ModelSettings(),
) {

    /**
     * 这一套是否配全了（能发请求）。
     *
     * 直接复用 `ModelSettings.isConfigured`：
     * 判据只有一条，不在这里写第二份。
     */
    val isConfigured: Boolean get() = settings.isConfigured
}

/**
 * 多配置的集合视图（供 UI 一次性渲染）。
 *
 * ## 为什么把「列表 + 当前」包成一个对象
 *
 * 两者必须**同时**读出来才有意义：只给列表，UI 不知道高亮哪个；
 * 只给当前 id，UI 画不出下拉项。分成两个 Flow 会让它们不同步 ——
 * 用户切换配置的瞬间，列表与高亮可能来自不同的读取时刻。
 *
 * ## 空列表的语义
 *
 * 理论上不该为空（仓库保证至少有一个配置，见 `ensureProfile`），
 * 但读失败/首次启动的瞬间可能是空的。UI 要能处理，所以不假设非空。
 */
data class ModelProfileSet(
    val profiles: List<ModelProfile> = emptyList(),
    /** 当前生效的配置 id；不在 [profiles] 里时由 UI/仓库兜底到第一个。 */
    val activeId: String = "",
) {

    /**
     * 当前生效的配置。
     *
     * 越界兜底到第一个 —— 与 `ModelSettings.activeModel` 同口径：
     * 「静默用第一个」比「报错不许聊」合理。
     */
    val active: ModelProfile?
        get() = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()

    val isEmpty: Boolean get() = profiles.isEmpty()
}
