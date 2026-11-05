package dev.praxis.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.praxis.core.model.settings.ModelSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多模型配置的存储契约。
 *
 * ## 为什么值得测（2026-11-05）
 *
 * 「选择模型配置」这一项最危险的地方不是 UI，而是**带前缀的 key**：
 *
 * ```
 *   p:<id>:model_base_url
 *   p:<id>:model_api_key
 * ```
 *
 * 一旦某个 setter 漏了前缀，就会写回旧的扁平 key —— 表现为
 * 「设置页改了不生效 / 切了配置还是老地址」，而**不会有任何编译错误**。
 *
 * 真机上发现这种 bug 的代价很高（要真配两家服务商、来回切、看请求打到哪）。
 *
 * ## 为什么用内存 DataStore 而不是 PreferenceDataStoreFactory（2026-11-05）
 *
 * 我第一版用的是真 DataStore（`PreferenceDataStoreFactory.create` + 临时文件），
 * 理由写在提交信息里：「假实现要自己写 `edit {}` 的原子读改写语义，
 * 容易把 bug 一起实现进去」。
 *
 * **那个理由在这个测试里是错的，而代价很实在**：CI 的单测步骤挂住十几分钟
 * 不结束（Gradle 默认不给测试任务设超时）。真 DataStore 要起自己的协程作用域、
 * 做文件锁、走跨线程握手 —— 这些都是**它自己**的复杂度，
 * 与本测试要验的东西（「写哪个 key」）毫无关系。
 *
 * 换掉之后要验的性质一条不少：本文件断言的全是**键名与取值**，
 * 而内存实现的 `edit` 语义（读-改-写同一个引用）恰好就是最朴素的那种，
 * 没有任何可被「一起实现进去」的空间。
 *
 * 记住这条：**测试替身的复杂度要跟着「被验的性质」走**，
 * 不是越真越好 —— 用真组件挂住了 CI，比用一个简单的假实现更糟。
 */
class ModelProfilesTest {

    /**
     * 最小内存 DataStore。
     *
     * `edit {}` 是 `DataStore` 的扩展函数，内部调 `updateData`，
     * 所以只要实现 `data` 与 `updateData` 就够了。
     */
    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    private fun newRepo(): SettingsRepository = SettingsRepository(MemoryStore())

    /** 第一次建配置：列表里出现它，且自动成为当前。 */
    @Test
    fun `first profile becomes active`() = runBlocking {
        val repo = newRepo()
        val id = repo.createProfile("workbuddy")

        val set = repo.modelProfiles.first()
        assertEquals(1, set.profiles.size)
        assertEquals("workbuddy", set.profiles[0].name)
        assertEquals(id, set.profiles[0].id)
        assertEquals("第一个配置应自动生效", id, set.activeId)
        assertEquals(id, set.active?.id)
    }

    /** 新建时传入的参数被写进带前缀的 key。 */
    @Test
    fun `created profile keeps its settings`() = runBlocking {
        val repo = newRepo()
        repo.createProfile(
            name = "server",
            settings = ModelSettings(
                baseUrl = "http://192.168.1.80:7500",
                model = "glm-5.2,glm-5.3",
                modelIndex = 1,
                temperature = 0.3,
            ),
        )

        val s = repo.modelProfiles.first().active!!.settings
        assertEquals("http://192.168.1.80:7500", s.baseUrl)
        assertEquals("glm-5.2,glm-5.3", s.model)
        assertEquals(1, s.modelIndex)
        assertEquals(0.3, s.temperature, 0.0001)
    }

    /**
     * **这是最容易出的 bug**：改参数必须写到当前 profile。
     *
     * 漏了前缀就会写回旧扁平 key —— 表现为「改完切走再回来改动消失」。
     */
    @Test
    fun `editing writes to the active profile only`() = runBlocking {
        val repo = newRepo()
        val a = repo.createProfile("A", ModelSettings(baseUrl = "http://a"))
        val b = repo.createProfile("B", ModelSettings(baseUrl = "http://b"))

        repo.setActiveProfile(b)
        repo.setBaseUrl("http://b-changed")
        repo.setModel("m1,m2")

        // 切回 a：它的值不该被 b 的编辑污染。
        repo.setActiveProfile(a)
        assertEquals("改 B 不该影响 A 的地址", "http://a", repo.modelSettings.first().baseUrl)

        // 切到 b：改动在。
        repo.setActiveProfile(b)
        assertEquals("http://b-changed", repo.modelSettings.first().baseUrl)
        assertEquals("m1,m2", repo.modelSettings.first().model)
    }

    /** 所有模型参数的 setter 都必须带前缀（漏一个就切配置串味）。 */
    @Test
    fun `every model setter is profile scoped`() = runBlocking {
        val repo = newRepo()
        val a = repo.createProfile("A")
        val b = repo.createProfile("B")
        repo.setActiveProfile(a)

        // 逐个改一遍。
        repo.setBaseUrl("http://a-url")
        repo.setModel("a-model")
        repo.setModelIndex(2)
        repo.setTemperature(0.11)
        repo.setContextLimit(1234)
        repo.setCompactThreshold(0.5f)
        repo.setModelProvider(dev.praxis.core.model.settings.ApiProvider.OpenAI)

        // 切到 B：这些改动一个都不该跟过来。
        repo.setActiveProfile(b)
        val sb = repo.modelSettings.first()
        assertNotEquals("地址不该串味", "http://a-url", sb.baseUrl)
        assertNotEquals("模型池不该串味", "a-model", sb.model)
        assertNotEquals("上下文上限不该串味", 1234, sb.contextLimit)

        // 切回 A：改动都还在。
        repo.setActiveProfile(a)
        val sa = repo.modelSettings.first()
        assertEquals("http://a-url", sa.baseUrl)
        assertEquals("a-model", sa.model)
        assertEquals(2, sa.modelIndex)
        assertEquals(0.11, sa.temperature, 0.0001)
        assertEquals(1234, sa.contextLimit)
        assertEquals(0.5f, sa.compactThreshold, 0.0001f)
    }

    /** modelSettings 跟着 activeId 走。 */
    @Test
    fun `modelSettings follows active profile`() = runBlocking {
        val repo = newRepo()
        val a = repo.createProfile("A", ModelSettings(baseUrl = "http://a", model = "ma"))
        val b = repo.createProfile("B", ModelSettings(baseUrl = "http://b", model = "mb"))

        repo.setActiveProfile(a)
        assertEquals("http://a", repo.modelSettings.first().baseUrl)
        assertEquals("ma", repo.modelSettings.first().activeModel)

        repo.setActiveProfile(b)
        assertEquals("http://b", repo.modelSettings.first().baseUrl)
        assertEquals("mb", repo.modelSettings.first().activeModel)
    }

    /** 重命名只改显示名，id 与参数不动。 */
    @Test
    fun `rename keeps id and settings`() = runBlocking {
        val repo = newRepo()
        val id = repo.createProfile("临时名", ModelSettings(baseUrl = "http://keep-me"))

        repo.renameProfile(id, "正式名")

        val p = repo.modelProfiles.first().profiles.single()
        assertEquals("id 不该变", id, p.id)
        assertEquals("正式名", p.name)
        assertEquals("参数不该丢", "http://keep-me", p.settings.baseUrl)
    }

    /** 删掉当前配置时，自动切到剩下的第一个。 */
    @Test
    fun `deleting active switches to another`() = runBlocking {
        val repo = newRepo()
        val a = repo.createProfile("A")
        val b = repo.createProfile("B")
        repo.setActiveProfile(b)

        repo.deleteProfile(b)

        val set = repo.modelProfiles.first()
        assertEquals(1, set.profiles.size)
        assertEquals("应剩下 A", a, set.profiles[0].id)
        assertEquals("activeId 必须指向存在的配置", a, set.activeId)
        assertNotNull(set.active)
    }

    /** 最后一个配置不许删。 */
    @Test
    fun `last profile cannot be deleted`() = runBlocking {
        val repo = newRepo()
        val only = repo.createProfile("唯一")

        repo.deleteProfile(only)

        assertEquals("删完就一个都没有了", 1, repo.modelProfiles.first().profiles.size)
    }

    /** 配置顺序 = 建立顺序（新建的排最后）。 */
    @Test
    fun `profiles keep creation order`() = runBlocking {
        val repo = newRepo()
        repo.createProfile("一")
        repo.createProfile("二")
        repo.createProfile("三")

        assertEquals(
            listOf("一", "二", "三"),
            repo.modelProfiles.first().profiles.map { it.name },
        )
    }

    /**
     * **连点「新建」不会撞 id**（2026-11-05 修的真 bug）。
     *
     * ## 这条测试抓到了什么
     *
     * 原实现是 `val id = "p" + System.currentTimeMillis()`，
     * 在 edit 块**外面**算好。
     *
     * 于是同一毫秒内建两个配置会得到**同一个 id** ——
     * 用户连点两下「新建」就是这种情形（真机上一毫秒足够处理两次点击）。
     *
     * 撞了之后的后果不是「多一条重复」那么简单：
     *
     * ```
     *   改一个配置的地址  -> 两个都改（key 是 p<id>:model_base_url）
     *   删一个配置        -> 两个一起消失（parseIds 里同一个 id 出现两次）
     *   下拉列表          -> 出现两条同名同 id 的项，选中态跟着一起跳
     * ```
     *
     * 这是测试**逼出来**的：我写这条用例时只想着「名字重复要能区分」，
     * 结果 5 条用例一起红了 —— 它们都建了 ≥2 个配置。
     */
    @Test
    fun `rapid creation yields distinct ids`() = runBlocking {
        val repo = newRepo()
        // 同一毫秒内连建 5 个（循环体里没有任何挂起点，
        // 实际上就是同一次事件循环里跑完）。
        val ids = (1..5).map { repo.createProfile("并发" + it) }

        assertEquals("五个 id 必须互不相同", 5, ids.toSet().size)
        val set = repo.modelProfiles.first()
        assertEquals("列表里应有五条", 5, set.profiles.size)
        assertEquals("id 也不该重复", 5, set.profiles.map { it.id }.toSet().size)
    }

    /** 重名允许（用户自己的事），靠 id 区分。 */
    @Test
    fun `duplicate names are allowed and distinguished by id`() = runBlocking {
        val repo = newRepo()
        val a = repo.createProfile("同名")
        val b = repo.createProfile("同名")

        assertNotEquals("id 必须不同", a, b)
        assertEquals(2, repo.modelProfiles.first().profiles.size)
    }

    /**
     * 向后兼容：没有任何 profile 时，读的是旧扁平 key。
     *
     * 这条守住「老用户升级后看到自己原来的配置，而不是一张白纸」。
     */
    @Test
    fun `legacy flat keys still readable when no profile exists`() = runBlocking {
        val repo = newRepo()
        // 模拟老版本写入（那时还没有 profile 结构）。
        repo.setBaseUrl("http://legacy")
        repo.setModel("legacy-model")

        val set = repo.modelProfiles.first()
        assertTrue("还没建过 profile", set.profiles.isEmpty())

        val s = repo.modelSettings.first()
        assertEquals("老配置必须仍然读得到", "http://legacy", s.baseUrl)
        assertEquals("legacy-model", s.activeModel)
    }

    /** 建了 profile 之后，旧值不再影响读数（新结构优先）。 */
    @Test
    fun `profile takes over once created`() = runBlocking {
        val repo = newRepo()
        repo.setBaseUrl("http://legacy")

        repo.createProfile("新的", ModelSettings(baseUrl = "http://new"))

        // 老 key 里还是 legacy，但现在生效的是 profile。
        assertEquals("http://new", repo.modelSettings.first().baseUrl)
    }

    /** 删掉不存在的 id 不做任何事。 */
    @Test
    fun `deleting unknown id is a no-op`() = runBlocking {
        val repo = newRepo()
        val a = repo.createProfile("A")

        repo.deleteProfile("p-does-not-exist")

        val set = repo.modelProfiles.first()
        assertEquals(1, set.profiles.size)
        assertEquals(a, set.activeId)
    }

    /** 切到不存在的 id 不做任何事（不写脏值）。 */
    @Test
    fun `switching to unknown id is a no-op`() = runBlocking {
        val repo = newRepo()
        val a = repo.createProfile("A")

        repo.setActiveProfile("p-nope")

        assertEquals("不该把 activeId 写成脏值", a, repo.modelProfiles.first().activeId)
    }
}
