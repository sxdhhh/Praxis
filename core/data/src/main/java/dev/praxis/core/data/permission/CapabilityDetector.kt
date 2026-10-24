package dev.praxis.core.data.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.praxis.core.model.permission.CapabilitySnapshot
import dev.praxis.core.model.permission.CapabilityState
import dev.praxis.core.model.permission.CapabilityStatus
import dev.praxis.core.model.permission.GrantChannel
import dev.praxis.core.model.permission.PraxisCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * **真实的**授权检测器。
 *
 * ## 为什么必须有这一层（2026-10-22）
 *
 * 用户原话：「权限管理 tmd 就跟没有一样连个正经的授权检测都没有」。
 *
 * 此前 Praxis 的「权限」只是一个策略枚举（三档能力上限），
 * 界面上的「已授予 / 可提升 / 不可直达」全部是**按枚举推出来的**，
 * 没有一次真正问过 Android「这个权限到底有没有」。
 * 于是出现「界面说已解锁 Root 级能力，实际 /sdcard 都读不了」这种
 * 完全割裂的状态。
 *
 * 这个类的职责就一件：**问系统**，把答案变成一个 [CapabilitySnapshot]。
 *
 * ## 为什么每个能力的检测方式都不一样（不能统一成 checkSelfPermission）
 *
 * 三条完全不同的路径，写成一样的代码必然是错的：
 *
 * 1. **通知**：API ≥ 33 才有 `POST_NOTIFICATIONS` 运行时权限。
 *    低于 33 时它是安装即授予，报「未授予」是假信息。
 * 2. **存储**：API ≥ 30 的「所有文件访问」**不是**运行时权限，
 *    `checkSelfPermission` 对它永远返回 DENIED，正确做法是
 *    `Environment.isExternalStorageManager()`；API ≤ 29 才是
 *    `WRITE_EXTERNAL_STORAGE` 的运行时授予。
 *    （这是最容易写错的一条 —— 照抄 `checkSelfPermission` 会让
 *    Android 11+ 的机器永远显示「未授予」，即使用户已经授权。）
 * 3. **高级执行通道**：不是权限，是「有没有可用提权通道」。
 *    Shizuku 用 binder 存活探测（不能直接引 Shizuku 依赖，见下），
 *    Root 用 `su -c id` 实际执行一次。
 *
 * ## 为什么不直接依赖 Shizuku 的 API（`rikka.shizuku`）
 *
 * 代价与收益不成比例：
 *  - 引入 Shizuku 库会把它的 `provider` / `UserService` 声明合并进 manifest，
 *    并且要求用户装 Shizuku 才有意义；
 *  - 真正需要 Shizuku 的时刻是**执行命令**，那是后续的 `ShellExecutor`；
 *  - 这一层只需要「有没有通道」这个事实，用 binder 存在性判断就够，
 *    判错了后果可控（最坏是多显示一次「可尝试」）。
 *
 * 所以这里用反射查 binder，探测失败一律当作**没有**（保守），
 * 不假装有 —— 假装有会让用户去点一个必然失败的按钮。
 */
class CapabilityDetector(
    private val context: Context,
) {

    private val _snapshot = MutableStateFlow(CapabilitySnapshot.Unknown)
    val snapshot: StateFlow<CapabilitySnapshot> = _snapshot.asStateFlow()

    /**
     * 重新检测全部能力。
     *
     * 必须从界面 `ON_RESUME` 调用：用户去系统设置里授予权限后回来，
     * 状态要在**同一帧**变过来。只在启动时检测一次的话，
     * 用户会看到「刚授权完还是红的」，然后去点第二次。
     */
    suspend fun refresh(): CapabilitySnapshot {
        val detected = withContext(Dispatchers.IO) {
            CapabilitySnapshot(
                states = PraxisCapability.entries.map { capability ->
                    when (capability) {
                        PraxisCapability.NOTIFICATION -> detectNotification()
                        PraxisCapability.STORAGE -> detectStorage()
                        PraxisCapability.PRIVILEGED_SHELL -> detectPrivilegedShell()
                        PraxisCapability.OVERLAY -> detectOverlay()
                        PraxisCapability.BATTERY_EXEMPTION -> detectBatteryExemption()
                        PraxisCapability.ACCESSIBILITY -> detectAccessibility()
                        PraxisCapability.LOCATION -> detectLocation()
                        PraxisCapability.DEVICE_ADMIN -> detectDeviceAdmin()
                    }
                },
            )
        }
        _snapshot.value = detected
        Timber.d(
            "CapabilityRefreshed: %s",
            detected.states.joinToString { "${it.capability.name}=${it.status}" },
        )
        return detected
    }

    // ── 通知 ──────────────────────────────────────────────────────
    private fun detectNotification(): CapabilityState {
        // API < 33：没有这个运行时权限，装了就有。
        // 报 UNSUPPORTED 而不是 GRANTED —— 说「不支持」是准确的，
        // 说「已授予」会让用户以为存在一个可以关掉的开关。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return CapabilityState(
                capability = PraxisCapability.NOTIFICATION,
                status = CapabilityStatus.UNSUPPORTED,
                detail = "当前系统版本无需授予",
            )
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return CapabilityState(
            capability = PraxisCapability.NOTIFICATION,
            status = if (granted) CapabilityStatus.GRANTED else CapabilityStatus.DENIED,
            detail = if (granted) "" else "长任务进度与结果提示不会显示",
        )
    }

    // ── 存储 ──────────────────────────────────────────────────────
    private fun detectStorage(): CapabilityState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // ★ 这里**不能**用 checkSelfPermission 查 MANAGE_EXTERNAL_STORAGE：
            //   它不在运行时权限体系里，那样写永远返回 DENIED。
            val allFiles = Environment.isExternalStorageManager()
            return CapabilityState(
                capability = PraxisCapability.STORAGE,
                status = if (allFiles) CapabilityStatus.GRANTED else CapabilityStatus.DENIED,
                detail = if (allFiles) "可读写所有文件" else "未获得「所有文件访问」授权",
            )
        }
        @Suppress("DEPRECATION")
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        return CapabilityState(
            capability = PraxisCapability.STORAGE,
            status = if (granted) CapabilityStatus.GRANTED else CapabilityStatus.DENIED,
            detail = if (granted) "" else "未授予存储写入",
        )
    }

    // ── 高级执行通道 ───────────────────────────────────────────────
    private fun detectPrivilegedShell(): CapabilityState {
        // 1) Root：真的执行一次。只看「有没有 su 文件」是不够的 ——
        //    很多机器有 su 但 magisk 未授权本应用，执行才是唯一可信判据。
        val rootOk = tryRunSu()
        if (rootOk) {
            return CapabilityState(
                capability = PraxisCapability.PRIVILEGED_SHELL,
                status = CapabilityStatus.GRANTED,
                detail = "Root 可用",
                // 显式标注来源：层级推导靠它区分 Root / Shizuku，
                // 不解析 detail 文案（见 GrantChannel 的 KDoc）。
                channel = GrantChannel.ROOT,
            )
        }

        // 2) Shizuku：binder 存活就说明服务在跑（本应用是否已授权由 Shizuku 自己管，
        //    在执行时再被拒绝一次，比这里猜错要好）。
        val shizuku = isShizukuAlive()
        if (shizuku) {
            return CapabilityState(
                capability = PraxisCapability.PRIVILEGED_SHELL,
                status = CapabilityStatus.GRANTED,
                detail = "Shizuku 已运行",
                channel = GrantChannel.SHIZUKU,
            )
        }

        // 3) 既没 Root 也没 Shizuku：判断「本机有没有装过」，
        //    装了但没启动 → DENIED（可以引导用户启动），
        //    没装 → UNSUPPORTED（不该给按钮，用户点了只会困惑）。
        val installed = isPackageInstalled("moe.shizuku.privileged.api")
        return CapabilityState(
            capability = PraxisCapability.PRIVILEGED_SHELL,
            status = if (installed) CapabilityStatus.DENIED else CapabilityStatus.UNSUPPORTED,
            detail = if (installed) "Shizuku 已安装但未运行" else "未检测到 Root / Shizuku",
        )
    }

    // ── 悬浮窗 ────────────────────────────────────────────────────
    private fun detectOverlay(): CapabilityState {
        // ★ 与存储同类：不在运行时权限体系里，必须问 Settings。
        //   用 checkSelfPermission(SYSTEM_ALERT_WINDOW) 永远返回 DENIED。
        val granted = Settings.canDrawOverlays(context)
        return CapabilityState(
            capability = PraxisCapability.OVERLAY,
            status = if (granted) CapabilityStatus.GRANTED else CapabilityStatus.DENIED,
            detail = if (granted) "可显示浮层" else "未允许显示在其他应用上层",
            channel = if (granted) GrantChannel.SYSTEM else GrantChannel.NONE,
        )
    }

    // ── 电池优化豁免 ──────────────────────────────────────────────
    private fun detectBatteryExemption(): CapabilityState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // API 22 及以下没有 Doze，也就没有这个开关。
            return CapabilityState(
                capability = PraxisCapability.BATTERY_EXEMPTION,
                status = CapabilityStatus.UNSUPPORTED,
                detail = "当前系统版本无需设置",
            )
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val granted = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        return CapabilityState(
            capability = PraxisCapability.BATTERY_EXEMPTION,
            status = if (granted) CapabilityStatus.GRANTED else CapabilityStatus.DENIED,
            detail = if (granted) "已豁免，长任务不会被休眠掐断" else "未豁免，后台长任务可能被系统掐断",
            channel = if (granted) GrantChannel.SYSTEM else GrantChannel.NONE,
        )
    }

    // ── 无障碍 ────────────────────────────────────────────────────
    /**
     * 无障碍服务是否已启用。
     *
     * ## 为什么读 `ENABLED_ACCESSIBILITY_SERVICES` 而不是 `AccessibilityManager`
     *
     * `AccessibilityManager.isEnabled` 只回答「本机有没有开无障碍」，
     * **不回答「开的是不是我们」** —— 用户可能开着别的辅助应用，
     * 那时这个值也是 true，我们会误报「已授予」，工具一跑就失败。
     *
     * 正确做法是读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
     * 这个以 `:` 分隔的完整服务名列表（格式 `pkg/cls`），
     * 查里面有没有我们的包名。
     *
     * ## 为什么必须做「组件名精确匹配 + 包名兜底」
     *
     * 精确匹配需要知道服务类名。Praxis 目前没有自己的无障碍服务
     * （`AccessibilityService` 声明为零），所以精确匹配必然落空 ——
     * 这时退回「按包名前缀匹配」没有意义（包名不会出现在别人的服务里）。
     *
     * 因此这里的结论是：**没有声明服务 = 本机不支持这一项**，
     * 返回 UNSUPPORTED 而不是 DENIED。给 DENIED 会让 UI 显示一个
     * 「去授予」按钮，点进系统设置却找不到 Praxis，是典型的假按钮。
     */
    private fun detectAccessibility(): CapabilityState {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val declared = hasAccessibilityServiceDeclared()
        if (!declared) {
            return CapabilityState(
                capability = PraxisCapability.ACCESSIBILITY,
                status = CapabilityStatus.UNSUPPORTED,
                detail = "本应用未提供无障碍服务",
            )
        }
        val granted = enabled.split(':').any { it.startsWith(context.packageName + "/") }
        return CapabilityState(
            capability = PraxisCapability.ACCESSIBILITY,
            status = if (granted) CapabilityStatus.GRANTED else CapabilityStatus.DENIED,
            detail = if (granted) "无障碍服务已开启" else "需在系统设置里手动开启",
            channel = if (granted) GrantChannel.SYSTEM else GrantChannel.NONE,
        )
    }

    /**
     * 本应用的 manifest 里是否声明过 `AccessibilityService`。
     *
     * 用 `queryIntentServices` 查 `android.accessibilityservice.AccessibilityService`
     * 这个 action —— 系统登记无障碍服务就是靠它。
     * 查不到就说明「这个应用没有无障碍能力可开」。
     */
    private fun hasAccessibilityServiceDeclared(): Boolean = try {
        val intent = android.content.Intent(
            "android.accessibilityservice.AccessibilityService",
        ).setPackage(context.packageName)
        context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
    } catch (t: Throwable) {
        false
    }

    // ── 位置 ──────────────────────────────────────────────────────
    /**
     * 位置权限：FINE 或 COARSE 任一即算授予。
     *
     * 与上游 `PermissionGuideViewModel.checkPermissions()` 同判据
     * （它是 `fine || coarse`）。只给 COARSE 的用户也该算「能用」——
     * 卡在「必须精确」会让一个本来能用的功能显示成不可用。
     */
    private fun detectLocation(): CapabilityState {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val granted = fine || coarse
        return CapabilityState(
            capability = PraxisCapability.LOCATION,
            status = if (granted) CapabilityStatus.GRANTED else CapabilityStatus.DENIED,
            detail = when {
                fine -> "精确位置"
                coarse -> "大致位置（未给精确）"
                else -> "未授予定位"
            },
            channel = if (granted) GrantChannel.SYSTEM else GrantChannel.NONE,
        )
    }

    // ── 设备管理员 ────────────────────────────────────────────────
    /**
     * 是否已激活设备管理员。
     *
     * ## 为什么查「所有已激活的 admin」而不是 `isAdminActive(component)`
     *
     * `DevicePolicyManager.isAdminActive()` 需要一个已知的
     * `ComponentName`。Praxis 的 admin receiver 类名若在重构中改过，
     * 硬编码那份名字会让检测永远返回 false（静默失效）。
     *
     * `getActiveAdmins()` 返回真实的已激活列表，按**包名**过滤 ——
     * 这样重构类名也不影响检测。代价是列表为 null 时要当作「无」。
     */
    private fun detectDeviceAdmin(): CapabilityState {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as? android.app.admin.DevicePolicyManager
        if (dpm == null) {
            return CapabilityState(
                capability = PraxisCapability.DEVICE_ADMIN,
                status = CapabilityStatus.UNSUPPORTED,
                detail = "本机不支持设备管理",
            )
        }
        val active = dpm.activeAdmins?.any { it.packageName == context.packageName } == true
        // 没有声明 receiver 时**不该给按钮** —— 点了系统会报「没有可激活的
        // 管理员」，那是假按钮。声明存在与否用「有没有 admin receiver」判断：
        // `DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN` 需要 EXTRA_DEVICE_ADMIN
        // 指向一个真实组件，拿不到就无从发起。
        val receiver = deviceAdminReceiver()
        if (receiver == null) {
            return CapabilityState(
                capability = PraxisCapability.DEVICE_ADMIN,
                status = CapabilityStatus.UNSUPPORTED,
                detail = "本应用未声明设备管理员组件",
            )
        }
        return CapabilityState(
            capability = PraxisCapability.DEVICE_ADMIN,
            status = if (active) CapabilityStatus.GRANTED else CapabilityStatus.DENIED,
            detail = if (active) "设备管理员已激活" else "未激活（激活后可锁屏、清数据）",
            channel = if (active) GrantChannel.SYSTEM else GrantChannel.NONE,
        )
    }

    /** 找出本应用声明的 `DeviceAdminReceiver` 组件（没有则返回 null）。 */
    private fun deviceAdminReceiver(): android.content.ComponentName? = try {
        val intent = android.content.Intent("android.app.action.DEVICE_ADMIN_ENABLED")
            .setPackage(context.packageName)
        context.packageManager
            .queryBroadcastReceivers(intent, 0)
            .firstOrNull()
            ?.activityInfo
            ?.let { android.content.ComponentName(it.packageName, it.name) }
    } catch (t: Throwable) {
        null
    }

    /**
     * 执行 `su -c id` 判断是否真有 Root。
     *
     * 两个必要的保护：
     *  - **超时**：`su` 弹授权框时会一直阻塞，不设超时会把整个检测拖死
     *    （界面上就是「一直在检测中」）。这里等 1.5 秒，无响应即判失败。
     *  - **吞掉异常**：没有 su 时 `ProcessBuilder` 直接抛 `IOException`，
     *    这是正常情况，不是错误，不该让检测整体崩掉。
     */
    private fun tryRunSu(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            val out = process.inputStream.bufferedReader().readText()
            process.exitValue() == 0 && out.contains("uid=0")
        }
    } catch (t: Throwable) {
        // 没有 su 二进制 → IOException；被 SELinux 拒绝 → 同样的异常。
        // 两种情况结论一致：没有 Root。
        false
    }

    /** 反射查 Shizuku 的 binder 是否存活。 */
    private fun isShizukuAlive(): Boolean = try {
        // Shizuku 的公开入口是 rikka.shizuku.Shizuku.pingBinder()。
        // 用反射是为了不把它变成编译期依赖（见类注释的取舍说明）。
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val ping = clazz.getMethod("pingBinder")
        ping.invoke(null) as? Boolean ?: false
    } catch (t: Throwable) {
        // 类不存在（没装 Shizuku）是最常见的情况，属正常返回。
        false
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}