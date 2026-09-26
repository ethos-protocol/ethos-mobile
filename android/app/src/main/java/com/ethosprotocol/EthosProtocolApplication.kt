package com.ethosprotocol

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ethosprotocol.utils.AppVersionUpdateChecker
import com.ethosprotocol.utils.StartupPerformance
import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class EthosProtocolApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var appVersionUpdateChecker: AppVersionUpdateChecker

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        StartupPerformance.markAppStart()

        // Defer non-critical initialization (widget updates) to after first frame
        // to reduce cold-start time (#317). Schedule after ~2 seconds to ensure
        // the app is fully rendered and responsive.
        Handler(Looper.getMainLooper()).postDelayed({
            VaultWidgetUpdateWorker.schedule(this)
        }, 2000)

        // Check for new app versions on startup (#424). Deferred so the version
        // check never blocks cold start; the checker compares the installed
        // version against the latest store version and surfaces an update prompt
        // (with a store link, and a forced update for critical releases).
        Handler(Looper.getMainLooper()).postDelayed({
            appVersionUpdateChecker.checkForUpdates()
        }, 3000)
    }
}
