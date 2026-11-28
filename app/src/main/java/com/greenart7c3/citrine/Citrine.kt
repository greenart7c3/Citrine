package com.greenart7c3.citrine

import android.app.Application
import android.content.Context
import coil3.SingletonImageLoader
import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.relays.AppRelayClient
import com.greenart7c3.citrine.server.EventSubscription
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.server.nip29.GroupManager
import com.greenart7c3.citrine.service.EventCleanup
import com.greenart7c3.citrine.service.EventDownloader
import com.greenart7c3.citrine.service.EventRebroadcaster
import com.greenart7c3.citrine.service.ImageLoaderFactory
import com.greenart7c3.citrine.service.ListImporter
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.NsiteManager
import com.greenart7c3.citrine.service.PokeyListener
import com.greenart7c3.citrine.service.RelayAggregator
import com.greenart7c3.citrine.service.RelayIdentity
import com.greenart7c3.citrine.service.TorManager
import com.greenart7c3.citrine.service.crashreports.CrashReportCache
import com.greenart7c3.citrine.service.crashreports.CrashReportSender
import com.greenart7c3.citrine.service.crashreports.ExitReasons
import com.greenart7c3.citrine.service.crashreports.ReportAssembler
import com.greenart7c3.citrine.service.crashreports.UnexpectedCrashSaver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

class Citrine : Application() {
    val crashReportCache: CrashReportCache by lazy { CrashReportCache(this.applicationContext) }

    val exceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            Log.e("AmberCoroutine", "Caught exception: ${throwable.message}", throwable)
        }
    val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    val relayClient: RelayClient = AppRelayClient(applicationScope)
    val eventCleanup = EventCleanup(applicationScope)

    init {
        EventSubscription.install(applicationScope, exceptionHandler) { error ->
            crashReportCache.appendReport(ReportAssembler(this).buildReport(error))
        }
        EventRebroadcaster.install(relayClient)
        ListImporter.install(relayClient)
        CrashReportSender.install(relayClient, applicationScope)
        EventDownloader.install(this, relayClient, applicationScope)
        RelayIdentity.install(this)
        GroupManager.install(this, applicationScope)
        NsiteManager.install(this, relayClient)
        TorManager.install(this, applicationScope)
        RelayAggregator.install(this, relayClient, exceptionHandler)
    }

    val pokeyListener = PokeyListener(this)

    override fun onCreate() {
        super.onCreate()

        Log.init(this)

        Thread.setDefaultUncaughtExceptionHandler(UnexpectedCrashSaver(crashReportCache))
        SingletonImageLoader.setSafe(ImageLoaderFactory())

        // Record why the previous process died (ANR, native crash, excessive resource usage) while
        // it can still be read, and leave it in the in-app log as well as the crash dialog.
        applicationScope.launch(Dispatchers.IO) {
            try {
                ExitReasons.detect(this@Citrine)
            } catch (e: Exception) {
                Log.e(TAG, "Could not read previous exit reasons", e)
            }
        }

        relayClient.client.connect()

        LocalPreferences.loadSettingsFromEncryptedStorage(this)
        if (Settings.listenToPokeyBroadcasts) {
            pokeyListener.register()
        }
    }

    companion object {

        @Volatile
        var job: Job? = null

        @Volatile
        var isImportingEvents = false

        // True on startup so the first maintenance tick clears any stale progress
        // notification left behind by a prior crash; set whenever notification id 2 is
        // posted and cleared once cancelled, avoiding redundant Binder IPCs when idle.
        @Volatile
        var hasProgressNotification = true

        const val TAG = "Citrine"

        fun cancelJob() {
            job?.cancelChildren()
            job?.cancel()
        }
    }
}

val Context.citrine: Citrine get() = applicationContext as Citrine
