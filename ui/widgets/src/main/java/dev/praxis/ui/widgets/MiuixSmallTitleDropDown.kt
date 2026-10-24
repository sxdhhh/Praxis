package dev.praxis.ui.widgets

// Copyright 2025, miuix-kotlin-multiplatform contributors
// SPDX-License-Identifier: Apache-2.0

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayListPopup

/**
 * A standalone dropdown menu styled to look and feel like a SmallTitle.
 * It functions as a selector that occupies the space of a title.
 *
 * @param items The list of options for the dropdown menu.
 * @param selectedIndex The index of the currently selected item.
 * @param onSelectedIndexChange A callback invoked when a new item is selected.
 * @param modifier The modifier to be applied to the entire component.
 * @param enabled Controls the enabled state of the dropdown.
 * @param placeholder The text to display when the items list is empty.
 */
@Composable
fun MiuixDropdown(
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "No Selection",
) {
    // State to manage whether the dropdown is expanded or not.
    val isDropdownExpanded = remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    val itemsNotEmpty = items.isNotEmpty()
    val actualEnabled = enabled && itemsNotEmpty

    // Box to anchor the popup menu.
    Box(modifier = modifier) {
        // The visible, clickable part of the component.
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = actualEnabled,
                    onClick = {
                        isDropdownExpanded.value = !isDropdownExpanded.value
                        if (isDropdownExpanded.value) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                        }
                    },
                )
                // Use the same padding as SmallTitle for consistent layout.
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Display the selected item's text, styled like SmallTitle.
            val textColor = if (actualEnabled) {
                MiuixTheme.colorScheme.onBackgroundVariant
            } else {
                MiuixTheme.colorScheme.disabledOnSecondaryVariant
            }
            Text(
                text = if (itemsNotEmpty) items[selectedIndex] else placeholder,
                fontSize = MiuixTheme.textStyles.subtitle.fontSize, // from SmallTitle
                fontWeight = FontWeight.Bold, // from SmallTitle
                color = textColor,
            )

            // The dropdown arrow icon.
            val iconColor = if (actualEnabled) {
                MiuixTheme.colorScheme.onSurfaceVariantActions
            } else {
                MiuixTheme.colorScheme.disabledOnSecondaryVariant
            }
            Image(
                modifier = Modifier.size(10.dp, 16.dp),
                imageVector = MiuixIcons.Basic.ArrowUpDown,
                colorFilter = ColorFilter.tint(iconColor),
                contentDescription = "Toggle Dropdown",
            )
        }

        // The popup menu itself, shown when isDropdownExpanded is true.
        //
        // ## 为什么用 OverlayListPopup 而不是 WindowListPopup（真机闪退修复）
        //
        // 上游原实现用的是 `WindowListPopup`，它内部是：
        //
        // ```
        // popupHost = { visible, hostContent ->
        //     if (visible) {
        //         Dialog(onDismissRequest = ..., properties = platformDialogProperties()) {
        //             hostContent()          // ← 新开一个 Window
        //         }
        //     }
        // }
        // ```
        //
        // 也就是说每展开一次下拉就**新建一个 Dialog Window**。这在
        // InstallerX 里没问题（它每个页面是独立 Activity + 独立 ComposeView），
        // 但在 Praxis 里页面跑在 `NavDisplay` 的 entry 里、外层是单个 Scaffold，
        // 新 Window 与宿主 Window 的 focus/输入法/转场状态会打架。
        // 真机现象：外观页点开「莫奈取色」下拉直接闪退。
        //
        // `OverlayListPopup` 是 miuix 官方的**同层**版本：走 `PopupLayout`
        // 在现有 composition 里画浮层，不开新 Window，行为与 `DropdownPreference`
        // 的内部实现一致（见 miuix-preference 的 OverlayDropdownPopup.kt）。
        // `renderInRootScaffold = true` 让它绘制到根 Scaffold 的 overlay 层，
        // 避免被 LazyColumn 的 clip 裁掉。
        OverlayListPopup(
            show = isDropdownExpanded.value,
            alignment = PopupPositionProvider.Align.Start,
            popupPositionProvider = DropdownWithStartMarginProvider,
            onDismissRequest = { isDropdownExpanded.value = false },
            renderInRootScaffold = true,
        ) {
            ListPopupColumn {
                items.forEachIndexed { index, text ->
                    DropdownImpl(
                        text = text,
                        optionSize = items.size,
                        isSelected = selectedIndex == index,
                        index = index,
                        onSelectedIndexChange = { newIndex ->
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSelectedIndexChange(newIndex)
                            isDropdownExpanded.value = false // Close dropdown after selection
                        },
                    )
                }
            }
        }
    }
}

/**
 * A custom PopupPositionProvider that is identical to the default dropdown provider,
 * but adds a horizontal margin.
 */
private val DropdownWithStartMarginProvider = object : PopupPositionProvider {
    // This positioning logic is copied directly from ListPopupDefaults.DropdownPositionProvider
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
    ): IntOffset {
        val offsetX = if (alignment == PopupPositionProvider.Align.End) {
            anchorBounds.right - popupContentSize.width - popupMargin.right
        } else {
            anchorBounds.left + popupMargin.left
        }
        val offsetY = if (windowBounds.bottom - anchorBounds.bottom > popupContentSize.height) {
            // Show below
            anchorBounds.bottom + popupMargin.bottom
        } else if (anchorBounds.top - windowBounds.top > popupContentSize.height) {
            // Show above
            anchorBounds.top - popupContentSize.height - popupMargin.top
        } else {
            // Middle
            anchorBounds.top + anchorBounds.height / 2 - popupContentSize.height / 2
        }
        return IntOffset(
            x = offsetX.coerceIn(
                windowBounds.left,
                (windowBounds.right - popupContentSize.width - popupMargin.right).coerceAtLeast(windowBounds.left),
            ),
            y = offsetY.coerceIn(
                (windowBounds.top + popupMargin.top).coerceAtMost(
                    windowBounds.bottom - popupContentSize.height - popupMargin.bottom,
                ),
                windowBounds.bottom - popupContentSize.height - popupMargin.bottom,
            ),
        )
    }

    // Provide a 12.dp horizontal margin.
    override fun getMargins(): PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
}
