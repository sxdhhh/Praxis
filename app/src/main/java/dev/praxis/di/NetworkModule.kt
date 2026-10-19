package dev.praxis.di

import dev.praxis.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * 网络层单例。
 *
 * Phase 1 还没有真实请求，这里先把 OkHttpClient 的「全局约定」定下来，
 * 免得 Phase 3 每个 provider 各配一套超时：
 *  - connect 15s：移动网络抖动下的经验值，再长用户会以为卡死。
 *  - read/write 各 120s：流式响应可能长时间没有新 chunk，但远小于它就该断。
 *  - 日志拦截器只在 debug 挂载，且用 BASIC（不打 body）——
 *    会话正文属于用户内容，落 logcat 就等于写进别人能读的地方。
 *  - 不主动设 proxy / 证书，交给系统默认，避免和用户在系统里的代理设置打架。
 */
val networkModule = module {

    single {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                },
            )
        }

        builder.build()
    }
}