package dev.praxis.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.praxis.core.model.settings.ColorSource
import dev.praxis.core.model.settings.ModelSettings
import dev.praxis.core.model.settings.ColorSpecVersion
import dev.praxis.core.model.settings.PaletteStyle
import dev.praxis.core.model.settings.ThemeMode
import dev.praxis.core.model.settings.ThemeSettings
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
        )
    }

    /** 模型配置（不含密钥）。 */
    val modelSettings: Flow<ModelSettings> = dataStore.data.map { prefs ->
        val defaults = ModelSettings()
        ModelSettings(
            baseUrl = prefs[KEY_BASE_URL] ?: defaults.baseUrl,
            model = prefs[KEY_MODEL] ?: defaults.model,
            temperature = prefs[KEY_TEMPERATURE] ?: defaults.temperature,
        )
    }

    suspend fun setBaseUrl(url: String) = put(KEY_BASE_URL, url.trim())

    suspend fun setModel(model: String) = put(KEY_MODEL, model.trim())

    suspend fun setTemperature(value: Double) = put(KEY_TEMPERATURE, value)

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
        if (trimmed.isEmpty()) {
            put(KEY_API_KEY, "")
            return
        }

        val encrypted = cipher?.encrypt(trimmed)
        put(KEY_API_KEY, encrypted ?: trimmed)
    }

    /**
     * 读取明文密钥。
     *
     * 兼容 v1 的明文存量：读到不含分隔符的值就原样返回，
     * 并在下一次 [setApiKey] 时自动升级为密文（无需显式迁移脚本）。
     */
    suspend fun apiKey(): String {
        val raw = dataStore.data.map { it[KEY_API_KEY] ?: "" }.first()
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

    /** 用侧边栏的引擎开关（miuix / M3）。 */
    suspend fun setUseMiuixEngine(enabled: Boolean) = put(KEY_USE_MIUIX, enabled)

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
private val KEY_USE_MIUIX = booleanPreferencesKey("use_miuix_engine")
private val KEY_BASE_URL = stringPreferencesKey("model_base_url")
private val KEY_MODEL = stringPreferencesKey("model_id")
private val KEY_TEMPERATURE = doublePreferencesKey("model_temperature")
private val KEY_API_KEY = stringPreferencesKey("model_api_key")

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
