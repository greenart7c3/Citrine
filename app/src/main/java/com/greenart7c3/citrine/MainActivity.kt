package com.greenart7c3.citrine

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.greenart7c3.citrine.ui.dialogs.ContactsDialog
import com.greenart7c3.citrine.ui.theme.CitrineTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            val intent = Intent(this, WebSocketServerService::class.java)
            startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

    private var service: WebSocketServerService? = null
    private var bound = false
    private var isLoading = mutableStateOf(true)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebSocketServerService.LocalBinder
            this@MainActivity.service = binder.getService()
            bound = true
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

        setContent {
            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current
            var pubKey by remember {
                mutableStateOf("")
            }

            val launcherLogin = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult(),
                onResult = { result ->
                    if (result.resultCode != Activity.RESULT_OK) {
                        Toast.makeText(
                            context,
                            "Sign request rejected",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        result.data?.let {
                            pubKey = it.getStringExtra("signature") ?: ""
                        }
                    }
                }
            )

            CitrineTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (pubKey.isNotBlank()) {
                        ContactsDialog(pubKey = pubKey) {
                            pubKey = ""
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isLoading.value) {
                            CircularProgressIndicator()
                        } else {
                            val isStarted = service?.webSocketServer?.server != null
                            if (isStarted) {
                                Text("Relay started at")
                                Text("ws://localhost:${service?.webSocketServer?.port() ?: 0}")
                                ElevatedButton(
                                    onClick = {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            isLoading.value = true
                                            stop()
                                            delay(1000)
                                            isLoading.value = false
                                        }
                                    }
                                ) {
                                    if (service?.webSocketServer?.server != null) {
                                        Text("Stop")
                                    } else {
                                        Text("Start")
                                    }
                                }
                            } else {
                                Text("Relay not running")
                                ElevatedButton(
                                    onClick = {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            isLoading.value = true
                                            start()
                                            delay(1000)
                                            isLoading.value = false
                                        }
                                    }
                                ) {
                                    if (service?.webSocketServer?.server != null) {
                                        Text("Stop")
                                    } else {
                                        Text("Start")
                                    }
                                }
                            }

                            ElevatedButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
                                        val signerType = "get_public_key"
                                        intent.putExtra("type", signerType)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        launcherLogin.launch(intent)
                                    } catch (e: Exception) {
                                        Log.d("intent", e.message ?: "", e)
                                        coroutineScope.launch(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                "No external signer installed",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            ) {
                                Text("Contact Lists")
                            }
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

    private fun stop() {
        val intent = Intent(applicationContext, WebSocketServerService::class.java)
        stopService(intent)
        unbindService(connection)
        bound = false
        service = null
    }

    private fun start() {
        val intent = Intent(applicationContext, WebSocketServerService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
}
