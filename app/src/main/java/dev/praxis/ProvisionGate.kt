package dev.praxis

import android.content.Context
import android.content.Intent

import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.permission.PraxisPermissionLevel
import kotlinx.coroutines.runBlocking

/**
 * HyperCeiler 引导链（`:ui:provision`）的启动闸门。
 *
 * ## 为什么闸门放在 `:app` 而不是 provision 模块里
 *
 * provision 是 1:1 搬运的上游代码，**一行都不该改**。它自己的入口
 * `DefaultActivity` 已经带了完整判断：
 *
 * ```java
 * if (isDeviceIsProvisioned() && !OobeUtils.isDebugOobeMode(this)) {
 *     finishSetup();   // 已引导过 → 立刻 finish
 *     return;
 * }
 * ```
 *
 * 也就是说「要不要显示引导」这件事 provision 自己会判断。但有一个
 * 差别：`OobeUtils.isProvisioned` 读的是 **provision 自己的**
 * SharedPreferences（`PREF_OOBE_STATE` / `is_provisioned`），
 * 而 `MainActivity` 是 Compose 单 Activity。如果直接把
 * `DefaultActivity` 设成 launcher，引导完成后它 `finish()`，
 * 没有人去拉起 `PraxisApp` —— 用户会看到黑屏。
 *
 * 所以闸门需要两头都要管：**启动**（未引导时先走 provision）与
 * **回归**（引导完成后回到 `MainActivity`）。这两件事都必须发生在
 * 宿主侧，因此放在 `:app`。
 *
 * ## 状态归属
 *
 * 状态仍然由 provision 自己持有（同一个 sp 文件、同一个 key），
 * 这里只**读**不写。这样「重置引导」只要清 provision 的 sp 就够，
 * 不会出现两份状态打架。
 */
internal object ProvisionGate {

    /** provision 里 `OobeUtils` 用的 sp 文件名与 key，见 `fan/provision/OobeUtils.java:45-47`。 */
    private const val PREF_OOBE_STATE = "pref_oobe_state"
    private const val KEY_IS_PROVISIONED = "is_provisioned"

    /** provision 引导入口。 */
    private const val ACTIVITY_CLASS = "com.sevtinge.hyperceiler.provision.activity.DefaultActivity"

    /**
     * 引导是否已完成。
     *
     * 读不到类或读不到 sp 一律当「未完成」——宁可多走一次引导，
     * 也不能因为读失败把用户卡在一个永远进不去的主界面。
     */
    fun isDone(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREF_OOBE_STATE, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_PROVISIONED, false)
    }.getOrDefault(false)

    /**
     * 拉起引导。返回 `false` 表示 provision 模块不在编译产物里
     * （比如裁剪过），调用方应直接放行进主界面。
     */
    fun launch(context: Context): Boolean = runCatching {
        val intent = Intent().apply {
            setClassName(context.packageName, ACTIVITY_CLASS)
            // 引导链自己管返回栈，不要让它落进 PraxisApp 的栈里。
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 把引导期间暂存的**权限级别**转存进 `SettingsRepository`（DataStore）。
     *
     * ## 只转存级别，不转存协议
     *
     * `SettingsRepository` 里已经没有协议相关成员了（上一轮删除旧引导页时
     * 一并移除，现在只剩注释里提到过 `acceptAgreement`）。协议状态由
     * `:ui:provision` 自己的 `ProvisionPrefs` 独占持有 —— 那是对的：
     * 「协议签没签」是引导链的私有状态，主界面不关心，
     * 强塞进 DataStore 只是多一处需要同步的副本。
     *
     * ## 权限级别为什么必须转存
     *
     * 用户在引导页选的档位决定「把哪些工具描述发给模型」
     * （见 `PraxisPermissionLevel` 的 KDoc：它是结论而非开关）。
     * 这个值必须进 `SettingsRepository`，否则设置页读不到、
     * 工具层也读不到，引导就白选了。
     *
     * ## 为什么按 key 读而不 import `ProvisionPrefs`
     *
     * `:app` 对 `:ui:provision` 用 `implementation`，编译期看不到其类型
     * （刻意的解耦，见 `app/build.gradle.kts`）。文件名 `praxis_provision`
     * 与 key `permission_level` 是稳定契约，直接按名字读即可；
     * 读失败由 `runCatching` 兜住，表现只是「档位没转过去」，不崩。
     *
     * ## 幂等性
     *
     * 只在 key 存在时才写。否则每次冷启动都会把用户后来在设置页
     * 改过的档位覆盖回引导时那个值。
     */
    fun importFromProvision(context: Context, repository: SettingsRepository) {
        val prefs = context.getSharedPreferences("praxis_provision", Context.MODE_PRIVATE)

        val level = prefs.getString("permission_level", null) ?: return
        val parsed = runCatching { PraxisPermissionLevel.valueOf(level) }.getOrNull() ?: return
        // `runBlocking` 在这里可以接受：调用点是 `LaunchedEffect` 里的
        // 一次性初始化（在 Compose 的协程里，不在主线程测量/布局阶段），
        // 且 DataStore 的首次写入是毫秒级。改成 suspend 会把
        // `LaunchedEffect` 的 lambda 签名复杂度传染给调用方，
        // 收益不成比例。
        runBlocking { repository.setPermissionLevel(parsed) }
    }
}