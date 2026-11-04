package dev.praxis.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.praxis.core.model.settings.ModelSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 多模型配置的存储契约。
 *
 * ## 为什么值得用真 DataStore 测（2026-11-05）
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
 * 用真 DataStore + 临时文件测，一次性把所有前缀路径覆盖掉。
 *
 * ## 为什么用 PreferenceDataStoreFactory 而不是假 DataStore
 *
 * 假实现要自己实现 `DataStore<Preferences>` 的读改写，而 `edit {}` 的
 * **原子读改写语义**正是这里要验的（多次 set 之间不能互相覆盖）。
 * 自己写一个假的反而容易把 bug 一起实现进去。
 */
class ModelProfilesTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tmpDir: File = Files.createTempDirectory("praxis-profiles").toFile()

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(tmpDir, "test.preferences_pb") },
    )

    private val repo = SettingsRepository(dataStore)

    @After
    fun tearDown() {
        scope.cancel()
        tmpDir.deleteRecursively()
    }

    /** 第一次建配置：列表里出现它，且自动成为当前。 */
    @Test
    fun `first profile becomes active`() = runBlocking {
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
        val a = repo.createProfile("A", ModelSettings(baseUrl = "http://a"))
        val b = repo.createProfile("B", ModelSettings(baseUrl = "http://b"))

        // b 是后建的，但 a 先建所以 active 还是 a；显式切到 b。
        repo.setActiveProfile(b)

        repo.setBaseUrl("http://b-changed")
        repo.setModel("m1,m2")

        // 切回 a：它的值不该被 b 的编辑污染。
        repo.setActiveProfile(a)
        val sa = repo.modelSettings.first()
        assertEquals("改 B 不该影响 A 的地址", "http://a", sa.baseUrl)

        // 切到 b：改动在。
        repo.setActiveProfile(b)
        val sb = repo.modelSettings.first()
        assertEquals("http://b-changed", sb.baseUrl)
        assertEquals("m1,m2", sb.model)
    }

    /** modelSettings 跟着 activeId 走。 */
    @Test
    fun `modelSettings follows active profile`() = runBlocking {
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
        val id = repo.createProfile("临时名", ModelSettings(baseUrl = "http://keep-me"))

        repo.renameProfile(id, "正式名")

        val p = repo.modelProfiles.first().profiles.single()
        assertEquals("id 不该变", id, p.id)
        assertEquals("正式名", p.name)
        assertEquals("参数不该丢", "http://keep-me", p.settings.baseUrl)
    }

    /** 删掉当前配置时，自动切到剩下的第一个（否则 activeId 指向不存在的 id）。 */
    @Test
    fun `deleting active switches to another`() = runBlocking {
        val a = repo.createProfile("A")
        val b = repo.createProfile("B")
        repo.setActiveProfile(b)

        repo.deleteProfile(b)

        val set = repo.modelProfiles.first()
        assertEquals(1, set.profiles.size)
        assertEquals("应剩下 A", a, set.profiles[0].id)
        assertEquals("activeId 必须指向存在的配置", a, set.activeId)
        // 而且读出来的确实是 A（不是回落到空配置）。
        assertNotNull(set.active)
    }

    /** 最后一个配置不许删。 */
    @Test
    fun `last profile cannot be deleted`() = runBlocking {
        val only = repo.createProfile("唯一")

        repo.deleteProfile(only)

        assertEquals("删完就一个都没有了，界面会空掉", 1, repo.modelProfiles.first().profiles.size)
    }

    /** 配置顺序 = 建立顺序（新建的排最后）。 */
    @Test
    fun `profiles keep creation order`() = runBlocking {
        repo.createProfile("一")
        repo.createProfile("二")
        repo.createProfile("三")

        assertEquals(
            listOf("一", "二", "三"),
            repo.modelProfiles.first().profiles.map { it.name },
        )
    }

    /** 重名允许（用户自己的事），靠 id 区分。 */
    @Test
    fun `duplicate names are allowed and distinguished by id`() = runBlocking {
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
        // 模拟老版本写入（没有 profile 结构）。
        repo.setBaseUrl("http://legacy")
        repo.setModel("legacy-model")

        val set = repo.modelProfiles.first()
        assertTrue("还没建过 profile", set.profiles.isEmpty())

        val s = repo.modelSettings.first()
        assertEquals("老配置必须仍然读得到", "http://legacy", s.baseUrl)
        assertEquals("legacy-model", s.activeModel)
    }

    /** 删掉不存在的 id 不做任何事（不写脏值）。 */
    @Test
    fun `deleting unknown id is a no-op`() = runBlocking {
        val a = repo.createProfile("A")

        repo.deleteProfile("p-does-not-exist")

        val set = repo.modelProfiles.first()
        assertEquals(1, set.profiles.size)
        assertEquals(a, set.activeId)
    }

    /** 切到不存在的 id 不做任何事。 */
    @Test
    fun `switching to unknown id is a no-op`() = runBlocking {
        val a = repo.createProfile("A")

        repo.setActiveProfile("p-nope")

        assertEquals("不该把 activeId 写成脏值", a, repo.modelProfiles.first().activeId)
    }
}
