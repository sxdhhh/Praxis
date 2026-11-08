package dev.praxis.core.imagegen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次生图的进行中状态（文档 §6.1）。
 *
 * 只描述「现在到哪一步了」，不含参数与结果 —— 那些在工具结果里。
 */
data class ImageGenProgress(
    /** 本地是模型 id，远端是模型名。只用于显示。 */
    val targetLabel: String,
    val phase: Phase,
    /** 0~1；null = 总量未知（不编假进度）。 */
    val fraction: Float? = null,
) {
    enum class Phase {
        /** 探活 / 引擎启动 / 远端建连。首次启动可能 3~10 秒。 */
        Preparing,
        /** 扩散中（本地按 NDJSON 的 progress 回报）。 */
        Running,
        /** 在落盘/收尾。 */
        Saving,
    }
}

/**
 * 生图进度的广播点（文档 §6.1）。
 *
 * ## 为什么需要它：工具执行是「黑盒」
 *
 * `Tool.execute()` 是一个 suspend 函数，跑完才返回。一次生图 20~60 秒，
 * 期间聊天层只能显示「正在执行」—— 用户看到的是几十秒不动的圈，
 * 有人会以为卡死并杀掉 App（这是文档里写明的真实反馈）。
 *
 * 所以在 core 放一个窄接口：工具往这里写，聊天层从这里读，两边都不用认识对方。
 *
 * ## 为什么是 StateFlow 而不是回调链
 *
 * 回调要把 lambda 从 :app 一路传进 :feature:chat 的 ViewModel，穿三四层；
 * 而 StateFlow 天然是「最新值」语义 —— 界面错过中间帧也没关系，它只需要显示现在的样子。
 */
class ImageGenProgressBus {

    private val _state = MutableStateFlow<ImageGenProgress?>(null)

    /** null = 当前没有生图在跑。 */
    val state: StateFlow<ImageGenProgress?> = _state.asStateFlow()

    fun publish(progress: ImageGenProgress?) {
        _state.value = progress
    }

    fun clear() {
        _state.value = null
    }
}
