package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对外模型清单（文档 9.1 的 gatewayModels）。
 *
 * ## 为什么这条值得单测
 *
 * 这份清单直接决定用户在选择器里能点什么。少一个名字，用户会以为那家不能用；
 * 多一个重复项（只差大小写），用户以为有两套、实际是同一个。
 */
class GatewayModelCatalogTest {

    private val natives = listOf(
        NativeModel(provider = "custom-a", id = "m1"),
        NativeModel(provider = "custom-a", id = "m2"),
        NativeModel(provider = "custom-b", id = "m1"),
    )

    @Test
    fun 原生清单跨家去重() {
        val names = GatewayModelCatalog.visibleNames(natives, emptyList())

        assertEquals(listOf("m1", "m2"), names)
    }

    @Test
    fun 映射别名也进清单() {
        val mappings = listOf(ModelMapping(alias = "fast", target = "m1", provider = "custom-a"))

        val names = GatewayModelCatalog.visibleNames(natives, mappings)

        assertEquals(listOf("m1", "m2", "fast"), names)
    }

    @Test
    fun 未启用的映射不进清单() {
        val mappings = listOf(
            ModelMapping(alias = "off", target = "m1", provider = "custom-a", enabled = false),
        )

        val names = GatewayModelCatalog.visibleNames(natives, mappings)

        assertTrue(names.none { it == "off" })
    }

    @Test
    fun 只差大小写的名字只出现一次() {
        // 文档 5.4：匹配是大小写不敏感的，清单里也不该出现两个只差大小写的名字。
        val mappings = listOf(ModelMapping(alias = "M1", target = "m2", provider = "custom-a"))

        val names = GatewayModelCatalog.visibleNames(natives, mappings)

        assertEquals(listOf("m1", "m2"), names)
    }

    @Test
    fun 空名字被忽略() {
        val mappings = listOf(ModelMapping(alias = "   ", target = "m1", provider = "custom-a"))

        val names = GatewayModelCatalog.visibleNames(natives, mappings)

        assertEquals(listOf("m1", "m2"), names)
    }

    @Test
    fun 两边都空时给空清单() {
        assertTrue(GatewayModelCatalog.visibleNames(emptyList(), emptyList()).isEmpty())
    }
}
