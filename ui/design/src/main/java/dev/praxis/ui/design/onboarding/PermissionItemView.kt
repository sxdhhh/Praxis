package dev.praxis.ui.design.onboarding

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Checkable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.praxis.ui.design.R

/**
 * 逐字移植 HyperCeiler `widget/PermissionItemView.java`（92 行）。
 *
 * 布局 `provision_permission_list_item_view.xml` 是逐字拷贝的，
 * 所以行高、内边距、字号、文字色、圆点位图全部来自原版资源，
 * 不在 Kotlin 里重复声明（那样必然漂移）。
 *
 * 与原版的差异仅两处 API 写法（行为相同）：
 *
 * | 原版 | 这里 |
 * |---|---|
 * | `getResources().getColor(id, theme)` | `context.getColor(id)` |
 * | `getDrawable(id)` | `context.getDrawable(id)` |
 *
 * `Checkable` 必须实现：`AccessibilityNodeInfo` 会把它报成可选中控件，
 * TalkBack 才会读「已选择/未选择」。省掉这个接口会丢无障碍信息。
 */
class PermissionItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), Checkable {

    private var checkedState = false
    private val itemTitle: TextView
    private val itemIcon: ImageView

    init {
        LayoutInflater.from(context)
            .inflate(R.layout.provision_permission_list_item_view, this, true)
        itemTitle = findViewById(R.id.item_title)
        itemIcon = findViewById(R.id.item_icon)
        // 该色在 values-night 有对应项，所以深色模式自动翻转。
        itemTitle.setTextColor(context.getColor(R.color.provision_list_item_text_unselected))
        itemIcon.visibility = if (checkedState) VISIBLE else INVISIBLE
    }

    fun setItemTitle(resId: Int) = setItemTitle(context.getString(resId))

    fun setItemTitle(title: String) {
        itemTitle.text = title
    }

    /**
     * 原版每次调用都重新 `setImageDrawable` —— 看着多余，实际必要：
     * `PermissionSettingsFragment` 里有绕过 `setChecked` 直接调它的地方。
     */
    fun updateItemState(checked: Boolean) {
        itemIcon.setImageDrawable(context.getDrawable(R.drawable.provision_picker_btn_radio))
        itemIcon.visibility = if (checked) VISIBLE else INVISIBLE
    }

    override fun isChecked(): Boolean = checkedState

    override fun setChecked(checked: Boolean) {
        checkedState = checked
        updateItemState(checked)
    }

    /** 原版是空实现 —— 点击由外层容器处理。 */
    override fun toggle() = Unit
}