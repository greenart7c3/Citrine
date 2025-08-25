package com.greenart7c3.citrine.ui

import androidx.compose.runtime.Composable

@Composable
fun DownloadYourEventsUserScreen() {
}

// @OptIn(ExperimentalMaterial3Api::class)
// @Composable
// fun SelectRelayModal(
//    signer: NostrSigner,
//    onDone: (List<String>) -> Unit,
//    onDismiss: () -> Unit,
// ) {
//    var relayText by remember { mutableStateOf(TextFieldValue()) }
//    var relays = remember { mutableListOf<String>() }
//    val sheetState = rememberModalBottomSheetState(
//        skipPartiallyExpanded = true,
//    )
//    var loading by remember { mutableStateOf(false) }
//
//    LaunchedEffect(Unit) {
//        launch(Dispatchers.IO) {
//            loading = true
//            try {
//                val database = AppDatabase.getDatabase(Citrine.getInstance())
//                var contactList = database.eventDao().getContactList(signer.pubKey)?.toEvent() as ContactListEvent?
//                if (contactList == null) {
//                    contactList = EventDownloader.fetchContactList(
//                        signer = signer,
//                    )
//                }
//                contactList?.let {
//                    val contactListRelays = it.relays()
//                    contactListRelays?.forEach {
//                        val formattedUrl = RelayUrlFormatter.normalizeOrNull(it.key)
//                        if (formattedUrl == null) return@forEach
//                        if (!relays.contains(formattedUrl)) relays.add(formattedUrl)
//                    }
//                }
//                var advertisedRelayList = database.eventDao().getAdvertisedRelayList(signer.pubKey)?.toEvent() as AdvertisedRelayListEvent?
//                if (advertisedRelayList == null) {
//                    advertisedRelayList = EventDownloader.fetchAdvertisedRelayList(
//                        signer = signer,
//                    )
//                }
//                advertisedRelayList?.let {
//                    it.relays().forEach {
//                        val formattedUrl = RelayUrlFormatter.normalizeOrNull(it.relayUrl)
//                        if (formattedUrl == null) return@forEach
//                        if (!relays.contains(formattedUrl)) relays.add(formattedUrl)
//                    }
//                }
//            } finally {
//                loading = false
//            }
//        }
//    }
//
//    val scope = rememberCoroutineScope()
//    ModalBottomSheet(
//        modifier = Modifier
//            .fillMaxSize(),
//        content = {
//            val keyboardController = LocalSoftwareKeyboardController.current
//            Column(
//                Modifier
//                    .fillMaxSize()
//                    .padding(16.dp),
//            ) {
//                if (loading) {
//                    Box(
//                        modifier = Modifier.fillMaxSize(),
//                        contentAlignment = Alignment.Center,
//                    ) {
//                        CircularProgressIndicator()
//                    }
//                } else {
//                    OutlinedTextField(
//                        keyboardOptions = KeyboardOptions.Default.copy(
//                            autoCorrectEnabled = false,
//                            imeAction = ImeAction.Done,
//                            capitalization = KeyboardCapitalization.None,
//                        ),
//                        keyboardActions = KeyboardActions(
//                            onDone = {
//                                if (relayText.text.isBlank()) return@KeyboardActions
//                                val url = RelayUrlFormatter.normalizeOrNull(relayText.text)
//                                if (url == null) return@KeyboardActions
//                                if (relays.contains(url)) {
//                                    return@KeyboardActions
//                                }
//
//                                loading = true
//                                relays.add(url)
//                                relayText = TextFieldValue()
//                                keyboardController?.hide()
//                                loading = false
//                            },
//                        ),
//                        modifier = Modifier.fillMaxWidth(),
//                        value = relayText,
//                        onValueChange = {
//                            relayText = it
//                        },
//                        label = {
//                            Text(stringResource(R.string.wss))
//                        },
//                    )
//                    LazyColumn(
//                        modifier = Modifier
//                            .fillMaxWidth()
//                            .fillMaxHeight(0.9f)
//                            .padding(top = 4.dp),
//                    ) {
//                        items(relays) {
//                            RelayCard(
//                                relay = it,
//                                modifier = Modifier.fillMaxWidth(),
//                                onClick = {
//                                    loading = true
//                                    relays.removeIf { url -> url == it }
//                                    loading = false
//                                },
//                            )
//                        }
//                    }
//                    ElevatedButton(
//                        modifier = Modifier
//                            .fillMaxWidth()
//                            .padding(top = 4.dp, bottom = 4.dp),
//                        content = {
//                            Text(stringResource(R.string.fetch_events))
//                        },
//                        onClick = {
//                            // TODO: find out why nostr.band keeps downloading events forever
//                            onDone(relays.filter { !it.contains("localhost") && !it.contains("127.0.0.1") })
//                        },
//                    )
//                }
//            }
//        },
//        sheetState = sheetState,
//        onDismissRequest = {
//            scope.launch {
//                sheetState.hide()
//            }
//            onDismiss()
//        },
//    )
// }
//
// @Composable
// fun DownloadYourEventsUserScreen(
//    modifier: Modifier,
//    navController: NavController,
// ) {
//    val scope = rememberCoroutineScope()
//    val context = LocalContext.current
//    var shouldShowDialog by remember { mutableStateOf(false) }
//    var npub by remember { mutableStateOf(TextFieldValue()) }
//    var signer by remember { mutableStateOf<NostrSigner?>(null) }
//
//    val launcherLogin = rememberLauncherForActivityResult(
//        contract = ActivityResultContracts.StartActivityForResult(),
//        onResult = { result ->
//            if (result.resultCode != RESULT_OK) {
//                Toast.makeText(
//                    context,
//                    context.getString(R.string.sign_request_rejected),
//                    Toast.LENGTH_SHORT,
//                ).show()
//            } else {
//                result.data?.let {
//                    try {
//                        val key = it.getStringExtra("signature") ?: ""
//                        val packageName = it.getStringExtra("package") ?: ""
//
//                        val returnedKey = if (key.startsWith("npub")) {
//                            when (val parsed = Nip19Parser.uriToRoute(key)?.entity) {
//                                is NPub -> parsed.hex
//                                else -> ""
//                            }
//                        } else {
//                            key
//                        }
//
//                        val localNpub = Hex.decode(returnedKey).toNpub()
//
//                        signer = NostrSignerExternal(
//                            returnedKey,
//                            ExternalSignerLauncher(returnedKey, packageName),
//                        )
//                        (signer as NostrSignerExternal).launcher.registerLauncher(
//                            contentResolver = Citrine.getInstance()::contentResolverFn,
//                            launcher = { intent ->
//                            },
//                        )
//                        npub = TextFieldValue(localNpub)
//                    } catch (e: Exception) {
//                        Log.d(Citrine.TAG, e.message ?: "", e)
//                    }
//                }
//            }
//        },
//    )
//
//    if (shouldShowDialog) {
//        SelectRelayModal(
//            signer = signer!!,
//            onDone = {
//                shouldShowDialog = false
//
//                Citrine.isImportingEvents = true
//                Citrine.getInstance().cancelJob()
//                Citrine.job = Citrine.getInstance().applicationScope.launch {
//                    EventDownloader.setProgress("Connecting to ${it.size} relays")
//                    Citrine.getInstance().client.reconnect(
//                        relays = it.map {
//                            RelaySetupInfoToConnect(
//                                url = it,
//                                read = true,
//                                write = false,
//                                forceProxy = false,
//                                feedTypes = COMMON_FEED_TYPES,
//                            )
//                        }.toTypedArray(),
//                    )
//                    delay(5000)
//
//                    EventDownloader.fetchEvents(
//                        signer = signer!!,
//                        scope = Citrine.getInstance().applicationScope,
//                        onAuth = { relay, challenge, subId ->
//                            RelayAuthEvent.create(
//                                relay.url,
//                                challenge,
//                                if (signer is NostrSignerExternal) signer!! else NostrSignerInternal(KeyPair()),
//                                onReady = {
//                                    Citrine.getInstance().applicationScope.launch {
//                                        relay.send(it)
//                                        delay(2000)
//
//                                        val filters = relay.relaySubFilter.subs.allSubscriptions()[subId] ?: listOf(
//                                            TypedFilter(
//                                                types = COMMON_FEED_TYPES,
//                                                filter = SincePerRelayFilter(
//                                                    authors = listOf(signer!!.pubKey),
//                                                    until = TimeUtils.now(),
//                                                    limit = LIMIT,
//                                                ),
//                                            ),
//                                        )
//
//                                        relay.sendFilter(subId, filters)
//                                    }
//                                },
//                            )
//                        },
//                    )
//                }
//                navController.navigateUp()
//            },
//            onDismiss = {
//                shouldShowDialog = false
//            },
//        )
//    }
//
//    Column(
//        modifier,
//        horizontalAlignment = Alignment.CenterHorizontally,
//        verticalArrangement = Arrangement.spacedBy(8.dp),
//    ) {
//        OutlinedTextField(
//            modifier = Modifier.fillMaxWidth(),
//            value = npub,
//            onValueChange = {
//                npub = it
//            },
//            label = {
//                Text(stringResource(R.string.npub1))
//            },
//        )
//        ElevatedButton(
//            content = {
//                Text(stringResource(R.string.login_with_external_signer))
//            },
//            onClick = {
//                try {
//                    val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
//                    val signerType = "get_public_key"
//                    intent.putExtra("type", signerType)
//
//                    val permissions =
//                        listOf(
//                            Permission(
//                                "sign_event",
//                                22242,
//                            ),
//                        )
//                    val jsonArray = StringBuilder("[")
//                    permissions.forEachIndexed { index, permission ->
//                        jsonArray.append(permission.toJson())
//                        if (index < permissions.size - 1) {
//                            jsonArray.append(",")
//                        }
//                    }
//                    jsonArray.append("]")
//
//                    intent.putExtra("permissions", jsonArray.toString())
//                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
//                    launcherLogin.launch(intent)
//                } catch (e: Exception) {
//                    Log.d(Citrine.TAG, e.message ?: "", e)
//                    scope.launch(Dispatchers.Main) {
//                        Toast.makeText(
//                            context,
//                            context.getString(R.string.no_external_signer_installed),
//                            Toast.LENGTH_SHORT,
//                        ).show()
//                    }
//                    val intent = Intent(Intent.ACTION_VIEW, "https://github.com/greenart7c3/Amber/releases".toUri())
//                    launcherLogin.launch(intent)
//                }
//            },
//        )
//        ElevatedButton(
//            content = {
//                Text(stringResource(R.string.fetch_events))
//            },
//            onClick = {
//                if (signer == null && npub.text.isNotBlank()) {
//                    val entity = Nip19Parser.uriToRoute(npub.text)
//
//                    if (entity == null) {
//                        Toast.makeText(
//                            context,
//                            "Invalid account",
//                            Toast.LENGTH_SHORT,
//                        ).show()
//                        return@ElevatedButton
//                    }
//
//                    if (entity.entity !is NPub) {
//                        Toast.makeText(
//                            context,
//                            "Invalid account",
//                            Toast.LENGTH_SHORT,
//                        ).show()
//                        return@ElevatedButton
//                    }
//
//                    signer = NostrSignerInternal(
//                        KeyPair(
//                            pubKey = Hex.decode((entity.entity as NPub).hex),
//                        ),
//                    )
//                }
//
//                if (signer == null) {
//                    Toast.makeText(
//                        context,
//                        "Invalid account",
//                        Toast.LENGTH_SHORT,
//                    ).show()
//                    return@ElevatedButton
//                }
//
//                shouldShowDialog = true
//            },
//        )
//    }
// }
//
// @Composable
// fun RelayCard(
//    modifier: Modifier = Modifier,
//    relay: String,
//    onClick: () -> Unit,
// ) {
//    Card(
//        modifier
//            .fillMaxWidth()
//            .padding(vertical = 4.dp),
//        border = BorderStroke(1.dp, Color.LightGray),
//        colors = CardDefaults.cardColors().copy(
//            containerColor = MaterialTheme.colorScheme.background,
//        ),
//    ) {
//        Row(
//            Modifier
//                .fillMaxWidth(),
//            verticalAlignment = Alignment.CenterVertically,
//            horizontalArrangement = Arrangement.Center,
//        ) {
//            Text(
//                relay,
//                Modifier
//                    .weight(0.9f)
//                    .padding(8.dp)
//                    .padding(start = 8.dp),
//                maxLines = 1,
//                overflow = TextOverflow.Ellipsis,
//            )
//            IconButton(
//                onClick = onClick,
//            ) {
//                Icon(
//                    Icons.Default.Delete,
//                    stringResource(R.string.delete),
//                )
//            }
//        }
//    }
// }
