package com.greenart7c3.citrine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.anggrayudi.storage.SimpleStorageHelper
import com.greenart7c3.citrine.service.RelayAggregator
import com.greenart7c3.citrine.service.RelayIdentity
import com.greenart7c3.citrine.service.crashreports.CrashReportScreen
import com.greenart7c3.citrine.ui.CitrineScaffold
import com.greenart7c3.citrine.ui.theme.CitrineTheme

class MainActivity : ComponentActivity() {
    private val storageHelper = SimpleStorageHelper(this@MainActivity)

    // Launcher used by the aggregator's foreground signer path. Going through
    // registerForActivityResult preserves the calling package on the intent — startActivity
    // alone would leave Amber reading a null callingPackage and rejecting the request.
    private val aggregatorSignerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.let { RelayAggregator.deliverSignerResponse(it) }
    }

    // Same mechanism for the relay identity signer (NIP-29 group metadata signing).
    private val relaySignerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.let { RelayIdentity.deliverSignerResponse(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        RelayAggregator.registerActivityLauncher { intent ->
            aggregatorSignerLauncher.launch(intent)
        }

        RelayIdentity.registerActivityLauncher { intent ->
            relaySignerLauncher.launch(intent)
        }

        setContent {
            CitrineTheme {
                Box(Modifier.fillMaxSize()) {
                    CitrineScaffold(
                        storageHelper = storageHelper,
                    )
                    // A report left by a previous run takes over the whole window, above the bars.
                    CrashReportScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        RelayIdentity.unregisterActivityLauncher()
        RelayAggregator.unregisterActivityLauncher()
        super.onDestroy()
    }
}
