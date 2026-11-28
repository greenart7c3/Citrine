package com.greenart7c3.citrine.ui

import android.app.Activity
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.utils.toDateString
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndConfirm
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ContactsScreen(
    modifier: Modifier,
    pubKey: String,
    packageName: String,
    navController: NavController,
    viewModel: ContactsViewModel = viewModel(factory = ContactsViewModel.factory(LocalContext.current.applicationContext)),
) {
    var loading by remember {
        mutableStateOf(true)
    }
    val context = LocalContext.current
    val relayClient = LocalRelayClient.current
    val coroutineScope = rememberCoroutineScope()

    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val ensureSignerAuthorizedMsg = stringResource(R.string.make_sure_the_signer_application_has_authorized_this_transaction)
    val noRelaysFoundMsg = stringResource(R.string.no_relays_found)

    val signer = NostrSignerExternal(
        pubKey,
        packageName,
        contentResolver = context.contentResolver,
    )
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                Toast.makeText(
                    context,
                    signRequestRejectedMsg,
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                result.data?.let {
                    coroutineScope.launch(Dispatchers.IO) {
                        signer.newResponse(it)
                    }
                }
            }
        },
    )
    signer.registerForegroundLauncher {
        try {
            launcher.launch(it)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("Signer", "Error opening Signer app", e)
            coroutineScope.launch {
                Toast.makeText(
                    context,
                    ensureSignerAuthorizedMsg,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val events = remember { mutableListOf<ContactListEvent>() }
    var outboxRelays by remember { mutableStateOf<AdvertisedRelayListEvent?>(null) }
    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            loading = true
            for (contactListEvent in viewModel.contactLists(pubKey)) {
                Log.d("ContactsScreen", "ContactListEvent: $contactListEvent")
                events.add(contactListEvent)
            }
            outboxRelays = viewModel.outboxRelays(pubKey)

            loading = false
        }
    }

    if (loading) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
    } else {
        Column(
            modifier = modifier
                .background(MaterialTheme.colorScheme.background)
                .fillMaxSize(),
        ) {
            if (events.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.no_follow_list_found),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
            ) {
                items(events.size) {
                    val event = events[it]
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                    ) {
                        Text(
                            "Date: ${event.createdAt.toDateString()}",
                            modifier = Modifier.padding(
                                start = 6.dp,
                                end = 6.dp,
                                top = 6.dp,
                            ),
                        )
                        Text(
                            "Following: ${event.verifiedFollowKeySet().size}",
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                        Text(
                            "Relays: ${event.relays().keys.size}",
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            ElevatedButton(
                                onClick = {
                                    val relays = event.relays()
                                    if (relays.isNullOrEmpty() && outboxRelays == null) {
                                        coroutineScope.launch {
                                            Toast.makeText(
                                                context,
                                                noRelaysFoundMsg,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        return@ElevatedButton
                                    }

                                    coroutineScope.launch(Dispatchers.IO) {
                                        val signedEvent = ContactListEvent.create(
                                            event.content,
                                            event.tags,
                                            signer,
                                        )
                                        val outbox = outboxRelays?.writeRelays()?.map { relay ->
                                            NormalizedRelayUrl(
                                                url = relay,
                                            )
                                        }
                                        val localRelays = outbox ?: relays.mapNotNull { relay ->
                                            if (relay.value.write) {
                                                relay.key
                                            } else {
                                                null
                                            }
                                        }

                                        loading = true

                                        delay(1000)
                                        relayClient.client.publishAndConfirm(signedEvent, relayList = localRelays.toSet())
                                        CustomWebSocketService.server?.innerProcessEvent(signedEvent, null)
                                        relayClient.client.disconnect()
                                        loading = false
                                        coroutineScope.launch(Dispatchers.Main) {
                                            navController.navigateUp()
                                        }
                                    }
                                },
                                modifier = Modifier.padding(
                                    start = 6.dp,
                                    end = 6.dp,
                                    bottom = 6.dp,
                                ),
                            ) {
                                Text(stringResource(R.string.restore))
                            }
                        }
                    }
                }
            }
        }
    }
}
