package dev.praxis.ui.design.glass

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 玻璃渲染后端选择 —— 含**临时调试开关**（用户要求：「测试可以开一个临时用来启用 old 渲染的开关」）。
 *
 * # 为什么需要它
 *
 * 低版本管线（[dev.praxis.ui.design.glass.low]）只在 API < 33 时会走。
 * 但用户手上多是高版本设备 —— 没有开关就没法验收低版本管线。
 *
 * 所以这里提供一个**运行时可切**的开关：
 *  - `AUTO`（默认）：按 SDK_INT 自动选（高版本 miuix / 低版本自写）—— 生产行为；
 *  - `FORCE_LOW`：**强制走低版本自写管线**（高版本设备上也能看到低版本效果）。
 *
 * # 是否持久化
 *
 * **不持久化**（只存内存）。理由：它是**临时测试开关**，
 * 开着它重启 App 会让用户以为「玻璃坏了」。重启即恢复 AUTO。
 *
 * # 正式版要不要保留
 *
 * 建议保留 —— 低版本用户遇到玻璃问题时，可以切回来看是不是管线差异。
 * 但**默认必须是 AUTO**，且不写盘。
 */
object GlassBackend {

    enum class Mode(val displayName: String) {
        /** 按 SDK_INT 自动选（生产默认）。 */
        AUTO("自动（按系统版本）"),

        /** 强制低版本自写管线（调试用）。 */
        FORCE_LOW("强制低版本管线（调试）"),

        /** 高版本设备上强制不走玻璃（对照用，排查「玻璃是不是卡顿源」）。 */
        FORCE_NONE("强制无玻璃（对照）"),
    }

    /** 当前模式（内存态，不持久化）。 */
    var mode by mutableStateOf(Mode.AUTO)

    /**
     * 是否走低版本自写管线。
     *
     * 所有分流点（`MainActivity` / `AppScaffold` / `LiquidToggle`）都调它 ——
     * 这样「改一处就全局生效」，不必在三个地方各写一遍 SDK 判断。
     */
    val useLowPipeline: Boolean
        get() = when (mode) {
            Mode.AUTO -> Build.VERSION.SDK_INT < 33
            Mode.FORCE_LOW -> true
            Mode.FORCE_NONE -> true
        }

    /** 是否完全不要玻璃（[Mode.FORCE_NONE] 时顶栏/底栏都用实色）。 */
    val noGlassAtAll: Boolean
        get() = mode == Mode.FORCE_NONE
}