package dev.praxis

import android.app.Application
import dev.praxis.di.appModule
import dev.praxis.di.networkModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

class PraxisApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        startKoin {
            androidContext(this@PraxisApplication)
            modules(appModule, networkModule)
        }

        Timber.i("Praxis started (domain model: Operit-compatible), baseUrl=%s", BuildConfig.DEFAULT_BASE_URL)
    }
}