package dev.praxis.data.imagegen

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 当前网络类型（文档 §7.2 的「仅 Wi-Fi 下载」要它）。
 *
 * ## 为什么用 NetworkCapabilities 而不是 `activeNetworkInfo.type`
 *
 * 后者在 API 29 起已废弃，且对 VPN / 以太网 / 多网络并存会给出误导结果。
 * 取「当前默认网络的能力」才是系统真正在用的那条链路。
 */
object NetworkStatus {

    /** 默认网络是否 Wi-Fi（以太网也算「不计流量」，同样放行）。 */
    fun isOnUnmeteredWifi(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return wifi || ethernet
    }
}
