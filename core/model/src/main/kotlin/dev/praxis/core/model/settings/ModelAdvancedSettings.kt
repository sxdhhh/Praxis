package dev.praxis.core.model.settings

/**
 * 自定义请求头的一条键值。
 *
 * ## 为什么用 `Pair` 而不是 `Map`
 *
 * 用户正在编辑的行很可能**键还是空的**（刚点「添加」还没输）。
 * `Map` 存不了空键，编辑过程中就得先把行暂存在别的结构里，
 * 保存时再拼 —— 两套状态必然不同步。用有序的 `List<Pair>` 全程只有一套。
 *
 * 有序也是必须的：用户看到的顺序应当与他输入的顺序一致，
 * 否则每保存一次行就跳位。
 */
typealias CustomHeader = Pair<String, String>

/**
 * 模型服务的进阶配置：自定义请求头 + 请求队列。
 *
 * 对齐 Operit 的 `ModelConfigData.customHeaders`、`requestLimitPerMinute`、
 * `maxConcurrentRequests` 三个字段。
 *
 * ## 为什么不放进 [ModelSettings]
 *
 * [ModelSettings] 会跟着状态快照、日志、crash report 到处流。
 * 请求头的值里经常放**中转站令牌**（`X-Api-Key`、`Authorization` 别名等），
 * 与 [ModelSettings] 的 KDoc 里「apiKey 不在这里」是同一个理由：
 * 敏感值单独存取。
 *
 * 另外这两组配置的变更频率与语义都不同于模型参数：
 * 改温度是「换个口味」，改请求头可能直接让请求 401。放在一起会让
 * 「保存」这个动作的含义变模糊。
 */
data class ModelAdvancedSettings(
    /**
     * 自定义请求头。空表示不加任何头。
     *
     * ## 谁会用这个
     *
     *  - 中转站要额外标识：`X-Api-Key` / `X-Account-Id`；
     *  - OpenRouter 的 `HTTP-Referer` / `X-Title`（Praxis 已内置，见 provider）；
     *  - 企业网关要 `X-Tenant`；
     *  - 部分服务要求把版本固定在头里。
     *
     * 这些都没有统一标准，所以只能让用户自己填。
     */
    val customHeaders: List<CustomHeader> = emptyList(),
    /**
     * 每分钟最多请求数。0 = 不限流。
     *
     * ## 什么时候需要
     *
     * 免费额度 / 低档套餐常有 RPM 上限，超了返回 429。
     * 客户端主动限流比「撞 429 再重试」体验好得多：不会浪费一次请求，
     * 也不会有「突然卡 10 秒」的莫名停顿。
     */
    val requestLimitPerMinute: Int = 0,
    /**
     * 最大并发请求数。0 = 不限。
     *
     * ## 与 [requestLimitPerMinute] 的分工
     *
     *  - RPM 管的是**单位时间内的总量**（时间维）；
     *  - 并发管的是**同一瞬间在飞的个数**（空间维）。
     *
     * 两者都要：RPM 低但并发不限时，一次并发 5 个仍可能把额度瞬间打光；
     * 并发限制 1 但 RPM 不限时，串行请求不会有并发冲击，但总量仍可能超。
     *
     * Praxis 实际只有「对话 + 摘要 + 标题 + 记忆」四类请求，并发天然很低，
     * 所以默认 0（不限）。用户明确知道自己在做什么时再填。
     */
    val maxConcurrentRequests: Int = 0,
) {
    /** 有没有真的配了东西。用于分区标题右侧显示「已配置 N 项」。 */
    val configuredHeaderCount: Int
        get() = customHeaders.count { it.first.isNotBlank() }

    val hasQueueLimits: Boolean
        get() = requestLimitPerMinute > 0 || maxConcurrentRequests > 0

    companion object {
        const val SEPARATOR = "\u001F"

        /**
         * 序列化成 DataStore 的单个字符串。
         *
         * ## 为什么不是 JSON
         *
         * 用 `\u001F`（ASCII Unit Separator）分隔 `键\u001F值\u001F键\u001F值...`。
         * 三个理由：
         *
         *  1. `core:model` 是纯 Kotlin 模块，没有 kotlinx-serialization 依赖，
         *     为这个加一层序列化器不划算；
         *  2. HTTP 头的名与值都不允许含控制字符（RFC 7230），
         *     `\u001F` 不可能出现在其中，做分隔符**语义上无歧义**；
         *  3. 人眼可读性不重要 —— 这里存的是头，不是给人看的。
         *
         * 用 `\u001F` 而不是 `|` 或 `,`：后两者都是合法的头值字符
         * （`Accept: text/html,application/json`、`X-Foo: a|b`），
         * 用它们当分隔符会在含逗号的头值上静默截断。
         */
        fun serializeHeaders(headers: List<CustomHeader>): String =
            headers
                .filter { it.first.isNotBlank() }
                .joinToString(SEPARATOR) { "${it.first.trim()}$SEPARATOR${it.second}" }

        /**
         * 反序列化。
         *
         * ## 奇偶不齐怎么办
         *
         * 存储里出现奇数段只有一种可能：写到一半被打断（进程被杀）。
         * 这时**丢掉最后那段孤儿**，前面完好的键值照常返回 ——
         * 报错会让整份配置不可读，代价远大于少一条头。
         */
        fun parseHeaders(raw: String): List<CustomHeader> {
            if (raw.isBlank()) return emptyList()
            val parts = raw.split(SEPARATOR)
            val result = mutableListOf<CustomHeader>()
            var i = 0
            while (i + 1 < parts.size) {
                val key = parts[i].trim()
                if (key.isNotEmpty()) result += key to parts[i + 1]
                i += 2
            }
            return result
        }
    }
}
