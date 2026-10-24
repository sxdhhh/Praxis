package dev.praxis.feature.settings.character

import dev.praxis.core.model.character.CharacterCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色卡界面的**派生状态**判定。
 *
 * ## 为什么只测派生属性，不测 ViewModel 的完整流程
 *
 * `CharacterViewModel` 需要真实的 `CharacterRepository`（Room + DataStore），
 * 那是 Android 依赖，纯 JVM 测试起不来。而它的业务流程已经在
 * `core:data` 的 `CharacterRepositoryTest` 里覆盖过（安装/保存/删除/更新）。
 *
 * 这里测的是**只有 UI 层才知道的规则**：
 * 「什么时候该显示删除按钮」「空列表时 activeCard 不能崩」。
 * 这些判错的表现是「按钮该出现时没有」或「点击后闪退」，
 * 而它们与数据库无关，是纯状态计算。
 */
class CharacterUiStateTest {

    private fun card(
        id: String,
        name: String = "角色",
        attached: List<String> = emptyList(),
    ) = CharacterCard(id = id, name = name, attachedTagIds = attached)

    // ── 列表状态 ─────────────────────────────────────────────

    @Test
    fun `activeCard 在列表为空时返回 null 而不是崩`() {
        // 首次启动、或数据库刚被清空时列表是空的。此时 `first { it.id == activeId }`
        // 会抛 NoSuchElementException —— 界面直接白屏。
        val state = CharactersUiState(cards = emptyList(), activeId = "x")
        assertNull(state.activeCard)
    }

    @Test
    fun `activeCard 命中活跃 id`() {
        val state = CharactersUiState(
            cards = listOf(card("a"), card("b")),
            activeId = "b",
        )
        assertEquals("b", state.activeCard?.id)
    }

    @Test
    fun `activeId 指向不存在的卡时 activeCard 为 null`() {
        // 用户删掉了当前活跃卡、但 DataStore 里的 id 还没纠正的瞬间。
        // 返回 null 让界面显示「未选择」，比显示一张错的卡安全。
        val state = CharactersUiState(cards = listOf(card("a")), activeId = "gone")
        assertNull(state.activeCard)
    }

    @Test
    fun `标签计数按卡各自统计`() {
        val state = CharactersUiState(
            cards = listOf(
                card("a", attached = listOf("t1", "t2")),
                card("b", attached = emptyList()),
            ),
            activeId = "a",
        )
        assertEquals(2, state.tagCountOf("a"))
        assertEquals(0, state.tagCountOf("b"))
    }

    @Test
    fun `不存在的卡标签计数为 0`() {
        val state = CharactersUiState(cards = listOf(card("a")), activeId = "a")
        assertEquals(0, state.tagCountOf("missing"))
    }

    // ── 编辑状态 ─────────────────────────────────────────────

    @Test
    fun `新建的卡不可删除`() {
        // 新建时 id 为空、还没落库，此时「删除」没有意义。
        val state = CharacterEditUiState(
            card = CharacterCard(id = "", name = "新角色"),
            isNew = true,
        )
        assertFalse(state.canDelete)
    }

    @Test
    fun `内置默认卡不可删除`() {
        // 删除默认卡会让「用户删光所有卡」后没有任何可用卡，
        // 而 activeCharacterCardId 仍指向它 → 提示词为空、界面看着选中。
        val state = CharacterEditUiState(
            card = CharacterCard(
                id = CharacterCard.DEFAULT_ID,
                name = "Praxis",
                isDefault = true,
            ),
            isNew = false,
        )
        assertFalse(state.canDelete)
    }

    @Test
    fun `用户自建卡可删除`() {
        val state = CharacterEditUiState(
            card = CharacterCard(id = "user.1", name = "我的角色"),
            isNew = false,
        )
        assertTrue(state.canDelete)
    }

    @Test
    fun `isDefault 被外部改成 false 时仍按 id 保护`() {
        // 这是防篡改：`isDefault` 是可序列化字段，导入的卡可能带着 false，
        // 但 id 是 DEFAULT_ID 就一定是内置卡 —— 必须按 id 判，不能按标志位。
        val state = CharacterEditUiState(
            card = CharacterCard(
                id = CharacterCard.DEFAULT_ID,
                name = "Praxis",
                isDefault = false,
            ),
            isNew = false,
        )
        assertFalse("按 id 判，不按 isDefault 标志位", state.canDelete)
    }

    @Test
    fun `编辑状态默认是 loading`() {
        // 默认 loading=true 而不是 false：界面不会先闪一帧空白表单
        // 再填内容（那会让用户以为卡是空的）。
        assertTrue(CharacterEditUiState().loading)
    }

    @Test
    fun `列表状态默认是 loading`() {
        assertTrue(CharactersUiState().loading)
    }
}