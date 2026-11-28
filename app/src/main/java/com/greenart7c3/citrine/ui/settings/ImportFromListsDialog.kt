package com.greenart7c3.citrine.ui.settings

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.service.ListImporter
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Picker shown after a successful Amber login. Fetches the logged-in user's Nostr lists (follow,
 * mute, and NIP-51 curations), lets the user expand a list and tick individual pubkeys (showing
 * each user's profile picture and name), and returns the selection via [onConfirm].
 */
@Composable
fun ImportFromListsDialog(
    signerPubkey: String,
    signerPackage: String,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val signer = rememberImportListsSigner(signerPubkey, signerPackage)

    val fetched = remember { FetchedLists() }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(signer) {
        fetched.load(signer)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
        ) {
            Column(Modifier.fillMaxSize()) {
                ImportListsTitle(onDismiss = onDismiss)
                HorizontalDivider()

                when {
                    fetched.loading -> ImportListsLoading()
                    fetched.error -> ImportListsMessage(message = stringResource(R.string.import_lists_error), onDismiss = onDismiss)
                    fetched.lists.isEmpty() -> ImportListsMessage(message = stringResource(R.string.no_lists_found), onDismiss = onDismiss)
                    else -> ImportListsPicker(
                        lists = fetched.lists,
                        profiles = fetched.profiles,
                        expandedId = expandedId,
                        onToggleExpand = { list, expanded -> expandedId = if (expanded) null else list.id },
                        selected = selected,
                        onTogglePubkey = { pubkey ->
                            selected = if (pubkey in selected) selected - pubkey else selected + pubkey
                        },
                    )
                }

                if (!fetched.loading && !fetched.error && fetched.lists.isNotEmpty()) {
                    HorizontalDivider()
                    ImportListsActions(
                        selectedCount = selected.size,
                        onDismiss = onDismiss,
                        onConfirm = { onConfirm(selected) },
                    )
                }
            }
        }
    }
}

private class FetchedLists {
    var loading by mutableStateOf(true)
    var error by mutableStateOf(false)
    var lists by mutableStateOf<List<ListImporter.UserListEntry>>(emptyList())
    var profiles by mutableStateOf<Map<String, ListImporter.ProfileInfo>>(emptyMap())

    suspend fun load(signer: NostrSignerExternal) {
        loading = true
        error = false
        try {
            val result = ListImporter.fetch(signer)
            lists = result.lists
            profiles = result.profiles
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ImportLists", "fetch failed", e)
            error = true
        } finally {
            loading = false
        }
    }
}

@Composable
private fun rememberImportListsSigner(signerPubkey: String, signerPackage: String): NostrSignerExternal {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val signer = remember(signerPubkey, signerPackage) {
        NostrSignerExternal(signerPubkey, signerPackage, contentResolver = context.contentResolver)
    }
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val noSignerMsg = stringResource(R.string.make_sure_the_signer_application_has_authorized_this_transaction)

    val decryptLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(context, signRequestRejectedMsg, Toast.LENGTH_SHORT).show()
        } else {
            result.data?.let { coroutineScope.launch(Dispatchers.IO) { signer.newResponse(it) } }
        }
    }

    LaunchedEffect(signer) {
        signer.registerForegroundLauncher { intent ->
            try {
                decryptLauncher.launch(intent)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("ImportLists", "Error opening Signer app", e)
                coroutineScope.launch {
                    Toast.makeText(
                        context,
                        noSignerMsg,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
    return signer
}

@Composable
private fun ImportListsTitle(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.import_from_lists_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
        }
    }
}

@Composable
private fun ImportListsLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.importing_lists))
    }
}

@Composable
private fun ImportListsMessage(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(12.dp))
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.close))
        }
    }
}

@Composable
private fun ColumnScope.ImportListsPicker(
    lists: List<ListImporter.UserListEntry>,
    profiles: Map<String, ListImporter.ProfileInfo>,
    expandedId: String?,
    onToggleExpand: (ListImporter.UserListEntry, Boolean) -> Unit,
    selected: Set<String>,
    onTogglePubkey: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.select_lists_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(12.dp),
    )
    LazyColumn(modifier = Modifier.weight(1f)) {
        items(lists, key = { it.id }) { list ->
            val expanded = expandedId == list.id
            UserListCard(
                list = list,
                expanded = expanded,
                onToggleExpand = { onToggleExpand(list, expanded) },
                profiles = profiles,
                selected = selected,
                onTogglePubkey = onTogglePubkey,
            )
        }
    }
}

@Composable
private fun UserListCard(
    list: ListImporter.UserListEntry,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    profiles: Map<String, ListImporter.ProfileInfo>,
    selected: Set<String>,
    onTogglePubkey: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = list.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = list.pubkeys.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            HorizontalDivider()
            list.pubkeys.forEach { pubkey ->
                MemberRow(
                    pubkey = pubkey,
                    profile = profiles[pubkey],
                    checked = pubkey in selected,
                    onToggle = { onTogglePubkey(pubkey) },
                )
            }
        }
    }
}

@Composable
private fun ImportListsActions(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.cancel))
        }
        Spacer(Modifier.size(8.dp))
        TextButton(
            enabled = selectedCount > 0,
            onClick = onConfirm,
        ) {
            Text(stringResource(R.string.add_selected, selectedCount))
        }
    }
}

@Composable
private fun MemberRow(
    pubkey: String,
    profile: ListImporter.ProfileInfo?,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(profile?.picture)
        Spacer(Modifier.size(12.dp))
        Text(
            text = profile?.name?.takeIf { it.isNotBlank() } ?: pubkey.toShortenHex(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun UserAvatar(picture: String?) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (picture.isNullOrBlank()) {
            AvatarPlaceholder()
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(picture).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp),
                loading = { AvatarPlaceholder() },
                error = { AvatarPlaceholder() },
            )
        }
    }
}

@Composable
private fun AvatarPlaceholder() {
    Icon(
        imageVector = Icons.Default.Person,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
    )
}
