package dev.praxis.ui.widgets

// Copyright 2025, miuix-kotlin-multiplatform contributors
// SPDX-License-Identifier: Apache-2.0

import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup

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
    // 把 items 包成一个 DropdownEntry：入参是 entry 列表，
    // 每个 item 的 onClick 自己负责选中并收起。
    val entry = remember(items, selectedIndex) {
        DropdownEntry(
            items = items.mapIndexed { index, text ->
                DropdownItem(
                    text = text,
                    selected = selectedIndex == index,
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        onSelectedIndexChange(index)
                        isDropdownExpanded.value = false
                    },
                )
            },
        )
    }

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
        // ## 换成 OverlayDropdownPopup（2026-11-03）
        //
        // 用户原话：「现在只有加号的那个菜单有弹窗动画，模型和功能菜单没有弹出和关闭的动画」。
        //
        // `OverlayListPopup` 只有整体出现/消失；`OverlayDropdownPopup` 是它的升级版，
        // 带逐项的出现动画与关闭动画，并通过 `onDismissFinished` 给出「关闭动画真正结束」
        // 的时刻 —— 这个回调是「关完再允许重开」的必需信号，ListPopup 没有。
        //
        // ## 关于「收起态形变」—— 官方 0.9.4 做不了，别再照 NexioSchedule 抄
        //
        // NexioSchedule 的调用里有 `collapseSize` / `collapseContent` / `dragSelectState`，
        // 那是它自己 fork 进 :miuix 模块的增强版
        // （miuix/src/commonMain/.../popup/LiquidOverlayDropdownPopup.kt）。
        // Praxis 依赖的是官方 0.9.4，javap 核实过签名只有 8 个参数：
        //
        //   entries / show / onDismiss / onDismissFinished / maxHeight
        //   dropdownColors / renderInRootScaffold / collapseOnSelection
        //
        // **没有** collapseSize / collapseContent / onFractionProgress / dragSelectState。
        // 我先后两次照着 NexioSchedule 的调用写参数、两次编译失败 —— 记在这里，
        // 下次要抄之前先 javap 一遍官方 aar。
        //
        // 想要「胶囊长成面板」那种形变，只能 fork miuix 或手写插值，
        // 那属于 P6 的范围，不在这一项里硬塞。
        //
        // `renderInRootScaffold = true` 保留：让浮层画到根 Scaffold 的 overlay 层，
        // 避免被 LazyColumn 的 clip 裁掉（模型选择在设置页的 LazyColumn 里）。
        OverlayDropdownPopup(
            entries = listOf(entry),
            show = isDropdownExpanded.value,
            onDismiss = { isDropdownExpanded.value = false },
            onDismissFinished = {
                // 关闭动画结束：下一帧再允许重新打开，避免「点一下又立刻开」的重叠。
                isDropdownExpanded.value = false
            },
            maxHeight = null,
            dropdownColors = DropdownDefaults.dropdownColors(),
            renderInRootScaffold = true,
            // 单选场景：选中后收起。
            collapseOnSelection = true,
        )
    }
}

