package com.greenart7c3.citrine.ui

import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.service.PokeyListener
import com.greenart7c3.citrine.service.crashreports.CrashReportCache
import kotlinx.coroutines.CoroutineScope

/** The outbound relay connections. */
val LocalRelayClient = staticCompositionLocalOf<RelayClient> { error("No RelayClient provided") }

/** For work that must outlive the screen that starts it (imports, downloads, rebroadcasts). */
val LocalAppScope = staticCompositionLocalOf<CoroutineScope> { error("No app scope provided") }

/** Turns listening to Pokey's broadcasts on and off. */
val LocalPokeyListener = staticCompositionLocalOf<PokeyListener> { error("No PokeyListener provided") }

/** The crash report waiting for the user, read by the crash dialog. */
val LocalCrashReportCache = staticCompositionLocalOf<CrashReportCache> { error("No CrashReportCache provided") }

fun appDependencies(citrine: Citrine): Array<ProvidedValue<*>> = arrayOf(
    LocalRelayClient provides citrine.relayClient,
    LocalAppScope provides citrine.applicationScope,
    LocalPokeyListener provides citrine.pokeyListener,
    LocalCrashReportCache provides citrine.crashReportCache,
)
