package dev.praxis.feature.settings.character

import dev.praxis.core.data.character.CharacterRepository
import dev.praxis.core.model.character.CharacterCard
import dev.praxis.core.model.character.CharacterPromptComposer
import dev.praxis.core.model.character.PromptFunctionType
import dev.praxis.core.model.character.PromptTag
import dev.praxis.core.model.character.PromptTagType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 角色卡列表页的状态。
 *
 * `busy` / `message` 只用于「删除」「导入」这类**会改变库内容**的操作：
 * 纯文本编辑走编辑页自己的本地状态，不在这里做逐字刷新 ——
 * 每敲一个字就写一次数据库会让输入框在低端机上卡顿。
 */
data class CharactersUiState(
    val cards: List<CharacterCard> = emptyList(),
    val activeId: String = CharacterCard.DEFAULT_ID,
    val tags: List<PromptTag> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    /** 一次性提示（错误或成功）。消费后置空。 */
    val message: String? = null,
) {
    /** 活跃卡。列表为空时返回 null（UI 不该在这时崩）。 */
    val activeCard: CharacterCard? get() = cards.firstOrNull { it.id == activeId }

    /** 某张卡附着的标签数，用于列表副标题。 */
    fun tagCountOf(cardId: String): Int =
        cards.firstOrNull { it.id == cardId }?.attachedTagIds?.size ?: 0
}

/** 角色卡编辑页的状态。 */
data class CharacterEditUiState(
    val card: CharacterCard = CharacterCard(id = "", name = ""),
    val tags: List<PromptTag> = emptyList(),
    val allTags: List<PromptTag> = emptyList(),
    /** 提示词预览（跟随当前编辑内容实时生成）。 */
    val promptPreview: String = "",
    val isNew: Boolean = true,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
) {
    /** 该卡是否可删除（默认卡不可删）。 */
    val canDelete: Boolean get() = !isNew && !card.isBuiltinDefault
}

/**
 * 角色卡列表 / 编辑 / 标签的 ViewModel。
 *
 * ## 为什么用一个 ViewModel 管三页
 *
 * 三个页面共享同一份数据（角色卡、标签、活跃 id）。拆成三个 ViewModel
 * 就要么各自订阅、各自持有一份状态（切换页面时出现「刚改的还没刷新」），
 * 要么引入一个共享的 StateHolder —— 那还不如直接一个。
 *
 * 用**构造注入 [scope]** 而不是 `viewModelScope`：
 * 这样纯 JVM 单测能传一个 `TestScope` 来验证状态流转，不需要 Robolectric。
 * 生产代码在 Route 里传 `rememberCoroutineScope()`。
 */
class CharacterViewModel(
    private val repository: CharacterRepository,
    private val scope: CoroutineScope,
) {

    private val _listState = MutableStateFlow(CharactersUiState())
    val listState: StateFlow<CharactersUiState> = _listState.asStateFlow()

    private val _editState = MutableStateFlow(CharacterEditUiState())
    val editState: StateFlow<CharacterEditUiState> = _editState.asStateFlow()

    private var started = false
    private var editingCardId: String? = null

    /**
     * 开始观察。
     *
     * 幂等：Compose 重组时 Route 会重复调用，不幂等就会累积多个订阅，
     * 表现为「一次操作触发 N 次写库」——这类问题极难从现象反推原因。
     */
    fun start() {
        if (started) return
        started = true

        combine(
            repository.cards,
            repository.activeCardId,
            repository.tags,
        ) { cards, activeId, tags ->
            CharactersUiState(cards = cards, activeId = activeId, tags = tags, loading = false)
        }
            // catch 必须写：迁移失败 / Room 抛异常时 Flow 会终止，
            // 不 catch 则列表永远停在 loading，用户以为页面坏了。
            .catch { error ->
                _listState.update {
                    it.copy(
                        loading = false,
                        message = "读取角色卡失败：${error.message ?: "未知错误"}",
                    )
                }
            }
            .onEach { next -> _listState.value = next }
            .launchIn(scope)

        scope.launch {
            runCatching { repository.ensureDefaultCard() }
                .onFailure { error ->
                    _listState.update {
                        it.copy(message = "初始化默认角色卡失败：${error.message ?: "未知错误"}")
                    }
                }
        }
    }

    fun consumeMessage() {
        _listState.update { it.copy(message = null) }
        _editState.update { it.copy(message = null) }
    }

    // ── 列表页操作 ────────────────────────────────────────────

    fun setActive(cardId: String) {
        scope.launch {
            val ok = runCatching { repository.setActive(cardId) }.getOrDefault(false)
            if (!ok) _listState.update { it.copy(message = "切换失败：角色卡不存在") }
        }
    }

    fun delete(cardId: String) {
        scope.launch {
            _listState.update { it.copy(busy = true) }
            val deleted = runCatching { repository.delete(cardId) }.getOrElse { false }
            _listState.update {
                it.copy(
                    busy = false,
                    message = if (deleted) null else "这是内置默认角色卡，不能删除",
                )
            }
        }
    }

    /** 新建一张卡并进入编辑页（回调返回新 id）。 */
    fun createAndEdit(onCreated: (String) -> Unit) {
        scope.launch {
            runCatching { repository.create(name = "新角色") }
                .onSuccess { id -> onCreated(id) }
                .onFailure { error ->
                    _listState.update { it.copy(message = "新建失败：${error.message}") }
                }
        }
    }

    fun importTavernJson(rawJson: String, onImported: (String) -> Unit) {
        scope.launch {
            _listState.update { it.copy(busy = true) }
            repository.importFromTavernJson(rawJson)
                .onSuccess { id ->
                    _listState.update { it.copy(busy = false) }
                    onImported(id)
                }
                .onFailure { error ->
                    _listState.update {
                        it.copy(
                            busy = false,
                            message = "导入失败：${error.message ?: "JSON 格式不正确"}",
                        )
                    }
                }
        }
    }

    fun exportCard(cardId: String, onJson: (String) -> Unit) {
        scope.launch {
            repository.exportCardAsTavernJson(cardId)
                .onSuccess(onJson)
                .onFailure { error ->
                    _listState.update { it.copy(message = "导出失败：${error.message}") }
                }
        }
    }

    // ── 编辑页 ────────────────────────────────────────────────

    /**
     * 载入一张卡到编辑状态。
     *
     * @param cardId null 表示新建（空卡，保存时才落库）
     */
    fun loadForEdit(cardId: String?) {
        editingCardId = cardId
        scope.launch {
            _editState.update { it.copy(loading = true) }
            val allTags = runCatching { repository.tags.first() }.getOrDefault(emptyList())

            if (cardId == null) {
                _editState.update {
                    it.copy(
                        card = CharacterCard(id = "", name = ""),
                        allTags = allTags,
                        tags = emptyList(),
                        promptPreview = "",
                        isNew = true,
                        loading = false,
                        message = null,
                    )
                }
                return@launch
            }

            val cards = runCatching { repository.cards.first() }.getOrDefault(emptyList())
            val card = cards.firstOrNull { it.id == cardId }
            if (card == null) {
                _editState.update {
                    it.copy(loading = false, message = "角色卡不存在（可能已被删除）")
                }
                return@launch
            }
            val attached = card.attachedTagIds.mapNotNull { id -> allTags.firstOrNull { it.id == id } }
            _editState.update {
                it.copy(
                    card = card,
                    allTags = allTags,
                    tags = attached,
                    promptPreview = previewOf(card, attached),
                    isNew = false,
                    loading = false,
                    message = null,
                )
            }
        }
    }

    /** 编辑单个字段。只改内存状态，保存时才写库。 */
    fun updateCard(transform: (CharacterCard) -> CharacterCard) {
        _editState.update { current ->
            val next = transform(current.card)
            current.copy(card = next, promptPreview = previewOf(next, current.tags))
        }
    }

    /** 切换某个标签的附着状态。 */
    fun toggleTag(tag: PromptTag) {
        _editState.update { current ->
            val attached = current.card.attachedTagIds
            val isAttached = tag.id in attached
            val nextTags = if (isAttached) {
                current.tags.filterNot { it.id == tag.id }
            } else {
                current.tags + tag
            }
            val nextCard = current.card.copy(
                attachedTagIds = if (isAttached) {
                    attached.filterNot { it == tag.id }
                } else {
                    (attached + tag.id).distinct()
                },
            )
            current.copy(
                card = nextCard,
                tags = nextTags,
                promptPreview = previewOf(nextCard, nextTags),
            )
        }
    }

    fun save(onSaved: () -> Unit) {
        val current = _editState.value
        val card = current.card
        if (card.name.isBlank()) {
            _editState.update { it.copy(message = "名字不能为空") }
            return
        }
        scope.launch {
            _editState.update { it.copy(saving = true) }
            runCatching {
                if (current.isNew) {
                    // 新建：先 create 拿到真实 id，再用完整字段覆盖。
                    // 不能直接 save(card)，因为 card.id 为空会触发 require。
                    val newId = repository.create(
                        name = card.name,
                        description = card.description,
                        characterSetting = card.characterSetting,
                    )
                    repository.save(card.copy(id = newId))
                    newId
                } else {
                    repository.save(card)
                    card.id
                }
            }
                .onSuccess { id ->
                    editingCardId = id
                    _editState.update { it.copy(saving = false, isNew = false) }
                    onSaved()
                }
                .onFailure { error ->
                    _editState.update {
                        it.copy(saving = false, message = "保存失败：${error.message ?: "未知错误"}")
                    }
                }
        }
    }

    fun deleteEditing(onDeleted: () -> Unit) {
        val current = _editState.value
        if (!current.canDelete) {
            _editState.update { it.copy(message = "内置默认角色卡不能删除") }
            return
        }
        scope.launch {
            val deleted = runCatching { repository.delete(current.card.id) }.getOrElse { false }
            if (deleted) {
                onDeleted()
            } else {
                _editState.update { it.copy(message = "删除失败") }
            }
        }
    }

    fun exportEditing(onJson: (String) -> Unit) {
        val id = editingCardId ?: return
        exportCard(id, onJson)
    }

    /**
     * 生成提示词预览。
     *
     * ## 为什么是同步函数
     *
     * [CharacterPromptComposer.compose] 本身是纯同步的字符串拼接。
     * 把它包成 suspend + `withContext(Dispatchers.Default)` 有两个问题：
     *
     * 1. `updateCard` / `toggleTag` 是在 `MutableStateFlow.update { }` 的
     *    **非挂起** lambda 里调用的，那里没法 await 一个 suspend 函数 ——
     *    会直接编译失败；
     * 2. 即便绕过去（比如 `scope.launch`），预览会滞后于输入一帧，
     *    用户看到的是「上一个字」的内容。
     *
     * 几千字字符串拼接在现代设备上是微秒级，不需要切线程。
     * 真正重的是数据库写入，那部分本来就在 suspend 上下文里。
     */
    private fun previewOf(card: CharacterCard, tags: List<PromptTag>): String =
        CharacterPromptComposer.compose(
            card = card,
            tagLookup = { id -> tags.firstOrNull { it.id == id } },
            functionType = PromptFunctionType.Chat,
        )

    // ── 标签页 ────────────────────────────────────────────────

    fun createTag(name: String, content: String, type: PromptTagType) {
        if (content.isBlank()) {
            _listState.update { it.copy(message = "标签内容不能为空") }
            return
        }
        scope.launch {
            runCatching { repository.createOrReuseTag(name, content, tagType = type) }
                .onFailure { error ->
                    _listState.update { it.copy(message = "创建标签失败：${error.message}") }
                }
        }
    }

    fun updateTag(tag: PromptTag) {
        scope.launch {
            runCatching { repository.saveTag(tag) }
                .onFailure { error ->
                    _listState.update { it.copy(message = "保存标签失败：${error.message}") }
                }
        }
    }

    fun deleteTag(tagId: String) {
        scope.launch {
            runCatching { repository.deleteTag(tagId) }
                .onFailure { error ->
                    _listState.update { it.copy(message = "删除标签失败：${error.message}") }
                }
        }
    }
}