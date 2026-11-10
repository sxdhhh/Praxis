package dev.praxis.core.gateway

/**
 * 模型清单刷新的缓存与「失败保留旧清单」规则（文档 §5.4.1 第 17 条）。
 *
 * ## 文档要求的三件事
 *
 * > 按家拉 `/models` + **快照** + **缓存** + 失败诊断，**刷新失败保留旧清单**
 *
 * 这条分开看是三件：
 *
 * | 件 | 落在哪 |
 * |---|---|
 * | 快照 | `gateway_provider.modelsJson` —— 它就是「上次成功拉到的那份」 |
 * | 缓存 | 本文件的 [isFresh]：多久之内不必再拉 |
 * | 失败保留旧清单 | 写入只发生在**成功**之后（见 [decideWrite]） |
 *
 * ## 为什么缓存要有
 *
 * 拉清单是一次真实网络请求（有些家中转站还限速）。用户在设置页来回点几下
 * 不该打那么多次上游；而「上次是什么时候拉的」本身也是他要看的信息。
 */
object GatewayModelCatalogCache {

    /** 默认 6 小时：够久到不用来回拉，够短到上游下架模型能及时发现。 */
    const val DEFAULT_TTL_MS: Long = 6L * 60L * 60L * 1000L

    /**
     * 这份快照还算不算新鲜。
     *
     * @param fetchedAtMillis 上次**成功**拉取的时间；null/0 = 从没成功过（一律算不新鲜）。
     * @param nowMillis 当前时间（可注入，便于单测）。
     */
    fun isFresh(
        fetchedAtMillis: Long?,
        nowMillis: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Boolean {
        if (fetchedAtMillis == null || fetchedAtMillis <= 0L) return false
        val age = nowMillis - fetchedAtMillis
        // 时钟回拨（用户改了系统时间）时 age 会是负数：那时算「新鲜」而**不是**
        // 报错 —— 快照还在，让用户能继续用。
        if (age < 0L) return true
        return age < ttlMs
    }

    /** 人话的「多久之前」。UI 直接显示这个。 */
    fun ageText(fetchedAtMillis: Long?, nowMillis: Long): String {
        if (fetchedAtMillis == null || fetchedAtMillis <= 0L) return "还没拉过"
        val age = nowMillis - fetchedAtMillis
        if (age < 0L) return "刚刚"
        val minutes = age / 60_000L
        return when {
            minutes < 1L -> "刚刚"
            minutes < 60L -> minutes.toString() + " 分钟前"
            else -> (minutes / 60L).toString() + " 小时前"
        }
    }

    /** 一次刷新的结果该怎么处置（纯函数，便于单测）。 */
    enum class WriteDecision {
        /** 成功：写入新快照并更新时间。 */
        Write,

        /** 失败：**不动**旧快照（文档明确要求）。 */
        KeepOld,
    }

    /**
     * 这次拉取要不要写进库。
     *
     * **失败一律 KeepOld**：拉失败时把清单清空会让用户失去「上次能用的是什么」，
     * 而那正是他排查时唯一能对照的东西。
     */
    fun decideWrite(
        success: Boolean,
        /** 拉到的清单（成功时才看）。 */
        fetched: List<String> = emptyList(),
    ): WriteDecision = when {
        // when 作**表达式**用时分支末尾不能带逗号（我第 4 次踩这个坑）。
        !success -> WriteDecision.KeepOld
        // 成功但上游返回空清单：**也保留旧的**。
        // 空清单多半是「这家临时返回了空数组」，直接覆盖会把用户手填的模型抹掉。
        fetched.isEmpty() -> WriteDecision.KeepOld
        else -> WriteDecision.Write
    }
}
