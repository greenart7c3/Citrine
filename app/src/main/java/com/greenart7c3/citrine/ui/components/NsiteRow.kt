package com.greenart7c3.citrine.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.NsiteInfo
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.NsiteManager

@Composable
fun NsiteRow(
    nsite: NsiteInfo,
    updateAvailable: Boolean,
    onOpen: () -> Unit,
    onToggleAutoUpdate: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NsiteIcon(model = remember(nsite.folderName) { NsiteManager.localIconFile(nsite.folderName) })
            Column(modifier = Modifier.weight(0.9f).padding(start = 12.dp)) {
                Text(
                    nsite.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "${nsite.folderName}.localhost:${Settings.port}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = nsite.autoUpdate, onCheckedChange = onToggleAutoUpdate)
                Text(
                    stringResource(R.string.auto_update),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (updateAvailable) {
                TextButton(onClick = onUpdate) {
                    Text(stringResource(R.string.update_now))
                }
            }
        }
    }
}
