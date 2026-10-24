package dev.praxis.ui.design.onboarding

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 替代 HyperCeiler 的 `fan.provision.CustomDispatchFrameLayout`。
 *
 * 原版那 63 行里唯一有行为的部分是转场期的点击锁：
 *
 * ```java
 * public boolean dispatchTouchEvent(MotionEvent ev) {
 *     if (isAnimEnded()) return super.dispatchTouchEvent(ev);
 *     return true;   // 动画未结束，吞掉事件
 * }
 * ```
 *
 * 目的：转场期间连点「下一步」会把状态机推两格。
 *
 * 这里保留类名与布局职责（`provision_fragment_content.xml` 指定了它），
 * 但不做事件拦截 —— Praxis 的转场是 Compose `AnimatedContent`，
 * 那把锁应该在 Compose 层实现，不属于这个 View。
 */
class ProvisionRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr)