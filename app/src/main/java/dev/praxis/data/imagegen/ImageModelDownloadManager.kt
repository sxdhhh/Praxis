package dev.praxis.data.imagegen

import dev.praxis.core.imagegen.DownloadProgress
import dev.praxis.core.imagegen.ImageDownloadPlanner
import dev.praxis.core.imagegen.ImageDownloadState
import dev.praxis.core.imagegen.ImageModel
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型下载的编排层。
 *
 * ## 为什么要有这一层（而不是界面直接调 downloader）
 *
 * 三件事只能在这里统一：
 * 1. **单飞**：同一时间只允许一个下载。同时下两个大包会把存储写满，
 *    而「写到一半发现没空间」是最难处理的状态；
 * 2. **进度落到 StateFlow**：界面/通知订阅同一个源，不会各算一套进度；
 * 3. **取消语义**：见 [cancel]。
 *
 * ## 为什么取消不用 `Job.cancel()`
 *
 * [ModelDownloader] 里是**阻塞 IO**，`Job.cancel()` 打断不了卡在 `read()` 上的线程，
 * 还会让清理逻辑来不及跑（残留的 `.part` 会让下次空间预检误判）。
 * 所以取消是「置标志位 → 循环自己退出 → 清理 → 返回 Cancelled」。
 */
class ImageModelDownloadManager(
    private val downloader: ModelDownloader,
    private val modelsRoot: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 下载前的闸门：返回 null = 放行；返回一句话 = 拒绝原因。
     *
     * 「仅 Wi-Fi 下载」这类策略需要 Context（网络类型）与设置（开关），
     * 两者都在 :app —— 所以闸门由外部注入，管理器只管「被挡下就如实报」。
     */
    private val downloadGuard: suspend () -> String? = { null },
    /** 当前下载源（设置页可改）。 */
    private val baseUrl: suspend () -> String = {
        dev.praxis.core.imagegen.ImageDownloadSettings.DEFAULT_BASE_URL
    },
) {

    private val _state = MutableStateFlow<ImageDownloadState>(ImageDownloadState.Idle)
    val state: StateFlow<ImageDownloadState> = _state.asStateFlow()

    private var job: Job? = null
    private var cancelRequested = false

    /** 当前是否有下载在跑（界面据此禁用按钮，而不是靠 state 的瞬时值猜）。 */
    fun isBusy(): Boolean = job?.isActive == true

    /**
     * 开始下载。
     *
     * @return false = 已有下载在跑，这次请求被拒绝（**不排队**：排队会让用户以为点了两次
     *   会下两个，实际存储只够一个）。
     */
    fun start(model: ImageModel): Boolean {
        if (isBusy()) return false
        cancelRequested = false
        _state.value = ImageDownloadState.Running(model.id, null)
        job = scope.launch {
            // 闸门在取计划之前：被策略挡下时连目录都不用建。
            val blocked = downloadGuard()
            if (blocked != null) {
                _state.value = ImageDownloadState.Failed(
                    modelId = model.id,
                    reason = ImageDownloadState.Failed.Reason.Blocked,
                    detail = blocked,
                )
                return@launch
            }
            val plan = ImageDownloadPlanner.plan(modelsRoot, model)
            val source = baseUrl()
            val result = withContext(io) {
                downloader.download(
                    plan = plan,
                    baseUrl = source,
                    onProgress = { progress ->
                        _state.value = ImageDownloadState.Running(model.id, progress)
                    },
                    shouldStop = { cancelRequested },
                )
            }
            _state.value = when (result) {
                is DownloadResult.Completed -> ImageDownloadState.Completed(
                    modelId = model.id,
                    spaceWasUnknown = result.spaceWasUnknown,
                    leftoverZipName = result.leftoverZipName,
                )
                is DownloadResult.InsufficientSpace -> ImageDownloadState.Failed(
                    modelId = model.id,
                    reason = ImageDownloadState.Failed.Reason.InsufficientSpace,
                    detail = "空间不足：还需要 " + (result.requiredBytes - result.freeBytes) + " 字节",
                )
                is DownloadResult.Cancelled -> ImageDownloadState.Cancelled(model.id)
                is DownloadResult.Failed -> ImageDownloadState.Failed(
                    modelId = model.id,
                    reason = ImageDownloadState.Failed.Reason.Transport,
                    detail = result.detail,
                )
            }
        }
        return true
    }

    /**
     * 请求取消。异步生效：循环在下一块数据处退出并清理，随后 state 变 [ImageDownloadState.Cancelled]。
     */
    fun cancel() {
        if (!isBusy()) return
        cancelRequested = true
    }

    /** 回到 Idle（界面消费完终态后调用，避免旧结果一直被当成当前状态）。 */
    fun acknowledge() {
        if (!isBusy()) _state.value = ImageDownloadState.Idle
    }
}
