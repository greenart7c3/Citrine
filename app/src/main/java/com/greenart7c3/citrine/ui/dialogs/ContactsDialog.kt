package com.greenart7c3.citrine.ui.dialogs

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.relays.Relay
import com.greenart7c3.citrine.toDateString
import com.greenart7c3.citrine.ui.CloseButton
import com.vitorpamplona.quartz.encoders.bechToBytes
import com.vitorpamplona.quartz.encoders.toHexKey
import com.vitorpamplona.quartz.events.ContactListEvent
import com.vitorpamplona.quartz.signers.ExternalSignerLauncher
import com.vitorpamplona.quartz.signers.NostrSignerExternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun ContactsDialog(pubKey: String, onClose: () -> Unit) {
    var loading by remember {
        mutableStateOf(true)
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val signer = NostrSignerExternal(
        pubKey.bechToBytes().toHexKey(),
        ExternalSignerLauncher(pubKey, "")
    )
    val launcher = rememberLauncherForActivityResult(
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
                    coroutineScope.launch(Dispatchers.IO) {
                        signer.launcher.newResult(it)
                    }
                }
            }
        }
    )
    signer.launcher.registerLauncher(
        launcher = {
            try {
                launcher.launch(it)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("Signer", "Error opening Signer app", e)
                coroutineScope.launch {
                    Toast.makeText(
                        context,
                        "Make sure the signer application has authorized this transaction",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        },
        contentResolver = { context.contentResolver }
    )

    val events = mutableListOf<ContactListEvent>()
    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            loading = true
            val dataBaseEvents = AppDatabase.getDatabase(context).eventDao().getContactLists(pubKey.bechToBytes().toHexKey())
            dataBaseEvents.forEach {
                events.add(it.toEvent() as ContactListEvent)
            }
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            Modifier.fillMaxSize()
        ) {
            if (loading) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .fillMaxSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CloseButton(
                            onCancel = onClose
                        )
                    }

                    LazyColumn(
                        Modifier.fillMaxSize()
                    ) {
                        items(events.size) {
                            val event = events[it]
                            Card(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp)
                            ) {
                                Text(
                                    "Date: ${event.createdAt.toDateString()}",
                                    modifier = Modifier.padding(
                                        start = 6.dp,
                                        end = 6.dp,
                                        top = 6.dp
                                    )
                                )
                                Text(
                                    "Following: ${event.verifiedFollowKeySet.size}",
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                                Text(
                                    "Communities: ${event.verifiedFollowCommunitySet.size}",
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                                Text(
                                    "Hashtags: ${event.verifiedFollowTagSet.size}",
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                                Text(
                                    "Relays: ${event.relays()?.keys?.size ?: 0}",
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    ElevatedButton(
                                        onClick = {
                                            val relays = event.relays()
                                            if (relays.isNullOrEmpty()) {
                                                coroutineScope.launch {
                                                    Toast.makeText(
                                                        context,
                                                        "No relays found",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                return@ElevatedButton
                                            }

                                            ContactListEvent.create(
                                                event.content,
                                                event.tags,
                                                signer
                                            ) { signedEvent ->
                                                relays.forEach { relay ->
                                                    if (relay.value.write) {
                                                        val mRelay = Relay(relay.key)
                                                        mRelay.connectAndRun { localRelay ->
                                                            localRelay.send(signedEvent)
                                                            coroutineScope.launch(Dispatchers.IO) {
                                                                delay(1000)
                                                                localRelay.disconnect()
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.padding(
                                            start = 6.dp,
                                            end = 6.dp,
                                            bottom = 6.dp
                                        )
                                    ) {
                                        Text("Restore")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
