package dev.praxis.core.data.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.praxis.core.model.permission.PraxisCapability
import timber.log.Timber

/**
 * 运行时权限申请的**最小桥接接口**。
 *
 * ## 为什么不直接用 `ActivityResultLauncher`
 *
 * `ActivityResultLauncher` 来自 `androidx.activity`，把它写进 `core:data`
 * 的公共签名会带来两个实际成本：
 *  - `core:data` 从此依赖 `androidx.activity`（一个数据层模块不该知道 UI 框架）；
 *  - 单元测试里没法构造 —— `ActivityResultLauncher` 是 final class，
 *    要 mock 就得引入 mockk 并处理它的泛型，测一个「点了会不会弹框」不值当。
 *
 * 定义成只有 `launch(Array<String>)` 一个方法的接口后：
 *  - UI 层用 `rememberLauncherForActivityResult(...)` 拿到的 launcher
 *    用一行 lambda 适配即可（`{ perms -> launcher.launch(perms) }`）；
 *  - 测试里传一个记录调用的假实现就能断言。
 *
 * 设计原则与本项目其它端口一致：跨层只暴露本层需要的最小能力，
 * 不把宿主框架的类型泄漏成依赖。
 */
fun interface PermissionLaunchBridge {
    /** 发起一次系统权限申请。 */
    fun launch(permissions: Array<String>)
}

/**
 * 「去授予」的**实际动作**。
 *
 * ## 为什么检测之外还必须有它
 *
 * 只做检测会把权限页变成一个「只报错、不解决」的面板 ——
 * 用户看到三个红点，却没有任何可点的东西。这跟之前的「假状态」是同一类问题，
 * 只是从「假装有权限」变成「假装会修」。
 *
 * ## 三条路径完全不同，必须分开
 *
 * 1. **通知**：API 33+ 走 `ActivityResultContracts.RequestPermission` 弹系统框；
 *    API ≤ 33 时没有这个权限，直接不处理（返回 false 让 UI 不显示按钮）。
 * 2. **存储**：
 *    - API ≥ 30：「所有文件访问」**不能**用弹窗申请，
 *      只能 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 跳系统设置页。
 *      这是 Android 11 起的硬性规定，试图用 `requestPermissions` 申请
 *      `MANAGE_EXTERNAL_STORAGE` 会直接被忽略，用户会以为按钮坏了。
 *    - API ≤ 29：走标准运行时申请。
 * 3. **高级执行通道**：没有「申请」这回事，只能引导用户
 *    去启动 Shizuku 或授予 Root。这里跳到 Shizuku 应用（装了的话），
 *    否则什么都不做（UI 那边已经用 UNSUPPORTED 把按钮去掉了）。
 *
 * ## 为什么用系统设置页兜底
 *
 * 用户拒绝两次之后，Android 会把「请求权限」标成「不再询问」，
 * 再调 `requestPermissions` 不会弹任何东西（静默失败）。
 * 这时唯一可行的路径是跳到应用详情页让用户手动开。
 * 所以每个能力在被永久拒绝后都要能落到 `openAppSettings()`。
 */
class CapabilityRequester(
    private val context: Context,
) {

    /**
     * 发起一次授予流程。
     *
     * @param capability 目标能力
     * @param launcher 通知/存储（API ≤ 29）用的运行时申请入口。
     *   传 null 表示当前场景拿不到 Activity（例如从 Service 调用），
     *   这时直接走系统设置页。
     * @return 是否**真的**启动了某个界面/弹窗。返回 false 说明这次点击
     *   什么也不会发生 —— UI 该据此隐藏按钮，而不是让用户点了没反应。
     */
    fun request(
        capability: PraxisCapability,
        launcher: PermissionLaunchBridge? = null,
    ): Boolean = when (capability) {
        PraxisCapability.NOTIFICATION -> requestNotification(launcher)
        PraxisCapability.STORAGE -> requestStorage(launcher)
        PraxisCapability.PRIVILEGED_SHELL -> openShizuku()
        PraxisCapability.OVERLAY -> requestOverlay()
        PraxisCapability.BATTERY_EXEMPTION -> requestBatteryExemption()
        PraxisCapability.ACCESSIBILITY -> openAccessibilitySettings()
        PraxisCapability.LOCATION -> requestLocation(launcher)
        PraxisCapability.DEVICE_ADMIN -> requestDeviceAdmin()
    }

    // ── 悬浮窗 ────────────────────────────────────────────────────
    /**
     * 悬浮窗只能跳系统设置页。
     *
     * 它走的是 `SYSTEM_ALERT_WINDOW`，这个权限在 Android 6.0+ 起
     * **不再通过弹窗授予** —— `requestPermissions` 对它会被静默忽略。
     * 唯一路径是 `ACTION_MANAGE_OVERLAY_PERMISSION`。
     */
    private fun requestOverlay(): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        return startSafely(intent) || startSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
    }

    // ── 电池优化豁免 ──────────────────────────────────────────────
    /**
     * 电池优化豁免：跳系统的「忽略电池优化」确认框。
     *
     * ## 为什么用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 而不是
     * `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
     *
     * 前者直接弹出「是否允许 Praxis 不受电池优化限制」的确认框，
     * 一步到位；后者把用户丢进一个**列表**里，让他自己找应用 ——
     * 多两步操作，且列表里应用名可能是缩写，容易点错。
     *
     * 代价：Google Play 对 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     * 有政策限制。本项目不发布到 Play，这一条不构成约束；
     * 若将来要上架，需要换回 SETTINGS 那个 action（列表页）。
     */
    private fun requestBatteryExemption(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        )
        return startSafely(intent) ||
            startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /**
     * 无障碍：只能跳系统设置页，而且**必须由用户手动开启**。
     *
     * Android 从 6.0 起禁止应用自行开启自己的无障碍服务
     * （`WRITE_SECURE_SETTINGS` 之外没有 API），所以这里只做导航。
     *
     * 用 `ACTION_ACCESSIBILITY_SETTINGS` 而不是带包名的深链：
     * 带包名的 `:settings:fragment_args_key` 只在部分 ROM 上支持，
     * 不支持时会导致「点了没反应」。落到列表页虽然多一步，
     * 但每一台机器都能到。
     */
    private fun openAccessibilitySettings(): Boolean =
        startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    // ── 位置 ──────────────────────────────────────────────────────
    /**
     * 位置：标准运行时申请。
     *
     * 同时申请 FINE + COARSE：只申请 FINE 时，用户在 Android 12+
     * 的「大致/精确」二选一弹框里选「大致」会让 FINE 保持未授予，
     * 而我们又把「FINE 或 COARSE 任一」当判据（与检测侧一致），
     * 所以两个一起请，用户怎么选都算成功。
     */
    private fun requestLocation(launcher: PermissionLaunchBridge?): Boolean {
        if (launcher == null) {
            openAppSettings()
            return true
        }
        return try {
            launcher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            true
        } catch (t: Throwable) {
            Timber.w(t, "requestLocation failed, falling back to app settings")
            openAppSettings()
            true
        }
    }

    // ── 设备管理员 ────────────────────────────────────────────────
    /**
     * 设备管理员：走 `ACTION_ADD_DEVICE_ADMIN`，必须带一个真实的
     * `ComponentName`（从 manifest 里查出来的 receiver）。
     *
     * ## 为什么找不到 receiver 就直接返回 false（不落到应用详情页）
     *
     * 应用详情页里没有「设备管理员」这个开关 —— 那是系统「安全 →
     * 设备管理应用」里的入口。把用户送到应用详情页等于送错地方，
     * 他会以为流程完了但状态没变。返回 false 让 UI 保持
     * UNSUPPORTED 的判据（不显示按钮）更诚实。
     */
    private fun requestDeviceAdmin(): Boolean {
        val receiver = deviceAdminReceiver() ?: return false
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, receiver)
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "激活后 Praxis 才能执行锁屏、清空数据这类设备级操作。",
            )
        }
        return startSafely(intent)
    }

    /** 找出本应用声明的 `DeviceAdminReceiver` 组件（与检测侧同一判据）。 */
    private fun deviceAdminReceiver(): android.content.ComponentName? = try {
        val intent = Intent("android.app.action.DEVICE_ADMIN_ENABLED")
            .setPackage(context.packageName)
        context.packageManager
            .queryBroadcastReceivers(intent, 0)
            .firstOrNull()
            ?.activityInfo
            ?.let { android.content.ComponentName(it.packageName, it.name) }
    } catch (t: Throwable) {
        null
    }

    // ── 通知 ──────────────────────────────────────────────────────
    private fun requestNotification(
        launcher: PermissionLaunchBridge?,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // 33 以下没有这个运行时权限，申请是无意义动作。
            return false
        }
        if (launcher == null) {
            openAppSettings()
            return true
        }
        return try {
            launcher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
            true
        } catch (t: Throwable) {
            // 典型原因：Activity 已 destroy 后再 launch（IllegalStateException）。
            // 这时落到系统设置页，用户仍然能完成授权。
            Timber.w(t, "requestNotification failed, falling back to app settings")
            openAppSettings()
            true
        }
    }

    // ── 存储 ──────────────────────────────────────────────────────
    private fun requestStorage(
        launcher: PermissionLaunchBridge?,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // ★ 必须跳设置页。用 requestPermissions 申请 MANAGE_EXTERNAL_STORAGE
            //   系统会静默忽略，用户看到的就是「点了没反应」。
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            )
            return startSafely(intent) || startSafely(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }

        if (launcher == null) {
            openAppSettings()
            return true
        }
        return try {
            @Suppress("DEPRECATION")
            launcher.launch(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
            true
        } catch (t: Throwable) {
            Timber.w(t, "requestStorage failed, falling back to app settings")
            openAppSettings()
            true
        }
    }

    // ── 高级执行通道 ───────────────────────────────────────────────
    private fun openShizuku(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return false
        return startSafely(intent)
    }

    /** 打开本应用的系统详情页（永久拒绝后的唯一出路）。 */
    fun openAppSettings(): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        return startSafely(intent)
    }

    /**
     * 启动 Activity，失败返回 false。
     *
     * 必须自己判返回值：某些 ROM / 已卸载应用会让 `startActivity` 抛
     * `ActivityNotFoundException`，或目标 Action 根本没实现。
     * 不捕获就会在点击时直接崩掉整个页面。
     */
    private fun startSafely(intent: Intent): Boolean = try {
        // 从非 Activity 上下文（Application）启动必须加这个 flag，
        // 否则 Android 8+ 直接抛 AndroidRuntimeException。
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Timber.w(t, "startActivity failed: %s", intent.action)
        false
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}