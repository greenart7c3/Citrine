package com.greenart7c3.citrine

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.anggrayudi.storage.SimpleStorageHelper
import com.greenart7c3.citrine.service.RelayAggregator
import com.greenart7c3.citrine.ui.CitrineScaffold
import com.greenart7c3.citrine.ui.theme.CitrineTheme

class MainActivity : ComponentActivity() {
    private val storageHelper = SimpleStorageHelper(this@MainActivity)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Amber returns AUTH-signed events via a fresh Intent when launched from the
        // aggregator's application-context launcher; route it back so the suspended
        // sign() call resumes.
        intent?.let { RelayAggregator.deliverSignerResponse(it) }

        setContent {
            CitrineTheme {
                CitrineScaffold(
                    storageHelper = storageHelper,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        RelayAggregator.deliverSignerResponse(intent)
    }
}
