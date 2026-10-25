package dev.praxis.data.attachment

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 朗读助手：把一段文本交给系统 TTS 念出来。
 *
 * ## 为什么用系统 TTS 而不是自带模型
 *
 * 自带模型意味着几十 MB 权重 + 推理运行时；系统 TTS 在几乎所有 Android
 * 设备上已预装（Google TTS / 厂商 TTS），零体积零依赖。音质不如大模型，
 * 但「把回复念出来」这个用途够了。
 *
 * ## 生命周期为什么由调用方管
 *
 * `TextToSpeech` 初始化是**异步**的（要绑定引擎服务），且必须显式
 * `shutdown()`，否则会一直占着音频焦点。挂成 object 单例最容易漏掉
 * shutdown（进程活着就一直占着）。所以这里只管「初始化/朗读/释放」
 * 三件事，由 Composable 用 `DisposableEffect` 管寿命。
 */
class SpeechReader(
    context: Context,
    private val onReady: (Boolean) -> Unit,
) {
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    val isReady: Boolean get() = ready

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                // 中文优先。设失败也不阻断：有些设备只装了英文引擎，
                // 念中文会读成拼音，但至少能出声，比直接失败好。
                runCatching { tts?.language = Locale.SIMPLIFIED_CHINESE }
            }
            ready = ok
            onReady(ok)
        }
    }

    /**
     * 朗读 [text]。
     *
     * 先 stop 再 speak：`QUEUE_FLUSH` 在部分 ROM 上与「刚进入引擎尚未出声」
     * 的上一段有竞态，会两段叠着念。显式 stop 一次能清干净。
     *
     * @return false 表示引擎未就绪或调用失败。
     */
    fun speak(text: String): Boolean {
        if (!ready) return false
        val engine = tts ?: return false
        val body = text.trim()
        if (body.isEmpty()) return false
        return runCatching {
            engine.stop()
            // 长文分段：部分引擎对超长单段会截断。按 4000 字切，
            // 首段 FLUSH、其余 ADD，保证顺序不丢。
            body.chunked(4000).forEachIndexed { index, chunk ->
                val mode = if (index == 0) {
                    TextToSpeech.QUEUE_FLUSH
                } else {
                    TextToSpeech.QUEUE_ADD
                }
                engine.speak(chunk, mode, null, "praxis-" + index)
            }
            true
        }.getOrDefault(false)
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun isSpeaking(): Boolean = runCatching {
        tts?.isSpeaking == true
    }.getOrDefault(false)

    /** 释放引擎。不调会一直占着音频焦点（抢音乐、蓝牙路由异常）。 */
    fun shutdown() {
        ready = false
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    companion object {
        /**
         * 设备是否有可用 TTS 引擎（不需初始化实例的轻量判断）。
         *
         * 用于决定是否显示「自动朗读」开关 —— 没引擎却给开关，
         * 就是本项目反复否掉的「假按钮」。
         */
        fun isAvailable(context: Context): Boolean = runCatching {
            val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
        }.getOrDefault(false)
    }
}
