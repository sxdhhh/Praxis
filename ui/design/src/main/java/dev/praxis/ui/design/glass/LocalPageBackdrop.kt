package dev.praxis.ui.design.glass

import androidx.compose.runtime.compositionLocalOf
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * 当前页面内容层的采样源，供**页面之上的浮层**（底栏、悬浮按钮、弹出面板）折射。
 *
 * ## 为什么要下发而不是各层自己建
 *
 * 玻璃成立的唯一前提是「采样到真实内容」。浮层如果自己 `rememberLayerBackdrop`，
 * 它录到的是**它自己**（浮层内部没有内容），折射出来是一块半透明实色 —— 
 * 看起来像玻璃但完全没有内容穿过，这正是本轮修复前的状态。
 *
 * 所以采样源必须由**内容层的持有者**（[dev.praxis.ui.design.component.AppScaffold]）
 * 创建并下发，浮层只做消费。
 *
 * ## 为什么是可空
 *
 * 低版本（API < 33）走 [GlassBackend.useLowPipeline]，miuix-blur 不可用，
 * 此时没有采样源；浮层必须优雅退回实色，否则会在低版本设备上崩
 * （miuix-blur 加载即 `NoClassDefFoundError`）。
 * 消费方一律通过 `?: 实色` 处理，不要 `!!`。
 */
val LocalPageBackdrop = compositionLocalOf<Backdrop?> { null }