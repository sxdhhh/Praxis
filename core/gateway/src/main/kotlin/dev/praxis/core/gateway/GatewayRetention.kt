package dev.praxis.core.gateway

/**
 * 请求记录的保留期（文档 §5.7 的数据保留）。
 *
 * ## 为什么必须有：它本来会无限长
 *
 * `gateway_request` 每发一次请求就多一行。没有清理的话，一个常用一年的人
 * 会有几十万行 —— 报表聚合会变慢，而用户的存储是有限的（手机比服务器更紧）。
 * 文档给的默认值是 **7 天**（服务器上更宽，手机上调小）。
 *
 * ## 为什么按「本地时区的那一天」算起点
 *
 * 与日聚合键同一个理由（网关 #329）：用 UTC 算会让东八区用户的「7 天」整体错 8 小时。
 * 这里复用 [GatewayDailyKey]，不另写一套日期运算。
 */
object GatewayRetention {

    const val DEFAULT_DAYS: Int = 7

    /** 允许的范围：1 天 ~ 1 年。0 不是「不保留」，是配置错误（见下）。 */
    const val MIN_DAYS: Int = 1
    const val MAX_DAYS: Int = 365

    /**
     * 保留期的切点：早于它的记录该删。
     *
     * @param days 保留天数；超出范围会被夹到边界 —— 与并发数同理，
     *   这不是用户逐位输入的精确值，而是 1~365 的选择。
     */
    fun cutoffMillis(nowMillis: Long, days: Int, zone: java.time.ZoneId): Long {
        val clamped = days.coerceIn(MIN_DAYS, MAX_DAYS)
        return java.time.Instant.ofEpochMilli(nowMillis)
            .atZone(zone)
            .toLocalDate()
            .minusDays(clamped.toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    /** 解析设置里的天数：坏值（0 / 负数 / 超大）夹到边界，解析不了用默认。 */
    fun parseDays(raw: Int?): Int {
        if (raw == null) return DEFAULT_DAYS
        return raw.coerceIn(MIN_DAYS, MAX_DAYS)
    }
}
