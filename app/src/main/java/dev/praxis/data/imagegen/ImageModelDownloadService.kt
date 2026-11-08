package dev.praxis.data.imagegen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.praxis.core.imagegen.ImageDownloadState
import dev.praxis.core.imagegen.ImageModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 模型下载的前台服务（docs/PORTING-IMAGEGEN.md §8.4 第 ⑤ 条）。
 *
 * ## 为什么必须是前台服务，而不是「在页面作用域里下」
 *
 * 一个模型几 GB、几分钟到几十分钟。用户点完下载十有八九会切走（锁屏、看别的 App），
 * 而普通后台协程会被系统回收 —— 表现就是「下到一半没了，还得重来」。
 * 前台服务带通知，既保住进程也让用户看得到进度。
 *
 * ## 为什么通知要分「进度」和「终态」两种
 *
 * 进度通知是 `ongoing`（划不掉）；终态必须**可划掉且会自动消失**。
 * 留一条永远转的圈是最糟的结果：用户不知道到底完了没有。
 *
 * ## 与 [ImageModelDownloadManager] 的分工
 *
 * 单飞、进度、取消都在 manager 里（它是纯 Kotlin，能单测）；这个服务只做两件事：
 * 把进程钉在前台、把 manager 的状态翻译成通知。
 */
class ImageModelDownloadService : Service() {

    companion object {
        const val EXTRA_MODEL_ID: String = "model_id"

        private const val CHANNEL_ID = "praxis_model_download"
        private const val NOTIFICATION_ID = 0x1A6E

        /** 由界面调用：启动一个模型的下载。 */
        fun start(context: Context, modelId: String) {
            val intent = Intent(context, ImageModelDownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
            // API 26+ 必须用 startForegroundService 才能保证服务被拉起后有 5 秒时间
            // 调 startForeground（否则 ANR）。minSdk 28，所以只有这一条路径。
            context.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Koin 里注册的是单例：服务与界面观察同一份状态。
        // GlobalContext.get() 返回的是 Koin 容器本身，还要再 get<T>() 才拿到实例
        // （CI 抓到过：只调一次会得到 Assignment type mismatch: actual type is 'Koin'）。
        manager = org.koin.core.context.GlobalContext.get().get<ImageModelDownloadManager>()
    }

    private lateinit var manager: ImageModelDownloadManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先起前台通知，再做任何可能耗时/抛异常的事（5 秒宽限）。
        startAsForeground("正在准备下载")

        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        if (modelId == null) {
            finishWith("没有指定模型", failed = true)
            return START_NOT_STICKY
        }
        val model = ImageModelCatalog.builtin(socModel()).firstOrNull { it.id == modelId }
        if (model == null) {
            finishWith("内置清单里没有这个模型：" + modelId, failed = true)
            return START_NOT_STICKY
        }

        // 单飞：已有下载在跑时 start() 返回 false。这里明说，而不是静默排队。
        if (!manager.start(model)) {
            finishWith("已经有一个模型在下载了", failed = false)
            return START_NOT_STICKY
        }

        watcher = scope.launch {
            manager.state.collect { state ->
                when (state) {
                    is ImageDownloadState.Running -> notifyProgress(model.name, state)
                    is ImageDownloadState.Completed -> finishWith(
                        "下载完成：" + model.name,
                        failed = false,
                    )
                    is ImageDownloadState.Cancelled -> finishWith("已取消下载", failed = false)
                    is ImageDownloadState.Failed -> when (state.reason) {
                        ImageDownloadState.Failed.Reason.InsufficientSpace ->
                            finishWith("空间不足：" + state.detail, failed = true)
                        ImageDownloadState.Failed.Reason.Transport ->
                            finishWith("下载失败：" + state.detail, failed = true)
                    }
                    ImageDownloadState.Idle -> Unit
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 服务被系统回收时不留一个还在写盘的协程（manager 的取消是标志位，见它的 KDoc）。
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** 骁龙档位要从芯片型号推（`Build.SOC_MODEL` 是 API 31 才有）。 */
    private fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE

    private fun startAsForeground(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 渠道重复创建是幂等的（与截屏服务同一做法）。
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "模型下载", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyProgress(modelName: String, state: ImageDownloadState.Running) {
        val progress = state.progress
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(modelName)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val fraction = progress?.fraction
        if (fraction == null) {
            // 总量未知时不画一条假进度条（与界面同一纪律）。
            builder.setContentText(if (progress == null) "正在准备…" else "已下载 " + progress.bytesDownloaded / (1024 * 1024) + " MB")
        } else {
            val percent = (fraction * 100).toInt()
            builder.setProgress(100, percent, false).setContentText(percent.toString() + "%")
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, builder.build())
    }

    /** 终态：可划掉、发一次提示音，然后停服务。 */
    private fun finishWith(text: String, failed: Boolean) {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(text)
            .setSmallIcon(
                if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done,
            )
            .setOngoing(false)
            .setAutoCancel(true)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, builder.build())
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }
}
