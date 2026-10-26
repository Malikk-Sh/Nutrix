package com.nutrix.app

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nutrix.app.core.time.AppLocale
import com.nutrix.app.backup.RestoreRecovery
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NutrixApp : Application(), Configuration.Provider {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        RestoreRecovery.recover(base)
    }

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var startup: AppStartup

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        AppLocale.init(getString(R.string.locale_tag))
        startup.onAppCreate()
    }
}
