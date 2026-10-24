package dev.praxis.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加载顺序（拓扑排序）与依赖校验。
 *
 * ## 这些断言锁的是「加载顺序错了会怎样」
 *
 * 插件依赖顺序错误的表象是 `undefined is not a function` —— 与真正原因
 * 隔了三层。所以这里把「依赖在前后」「环被报出来」「链式失效被传递」
 * 全部固定住，让任何人改动排序逻辑时立刻发现问题。
 */
class PluginLoadOrderTest {

    private fun plugin(
        id: String,
        requires: List<String> = emptyList(),
        enabled: Boolean = true,
        version: String = "1.0.0",
        fatal: Boolean = false,
    ): InstalledPlugin = InstalledPlugin(
        manifest = PluginManifest(
            id = id,
            version = version,
            main = "index.js",
            displayName = LocalizedText.ofZh(id),
            requires = requires.map { PluginRequirement(id = it) },
        ),
        installPath = "/tmp/$id",
        enabled = enabled,
        problems = if (fatal) {
            listOf(ManifestProblem.Fatal("main", "缺失"))
        } else {
            emptyList()
        },
    )

    @Test
    fun `无依赖时按 id 字典序稳定输出`() {
        val ordering = PluginLoadOrder.resolve(
            listOf(plugin("c.pkg"), plugin("a.pkg"), plugin("b.pkg")),
        )
        assertEquals(listOf("a.pkg", "b.pkg", "c.pkg"), ordering.ordered.map { it.id })
        assertTrue(!ordering.hasProblems)
    }

    @Test
    fun `依赖排在被依赖者之后`() {
        val ordering = PluginLoadOrder.resolve(
            listOf(
                plugin("consumer", requires = listOf("base")),
                plugin("base"),
            ),
        )
        assertEquals(listOf("base", "consumer"), ordering.ordered.map { it.id })
    }

    @Test
    fun `多级依赖链`() {
        // c → b → a，期望 a, b, c
        val ordering = PluginLoadOrder.resolve(
            listOf(
                plugin("c", requires = listOf("b")),
                plugin("b", requires = listOf("a")),
                plugin("a"),
            ),
        )
        assertEquals(listOf("a", "b", "c"), ordering.ordered.map { it.id })
    }

    @Test
    fun `缺少依赖包被跳过并给出原因`() {
        val ordering = PluginLoadOrder.resolve(
            listOf(plugin("alone", requires = listOf("ghost"))),
        )
        assertTrue(ordering.ordered.isEmpty())
        assertNotNull(ordering.skipped["alone"])
        assertTrue(ordering.skipped.getValue("alone").contains("ghost"))
    }

    @Test
    fun `依赖的包被禁用时也跳过`() {
        val ordering = PluginLoadOrder.resolve(
            listOf(
                plugin("base", enabled = false),
                plugin("consumer", requires = listOf("base")),
            ),
        )
        assertTrue(ordering.ordered.isEmpty())
        assertTrue(ordering.skipped.getValue("consumer").contains("禁用"))
    }

    @Test
    fun `链式失效会传递（A 依赖 B，B 依赖坏掉的 C）`() {
        // 这是「一遍过」算法会漏掉的场景：A 看到 B 是好的，
        // 但 B 随后因 C 被跳过，A 就变成依赖了加载不了的包。
        val ordering = PluginLoadOrder.resolve(
            listOf(
                plugin("a", requires = listOf("b")),
                plugin("b", requires = listOf("c")),
                plugin("c", fatal = true),
            ),
        )
        assertTrue("三个都不该被加载：${ordering.ordered.map { it.id }}", ordering.ordered.isEmpty())
        assertNotNull(ordering.skipped["c"])
        assertNotNull("B 必须因 C 坏掉而被跳过", ordering.skipped["b"])
        assertNotNull("A 必须因 B 被跳过而被跳过", ordering.skipped["a"])
    }

    @Test
    fun `循环依赖被检测并报出参与的包`() {
        val ordering = PluginLoadOrder.resolve(
            listOf(
                plugin("x", requires = listOf("y")),
                plugin("y", requires = listOf("x")),
            ),
        )
        assertTrue(ordering.ordered.isEmpty())
        assertEquals(1, ordering.cycles.size)
        val cycle = ordering.cycles.first()
        assertTrue("环里要有 x 与 y：$cycle", cycle.contains("x") && cycle.contains("y"))
        // 首尾相同，便于 UI 直接展示成 x → y → x
        assertEquals("环必须闭合", cycle.first(), cycle.last())
    }

    @Test
    fun `版本不满足时跳过`() {
        val ordering = PluginLoadOrder.resolve(
            listOf(
                InstalledPlugin(
                    manifest = PluginManifest(id = "base", version = "1.0.0", main = "i.js"),
                    installPath = "/tmp/base",
                    enabled = true,
                ),
                InstalledPlugin(
                    manifest = PluginManifest(
                        id = "consumer",
                        main = "i.js",
                        requires = listOf(PluginRequirement("base", minVersion = "2.0.0")),
                    ),
                    installPath = "/tmp/consumer",
                    enabled = true,
                ),
            ),
        )
        assertTrue(ordering.ordered.isEmpty())
        val reason = ordering.skipped.getValue("consumer")
        assertTrue("原因要说清版本：$reason", reason.contains("2.0.0"))
    }

    @Test
    fun `清单有致命问题的包不加载但不影响其它包`() {
        val ordering = PluginLoadOrder.resolve(
            listOf(
                plugin("broken", fatal = true),
                plugin("fine"),
            ),
        )
        assertEquals(listOf("fine"), ordering.ordered.map { it.id })
        assertNotNull(ordering.skipped["broken"])
    }

    @Test
    fun `未启用的包不加载但不算错误`() {
        val ordering = PluginLoadOrder.resolve(listOf(plugin("off", enabled = false)))
        assertTrue(ordering.ordered.isEmpty())
        assertTrue("未启用不是错误，不该进 skipped", ordering.skipped.isEmpty())
    }
}

/**
 * 市场 URL 与来源标记编解码。
 *
 * 路径拼错就是 404，而 404 在 UI 上表现为「市场没有内容」——
 * 用户会以为市场空了。所以路径必须被测试钉住。
 */
class MarketEndpointsTest {

    @Test
    fun `全量列表路径`() {
        assertEquals(
            "https://static.operit.app/market/v2/lists/all/updated/page-1.json",
            MarketEndpoints.allListUrl(MarketSort.Updated, 1),
        )
        assertEquals(
            "https://static.operit.app/market/v2/lists/all/downloads/page-3.json",
            MarketEndpoints.allListUrl(MarketSort.Downloads, 3),
        )
    }

    @Test
    fun `页码小于 1 时钳到 1`() {
        assertTrue(MarketEndpoints.allListUrl(MarketSort.Updated, 0).endsWith("page-1.json"))
        assertTrue(MarketEndpoints.allListUrl(MarketSort.Updated, -5).endsWith("page-1.json"))
    }

    @Test
    fun `按类型列表路径且类型转小写`() {
        assertEquals(
            "https://static.operit.app/market/v2/lists/type/toolpkg/updated/page-2.json",
            MarketEndpoints.typeListUrl("ToolPkg", MarketSort.Updated, 2),
        )
    }

    @Test
    fun `详情与下载走动态域名`() {
        assertEquals(
            "https://api.operit.app/market/v2/entries/abc",
            MarketEndpoints.entryUrl("abc"),
        )
        assertEquals(
            "https://api.operit.app/market/v2/assets/a1/download",
            MarketEndpoints.assetDownloadUrl("a1"),
        )
    }

    @Test
    fun `路径段里的斜杠被编码`() {
        assertEquals("a%2Fb", MarketEndpoints.encodeSegment("a/b"))
    }
}

class MarketOriginCodecTest {

    private val origin = MarketOriginCodec.Origin(
        market = MarketOriginCodec.MARKET_NAME,
        toolpkgId = "com.sxd91.agent_team",
        version = "1.0.0",
        authors = listOf("sxd91"),
    )

    @Test
    fun `编码后可原样解回`() {
        val encoded = MarketOriginCodec.encode(origin)
        assertTrue(encoded.startsWith("xor-v1:"))
        val decoded = MarketOriginCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(origin.toolpkgId, decoded!!.toolpkgId)
        assertEquals(origin.version, decoded.version)
        assertEquals(origin.authors, decoded.authors)
        assertEquals(MarketOriginCodec.MARKET_NAME, decoded.market)
    }

    @Test
    fun `没有前缀时返回 null 而不是抛`() {
        assertNull(MarketOriginCodec.decode("garbage"))
        assertNull(MarketOriginCodec.decode(""))
    }

    @Test
    fun `字节越界时返回 null`() {
        assertNull(MarketOriginCodec.decode("xor-v1:999,1,2"))
        assertNull(MarketOriginCodec.decode("xor-v1:abc"))
    }

    @Test
    fun `市场名不是 Operit 时校验不通过`() {
        val other = origin.copy(market = "OtherMarket")
        assertNull(MarketOriginCodec.validate(other, origin.toolpkgId))
    }

    @Test
    fun `包 id 不一致时校验不通过（防止标记被复制到别的包）`() {
        assertNotNull(MarketOriginCodec.validate(origin, origin.toolpkgId))
        assertNull(MarketOriginCodec.validate(origin, "another.pkg"))
    }

    @Test
    fun `版本为空时校验不通过`() {
        assertNull(MarketOriginCodec.validate(origin.copy(version = "  "), origin.toolpkgId))
    }
}