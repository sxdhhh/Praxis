package dev.praxis.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.praxis.core.model.settings.ColorSource
import dev.praxis.core.model.settings.ApiProvider
import dev.praxis.core.model.settings.ModelAdvancedSettings
import dev.praxis.core.model.settings.ModelSettings
import dev.praxis.core.model.settings.FunctionSlot
import dev.praxis.core.model.settings.FunctionModelSettings
import dev.praxis.core.model.settings.FunctionModelOverride
import dev.praxis.core.model.settings.ColorSpecVersion
import dev.praxis.core.model.settings.PaletteStyle
import dev.praxis.core.model.settings.PredictiveBackAnimation
import dev.praxis.core.model.settings.PredictiveBackExitDirection
import dev.praxis.core.model.settings.ThemeMode
import dev.praxis.core.model.settings.ThemeSettings
import dev.praxis.core.model.settings.TerminalSettings
import dev.praxis.core.model.linux.VirtualKeyboardLayoutCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 设置存储（DataStore Preferences）。
 *
 * 只存主题这一组，因为这是目前唯一有真实 UI 驱动的设置项。
 * 其余设置随功能落地逐组追加，不预先建一堆空 key。
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    /** 密钥加解密器。null 表示降级为明文存储（测试或 Keystore 不可用时）。 */
    private val cipher: dev.praxis.core.data.security.KeystoreCipher? = null,
) {

    /** 读主题设置；任何字段缺失或损坏都回落到默认值，不抛异常。 */
    val themeSettings: Flow<ThemeSettings> = dataStore.data.map { prefs ->
        val defaults = ThemeSettings()
        ThemeSettings(
            mode = prefs[KEY_THEME_MODE].toEnumOr(defaults.mode),
            colorSource = prefs[KEY_COLOR_SOURCE].toEnumOr(defaults.colorSource),
            seedColorArgb = prefs[KEY_SEED_COLOR] ?: defaults.seedColorArgb,
            paletteStyle = prefs[KEY_PALETTE].toEnumOr(defaults.paletteStyle),
            colorSpec = prefs[KEY_COLOR_SPEC].toEnumOr(defaults.colorSpec),
            // 预测性返回：用 fromNameOrDefault 而不是 toEnumOr。
            // 两者等效，但这里显式用它自带 Companion 的兜底，
            // 保证「枚举改名/降级安装」时落到 Aosp 而不是抛异常。
            predictiveBackAnimation = PredictiveBackAnimation.fromNameOrDefault(
                prefs[KEY_PREDICTIVE_BACK],
            ),
            predictiveBackExitDirection = PredictiveBackExitDirection.fromNameOrDefault(
                prefs[KEY_PREDICTIVE_BACK_DIRECTION],
            ),
        )
    }

    /**
     * 模型配置（不含密钥）——**当前生效的那个 profile**。
     *
     * ## 多配置之后这里读什么（2026-11-05）
     *
     * 先取 `model_profile_active` 拿到当前 id，再按该 id 读带前缀的 key；
     * 没有 profile（老用户首次升级）时 [readModelSettings] 传 null，
     * 读的是原来那批扁平 key —— 行为与多配置之前**逐字一致**。
     *
     * 所以升级路径是平滑的：
     *
     * ```
     *   老用户：没有 model_profile_ids  -> profileId = null -> 读旧 key
     *   新用户：createProfile 之后        -> profileId = p<ts> -> 读新 key
     * ```
     *
     * ## 为什么不做一次性迁移
     *
     * 「启动时把旧 key 抄成 p:default:xxx」看似干净，但它是**写操作**：
     * 抄一半崩了会得到两份不一致的配置。而按需读旧 key 是纯读，
     * 不存在中间态 —— 用户第一次在设置页点「新建」时自然就走新结构了。
     */
    val modelSettings: Flow<ModelSettings> = dataStore.data.map { prefs ->
        val activeId = prefs[KEY_PROFILE_ACTIVE]
        // 只有 activeId 真的在一个已知 profile 里才用前缀读；
        // 否则（老用户 / 刚删完）退回旧 key。
        val known = parseIds(prefs[KEY_PROFILE_IDS])
        val profileId = activeId?.takeIf { it in known }
        readModelSettings(prefs, profileId)
    }

    /**
     * 模型进阶配置（自定义请求头 + 请求队列）。
     *
     * ## 为什么请求头值要解密而不是直接给
     *
     * 存的是密文（与 `apiKey` 同一条路径：Android Keystore 里的密钥，
     * 不落在 data/data 里）。请求头里常放中转站令牌，理由与 apiKey 完全相同。
     *
     * ## 解密失败返回空表而不是抛
     *
     * 与 [apiKey] 同一口径：换机恢复 / Keystore 被清时抛异常会让
     * 整个设置页崩掉；返回空表等价于「还没配头」，用户重填即可。
     */
    val modelAdvancedSettings: Flow<ModelAdvancedSettings> = dataStore.data.map { prefs ->
        val rawHeaders = prefs[KEY_CUSTOM_HEADERS] ?: ""
        val decrypted = if (rawHeaders.isEmpty()) {
            ""
        } else {
            val c = cipher
            if (c == null) rawHeaders
            else if (!dev.praxis.core.data.security.KeystoreCipher.looksEncrypted(rawHeaders)) {
                rawHeaders
            } else {
                c.decrypt(rawHeaders) ?: ""
            }
        }
        ModelAdvancedSettings(
            customHeaders = ModelAdvancedSettings.parseHeaders(decrypted),
            // 夹下限不夹上限：RPM 填 0 是合法的（不限流）；
            // 上限没必要管 —— 填 1e9 等于不限流，行为正确。
            requestLimitPerMinute = (prefs[KEY_REQUEST_LIMIT_RPM] ?: 0).coerceAtLeast(0),
            maxConcurrentRequests = (prefs[KEY_MAX_CONCURRENT] ?: 0).coerceAtLeast(0),
        )
    }

    /**
     * 写入自定义请求头。
     *
     * 空表等于「删光所有头」，与「不修改」不同 —— 调用方要传用户看到的当前表，
     * 而不是只传新增的那条。
     */
    suspend fun setCustomHeaders(headers: List<dev.praxis.core.model.settings.CustomHeader>) {
        val serialized = dev.praxis.core.model.settings.ModelAdvancedSettings
            .serializeHeaders(headers)
        if (serialized.isEmpty()) {
            put(KEY_CUSTOM_HEADERS, "")
            return
        }
        val encrypted = cipher?.encrypt(serialized)
        put(KEY_CUSTOM_HEADERS, encrypted ?: serialized)
    }

    /** 每分钟最大请求数。0 = 不限流。 */
    suspend fun setRequestLimitPerMinute(limit: Int) =
        put(KEY_REQUEST_LIMIT_RPM, limit.coerceAtLeast(0))

    /** 最大并发请求数。0 = 不限。 */
    suspend fun setMaxConcurrentRequests(limit: Int) =
        put(KEY_MAX_CONCURRENT, limit.coerceAtLeast(0))


    // ─────────────────────────────────────────────────────────────
    // 多模型配置（2026-11-05）
    // ─────────────────────────────────────────────────────────────
    //
    // ## 存储结构
    //
    // ```
    //   model_profile_ids    StringSet   所有配置 id
    //   model_profile_active String      当前生效的 id
    //   model_profile_names  String      每配置显示名（`id\u0001name` 逐行）
    //   p:<id>:base_url      等          该配置的全部参数（key 加前缀）
    // ```
    //
    // ## 为什么用前缀 key 而不是「每配置一个 JSON」
    //
    // JSON 方案要在 `modelSettings` 的 Flow 里反序列化整个对象，而
    // `SettingsBackedProvider` 每轮请求都读它 —— 多一次解析。
    // 前缀 key 让「读一个字段」仍然是一次 map 查找，与单配置时完全一样。
    //
    // 代价是 key 数量翻倍，但 DataStore Preferences 本来就是扁平的，
    // 这不增加任何结构复杂度。
    //
    // ## 旧配置怎么办
    //
    // 首次读到「没有任何 profile」时，把旧的扁平 key 收拢成一个名为
    // 「默认配置」的 profile（见 `ensureDefaultProfile`）。用户升级后
    // 看到的是自己原来的配置，不是一张白纸。

    /**
     * 所有配置 id，**换行分隔的有序串**。
     *
     * ## 为什么不用 `stringSetPreferencesKey`
     *
     * StringSet **无序**，而配置列表必须保持用户建的顺序（新建的排最后）。
     * 用 Set 就只能在读取时另外排序，而「按什么排」又是个无法回答的问题
     * （按名字？按时间？都不对 —— 用户期望的就是他自己建的顺序）。
     *
     * id 是 `p<毫秒时间戳>`，本身不含换行，换行是安全的���隔符。
     */
    private val KEY_PROFILE_IDS = stringPreferencesKey("model_profile_ids")

    /** 把换行分隔的 id 串切成列表。 */
    private fun parseIds(raw: String?): List<String> =
        raw.orEmpty().split('\n').filter { it.isNotBlank() }

    /**
     * 同一毫秒内新建多个配置时的序号（见 [nextProfileId]）。
     *
     * 只保证**进程内**不重复 —— 跨进程靠时间戳区分。
     */
    private val profileSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** 当前生效的配置 id。 */
    private val KEY_PROFILE_ACTIVE = stringPreferencesKey("model_profile_active")

    /**
     * 配置 id -> 显示名。
     *
     * 编码成 `id\u0001name` 逐行拼接而不是 StringSet：
     * StringSet 无序，而配置列表要**保持用户建的顺序**。
     * `\u0001` 是 ASCII 控制符，不可能出现在用户输入的名字里。
     */
    private val KEY_PROFILE_NAMES = stringPreferencesKey("model_profile_names")

    /**
     * 某配置的某个字段的 key。
     *
     * [profileId] 为 null/空 时返回 [legacy] —— 这就是**旧配置兼容**的全部：
     * 没有 profileId 的调用方行为完全不变（读写的还是原来那批 key）。
     */
    private fun keyFor(profileId: String?, legacy: String): String =
        if (profileId.isNullOrBlank()) legacy else "p:" + profileId + ":" + legacy

    private fun profileKey(profileId: String?, suffix: String): Preferences.Key<String> =
        stringPreferencesKey(keyFor(profileId, suffix))

    /**
     * 当前所有配置（含参数，不含密钥）。
     *
     * 每次发射都读全部配置 —— 配置数量是个位数，代价可忽略；
     * 而「只读当前那条」会让下拉列表拿不到别的配置名。
     */
    val modelProfiles: Flow<dev.praxis.core.model.settings.ModelProfileSet> =
        dataStore.data.map { prefs ->
            val ids = parseIds(prefs[KEY_PROFILE_IDS])
            val names = parseProfileNames(prefs[KEY_PROFILE_NAMES].orEmpty())
            val profiles = ids.map { id ->
                dev.praxis.core.model.settings.ModelProfile(
                    id = id,
                    name = names[id] ?: id,
                    settings = readModelSettings(prefs, id),
                )
            }
            dev.praxis.core.model.settings.ModelProfileSet(
                profiles = profiles,
                activeId = prefs[KEY_PROFILE_ACTIVE].orEmpty(),
            )
        }

    /**
     * 从 Preferences 里读一套模型参数。
     *
     * [profileId] 为 null 时读旧 key —— 与多配置之前的行为逐字一致。
     */
    private fun readModelSettings(
        prefs: Preferences,
        profileId: String?,
    ): ModelSettings {
        val defaults = ModelSettings()
        return ModelSettings(
            baseUrl = prefs[profileKey(profileId, "model_base_url")] ?: defaults.baseUrl,
            model = prefs[profileKey(profileId, "model_id")] ?: defaults.model,
            modelIndex = prefs[intPreferencesKey(keyFor(profileId, "model_index"))]
                ?: defaults.modelIndex,
            temperature = prefs[doublePreferencesKey(keyFor(profileId, "model_temperature"))]
                ?: defaults.temperature,
            provider = ApiProvider.fromNameOrDefault(
                prefs[profileKey(profileId, "model_provider")],
            ),
            contextLimit = prefs[intPreferencesKey(keyFor(profileId, "context_limit"))] ?: 0,
            compactThreshold = (
                prefs[floatPreferencesKey(keyFor(profileId, "compact_threshold"))] ?: 0.75f
                ).coerceIn(0.1f, 0.95f),
            thinkingEffort = dev.praxis.core.model.settings.ThinkingEffort.entries
                .firstOrNull { it.name == prefs[profileKey(profileId, "thinking_effort")] }
                ?: dev.praxis.core.model.settings.ThinkingEffort.None,
        )
    }

    /**
     * 新建一个配置，返回它的 id。
     *
     * ## 为什么不校验重名
     *
     * 两个配置叫「公司」是用户自己的事 —— 拦住他只会让他改名而不是想清楚。
     * 界面上靠「当前选中」区分，不靠名字唯一。
     */
    suspend fun createProfile(
        name: String,
        settings: ModelSettings = ModelSettings(),
    ): String {
        // ## id 必须**在写入时**校验唯一性（2026-11-05 修）
        //
        // 原实现是 `val id = "p" + System.currentTimeMillis()`，
        // 在 edit 块**外面**算好。两个问题：
        //
        // 1. **同一毫秒内建两个配置会撞 id**。用户连点两下「新建」
        //    就是这种情形（真机上一毫秒足够处理两次点击）。
        //    撞了之后两个配置共用 id -> 改一个等于改两个、
        //    删一个等于删两个，而且 `modelProfiles` 会返回两条同 id 的记录。
        // 2. 在 edit 外面算，就没法拿「当前已有哪些 id」来避让。
        //
        // 修法两层保险：
        //  - `nextProfileId()` 用「时间戳 + 进程内自增序号」，同毫秒也不再撞；
        //  - 再与已有 id 比对，撞了就重新取（跨进程重启后序号会归零，
        //    这一层兜住那种极端情况）。
        var created = ""
        dataStore.edit { prefs ->
            val existing = parseIds(prefs[KEY_PROFILE_IDS]).toSet()
            var candidate = nextProfileId()
            while (candidate in existing) candidate = nextProfileId()
            created = candidate
            prefs[KEY_PROFILE_IDS] = (parseIds(prefs[KEY_PROFILE_IDS]) + candidate).joinToString("\n")
            prefs[KEY_PROFILE_NAMES] = appendProfileName(
                prefs[KEY_PROFILE_NAMES].orEmpty(),
                candidate,
                name.ifBlank { "新配置" },
            )
            writeModelSettings(prefs, candidate, settings)
            // 第一个配置自动成为当前 —— 否则用户建完发现「没生效」。
            if (prefs[KEY_PROFILE_ACTIVE].isNullOrBlank()) prefs[KEY_PROFILE_ACTIVE] = candidate
        }
        return created
    }

    /**
     * 生成一个配置 id。
     *
     * 形状 `p<毫秒时间戳>-<序号>`：
     *  - 时间戳在前：人眼排错时能看出「大概什么时候建的」，也是天然的有序前缀；
     *  - 序号在后：同一毫秒内多次创建也不会重复。
     *
     * 序号的初值从 0 开始，进程重启会归零 —— 但那没关系：
     * 重启后时间戳必然已经变了。两者同时相同的概率为零。
     */
    private fun nextProfileId(): String =
        "p" + System.currentTimeMillis() + "-" + profileSeq.getAndIncrement()

    /** 切换当前配置。id 不存在时不做任何事（不写脏值）。 */
    suspend fun setActiveProfile(id: String) {
        dataStore.edit { prefs ->
            if (id in parseIds(prefs[KEY_PROFILE_IDS])) prefs[KEY_PROFILE_ACTIVE] = id
        }
    }

    /** 重命名。id 不变，只改显示名。 */
    suspend fun renameProfile(id: String, name: String) {
        if (name.isBlank()) return
        dataStore.edit { prefs ->
            if (id !in parseIds(prefs[KEY_PROFILE_IDS])) return@edit
            prefs[KEY_PROFILE_NAMES] = appendProfileName(
                prefs[KEY_PROFILE_NAMES].orEmpty(),
                id,
                name.trim(),
            )
        }
    }

    /**
     * 删除一个配置。
     *
     * ## 三条规则
     *
     * 1. **最后一个不许删**：删完就一个配置都没有了，界面会空掉，
     *    而「新建」按钮在空列表下也还在 —— 但用户会先懵一下。
     *    留一个空配置比留一个空列表好。
     * 2. **删的是当前配置时，自动切到剩下的第一个**，否则 `activeId`
     *    指向一个不存在的 id，所有读取都会回落到旧 key（= 空配置）。
     * 3. **不删 key 本体**：DataStore 没有「批量删前缀」的 API，
     *    逐个删要枚举所有已知 key，漏一个就留脏数据。而这些 key 不再
     *    被任何 id 引用，等价于死数据 —— 占几十字节，不值得为它冒删错的风险。
     */
    suspend fun deleteProfile(id: String) {
        dataStore.edit { prefs ->
            val ids = parseIds(prefs[KEY_PROFILE_IDS])
            if (ids.size <= 1 || id !in ids) return@edit
            val remaining = ids - id
            prefs[KEY_PROFILE_IDS] = remaining.joinToString("\n")
            prefs[KEY_PROFILE_NAMES] = removeProfileName(prefs[KEY_PROFILE_NAMES].orEmpty(), id)
            if (prefs[KEY_PROFILE_ACTIVE] == id) {
                prefs[KEY_PROFILE_ACTIVE] = remaining.first()
            }
        }
    }

    /** 写一套完整参数到某配置（新建时用）。 */
    private fun writeModelSettings(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        profileId: String,
        settings: ModelSettings,
    ) {
        prefs[profileKey(profileId, "model_base_url")] = settings.baseUrl
        prefs[profileKey(profileId, "model_id")] = settings.model
        prefs[intPreferencesKey(keyFor(profileId, "model_index"))] = settings.modelIndex
        prefs[doublePreferencesKey(keyFor(profileId, "model_temperature"))] = settings.temperature
        prefs[profileKey(profileId, "model_provider")] = settings.provider.name
        prefs[intPreferencesKey(keyFor(profileId, "context_limit"))] = settings.contextLimit
        prefs[floatPreferencesKey(keyFor(profileId, "compact_threshold"))] = settings.compactThreshold
        prefs[profileKey(profileId, "thinking_effort")] = settings.thinkingEffort.name
    }

    private fun parseProfileNames(raw: String): Map<String, String> =
        raw.split('\n')
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val idx = line.indexOf('\u0001')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()

    private fun appendProfileName(raw: String, id: String, name: String): String {
        val kept = raw.split('\n')
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith(id + "\u0001") }
        return (kept + (id + "\u0001" + name)).joinToString("\n")
    }

    private fun removeProfileName(raw: String, id: String): String =
        raw.split('\n')
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith(id + "\u0001") }
            .joinToString("\n")

    /**
     * 把某个模型参数写进**当前 profile**（没有 profile 时写旧 key）。
     *
     * ## 为什么所有 setter 都走它
     *
     * 多配置之后，「设置页改了不生效」是这个功能最容易出的 bug：
     * 界面显示的是当前 profile 的值，而写入写到了旧扁平 key ——
     * 用户改完切走再回来，发现改动消失了。
     *
     * 统一走这一个入口，就不存在「某个 setter 忘了带前缀」的可能。
     */
    private suspend fun putModelPref(key: Preferences.Key<String>, value: String) {
        dataStore.edit { prefs ->
            val known = parseIds(prefs[KEY_PROFILE_IDS])
            val id = prefs[KEY_PROFILE_ACTIVE]?.takeIf { it in known }
            prefs[if (id == null) key else stringPreferencesKey("p:" + id + ":" + key.name)] = value
        }
    }

    private suspend fun putModelPrefInt(legacyName: String, value: Int) {
        dataStore.edit { prefs ->
            val known = parseIds(prefs[KEY_PROFILE_IDS])
            val id = prefs[KEY_PROFILE_ACTIVE]?.takeIf { it in known }
            prefs[intPreferencesKey(keyFor(id, legacyName))] = value
        }
    }

    private suspend fun putModelPrefDouble(legacyName: String, value: Double) {
        dataStore.edit { prefs ->
            val known = parseIds(prefs[KEY_PROFILE_IDS])
            val id = prefs[KEY_PROFILE_ACTIVE]?.takeIf { it in known }
            prefs[doublePreferencesKey(keyFor(id, legacyName))] = value
        }
    }

    private suspend fun putModelPrefFloat(legacyName: String, value: Float) {
        dataStore.edit { prefs ->
            val known = parseIds(prefs[KEY_PROFILE_IDS])
            val id = prefs[KEY_PROFILE_ACTIVE]?.takeIf { it in known }
            prefs[floatPreferencesKey(keyFor(id, legacyName))] = value
        }
    }

    suspend fun setBaseUrl(url: String) = putModelPref(KEY_BASE_URL, url.trim())

    suspend fun setModel(model: String) = putModelPref(KEY_MODEL, model.trim())

    /**
     * 当前使用模型池里的第几个（0 起）。
     *
     * `coerceAtLeast(0)`：负数索引在 `activeModel` 里虽然也会被兜底成 0，
     * 但存一个负数进库没有意义 —— 它只会让「设置页显示第几项」这件事
     * 更难解释。上界不在这里夹：这里不知道池子有几项（那要去解析 model），
     * 由 [dev.praxis.core.model.settings.ModelSettings.activeModel] 兜底。
     */
    suspend fun setModelIndex(index: Int) =
        putModelPrefInt("model_index", index.coerceAtLeast(0))

    suspend fun setTemperature(value: Double) =
        putModelPrefDouble("model_temperature", value)

    /** 选中的服务商预设（只用于回填 UI 与判断是否需要 key）。 */
    suspend fun setModelProvider(provider: ApiProvider) =
        putModelPref(KEY_MODEL_PROVIDER, provider.name)

    /** 模型上下文上限（token）。0 = 未知（进度条不画比例）。 */
    suspend fun setContextLimit(tokens: Int) =
        putModelPrefInt("context_limit", tokens.coerceAtLeast(0))

    /** 压缩阈值（0.1..0.95）。 */
    suspend fun setCompactThreshold(ratio: Float) =
        putModelPrefFloat("compact_threshold", ratio.coerceIn(0.1f, 0.95f))

    /** 思考强度。 */
    suspend fun setThinkingEffort(effort: dev.praxis.core.model.settings.ThinkingEffort) =
        putModelPref(KEY_THINKING_EFFORT, effort.name)

    /**
     * 用户自定义系统提示词。
     *
     * ## 为什么按原样存（不 trim、不截断）
     *
     * 用户写的提示词里，缩进、空行、Markdown 列表都是有意义的格式。
     * trim 掉首尾空白看似无害，但用户如果刻意在开头留一个空行做分隔，
     * 每次保存都被吃掉会让人以为编辑器在自己改内容。
     *
     * 只在「判断是否为空」时用 isBlank()，存的是原文。
     */
    suspend fun setSystemPrompt(text: String) = put(KEY_SYSTEM_PROMPT, text)

    /** 用户自定义系统提示词（Flow，供设置页与聊天页共用）。 */
    val systemPrompt: Flow<String> = dataStore.data.map { it[KEY_SYSTEM_PROMPT] ?: "" }

    /**
     * 对话显示风格（`cursor` / `bubble`）。
     *
     * 映射成 String 而不是在这里引用 `feature:chat` 的枚举：`core:data` 不应该
     * 依赖任何 feature 模块（依赖方向只能是 feature → core）。解析与回落
     * 交给读取方（`PraxisApp`），这里只保证「缺省给 cursor」。
     *
     * 缺省 `cursor` = Operit 的默认风格；只有用户主动切过才会变成 `bubble`。
     */
    val chatDisplayStyle: Flow<String> =
        dataStore.data.map { it[KEY_CHAT_DISPLAY_STYLE] ?: "cursor" }

    /**
     * 功能级模型覆盖（「功能模型配置」页）。
     *
     * ## 为什么整表存成一个字符串，而不是每个功能位一个 key
     *
     * 六个功能位各开一个 key 会有三个问题：
     *  1. 新增功能位要新增 key（且要处理「旧版本没有这个 key」）；
     *  2. 一条覆盖涉及 model / provider / baseUrl 三个值，拆 key 后
     *     没有原子性 —— 写到一半被杀进程会留下「有 provider 没 model」的半截配置；
     *  3. 读取要 map 六次，每次设置页重组都会重复算。
     *
     * 存成一行 JSON：一次读、一次写、原子、且新增功能位不需要动存储层。
     *
     * 解析失败（旧版本/手改）一律回落空表 = 全部跟随全局，不抛异常：
     * 这份配置只影响「用哪个模型」，坏掉最多是回默认，不该让设置页崩。
     */
    val functionModelSettings: Flow<FunctionModelSettings> = dataStore.data.map { prefs ->
        decodeFunctionOverrides(prefs[KEY_FUNCTION_MODELS])
    }

    /** 覆盖某个功能位的模型。 */
    suspend fun setFunctionModel(slot: FunctionSlot, override: FunctionModelOverride) {
        dataStore.edit { prefs ->
            val current = decodeFunctionOverrides(prefs[KEY_FUNCTION_MODELS])
            prefs[KEY_FUNCTION_MODELS] = encodeFunctionOverrides(current.with(slot, override))
        }
    }

    /** 清空全部功能覆盖（「恢复默认」用）。 */
    suspend fun clearFunctionModels() {
        dataStore.edit { it.remove(KEY_FUNCTION_MODELS) }
    }

    /**
     * 写入服务商密钥。
     *
     * ## 加密路径
     *
     * 有 cipher 时存 AES/GCM 密文（密钥在 Android Keystore 内，不进 data/data）。
     * 加密失败则回落存明文 —— 宁可降低强度也要保住可用性，
     * 因为「存不进密钥」会让用户完全无法使用模型。
     *
     * ## 为什么不在 apiKey() 里解密失败时抛
     *
     * 解密失败最常见的原因是「换机恢复 / Keystore 被清」。
     * 这时抛异常会让设置页与聊天页一起崩；返回空串等价于「还没配密钥」，
     * 设置页会提示重新填写，这是可恢复的。
     */
    suspend fun setApiKey(key: String) {
        val trimmed = key.trim()
        // ## 密钥必须按配置存（2026-11-05）
        //
        // 多配置之后，每个配置是**不同的服务商**，key 当然不同。
        // 存在同一个 `model_api_key` 里会出这种后果：
        //
        //   配置 A（公司中转站）填了 keyA
        //   切到配置 B（本地网关）-> 用的还是 keyA -> 401
        //
        // 而用户看到的是「我刚填过 key，怎么又不对了」。
        val targetKey = profileScopedApiKeyName()
        if (trimmed.isEmpty()) {
            dataStore.edit { prefs -> prefs[stringPreferencesKey(targetKey)] = "" }
            return
        }

        val encrypted = cipher?.encrypt(trimmed)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(targetKey)] = encrypted ?: trimmed
        }
    }

    /**
     * 当前配置对应的 apiKey 存储名。
     *
     * 没有 profile（老用户）时返回旧名 `model_api_key` ——
     * 行为与多配置之前完全一致，存量 key 不需要迁移。
     */
    private suspend fun profileScopedApiKeyName(): String {
        val prefs = dataStore.data.first()
        val known = parseIds(prefs[KEY_PROFILE_IDS])
        val id = prefs[KEY_PROFILE_ACTIVE]?.takeIf { it in known }
        return keyFor(id, KEY_API_KEY.name)
    }

    /**
     * 读取明文密钥。
     *
     * 兼容 v1 的明文存量：读到不含分隔符的值就原样返回，
     * 并在下一次 [setApiKey] 时自动升级为密文（无需显式迁移脚本）。
     */
    suspend fun apiKey(): String {
        // 读当前配置那一份（没有 profile 时就是旧名，兼容存量）。
        val name = profileScopedApiKeyName()
        val raw = dataStore.data.map { it[stringPreferencesKey(name)] ?: "" }.first()
        if (raw.isEmpty()) return ""

        val c = cipher ?: return raw
        if (!dev.praxis.core.data.security.KeystoreCipher.looksEncrypted(raw)) {
            // 旧版明文：直接用，下次保存时自动加密。
            return raw
        }
        return c.decrypt(raw) ?: ""
    }

    /**
     * 把存量明文密钥升级为密文（启动时调一次，幂等）。
     *
     * 不升级也不会错，但那样旧用户永远是明文，加固只对新用户生效。
     * 加密失败时保持原样（不丢数据）。
     */
    suspend fun migrateApiKeyIfNeeded() {
        val c = cipher ?: return
        val raw = dataStore.data.map { it[KEY_API_KEY] ?: "" }.first()
        if (raw.isEmpty()) return
        if (dev.praxis.core.data.security.KeystoreCipher.looksEncrypted(raw)) return

        val encrypted = c.encrypt(raw) ?: return
        put(KEY_API_KEY, encrypted)
    }

    suspend fun setThemeMode(mode: ThemeMode) = put(KEY_THEME_MODE, mode.name)

    suspend fun setColorSource(source: ColorSource) = put(KEY_COLOR_SOURCE, source.name)

    suspend fun setSeedColor(argb: Long) = put(KEY_SEED_COLOR, argb)

    suspend fun setPaletteStyle(style: PaletteStyle) = put(KEY_PALETTE, style.name)

    suspend fun setColorSpec(spec: ColorSpecVersion) = put(KEY_COLOR_SPEC, spec.name)

    /** 预测性返回动画档位。 */
    suspend fun setPredictiveBackAnimation(animation: PredictiveBackAnimation) =
        put(KEY_PREDICTIVE_BACK, animation.name)

    /** 预测性返回退场方向（仅 Scale / Classic 档生效）。 */
    suspend fun setPredictiveBackExitDirection(direction: PredictiveBackExitDirection) =
        put(KEY_PREDICTIVE_BACK_DIRECTION, direction.name)

    /** 用侧边栏的引擎开关（miuix / M3）。 */
    suspend fun setUseMiuixEngine(enabled: Boolean) = put(KEY_USE_MIUIX, enabled)

    /**
     * 对话显示风格（`cursor` / `bubble`）。
     *
     * ## 为什么存字符串而不是布尔
     *
     * Operit 的 `chat_style` 是字符串，将来若再加一种风格（比如「紧凑」），
     * 布尔键就必须换名迁移；字符串键只要新增一个取值，老值原样有效。
     *
     * 未知值一律回落 `cursor`：那是 Operit 的默认，也是本项目的默认。
     * **不要**回落 `bubble` —— 那会让「存了个坏值」表现为「样式自己变回旧样子」，
     * 比直接报错更难查。
     */
    suspend fun setChatDisplayStyle(style: String) = put(KEY_CHAT_DISPLAY_STYLE, style)

    /**
     * 上次停留的会话 id。空串表示「从未记录过」。
     *
     * 调用方拿到空串时应该自己去挑一个默认会话（比如最近更新的那个），
     * 而不是硬编码 `"main"` —— 硬编码会在「main 已被删除」时给出一个
     * 不存在的会话，用户看到空聊天页且无法发送。
     */
    val lastChatSessionId: Flow<String> =
        dataStore.data.map { it[KEY_LAST_CHAT_SESSION] ?: "" }

    /** 记录当前会话（切会话时调用）。 */
    suspend fun setLastChatSessionId(sessionId: String) = put(KEY_LAST_CHAT_SESSION, sessionId)

    /** 是否自动保存记忆。默认开启（与 Operit 的默认一致）。 */
    val autoMemoryEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_AUTO_MEMORY] ?: true }

    suspend fun setAutoMemoryEnabled(enabled: Boolean) = put(KEY_AUTO_MEMORY, enabled)

    /**
     * 被**关掉**的技能名集合（插件组的持久化底座）。
     *
     * ## 为什么存「关掉的」而不是「开着的」
     *
     * 技能是扫盘出来的（`SkillRegistry.reload()`），用户随时可能丢一个新
     * skill 进目录。存「开着的」集合，新技能默认是关的 —— 用户会觉得
     * 「我明明放进去了怎么不生效」。存「关掉的」，新技能默认为开。
     *
     * 存逗号分隔串而不是序列化 Set：技能名按约定不含逗号，串最简单可靠。
     */
    val disabledSkills: Flow<Set<String>> =
        dataStore.data.map { prefs ->
            prefs[KEY_DISABLED_SKILLS]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
        }

    /** 记录禁用的技能集合（空集合等价于「全部启用」）。 */
    suspend fun setDisabledSkills(names: Set<String>) =
        put(KEY_DISABLED_SKILLS, names.filter { it.isNotBlank() }.joinToString(","))

    /**
     * 用户禁用的**单个工具**名集合（对齐 Operit 工具组的 slot 粒度开关）。
     *
     * ## 与权限档位是独立维度
     *
     * 档位是「能力上限」（STANDARD/DEBUGGER/ROOT），禁用列表是
     * 「用户就是不想让这个工具跑」。两者都要满足，工具才发给模型
     * —— 见 `ToolRegistry.descriptorsFor(snapshot)`。
     *
     * ## 为什么必须持久化
     *
     * `ToolPermissionState` 是内存态（进程重启即丢）。用户关掉一个工具
     * 是长期意图，不写盘等于「重启就复活」，这比没有开关更糟。
     */
    val disabledTools: Flow<Set<String>> =
        dataStore.data.map { prefs ->
            prefs[KEY_DISABLED_TOOLS]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
        }

    /** 记录禁用的工具名集合。 */
    suspend fun setDisabledTools(names: Set<String>) =
        put(KEY_DISABLED_TOOLS, names.filter { it.isNotBlank() }.joinToString(","))

    /**
     * 终端设置（第 12 项缺陷：终端齿轮要有自己的目的地）。
     *
     * 读盘后立刻 [TerminalSettings.sanitized]：DataStore 里的值可能来自
     * 手改、降级安装、或将来改了取值范围。不夹取值域的话，终端渲染层会拿到
     * 0sp 字号（整屏文字不可见，且没有任何异常）或 0 行回滚（一提交命令就丢历史）。
     */
    val terminalSettings: Flow<TerminalSettings> = dataStore.data.map { prefs ->
        TerminalSettings(
            fontSizeSp = prefs[KEY_TERMINAL_FONT_SIZE]
                ?: TerminalSettings.DEFAULT_FONT_SIZE_SP,
            scrollbackLines = prefs[KEY_TERMINAL_SCROLLBACK]
                ?: TerminalSettings.DEFAULT_SCROLLBACK_LINES,
            showBanner = prefs[KEY_TERMINAL_BANNER] ?: true,
        ).sanitized()
    }

    /** 终端字号（sp）。越界值在这里就被夹回，不依赖调用方。 */
    suspend fun setTerminalFontSize(sp: Float) = put(
        KEY_TERMINAL_FONT_SIZE,
        sp.coerceIn(
            TerminalSettings.MIN_FONT_SIZE_SP,
            TerminalSettings.MAX_FONT_SIZE_SP,
        ),
    )

    /** 终端回滚行数上限。 */
    suspend fun setTerminalScrollback(lines: Int) = put(
        KEY_TERMINAL_SCROLLBACK,
        lines.coerceIn(
            TerminalSettings.MIN_SCROLLBACK_LINES,
            TerminalSettings.MAX_SCROLLBACK_LINES,
        ),
    )

    /** 终端首屏字符画开关。 */
    suspend fun setTerminalShowBanner(show: Boolean) = put(KEY_TERMINAL_BANNER, show)

    // ── Linux 环境：共享 tmp（2026-10-23 新增）─────────────────────

    /**
     * 容器的 `/tmp` 是否与宿主私有目录共享。
     *
     * ## 为什么它必须持久化而不是留在 ViewModel 内存里
     *
     * 这个值决定 proot 的 `-b host:/tmp` 启动参数。留在内存里的话：
     *  - 用户开了开关、装好环境、**重启 App** → 开关回到关闭，
     *    但容器 `/tmp` 里已经有文件，下次启动突然「文件不见了」；
     *  - 更糟的是「体积统计」按这个开关决定要不要单独统计 tmp 目录，
     *    重启后统计口径变了，用户看到体积凭空少了几百 MB。
     *
     * 放进 DataStore 后，开关状态跨重启稳定，统计口径也跟着稳定。
     */
    val linuxSharedTmp: Flow<Boolean> = dataStore.data.map { it[KEY_LINUX_SHARED_TMP] ?: false }

    /** 设置共享 tmp 开关。**改它需要重启容器才生效**（见 `LinuxEnvironment.sharedTmp`）。 */
    suspend fun setLinuxSharedTmp(enabled: Boolean) = put(KEY_LINUX_SHARED_TMP, enabled)

    // ── 虚拟键盘布局（2026-10-23 新增）────────────────────────────

    /**
     * 自定义的键帽标签（拍平的 14 个，旧的在前）。
     *
     * ## 为什么存成一个分隔字符串而不是 DataStore 的 stringSet
     *
     * `stringSet` 是**无序**的。而键帽标签天然有序（第 3 格永远是 `-`）。
     * 用 set 存就得额外存一份「顺序」的索引，两份数据要保持同步 ——
     * 那是典型的「一半更新了、一半没更新」bug 温床。
     *
     * 用单个字符串 + 分隔符：一次写入、一次读出，原子且有序。
     * 分隔符选 `\u0001`（SOH）而不是逗号/竖线：用户完全可能在标签里
     * 打出 `,` 或 `|`（它们是终端常用字符），而 `\u0001` 是控制字符，
     * `TextField` 里打不出来，也不会有输入法组合产生它。
     *
     * ## 读盘后立刻 `sanitize`
     *
     * 与 `terminalSettings` 同一个理由：DataStore 里的值可能来自手改、
     * 降级安装、或将来改了行列数。不校验的话一个 13 格的布局会让
     * `weight(1f)` 那一排对不齐，而原因极难看出来。
     */
    val virtualKeyboardLabels: Flow<List<String>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_VIRTUAL_KEYBOARD_LABELS]
        if (raw.isNullOrEmpty()) {
            VirtualKeyboardLayoutCodec.DEFAULT_LABELS
        } else {
            VirtualKeyboardLayoutCodec.sanitize(raw.split(LABEL_SEPARATOR))
        }
    }

    /**
     * 写入自定义键帽标签。
     *
     * @param labels 拍平的标签列表。写入前会 [VirtualKeyboardLayoutCodec.sanitize] 一次 ——
     *   保证「存进去的一定是合法布局」，读盘侧就不必承担修复责任
     *   （两边都修会让「到底存的是什么」变得不可预测）。
     */
    suspend fun setVirtualKeyboardLabels(labels: List<String>) {
        val clean = VirtualKeyboardLayoutCodec.sanitize(labels)
        put(KEY_VIRTUAL_KEYBOARD_LABELS, clean.joinToString(LABEL_SEPARATOR))
    }

    /** 恢复默认键帽标签。 */
    suspend fun resetVirtualKeyboardLabels() {
        put(
            KEY_VIRTUAL_KEYBOARD_LABELS,
            VirtualKeyboardLayoutCodec.DEFAULT_LABELS.joinToString(LABEL_SEPARATOR),
        )
    }

    // ── 权限级别（2026-10-24 引导页两段式改造新增）────────────────

    /**
     * 用户在引导页选定并确认过的权限级别。
     *
     * ## 为什么它必须落盘，而不是只用 `deriveFrom(snapshot)` 现算
     *
     * 两者回答的是不同问题：
     *
     * | 值 | 回答 |
     * |---|---|
     * | `deriveFrom(snapshot)` | 「本机**实际**到哪一级」（客观能力） |
     * | 本字段 | 「用户**希望**用到哪一级」（主观授权范围） |
     *
     * 只按实际能力算，会造出「用户只想让它跑基础命令，但因为有 Root
     * 就把所有 Root 级工具都发给模型」—— 那是越权，而且用户没法收回。
     * 所以引导页选的那一下必须存下来，作为**上限**使用。
     *
     * ## 缺省值为什么是 `null` 而不是 `STANDARD`
     *
     * `null` = 用户从未表达过意图。调用方据此可以判断「要不要在设置页
     * 再问一次」，而 `STANDARD` 会被误读成「他明确选了最低档」。
     *
     * 未知值（降级安装 / 枚举改名）一律回落成 `null`，见 [toLevelOrNull]。
     */
    val permissionLevel: Flow<dev.praxis.core.model.permission.PraxisPermissionLevel?> =
        dataStore.data.map { prefs -> prefs[KEY_PERMISSION_LEVEL].toLevelOrNull() }

    /**
     * 写入用户选定的权限级别；传 `null` 表示清空（回到「跟随实际能力」）。
     *
     * ## 为什么 null 走 remove 而不是 put
     *
     * 与 [acceptAgreement] 同一个理由：`stringPreferencesKey` 是 `Key<String>`，
     * `put(key, null)` 会因为泛型推断成 `Key<String?>` 而编译失败；
     * 语义上「键不存在」本身才是「没有选过」的规范表达。
     */
    suspend fun setPermissionLevel(
        level: dev.praxis.core.model.permission.PraxisPermissionLevel?,
    ) {
        if (level == null) {
            dataStore.edit { it.remove(KEY_PERMISSION_LEVEL) }
        } else {
            put(KEY_PERMISSION_LEVEL, level.name)
        }
    }

    /**
     * 是否关闭流式输出（1:1 对齐 Operit `disableStreamOutput`）。
     *
     * ## 为什么默认 false（保持流式）
     *
     * 流式是这类应用的默认体验：用户能立刻看到「模型在动」。
     * 默认关闭会让首次使用者以为应用卡死（十几秒无任何反馈）。
     * 所以这是个**主动选择**的降级开关，不是默认状态。
     *
     * ## 谁会真的关掉它
     *
     * - 用会吐 `<think>` / `<tool>` 标签的模型时，逐字渲染会产生大量
     *   「标签抖动」（先显示半个标签再被解析掉）；
     * - 网络极差时，一段段跳字的观感比等一次更差。
     *
     * 这是用户原话「流式输出时也应该跟随输出丝滑滚动而不是等 ai 输出完后再滚动」
     * 的另一面：滚动跟随是默认行为，而**这个开关**是让用户自己选择
     * 退回非流式的那条后路。
     */
    val disableStreamOutput: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_DISABLE_STREAM_OUTPUT] ?: false }

    /** 切换流式输出开关。 */
    suspend fun setDisableStreamOutput(disabled: Boolean) =
        put(KEY_DISABLE_STREAM_OUTPUT, disabled)

    /**
     * 是否自动朗读助手回复（1:1 对齐 Operit 的 isAutoReadEnabled）。
     *
     * 默认关闭：朗读会占音频焦点、打断用户正在听的音乐，
     * 默认开等于应用一启动就抢扬声器。
     */
    val autoReadReplies: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_AUTO_READ_REPLIES] ?: false }

    /** 切换自动朗读。 */
    suspend fun setAutoReadReplies(enabled: Boolean) =
        put(KEY_AUTO_READ_REPLIES, enabled)

    /**
     * 当前活动的记忆空间 id（空串 = 默认空间）。
     *
     * 为什么要持久化：切空间是长期意图。存内存的话，杀一次进程就回到「默认」，
     * 用户下次开口就会发现它又忘了「工作」里记的东西。
     */
    val activeMemorySpace: Flow<String> =
        dataStore.data.map { prefs -> prefs[KEY_ACTIVE_MEMORY_SPACE] ?: "" }


    /**
     * 用户显式创建过的记忆空间名字（按创建顺序，已去重）。
     *
     * ## 为什么不从记忆表反推（2026-10-29 修）
     *
     * 旧实现的空间列表来自 `store.all(null).groupingBy { it.spaceId }` ——
     * 也就是「有记忆的空间」才会出现在选择器里。这造成一个死锁：
     * 想建一个叫「工作」的空间，得先在里面存一条记忆；但那需要先切到
     * 「工作」；而「工作」还不在列表里，切不过去。
     *
     * 所以空间名要独立存一份：创建动作自己就是一个来源，与内容无关。
     * 记住的只是**名字**，条数仍然实算（删完记忆后「工作 · 0」是对的，
     * 不该因为没内容就消失）。
     */
    val memorySpaces: Flow<List<String>> =
        dataStore.data.map { prefs ->
            prefs[KEY_MEMORY_SPACES]
                ?.split(LABEL_SEPARATOR)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?: emptyList()
        }

    /**
     * 新建一个记忆空间。
     *
     * 空串与纯空白忽略：空串是默认空间的 id，不能被「创建」。
     * 重名也直接忽略（`distinct`），不报错 —— 用户重复点一次不该弹错。
     */
    suspend fun addMemorySpace(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_MEMORY_SPACES]
                ?.split(LABEL_SEPARATOR)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            if (trimmed in current) return@edit
            prefs[KEY_MEMORY_SPACES] = (current + trimmed).joinToString(LABEL_SEPARATOR)
        }
    }

    /**
     * 删除一个记忆空间的**名字**。
     *
     * 不删该空间下的记忆：空间只是标签，记忆是数据。
     * 名字删掉后记忆仍在库里（`spaceId` 不变），若用户重新创建同名空间
     * 会重新看到它们 —— 这比「删名字就等于删数据」安全。
     * 想清空数据请在记忆列表里逐条删。
     */
    suspend fun removeMemorySpace(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_MEMORY_SPACES]
                ?.split(LABEL_SEPARATOR)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            prefs[KEY_MEMORY_SPACES] = current
                .filter { it != trimmed }
                .joinToString(LABEL_SEPARATOR)
        }
    }

    /** 切换记忆空间。 */
    suspend fun setActiveMemorySpace(spaceId: String) =
        put(KEY_ACTIVE_MEMORY_SPACE, spaceId.trim())

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
                dataStore.edit { it[key] = value }
    }

}

/**
 * 枚举反序列化。
 *
 * 用 `entries.firstOrNull` 而非 `valueOf`：用户降级安装、或枚举被改名后，
 * `valueOf` 会直接抛 IllegalArgumentException 把整个设置页打挂；
 * 这里回落默认值，界面照常可用。
 */
private inline fun <reified T : Enum<T>> String?.toEnumOr(fallback: T): T =
    this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback

private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
private val KEY_COLOR_SOURCE = stringPreferencesKey("color_source")
private val KEY_SEED_COLOR = longPreferencesKey("seed_color_argb")
private val KEY_PALETTE = stringPreferencesKey("palette_style")
private val KEY_COLOR_SPEC = stringPreferencesKey("color_spec")
private val KEY_PREDICTIVE_BACK = stringPreferencesKey("predictive_back_animation")
private val KEY_PREDICTIVE_BACK_DIRECTION = stringPreferencesKey("predictive_back_exit_direction")
private val KEY_USE_MIUIX = booleanPreferencesKey("use_miuix_engine")

/**
 * 对话显示风格。取值 `cursor` / `bubble`，缺省按 `cursor`（= Operit 默认）。
 *
 * 与 Operit 的 `chat_style` 同义，只是键名带前缀避免与它的旧备份混淆。
 */
private val KEY_CHAT_DISPLAY_STYLE = stringPreferencesKey("chat_display_style")
private val KEY_BASE_URL = stringPreferencesKey("model_base_url")
private val KEY_MODEL = stringPreferencesKey("model_id")

/**
 * 当前使用模型池里的第几个（0 起）。
 *
 * ## 为什么用独立的 key 而不是把索引拼进 [KEY_MODEL]
 *
 * 拼成 `model_id = "a,b,c|1"` 之类会破坏「老版本读新库」的兼容性：
 * 旧版本会把整串当模型名发给服务端。独立 key 让旧版本读到 `model_id`
 * 时行为完全不变（它不认识 `model_index`，直接忽略）。
 *
 * 默认 0：老用户没有这个 key 时等价于「用池里第一个」，
 * 与 `activeModel` 的越界兜底一致。
 */
private val KEY_MODEL_INDEX = intPreferencesKey("model_index")
private val KEY_TEMPERATURE = doublePreferencesKey("model_temperature")
private val KEY_MODEL_PROVIDER = stringPreferencesKey("model_provider")
private val KEY_API_KEY = stringPreferencesKey("model_api_key")

/** 功能级模型覆盖表（JSON，见 [SettingsRepository.functionModelSettings]）。 */
private val KEY_FUNCTION_MODELS = stringPreferencesKey("function_models")
/** 上下文上限（token）。0 = 未知。 */
private val KEY_CONTEXT_LIMIT = intPreferencesKey("context_limit")
/** 压缩阈值（0..1）。 */
private val KEY_COMPACT_THRESHOLD = floatPreferencesKey("compact_threshold")
/** 思考强度（枚举名）。 */
private val KEY_THINKING_EFFORT = stringPreferencesKey("thinking_effort")

/**
 * 自定义请求头（`键值...`，密文）。
 *
 * 与 `KEY_API_KEY` 同样加密：值里可能是中转站令牌。
 */
private val KEY_CUSTOM_HEADERS = stringPreferencesKey("model_custom_headers")

/** 每分钟最大请求数。0 = 不限流（缺 key 时也是 0，老用户行为不变）。 */
private val KEY_REQUEST_LIMIT_RPM = intPreferencesKey("model_request_limit_rpm")

/** 最大并发请求数。0 = 不限。 */
private val KEY_MAX_CONCURRENT = intPreferencesKey("model_max_concurrent")
/** 用户自定义系统提示词（原文，不 trim）。 */
private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")

/**
 * 终端设置三个键（第 12 项缺陷）。
 *
 * 单独成组、前缀 `terminal_`：设置项会越来越多，按前缀分组后
 * 「哪些键属于终端」用 grep 就能看出来，不用逐个翻调用点。
 */
private val KEY_TERMINAL_FONT_SIZE = floatPreferencesKey("terminal_font_size_sp")
private val KEY_TERMINAL_SCROLLBACK = intPreferencesKey("terminal_scrollback_lines")
private val KEY_TERMINAL_BANNER = booleanPreferencesKey("terminal_show_banner")

/**
 * 共享 tmp 开关。
 *
 * 前缀 `linux_` 而不是 `terminal_`：它影响的是**容器启动参数**（`-b host:/tmp`），
 * 属于 Linux 环境的能力，不是终端页的观感设置。混进 `terminal_` 组会让
 * 后来者以为「终端设置页里所有键都是 terminal_ 前缀」，从而在找它时找错地方。
 */
private val KEY_LINUX_SHARED_TMP = booleanPreferencesKey("linux_shared_tmp")

/**
 * 自定义键帽标签（单个字符串，`\u0001` 分隔，拍平的 14 个）。
 *
 * 为什么不是 stringSet：set 无序，而键帽顺序有语义（见
 * [SettingsRepository.virtualKeyboardLabels] 的 KDoc）。
 */
private val KEY_VIRTUAL_KEYBOARD_LABELS = stringPreferencesKey("virtual_keyboard_labels")

/**
 * 上次停留的会话 id。
 *
 * ## 为什么需要它（2026-10-25 修：「重启后不是我退出前的会话」）
 *
 * 会话 id 原来只存在 `rememberSaveable`（`PraxisApp.kt:242`），
 * 那是**进程内**状态：只要进程死了（用户划掉、系统回收、崩溃重启），
 * 就会被重置回硬编码的 `"main"`。
 *
 * 后果不是「丢了一个会话」而是「看起来所有会话都乱了」：用户明明在
 * `chat-1758…` 里继续聊，重启后回到了 `main`，于是产生两个具体问题：
 *
 *  1. 看到的是另一个会话的内容 —— 用户会以为自己的消息丢了；
 *  2. 新消息被写进 `main` 而不是原会话 —— 上下文被劈成两半，
 *     而且没有任何报错能让用户意识到发生了这件事。
 *
 * 所以它必须落盘。放在 DataStore 而不是 Room：它是一条 UI 偏好
 * （「上次看的是哪个」），不是会话内容；Room 那边删会话时不该有
 * 一条孤立的偏好行被外键牵连。
 *
 * ## 为什么存 id 而不是「第几个」
 *
 * 会话会新增、删除、排序变化。下标一变就指错会话，而 id 不会。
 */
private val KEY_LAST_CHAT_SESSION = stringPreferencesKey("last_chat_session_id")

/**
 * 是否自动保存记忆（对话设置 →「记忆」分区的开关）。
 *
 * ## 为什么放在 DataStore 而不是 ViewModel 内部状态
 *
 * 这个开关的语义是「从今以后不再自动记」。若只在内存里，重启就恢复默认，
 * 用户会以为开关坏了 —— 而它是隐私相关的设置（不想让自己的对话被存下来），
 * 悄悄失效比没有这个开关更糟。
 */
private val KEY_AUTO_MEMORY = booleanPreferencesKey("auto_memory_enabled")
private val KEY_DISABLED_SKILLS = stringPreferencesKey("disabled_skills")
private val KEY_DISABLED_TOOLS = stringPreferencesKey("disabled_tools")

/**
 * 键帽标签之间的分隔符。
 *
 * 用 SOH（`\u0001`）而不是逗号/竖线/分号：终端用户会打出这些字符
 * （`ls -l | grep` 里的竖线就是），而 SOH 是控制字符，
 * `TextField` 里打不出来、输入法也不会产生它。
 *
 * 类型是 `String` 而不是 `Char`：`joinToString` 的 separator 参数
 * 是 `CharSequence`，`Char` 不会隐式转换（编不过）。用 String 让
 * `joinToString` 与 `split` 两侧都能直接用。
 */
private const val LABEL_SEPARATOR = "\u0001"



/**
 * 用户选定的权限级别上限（枚举名，见 [SettingsRepository.permissionLevel]）。
 */
private val KEY_PERMISSION_LEVEL = stringPreferencesKey("permission_level")

/**
 * 关闭流式输出的开关（1:1 对齐 Operit `disableStreamOutput`）。
 *
 * 语义是「关闭」而不是「开启」：键名与值都写成 disable，缺省 false。
 * 若写成 `stream_output_enabled`，缺省就得是 true —— 而 DataStore 里
 * 「键不存在」时返回 null，调用点每次都要写 `?: true`。用 disable 语义，
 * 缺省 false 与「不写就是默认行为」天然一致，少一处可能写错的地方。
 */
private val KEY_DISABLE_STREAM_OUTPUT = booleanPreferencesKey("disable_stream_output")
private val KEY_AUTO_READ_REPLIES = booleanPreferencesKey("auto_read_replies")
private val KEY_ACTIVE_MEMORY_SPACE = stringPreferencesKey("active_memory_space")
private val KEY_MEMORY_SPACES = stringPreferencesKey("memory_spaces")

/**
 * 把存储里的字符串还原成权限级别；认不出就返回 `null`。
 *
 * `null` 而不是 `STANDARD`：见 [SettingsRepository.permissionLevel] 的 KDoc ——
 * 认不出说明「这个值不可信」，此时应当回到「由实际能力决定」，
 * 而不是硬塞一个「用户选了最低档」的假事实。
 */
private fun String?.toLevelOrNull(): dev.praxis.core.model.permission.PraxisPermissionLevel? =
    this?.let { name ->
        dev.praxis.core.model.permission.PraxisPermissionLevel.entries
            .firstOrNull { it.name == name }
    }

/** 全局唯一的 DataStore 实例（Context 扩展，保证同进程只建一次）。 */
private val Context.praxisSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "praxis_settings",
)

/** 从 Context 建仓储（DI 入口在 :app 的 Koin module 里调用）。 */
fun createSettingsRepository(context: Context): SettingsRepository =
    SettingsRepository(
        dataStore = context.applicationContext.praxisSettingsStore,
        cipher = dev.praxis.core.data.security.KeystoreCipher(),
    )

/**
 * 把全局 DataStore 暴露给 `:app` 里其它端口实现（终端快照等）。
 *
 * ## 为什么必须走这个函数，而不是各自 `preferencesDataStore(name=…)`
 *
 * `preferencesDataStore` 是「按文件名 + 进程单例」的实现：同一进程里对
 * **同一个文件**创建两个 DataStore 实例，第二次访问会抛
 * `IllegalStateException: There are multiple DataStores active for the same file`。
 * 而 `praxisSettingsStore` 是 `private` 的，`:app` 里看不见它 ——
 * 以前只有「新建一个别的文件名」这一条路（会把设置拆成两个文件），
 * 或者把 `private` 去掉（破坏封装）。
 *
 * 这里给一个**只暴露实例、不暴露内部结构**的公开访问器：
 * 调用方拿到的是同一个 `DataStore<Preferences>`，可以直接 `edit { }`，
 * 但不知道 `SettingsRepository` 用了哪些键。
 *
 * ## 为什么用「键名前缀」而不是另建文件
 *
 * 终端快照与设置共用 `praxis_settings` 文件，靠键名
 * （`terminal_snapshot_v1`）隔离。收益是**不增加一个 DataStore 文件**，
 * 代价是两者写在同一个 atomic 文件里 —— 但 DataStore 的 `edit` 本身就是
 * 串行化的事务，不存在互相覆盖。
 */
fun createAppPreferencesStore(context: Context): DataStore<Preferences> =
    context.applicationContext.praxisSettingsStore

/**
 * 创建角色仓储，**复用与设置页同一个 DataStore**。
 *
 * ## 为什么不能自己新建一个 DataStore
 *
 * `preferencesDataStore` 是「按文件名委托 + 单例」的：
 * 同一个进程里对**同一个文件**创建两个 DataStore 实例，
 * 第二次访问会抛：
 *
 * ```
 * IllegalStateException: There are multiple DataStores active for the same file
 * ```
 *
 * 而活跃角色卡 id 存在设置的那个文件里（`praxis_settings`）——
 * 必须与 `SettingsRepository` 共用实例。
 *
 * 这里做成工厂函数而不是让 AppModule 直接 `get<DataStore<Preferences>>()`：
 * 那个类型**从来没有被注册过**（DataStore 是 Context 扩展的内部属性），
 * 按类型注入会在启动时抛「找不到定义」，而且报错点离真正原因很远。
 */
fun createCharacterRepository(
    context: Context,
    database: dev.praxis.core.data.character.CharacterDatabase,
): dev.praxis.core.data.character.CharacterRepository =
    dev.praxis.core.data.character.CharacterRepository(
        database = database,
        dataStore = context.applicationContext.praxisSettingsStore,
    )

// ── 功能级模型覆盖的编解码 ────────────────────────────────────────
//
// 手写而不是上 kotlinx.serialization：这里只有三层结构、字段全是可空字符串，
// 引入序列化插件会给 core:data 加注解处理器与运行时依赖；
// 而失败模式极简单（解析不了就当空表），手写 30 行更可控。

/**
 * 解码覆盖表。
 *
 * 格式（刻意做成扁平，便于人工排查）：
 * ```
 * {"chat":{"model":"deepseek-chat"},
 *  "title":{"model":"qwen-turbo","provider":"Qwen","baseUrl":"https://..."}}
 * ```
 *
 * 任何解析失败都返回空表：这份配置坏掉最多回到「全部跟随全局」，
 * 不该因为一行 JSON 把设置页打挂。
 */
internal fun decodeFunctionOverrides(raw: String?): FunctionModelSettings {
    if (raw.isNullOrBlank()) return FunctionModelSettings()
    return runCatching {
        val root = org.json.JSONObject(raw)
        val map = mutableMapOf<FunctionSlot, FunctionModelOverride>()
        for (slot in FunctionSlot.entries) {
            val obj = root.optJSONObject(slot.key) ?: continue
            val model = obj.optString("model").takeIf { it.isNotBlank() } ?: continue
            map[slot] = FunctionModelOverride(
                model = model,
                provider = ApiProvider.entries.firstOrNull { it.name == obj.optString("provider") },
                baseUrl = obj.optString("baseUrl").takeIf { it.isNotBlank() },
            )
        }
        FunctionModelSettings(overrides = map)
    }.getOrDefault(FunctionModelSettings())
}

/** 编码覆盖表。只写非空字段：让「跟随全局」在存储里不出现空串。 */
internal fun encodeFunctionOverrides(settings: FunctionModelSettings): String {
    val root = org.json.JSONObject()
    settings.overrides.forEach { (slot, o) ->
        val model = o.model ?: return@forEach
        val obj = org.json.JSONObject()
        obj.put("model", model)
        o.provider?.let { obj.put("provider", it.name) }
        o.baseUrl?.let { obj.put("baseUrl", it) }
        root.put(slot.key, obj)
    }
    return root.toString()
}
