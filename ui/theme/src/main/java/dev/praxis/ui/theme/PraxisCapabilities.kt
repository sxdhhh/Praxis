package dev.praxis.ui.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * 设备能力判定。
 *
 * miuix 的模糊/着色器链路要求 API 33+；Praxis 的 minSdk 是 28。
 * 因此所有「液态玻璃 / 背景模糊」效果都必须先过这一关，
 * 低版本走纯色降级，而不是让 miuix 在低版本上被调用后崩掉。
 *
 * 三个属性都标了 `@ChecksSdkIntAtLeast`：这是给 lint 看的契约。
 * 没有它，调用点写成 `if (PraxisCapabilities.dynamicColorSupported) dynamicDarkColorScheme(...)`
 * 会被 lint 判成 NewApi error（它只认 `Build.VERSION.SDK_INT >=` 与带该注解的 API），
 * 在 `abortOnError = true` 下直接把 CI 打红。
 */
object PraxisCapabilities {

    /** 背景模糊与液态玻璃是否可用。 */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val glassSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 动态取色（Monet）是否可用。 */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val dynamicColorSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** 预测性返回手势是否可用（用于转场动画分支）。 */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val predictiveBackSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}