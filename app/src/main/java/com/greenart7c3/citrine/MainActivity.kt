package com.greenart7c3.citrine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.greenart7c3.citrine.ui.theme.CitrineTheme


class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->

        }

    private var service: WebSocketServerService? = null
    private var bound = false
    private var port = mutableIntStateOf(0)
    private var isLoading = mutableStateOf(true)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebSocketServerService.LocalBinder
            this@MainActivity.service = binder.getService()
            bound = true
            port.intValue = this@MainActivity.service?.webSocketServer?.port() ?: 0
            this@MainActivity.isLoading.value = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            this@MainActivity.isLoading.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")

        val intent = Intent(this, WebSocketServerService::class.java)
        startService(intent)

        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            CitrineTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isLoading.value) {
                            CircularProgressIndicator()
                        } else {
                            Text("Relay started at")
                            Text("ws://localhost:${port.intValue}")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}