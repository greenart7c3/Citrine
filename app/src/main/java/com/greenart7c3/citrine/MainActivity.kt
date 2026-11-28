package com.greenart7c3.citrine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.greenart7c3.citrine.service.RelayAggregator
import com.greenart7c3.citrine.service.RelayIdentity
import com.greenart7c3.citrine.service.crashreports.CrashReportScreen
import com.greenart7c3.citrine.ui.CitrineScaffold
import com.greenart7c3.citrine.ui.StoragePickerHelper
import com.greenart7c3.citrine.ui.appDependencies
import com.greenart7c3.citrine.ui.theme.CitrineTheme

class MainActivity : ComponentActivity() {
    private val storageHelper = StoragePickerHelper.create(this@MainActivity)

    private val aggregatorSignerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.let { RelayAggregator.deliverSignerResponse(it) }
    }

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
            CompositionLocalProvider(*appDependencies(citrine)) {
                CitrineTheme {
                    Box(Modifier.fillMaxSize()) {
                        CitrineScaffold(
                            storageHelper = storageHelper,
                        )
                        CrashReportScreen()
                    }
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
