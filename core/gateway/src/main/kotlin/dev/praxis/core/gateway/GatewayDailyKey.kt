package dev.praxis.core.gateway

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日聚合的键（文档 §5.7）。
 *
 * ## 为什么必须显式传时区
 *
 * 网关 #329 的真实事故：容器没设 TZ，UTC+8 用户的「今天」实际是昨天 08:00 到今天 08:00 ——
 * 报表日界线整体错 8 小时。Praxis 里 `LocalDate` 默认用系统时区，但**聚合键必须显式**
 * 由这里生成，否则一旦哪天有人把某处改成 UTC，错误会静默出现。
 */
object GatewayDailyKey {

    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 某一时刻在 [zone] 下属于哪一天。 */
    fun of(epochMillis: Long, zone: ZoneId): String =
        FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** 往前推 [days] 天的那一天（含今天为 0）。 */
    fun daysBefore(epochMillis: Long, zone: ZoneId, days: Long): String =
        FORMAT.format(
            Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().minusDays(days),
        )
}
