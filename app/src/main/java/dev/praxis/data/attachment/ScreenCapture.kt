package dev.praxis.data.attachment

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.io.FileOutputStream

/**
 * 一次性截屏：授权一次、抓一帧、立刻释放。
 *
 * ## 为什么由 Activity + Service 两层组成
 *
 * `MediaProjection` 的授权必须先经 `createScreenCaptureIntent()` 弹系统确认框，
 * 这个 Intent 只能由 **Activity** 发（`ActivityResultContracts.StartActivityForResult`）。
 * 而 Android 14 起，光拿到授权结果还不够：调用 `getMediaProjection()` 前
 * **必须先有一个 `mediaProjection` 类型的前台服务**，否则抛 `SecurityException`。
 *
 * 所以流程是三段：
 *
 *  1. [ScreenCaptureActivity] 弹系统授权框；
 *  2. 拿到 resultCode/Intent 后启动 [ScreenCaptureService]（前台服务）；
 *  3. Service 里 `getMediaProjection()` → 建 VirtualDisplay → 抓一帧 → 释放。
 *
 * ## 为什么不做常驻投屏
 *
 * 用户的用途是「把现在这屏发给模型看」，一次一帧。常驻投屏会一直占着
 * 编码器和通知栏，用户还得手动关 —— 那是一个录屏功能，不是这里要的。
 * 抓完立刻 `release()`，通知也一并撤掉。
 *
 * ## 为什么不用无障碍截图
 *
 * `AccessibilityService.takeScreenshot()` 不弹框，但要求用户到设置里手动
 * 开启无障碍服务，且部分 ROM 会定期杀掉。MediaProjection 的授权框虽然
 * 每次都弹，但用户点一下就走，不需要跳设置页 —— 对「偶尔发一次截图」这个
 * 频率，一次性授权更合适。
 */
object ScreenCapture {

    /** 截图成功后的落盘文件（调用方负责读取并删除）。 */
    @Volatile
    var pendingResult: File? = null

    /** 失败原因（null = 没失败）。 */
    @Volatile
    var pendingError: String? = null

    /** 清掉上一次的结果，避免读到旧图。 */
    fun reset() {
        pendingResult = null
        pendingError = null
    }
}

/**
 * 透明 Activity，只做一件事：弹系统截屏授权框。
 *
 * 为什么要独立 Activity 而不是在 Compose 里直接 launcher：MediaProjection
 * 的授权结果必须交给一个**前台服务**去消费，而服务不能自己发起 Activity
 * 跳转（后台启动限制）。所以中间需要一个 Activity 承接 resultCode。
 */
class ScreenCaptureActivity : Activity() {

    private val requestCode = 0x5C12

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenCapture.reset()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
        } catch (e: Exception) {
            // 部分 ROM 会禁用截屏（如某些企业设备），不能让 Activity 卡住。
            ScreenCapture.pendingError = "系统不允许截屏"
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != this.requestCode) {
            finish()
            return
        }
        if (resultCode != RESULT_OK || data == null) {
            // 用户拒绝授权：不是错误，静默退出由调用方判断。
            ScreenCapture.pendingError = null
            finish()
            return
        }
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            ScreenCapture.pendingError = "启动截屏服务失败"
        }
        finish()
    }
}

/**
 * 前台服务：拿 MediaProjection、抓一帧、释放。
 *
 * ## 为什么必须 `startForeground`（而且要带类型）
 *
 * Android 14（API 34）起，`MediaProjection.getMediaProjection()` 会检查
 * 调用进程是否已有 `mediaProjection` 类型的前台服务。没有就抛
 * `SecurityException: Media projections require a foreground service of type
 * ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`。
 *
 * 低版本没有这个要求，但照样 startForeground 无害，所以统一走一条路径，
 * 只在类型参数上做版本分支。
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "praxis_screen_capture"
        private const val NOTIFICATION_ID = 0x5C13
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先起前台，再碰 MediaProjection —— 顺序反了在 API 34+ 会抛异常。
        startAsForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData == null) {
            ScreenCapture.pendingError = "没有拿到授权数据"
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            captureOnce(resultCode, resultData)
        } catch (e: Exception) {
            ScreenCapture.pendingError = e.message ?: "截屏失败"
        } finally {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 渠道重复创建是幂等的，不用判存在。
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "截屏", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在截屏")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 抓一帧并落盘到 `cache/praxis_screen/shot_<ts>.png`。
     *
     * 为什么是 PNG 而不是 JPEG：截图含 UI 文本和小图标，JPEG 的振铃效应
     * 会让文字边缘发虚，模型读小字更容易出错。体积大一些可以接受 ——
     * 只有一帧，而且后续 `ChatImagePreparer` 会再压一次。
     */
    private fun captureOnce(resultCode: Int, resultData: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        var reader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null
        try {
            reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 1)
            virtualDisplay = projection.createVirtualDisplay(
                "praxis-capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(Looper.getMainLooper()),
            )

            // ## 为什么要轮询而不是直接 acquireLatestImage()
            //
            // 刚建好的 VirtualDisplay 还没有产出第一帧，立刻拿会得到 null。
            // 常见做法是 ImageReader.setOnImageAvailableListener 再等，
            // 但那是异步的，服务里要额外同步。这里用短轮询（最多 ~600ms）
            // 更直白：一帧到位就走。截屏是用户主动点的，等不超过一秒。
            var image: android.media.Image? = null
            var tries = 0
            while (image == null && tries < 30) {
                image = reader.acquireLatestImage()
                if (image == null) {
                    Thread.sleep(20)
                    tries++
                }
            }

            if (image == null) {
                ScreenCapture.pendingError = "屏幕没有产出画面"
                return
            }

            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            // rowStride 可能大于 width*4（硬件对齐），必须按 rowStride 拷贝
            // 再裁掉尾部填充，否则图像会斜切。
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride

            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            if (cropped !== padded) padded.recycle()
            image.close()

            val dir = File(cacheDir, "praxis_screen").apply { mkdirs() }
            val out = File(dir, "shot_" + System.currentTimeMillis() + ".png")
            FileOutputStream(out).use { stream ->
                cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            cropped.recycle()

            ScreenCapture.pendingResult = out
        } finally {
            virtualDisplay?.release()
            reader?.close()
            // 抓完立刻释放：不释放会一直占着编码器，通知栏也撤不掉。
            projection.stop()
        }
    }
}