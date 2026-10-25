package dev.praxis.data.attachment

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 拍照落盘：给系统相机准备一个可写的 content:// 目标。
 *
 * ## 为什么不用 CameraX
 *
 * CameraX 要新加依赖、要自己搭预览界面、要处理相机权限与生命周期 —— 
 * 三件事加起来是一整个页面。而用户要的是「拍一张图发给模型看」，
 * 系统相机已经做完了取景、对焦、闪光灯、美颜。
 *
 * `ActivityResultContracts.TakePicture()` 一行就能调起系统相机，
 * **零新依赖**，成本只有一个 FileProvider。
 *
 * ## 为什么必须经 FileProvider
 *
 * targetSdk >= 24 起，把 `file://` 路径通过 Intent 传给别的应用会抛
 * `FileUriExposedException`。系统相机是另一个进程，所以必须给
 * `content://` URI，并配 `FLAG_GRANT_WRITE_URI_PERMISSION`。
 *
 * ## 为什么落 cache 而不是 files
 *
 * 照片只是「这一条消息的输入」，选完就编码进请求体了，原文件没有长期
 * 保留价值。放 cache 让系统在空间紧张时能回收，省得用户手动清理。
 */
object CameraCapture {

    /** FileProvider 的 authority，必须与 AndroidManifest 里的字面量一致。 */
    private const val AUTHORITY_SUFFIX = ".praxis.fileprovider"

    /**
     * 新建一个空的目标文件并返回它的 content URI。
     *
     * @return null 表示创建失败（cache 不可写 / FileProvider 未注册）。
     *   调用方应提示，而不是静默取消 —— 「点了拍照什么都没发生」最难排查。
     */
    fun newTarget(context: Context): Uri? {
        return try {
            val dir = File(context.cacheDir, "praxis_camera").apply { mkdirs() }
            // 文件名带时间戳：同一秒内连拍两张也不会互相覆盖。
            val file = File(dir, "shot_" + System.currentTimeMillis() + ".jpg")
            if (!file.exists()) file.createNewFile()
            FileProvider.getUriForFile(
                context,
                context.packageName + AUTHORITY_SUFFIX,
                file,
            )
        } catch (e: Exception) {
            // FileProvider 未注册 / paths 不含该子目录时这里会抛
            // IllegalArgumentException，绝不能让它冒到 UI 层闪退。
            null
        }
    }

    /**
     * 清理过期的拍照临时文件（超过 [olderThanMs]）。
     *
     * 为什么需要：用户拍了照但没发送就退出，文件会一直躺在 cache 里。
     * 系统只在**空间不足**时才回收 cache，正常情况下能存很久。
     */
    fun cleanStale(context: Context, olderThanMs: Long = 24 * 60 * 60 * 1000L) {
        try {
            val dir = File(context.cacheDir, "praxis_camera")
            if (!dir.isDirectory) return
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { f ->
                if (now - f.lastModified() > olderThanMs) f.delete()
            }
        } catch (_: Exception) {
            // 清理失败无所谓，不该影响任何主流程。
        }
    }
}
