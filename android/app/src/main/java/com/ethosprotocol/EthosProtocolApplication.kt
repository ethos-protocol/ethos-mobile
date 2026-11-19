package com.ethosprotocol

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ethosprotocol.utils.StartupPerformance
import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Request
import javax.inject.Inject

// Multidex (#430): minSdk = 28 (API 28+) means the Android platform handles multiple dex
// files natively. No MultiDexApplication subclass or MultiDex.install() call is needed —
// the legacy androidx.multidex support library is only required for minSdk < 21. Enabling
// multiDexEnabled = true in build.gradle.kts is sufficient; the runtime loader takes care
// of the rest automatically before Application.onCreate() runs.
@HiltAndroidApp
class EthosProtocolApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        StartupPerformance.markAppStart()

        // Configure Sentry event scrubbing and PII controls (#640) before crash
        // reporting is initialized so the hooks apply to every captured event.
        configureSentryScrubbing()

        // Defer non-critical initialization (widget updates) to after first frame
        // to reduce cold-start time (#317). Schedule after ~2 seconds to ensure
        // the app is fully rendered and responsive.
        Handler(Looper.getMainLooper()).postDelayed({
            VaultWidgetUpdateWorker.schedule(this)
        }, 2000)

    }

    /**
     * Applies Sentry scrubbing and PII controls (#640).
     *
     * - `sendDefaultPii = false` is set explicitly so Sentry never attaches
     *   default personally identifiable information (IP address, user data).
     * - `beforeSend` strips query strings from request URLs, removes
     *   `Authorization` headers, and drops request bodies before an event is
     *   sent. Breadcrumbs can carry request URLs with query strings (see #89
     *   and #84), so the same scrubbing is applied to breadcrumb data.
     */
    private fun configureSentryScrubbing() {
        SentryOptions().apply {
            isSendDefaultPii = false
            beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                scrubEvent(event)
                event
            }
            beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                scrubBreadcrumb(breadcrumb)
                breadcrumb
            }
        }
    }

    private fun scrubEvent(event: SentryEvent) {
        event.request?.let { request ->
            request.url = stripQueryString(request.url)
            request.queryString = null
            request.data = null
            request.cookies = null
            request.headers?.let { headers ->
                headers.keys
                    .filter { it.equals("Authorization", ignoreCase = true) }
                    .forEach { headers.remove(it) }
            }
        }
    }

    private fun scrubBreadcrumb(breadcrumb: io.sentry.Breadcrumb) {
        val data = breadcrumb.data ?: return
        data.keys.toList().forEach { key ->
            when {
                key.equals("url", ignoreCase = true) || key.equals("http.query", ignoreCase = true) ->
                    data[key] = stripQueryString(data[key] as? String)
                key.equals("Authorization", ignoreCase = true) ->
                    data.remove(key)
            }
        }
    }

    private fun stripQueryString(url: String?): String? {
        if (url == null) return null
        val queryIndex = url.indexOf('?')
        return if (queryIndex >= 0) url.substring(0, queryIndex) else url
    }

    // #486: React to system memory-pressure signals by dropping caches that are cheap
    // to rebuild (icon/color lookups) rather than letting them hold memory the OS is
    // explicitly asking back for the app not to be killed outright.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.ethosprotocol.utils.AssetCache.clearCache()
        }
    }
}
