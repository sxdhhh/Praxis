package dev.praxis.ui.design.onboarding

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil

/**
 * 逐字移植 HyperCeiler `renderengine/RenderViewLayout.java`（77 行）。
 *
 * ## 为什么这个类必须存在
 *
 * 原版 `provision_startup_layout.xml:32` 与 `provision_congratulation_layout.xml`
 * 都用它当第一个子项：
 *
 * ```xml
 * <com.sevtinge.hyperceiler.provision.renderengine.RenderViewLayout
 *     android:id="@+id/render_view_layout" .../>
 * ```
 *
 * 布局是逐字拷贝的，所以类名必须能解析，否则 inflate 抛
 * `ClassNotFoundException` 直接闪退。
 *
 * ## 它不渲染任何东西
 *
 * 名字带 Render，实际只做「反缩放 + 居中布局」：
 *
 * 1. 子 View 的 `scaleX/scaleY` 设为 `1 / childScale`（默认 0.5 → 放大 2 倍）；
 * 2. 子 View 被布局在屏幕正中 `childScale` 比例的矩形里。
 *
 * 合起来：子 View 画在中间 50% 区域、按 2 倍尺寸绘制。原版是给光晕 shader
 * 用的 —— 让 shader 在小画布上算再放大，这样内部坐标假设可以固定，
 * 不必随分辨率改（`getInternalWidth/Height` 就是内部尺寸）。
 *
 * 对照原版：默认值 `0.5f`、`ceil(w*s)` 居中、子 View `1/s`、
 * `attachView` 对 `TextureView` 跳过设色，全部一致。
 */
class RenderViewLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /** 原版字段 `mChildScale`，默认 0.5f。 */
    private var childScale = 0.5f

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val count = childCount
        if (count == 0) return

        val innerW = ceil(width * childScale).toInt()
        val innerH = ceil(height * childScale).toInt()
        val left = ((width - innerW) * 0.5f).toInt()
        val top = ((height - innerH) * 0.5f).toInt()

        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            // 先放大 1/s，再放进 1/s 大小的矩形 → 视觉尺寸复原，绘制分辨率减半。
            child.scaleX = 1.0f / childScale
            child.scaleY = 1.0f / childScale
            child.layout(left, top, left + innerW, top + innerH)
        }
    }

    /** 原版重载 1：默认黑底（`-16777216`）。 */
    fun attachView(view: View, scale: Float) = attachView(view, scale, Color.BLACK)

    /**
     * 原版重载 2：指定底色。
     *
     * `TextureView` 直接返回不设色 —— 它自己持有 SurfaceTexture，
     * 设背景会盖掉画面。
     */
    fun attachView(view: View, scale: Float, color: Int) {
        childScale = scale
        addView(view)
        if (view is TextureView) return
        view.setBackgroundColor(color)
    }

    /** shader 用的内部宽度（原版 `getInternalWidth`）。 */
    fun getInternalWidth(): Int = (width * childScale).toInt()

    /** shader 用的内部高度（原版 `getInternalHeight`）。 */
    fun getInternalHeight(): Int = (height * childScale).toInt()
}