package dev.praxis.feature.settings.character

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.praxis.core.data.character.CharacterRepository
import kotlinx.coroutines.launch

/**
 * 标签库接线层。
 *
 * ## 引用数在这里算，而不是再查一次库
 *
 * `CharacterRepository` 已经暴露了 `cards` 与 `tags` 两个 Flow。
 * 引用数 = 「对每个 tag，数有多少张卡的 attachedTagIds 含它」，
 * 是纯内存计算（几十张卡 × 几十个标签，可忽略）。
 *
 * 为它加一个 DAO 查询反而不划算：那个查询要 `LIKE '%id%'` 扫全表，
 * 而内存里已经有全部数据了。
 */
@Composable
fun PromptTagsRoute(
    repository: CharacterRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tags by repository.tags.collectAsState(initial = emptyList())
    val cards by repository.cards.collectAsState(initial = emptyList())

    val usageCounts = remember(tags, cards) {
        // 一次遍历统计，而不是「每个 tag 扫一遍所有卡」（O(n·m) → O(n+m)）。
        val counts = mutableMapOf<String, Int>()
        cards.forEach { card ->
            card.attachedTagIds.forEach { tagId ->
                counts[tagId] = (counts[tagId] ?: 0) + 1
            }
        }
        counts
    }

    PromptTagsScreen(
        tags = tags,
        usageCounts = usageCounts,
        onCreate = { name, content, type ->
            scope.launch {
                // 失败（如内容为空）由 Repository 返回空 id；
                // 界面不弹错，因为用户点「添加」时按钮已按内容非空禁用。
                runCatching { repository.createOrReuseTag(name, content, tagType = type) }
            }
        },
        onDelete = { tagId ->
            scope.launch {
                runCatching { repository.deleteTag(tagId) }
            }
        },
        modifier = modifier,
    )
}