package com.greenart7c3.citrine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.anggrayudi.storage.SimpleStorageHelper
import com.greenart7c3.citrine.ui.CitrineScaffold
import com.greenart7c3.citrine.ui.theme.CitrineTheme

class MainActivity : ComponentActivity() {
    private val storageHelper = SimpleStorageHelper(this@MainActivity)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CitrineTheme {
                CitrineScaffold(
                    storageHelper = storageHelper,
                )
            }
        }
    }
}
