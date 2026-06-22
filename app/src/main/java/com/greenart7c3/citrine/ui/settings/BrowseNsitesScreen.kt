package com.greenart7c3.citrine.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.service.NsiteManager
import com.greenart7c3.citrine.ui.components.NsiteIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrowseNsitesScreen(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by NsiteManager.discoveryState.collectAsState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            NsiteManager.discover()
        }
    }

    Surface(modifier) {
        when (val current = state) {
            is NsiteManager.DiscoveryState.Loading, NsiteManager.DiscoveryState.Idle -> {
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
            is NsiteManager.DiscoveryState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(current.message)
                }
            }
            is NsiteManager.DiscoveryState.Loaded -> {
                if (current.nsites.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_nsites_found))
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(current.nsites) { nsite ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !nsite.alreadyInstalled) {
                                        Toast.makeText(context, context.getString(R.string.installing_nsite, nsite.displayName), Toast.LENGTH_SHORT).show()
                                        scope.launch(Dispatchers.IO) {
                                            val result = NsiteManager.install(nsite)
                                            withContext(Dispatchers.Main) {
                                                val message = if (result.isSuccess) {
                                                    context.getString(R.string.nsite_installed, nsite.displayName)
                                                } else {
                                                    context.getString(R.string.nsite_install_failed, nsite.displayName)
                                                }
                                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                            }
                                            // Refresh the list so the installed item is now flagged.
                                            NsiteManager.discover()
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NsiteIcon(model = nsite.iconUrl)
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text(
                                        nsite.displayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        when {
                                            nsite.alreadyInstalled -> stringResource(R.string.nsite_already_installed)
                                            nsite.authorName.isNotBlank() -> stringResource(R.string.nsite_by_author, nsite.authorName)
                                            else -> nsite.address
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
