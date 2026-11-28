package com.greenart7c3.citrine.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.NsiteManager
import com.greenart7c3.citrine.ui.components.NsiteIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class InstallUiState(
    val address: String,
    val downloaded: Int,
    val total: Int,
)

@Composable
fun BrowseNsitesScreen(
    modifier: Modifier = Modifier,
    viewModel: BrowseNsitesViewModel = viewModel(factory = BrowseNsitesViewModel.factory()),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by NsiteManager.discoveryState.collectAsState()
    var installing by remember { mutableStateOf<InstallUiState?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            NsiteManager.discover()
        }
    }

    val onInstall: (NsiteManager.DiscoveredNsite, String, String) -> Unit = { nsite, nsiteInstalledMsg, nsiteInstallFailedMsg ->
        if (!nsite.alreadyInstalled && installing == null) {
            installing = InstallUiState(nsite.address, 0, 0)
            scope.launch(Dispatchers.IO) {
                val result = NsiteManager.install(nsite) { downloaded, total ->
                    installing = InstallUiState(nsite.address, downloaded, total)
                }
                withContext(Dispatchers.Main) {
                    installing = null
                    val message = if (result.isSuccess) {
                        nsiteInstalledMsg
                    } else {
                        nsiteInstallFailedMsg
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                // Refresh the list so the installed item is now flagged.
                NsiteManager.discover()
            }
        }
    }

    Surface(modifier) {
        when (val current = state) {
            is NsiteManager.DiscoveryState.Loading, NsiteManager.DiscoveryState.Idle -> DiscoveringNsites()
            is NsiteManager.DiscoveryState.Error -> CenteredMessage(current.message)
            is NsiteManager.DiscoveryState.Loaded -> {
                if (current.nsites.isEmpty()) {
                    CenteredMessage(stringResource(R.string.no_nsites_found))
                } else {
                    NsiteSearchList(
                        searchQuery = viewModel.searchQuery,
                        onSearchQueryChange = { viewModel.searchQuery = it },
                        nsites = viewModel.matching(current.nsites),
                        installing = installing,
                        onInstall = onInstall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveringNsites() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                stringResource(R.string.discovering_nsites),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message)
    }
}

@Composable
private fun NsiteSearchList(
    searchQuery: TextFieldValue,
    onSearchQueryChange: (TextFieldValue) -> Unit,
    nsites: List<NsiteManager.DiscoveredNsite>,
    installing: InstallUiState?,
    onInstall: (NsiteManager.DiscoveredNsite, installedMessage: String, failedMessage: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.search_nsites)) },
        )
        if (nsites.isEmpty()) {
            CenteredMessage(stringResource(R.string.no_nsites_found))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(nsites) { nsite ->
                    val nsiteInstalledMsg = stringResource(R.string.nsite_installed, nsite.displayName)
                    val nsiteInstallFailedMsg = stringResource(R.string.nsite_install_failed, nsite.displayName)
                    NsiteAuthorHeader(nsite)
                    NsiteCard(
                        nsite = nsite,
                        rowInstalling = installing?.takeIf { it.address == nsite.address },
                        installEnabled = installing == null,
                        onInstall = { onInstall(nsite, nsiteInstalledMsg, nsiteInstallFailedMsg) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NsiteAuthorHeader(nsite: NsiteManager.DiscoveredNsite) {
    val authorLabel = nsite.authorName.ifBlank {
        if (nsite.pubkey.length > 12) "${nsite.pubkey.take(8)}…${nsite.pubkey.takeLast(4)}" else nsite.pubkey
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuthorAvatar(
            pictureUrl = nsite.authorPicture.ifBlank { null },
            authorName = authorLabel,
        )
        Text(
            authorLabel,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "· ${relativeTimeShort(nsite.lastUpdated)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NsiteCard(
    nsite: NsiteManager.DiscoveredNsite,
    rowInstalling: InstallUiState?,
    installEnabled: Boolean,
    onInstall: () -> Unit,
) {
    var expanded by remember(nsite.address) { mutableStateOf(false) }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NsiteIcon(
                    model = nsite.iconUrl,
                    monogram = nsite.displayName.firstOrNull()?.toString(),
                    size = 48.dp,
                )
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        stringResource(R.string.nsite_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        nsite.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                NsiteAction(
                    nsite = nsite,
                    rowInstalling = rowInstalling,
                    installEnabled = installEnabled,
                    onInstall = onInstall,
                )
            }
            if (nsite.description.isNotBlank()) {
                Text(
                    nsite.description,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            NsiteDetailsToggle(expanded = expanded, onToggle = { expanded = !expanded })
            AnimatedVisibility(visible = expanded) {
                NsiteDetails(nsite = nsite, rowInstalling = rowInstalling)
            }
        }
    }
}

@Composable
private fun NsiteAction(
    nsite: NsiteManager.DiscoveredNsite,
    rowInstalling: InstallUiState?,
    installEnabled: Boolean,
    onInstall: () -> Unit,
) {
    when {
        rowInstalling != null -> InstallProgress(rowInstalling)
        nsite.alreadyInstalled -> InstalledNsiteActions(nsite)
        else -> {
            FilledTonalButton(
                onClick = onInstall,
                enabled = installEnabled,
            ) {
                Text(stringResource(R.string.install))
            }
        }
    }
}

@Composable
private fun InstallProgress(rowInstalling: InstallUiState) {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (rowInstalling.total > 0) {
            CircularProgressIndicator(
                progress = { rowInstalling.downloaded.toFloat() / rowInstalling.total },
                modifier = Modifier.size(28.dp),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun InstalledNsiteActions(nsite: NsiteManager.DiscoveredNsite) {
    val context = LocalContext.current
    val installedNsite = remember(nsite.address, nsite.alreadyInstalled) {
        Settings.nsites.firstOrNull { it.address == nsite.address }
    }
    var autoUpdate by remember(installedNsite) { mutableStateOf(installedNsite?.autoUpdate == true) }
    IconButton(onClick = {
        val enabled = !autoUpdate
        NsiteManager.setAutoUpdate(nsite.address, enabled)
        autoUpdate = enabled
    }) {
        Icon(
            imageVector = if (autoUpdate) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription = stringResource(R.string.auto_update),
            tint = if (autoUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FilledTonalButton(
        onClick = {
            if (installedNsite != null) {
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    "http://${installedNsite.folderName}.localhost:${Settings.port}".toUri(),
                )
                context.startActivity(browserIntent)
            }
        },
    ) {
        Text(stringResource(R.string.open))
    }
}

@Composable
private fun NsiteDetailsToggle(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            stringResource(R.string.nsite_details),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NsiteDetails(
    nsite: NsiteManager.DiscoveredNsite,
    rowInstalling: InstallUiState?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (rowInstalling != null) {
            Text(
                if (rowInstalling.total > 0) {
                    stringResource(R.string.nsite_installing_progress, rowInstalling.downloaded, rowInstalling.total)
                } else {
                    stringResource(R.string.installing_nsite, nsite.displayName)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (nsite.authorName.isNotBlank()) {
            Text(
                stringResource(R.string.nsite_by_author, nsite.authorName),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            nsite.address,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AuthorAvatar(
    pictureUrl: String?,
    authorName: String,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (pictureUrl == null) {
            AvatarFallback(authorName)
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(pictureUrl).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(32.dp),
                loading = { AvatarFallback(authorName) },
                error = { AvatarFallback(authorName) },
            )
        }
    }
}

@Composable
private fun AvatarFallback(authorName: String) {
    if (authorName.isNotBlank()) {
        Text(
            authorName.take(1).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    } else {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Compact relative timestamp ("now", "5m", "1h", "3d", "2w", "4mo", "1y") for the feed-style author header. */
private fun relativeTimeShort(epochSeconds: Long): String {
    val diffMillis = System.currentTimeMillis() - epochSeconds * 1000
    val minutes = diffMillis / 60_000
    val hours = diffMillis / 3_600_000
    val days = diffMillis / 86_400_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        days < 30 -> "${days / 7}w"
        days < 365 -> "${days / 30}mo"
        else -> "${days / 365}y"
    }
}
