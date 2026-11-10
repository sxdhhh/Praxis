package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型清单刷新的快照/缓存规则（文档 §5.4.1 第 17 条）。 */
class GatewayModelCatalogCacheTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `从没拉过一律不新鲜`() {
        assertFalse(GatewayModelCatalogCache.isFresh(null, now))
        assertFalse(GatewayModelCatalogCache.isFresh(0L, now))
    }

    @Test
    fun `刚拉完是新鲜的`() {
        assertTrue(GatewayModelCatalogCache.isFresh(now, now))
        assertTrue(GatewayModelCatalogCache.isFresh(now - 60_000L, now))
    }

    @Test
    fun `过了 TTL 就不新鲜`() {
        val ttl = GatewayModelCatalogCache.DEFAULT_TTL_MS
        assertFalse(GatewayModelCatalogCache.isFresh(now - ttl, now))
        assertTrue(GatewayModelCatalogCache.isFresh(now - ttl + 1000L, now))
    }

    @Test
    fun `时钟回拨时算新鲜而不是报错`() {
        // 用户把系统时间改早了：快照还在，别让页面变成「没拉过」。
        assertTrue(GatewayModelCatalogCache.isFresh(now + 86_400_000L, now))
    }

    @Test
    fun `时间文案`() {
        assertEquals("还没拉过", GatewayModelCatalogCache.ageText(null, now))
        assertEquals("刚刚", GatewayModelCatalogCache.ageText(now - 30_000L, now))
        assertEquals("5 分钟前", GatewayModelCatalogCache.ageText(now - 5 * 60_000L, now))
        assertEquals("3 小时前", GatewayModelCatalogCache.ageText(now - 3 * 3_600_000L, now))
    }

    @Test
    fun `失败保留旧清单`() {
        assertEquals(
            GatewayModelCatalogCache.WriteDecision.KeepOld,
            GatewayModelCatalogCache.decideWrite(success = false, fetched = listOf("a", "b")),
        )
    }

    @Test
    fun `成功但空清单也保留旧的`() {
        // 上游临时返回空数组时直接覆盖，会把用户手填的模型抹掉。
        assertEquals(
            GatewayModelCatalogCache.WriteDecision.KeepOld,
            GatewayModelCatalogCache.decideWrite(success = true, fetched = emptyList()),
        )
    }

    @Test
    fun `成功且非空才写`() {
        assertEquals(
            GatewayModelCatalogCache.WriteDecision.Write,
            GatewayModelCatalogCache.decideWrite(success = true, fetched = listOf("gpt-x")),
        )
    }
}
