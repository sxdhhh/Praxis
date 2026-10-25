package dev.praxis.data.attachment

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 读取当前设备位置，拼成一段可直接插进输入框的文字。
 *
 * ## 为什么返回「文字」而不是一个位置对象
 *
 * 位置的用途是**给模型看**（「我在哪，附近有什么」）。
 * 插进输入框后它就是对话上下文的一部分，用户还能自己编辑或删掉 —— 
 * 比塞进一个看不见的附件槽更可控。
 *
 * 这也是 Operit 的做法：它把位置结果作为 `AttachmentInfo` 的正文内容
 * （`AttachmentDelegate.kt:752` 的 `locationContent`），而不是二进制附件。
 * 区别只是它放进附件列表，Praxis 的 `ChatState.attachments` 只承载图片，
 * 所以这里直接把文字交给输入框。
 *
 * ## 为什么用 lastKnownLocation 而不是 requestLocationUpdates
 *
 * 单次刷新要等 GPS 冷启动，可能十几秒到一分钟，用户点一下「位置」
 * 等那么久会以为坏了。`lastKnownLocation` 是系统缓存（通常由其他应用
 * 或系统服务更新过），**毫秒级返回**。精度可能不是最新，但对「我在哪个
 * 城市/街区」这个用途足够。
 *
 * 三个 provider 依次尝试：GPS 最准、NETWORK 次之、PASSIVE 是「别的应用
 * 上次请求时的残留」，最后兜底。
 */
object LocationReader {

    /**
     * 读一次位置。返回 null 表示拿不到（无权限 / 定位关闭 / 无缓存）。
     *
     * 调用方应把 null 转成用户可见的提示，而不是静默失败。
     */
    @SuppressLint("MissingPermission")
    suspend fun read(context: Context): String? = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null

        // 权限由调用方保证（见 ChatTab 的权限请求）。这里再判一次是为了
        // 避免在「用户刚拒绝」的瞬间抛 SecurityException。
        val granted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext null

        val location = runCatching {
            // 依次尝试三个 provider，取第一个非空的。
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
            providers.firstNotNullOfOrNull { provider ->
                runCatching {
                    // isProviderEnabled 对 PASSIVE 恒为 true，对它无副作用。
                    if (!manager.isProviderEnabled(provider)) {
                        null
                    } else {
                        manager.getLastKnownLocation(provider)
                    }
                }.getOrNull()
            }
        }.getOrNull()

        // 兜底：某些机型上 lastKnownLocation 全为空，但定位本身可用。
        // 这里不做主动请求（见 KDoc 的理由），直接返回 null 让上层提示。
        location?.let { format(it) }
    }

    /** 把 Location 拼成一行人类可读 + 机器可读的文字。 */
    private fun format(location: Location): String {
        val lat = "%.6f".format(location.latitude)
        val lon = "%.6f".format(location.longitude)
        return buildString {
            append("[位置] 纬度 ").append(lat)
            append("，经度 ").append(lon)
            if (location.hasAccuracy()) {
                append("，精度 ±").append(location.accuracy.toInt()).append("m")
            }
            // 地图链接：模型不需要，但用户点开能看到具体在哪。
            append("\nhttps://maps.google.com/?q=").append(lat).append(',').append(lon)
        }
    }
}
