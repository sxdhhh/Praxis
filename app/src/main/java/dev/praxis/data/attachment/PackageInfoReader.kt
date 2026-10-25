package dev.praxis.data.attachment

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 读取已安装应用（「智能包」）的信息，拼成可插进输入框的文本。
 *
 * ## 「智能包」这个词的含义
 *
 * Operit 的 `attachment_package` 格子对应「把某个应用/能力包作为上下文」
 * （`AttachmentSelector.kt:225`）。Praxis 没有插件包体系，但有**已安装应用**
 * 这个等价物：用户常问「我这个应用为什么打不开」「XX 应用占多少空间」。
 *
 * 所以这里读的是 `PackageManager` 里的应用元信息，而不是 APK 二进制 ——
 * 后者要写文件、要授权、还要解析 DEX，成本高一个量级，而元信息已经
 * 足够支撑绝大多数「关于某个应用」的问题。
 *
 * ## 隐私边界
 *
 * 只取：应用名、包名、版本名/版本号、是否系统应用、安装时间。
 * **不取**：签名、权限清单、组件列表。前者可用于指纹识别，
 * 后两者体量大且与常见问题无关。
 */
object PackageInfoReader {

    /** 一条应用信息。 */
    data class AppBrief(
        val label: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val isSystem: Boolean,
        val updatedAt: Long,
    )

    /**
     * 读取全部可见应用，最多 [limit] 个（按最近更新排序）。
     *
     * 排序理由是「用户刚装的/刚更新的最可能要问」。
     */
    suspend fun readAll(context: Context, limit: Int = 40): List<AppBrief> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            runCatching {
                val flags = PackageManager.GET_META_DATA
                val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(flags)
                }
                packages.mapNotNull { info ->
                    val app: ApplicationInfo = info.applicationInfo ?: return@mapNotNull null
                    AppBrief(
                        label = runCatching {
                            pm.getApplicationLabel(app).toString()
                        }.getOrDefault(info.packageName),
                        packageName = info.packageName,
                        versionName = info.versionName ?: "?",
                        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            info.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            info.versionCode.toLong()
                        },
                        // FLAG_SYSTEM = 预装；判断「能不能卸载」用它。
                        isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        updatedAt = info.lastUpdateTime,
                    )
                }
                    .sortedByDescending { it.updatedAt }
                    .take(limit)
            }.getOrDefault(emptyList())
        }

    /**
     * 把应用列表拼成一段可读文本。
     *
     * 每行压缩到极限（应用名 + 包名 + 版本 + 类型），因为应用动辄上百个，
     * 完整格式化会把上下文预算吃光。
     */
    fun format(apps: List<AppBrief>, maxChars: Int = 3000): String {
        if (apps.isEmpty()) return "[应用] 读不到已安装应用列表"

        var used = 0
        val kept = ArrayList<String>(apps.size)
        for (app in apps) {
            val line = buildString {
                append("- ").append(app.label)
                if (app.label != app.packageName) append("（").append(app.packageName).append("）")
                append(" v").append(app.versionName)
                append(if (app.isSystem) " · 系统" else " · 用户")
            }
            if (used + line.length > maxChars) break
            kept += line
            used += line.length
        }

        return buildString {
            append("[应用] 已安装 ").append(kept.size).append(" 个（按最近更新排序）：\n")
            kept.forEach { append(it).append('\n') }
        }.trimEnd()
    }

    /** 打开系统应用详情页（供未来「点一下去看」用）。 */
    fun detailIntent(packageName: String): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:" + packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
