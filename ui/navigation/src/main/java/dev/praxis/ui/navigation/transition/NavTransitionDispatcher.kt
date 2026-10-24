// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
// 移植自 InstallerX-Revived:
//   app/src/main/java/com/rosan/installer/ui/animation/predictiveback/InstallerNavTransition.kt
package dev.praxis.ui.navigation.transition

import dev.praxis.core.model.settings.PredictiveBackAnimation
import dev.praxis.core.model.settings.PredictiveBackExitDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

/**
 * 预测性返回动画的**唯一分发点**。
 *
 * ## 为什么要有分发器而不是让调用方直接选
 *
 * 转场是纯函数值（`NavTransition` 是不可变对象），但 Praxis 里有三个地方要知道它：
 * ① `PraxisNavHost` 渲染；② 外观页给用户预览；③ 未来的单测。
 * 三处各写一遍 `when`，加一档就会漏改一处 —— 漏改的表现是「设置里能选，但界面不变」，
 * 这类缺陷在 UI 上极难察觉。
 *
 * 所以把所有映射收在 [navTransitionFor] 一处，加档位只需改这一个函数 +
 * `PredictiveBackAnimation` 枚举（编译器会逼你补全 when）。
 *
 * ## 为什么 `Miuix` 档要单独列
 *
 * 它不是「别的档位的别名」：它是 miuix 库自己维护的转场，
 * 会随库升级而变化。用户选它等于「我要库的默认观感」，
 * 所以这里直接透传 `NavTransitions.MiuixDefault`，不自己实现一份。
 *
 * @param animation 用户选择的档位
 * @param exitDirection Scale / Classic 档的退场方向；其余档位忽略
 */
fun navTransitionFor(
    animation: PredictiveBackAnimation,
    exitDirection: PredictiveBackExitDirection,
): NavTransition = when (animation) {
    PredictiveBackAnimation.Aosp -> AospNavTransition
    PredictiveBackAnimation.Miuix -> NavTransitions.MiuixDefault
    PredictiveBackAnimation.Scale -> scaleNavTransition(exitDirection)
    PredictiveBackAnimation.Classic -> ClassicNavTransition
}