package dev.praxis.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏右侧的「角色切换」入口（头像 + 角色名，点击进入角色页）。
 *
 * ## 结构照抄 Operit `ChatHeader.kt` 第 153–195 行
 *
 * 上游那段就是用户圈出来的那个按钮，逐项对应：
 *
 * | 上游 | 本项目 | 说明 |
 * |---|---|---|
 * | `Modifier.widthIn(max = 176.dp)` | 同 | 名字再长也不把标题挤掉 |
 * | `clip(CircleShape)` | 同 | 整体是个胶囊 |
 * | `padding(horizontal = 8.dp, vertical = 4.dp)` | 同 | |
 * | 头像 `size(24.dp)` + `clip(CircleShape)` | 同 | |
 * | 名字 `widthIn(max = 116.dp)` + Ellipsis | 同 | |
 * | `toChatHeaderName()` 截 12 字符 | 同 | 见 [CHAT_HEADER_NAME_MAX] |
 * | `MaterialTheme.*` / `Surface` / `Icons.Rounded.Person` | **miuix 等价物** | 这是本次唯一改动处 |
 *
 * ## 为什么头像位用图标而不是真图片
 *
 * 上游用 `coil.compose.rememberAsyncImagePainter` 加载 `activeCharacterAvatarUri`。
 * Praxis **没有角色系统**（`core:model` 里没有任何 persona / character 实体，
 * 命中的 `ChatRole` 是「用户/助手/工具」的消息角色，不是角色扮演的角色）。
 * 现在接 Coil 只能得到一个永远为 null 的 Uri —— 那是假接线。
 *
 * 所以：头像位画一个人形图标，等角色系统真做出来时把这里换成图片即可
 * （参数已经从上游一模一样地留好了）。
 */
@Composable
fun MiuixCharacterSwitcher(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = name.toChatHeaderName()

    Row(
        modifier = modifier
            // 176dp 上限：超过之后名字再长也不该把左边的标题吃掉。
            .widthIn(max = CHAT_HEADER_MAX_WIDTH)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像：圆形底色 + 人形图标（对应上游的 secondaryContainer 圆底）。
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Contacts,
                contentDescription = "角色头像",
                tint = MiuixTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp),
            )
        }
        // 名字：与头像之间留 6dp（上游是 Arrangement.spacedBy(6.dp)）。
        Text(
            text = displayName,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 6.dp)
                .widthIn(max = CHAT_HEADER_NAME_WIDTH),
        )
    }
}

/**
 * 名字截断：超过 [CHAT_HEADER_NAME_MAX] 个字符就截断加省略号。
 *
 * 与上游 `toChatHeaderName()` 一致。用**字符数**而不是像素宽度：
 * 中文名 12 个字已经很长，按字符截比按宽度截更可预测
 * （按宽度截时，中英混排会出现「英文显示得比中文多很多」的观感）。
 */
private fun String.toChatHeaderName(): String =
    if (length <= CHAT_HEADER_NAME_MAX) this else take(CHAT_HEADER_NAME_MAX) + "…"

/** 上游同值。 */
private const val CHAT_HEADER_NAME_MAX = 12

/** 整个胶囊的最大宽度（上游 `widthIn(max = 176.dp)`）。 */
private val CHAT_HEADER_MAX_WIDTH = 176.dp

/** 名字自身最大宽度（上游 `widthIn(max = 116.dp)`）。 */
private val CHAT_HEADER_NAME_WIDTH = 116.dp