package dev.praxis.core.imagegen

/**
 * `set_image_gen_config` 的边界规则（文档 §5.6）。
 *
 * ## 为什么这些规则必须在 core 里、且有单测
 *
 * 「哪些字段不许 AI 改」是一条**安全边界**：凭据、下载源、引擎来源、模型增删。
 * 把它们写在工具的实现文件里就只能靠 CI 编译保证「写对了」，而写错的表现是
 * 「模型能改下载源」这种不会报错、只会被利用的漏洞。抽成纯函数可以逐条钉住。
 */
object ImageGenConfigRules {

    /**
     * 不许 AI 改的键（命中即整次拒绝，不做部分应用）。
     *
     * 同时收下划线写法：模型很爱把 `apiKey` 写成 `api_key`，
     * 只认一种写法等于留了个绕过口子。
     */
    private val NOT_ALLOWED = setOf(
        "apikey", "api_key",
        "baseurl", "base_url",
        "enginesource", "engine_source",
        "deletemodel", "delete_model",
        "downloadmodel", "download_model",
    )

    /**
     * §7.1 的「输出」分区还没做（自动存相册 / 每步预览没有存储与实现）。
     *
     * 收到这些键时**不报错、也不假装改了** —— 如实回一句「这项还没做」，
     * 让模型能对用户说清楚，而不是含糊地说「都改好了」。
     */
    private val NOT_IMPLEMENTED = setOf("autosave", "auto_save", "showpreview", "show_preview")

    /** 归一化键名以便比对（大小写不敏感）。 */
    private fun normalize(key: String): String = key.trim().lowercase()

    /** @return 命中的越界键（原样返回，便于在错误信息里点名）。 */
    fun notAllowedKeys(keys: Collection<String>): List<String> =
        keys.filter { normalize(it) in NOT_ALLOWED }

    /** @return 命中「还没实现」的键。 */
    fun notImplementedKeys(keys: Collection<String>): List<String> =
        keys.filter { normalize(it) in NOT_IMPLEMENTED }

    /** 这个键是不是本工具认识的字段（不认识就忽略并说出来）。 */
    fun isKnownKey(key: String): Boolean = normalize(key) in setOf(
        "target", "width", "height", "steps", "cfg", "scheduler", "negative",
    ) + NOT_IMPLEMENTED + NOT_ALLOWED
}

/**
 * 一次配置修改的差异（文档 §5.6 的返回值形状）。
 *
 * ## 为什么要 diff
 *
 * 模型需要据此回复用户（「已经把步数从 20 改成 30」）。只回 `ok` 的话它只能含糊地说
 * 「改好了」，而用户无法核对 —— 这与「不要谎报验证状态」是同一条原则。
 */
data class ImageGenConfigChange(val field: String, val from: String, val to: String)

/** 差异计算（纯函数，便于单测）。 */
object ImageGenConfigDiff {

    /**
     * 逐字段比较默认参数，只列出**真的变了**的。
     *
     * 值一律转成字符串：调用方直接塞进 JSON，不再为每种类型写一遍序列化。
     */
    fun defaults(before: ImageGenDefaults, after: ImageGenDefaults): List<ImageGenConfigChange> {
        val changes = mutableListOf<ImageGenConfigChange>()
        fun add(field: String, from: Any?, to: Any?) {
            val a = from?.toString().orEmpty()
            val b = to?.toString().orEmpty()
            if (a != b) changes += ImageGenConfigChange(field, a, b)
        }
        add("width", before.width, after.width)
        add("height", before.height, after.height)
        add("steps", before.steps, after.steps)
        add("cfg", before.cfg, after.cfg)
        add("negative", before.negative, after.negative)
        add("scheduler", before.scheduler, after.scheduler)
        return changes
    }

    /** 目标的差异（`local:anythingv5` 这种紧凑写法，与文档示例一致）。 */
    fun target(before: ImageGenTarget?, after: ImageGenTarget?): ImageGenConfigChange? {
        val a = describe(before)
        val b = describe(after)
        return if (a == b) null else ImageGenConfigChange("target", a, b)
    }

    /** 目标的一句话描述。 */
    fun describe(target: ImageGenTarget?): String = when (target) {
        null -> ""
        is ImageGenTarget.Local -> "local:" + target.modelId
        is ImageGenTarget.Remote -> "remote:" + target.provider + "/" + target.model
    }
}
