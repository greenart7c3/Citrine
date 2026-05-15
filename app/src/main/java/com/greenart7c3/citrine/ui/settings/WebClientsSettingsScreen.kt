package com.greenart7c3.citrine.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.net.toUri
import com.anggrayudi.storage.SimpleStorageHelper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.ui.components.WebAppInput
import com.greenart7c3.citrine.ui.components.WebAppRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WebClientsSettingsScreen(
    modifier: Modifier = Modifier,
    storageHelper: SimpleStorageHelper,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Surface(modifier) {
        var webPath by remember { mutableStateOf(TextFieldValue("")) }
        var webClients by remember { mutableStateOf<Map<String, String>>(Settings.webClients.toMap()) }

        storageHelper.onFolderSelected = { _, folder ->
            val path = if (webPath.text.startsWith("/")) webPath.text else "/${webPath.text}"
            webClients = webClients + (path to folder.uri.toString())
            webPath = TextFieldValue("")
        }

        val applyChanges = {
            scope.launch(Dispatchers.IO) {
                isLoading = true
                Settings.webClients = webClients.toMutableMap()
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                onApplyChanges()
                delay(1500)
                isLoading = false
            }
            Unit
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    val nameNotBlankResource = stringResource(R.string.name_cannot_be_blank)
                    WebAppInput(
                        value = webPath,
                        onValueChange = { webPath = it },
                        onAdd = {
                            if (webPath.text.isBlank()) {
                                Toast.makeText(context, nameNotBlankResource, Toast.LENGTH_SHORT).show()
                                return@WebAppInput
                            }
                            storageHelper.openFolderPicker()
                        },
                    )
                }
                items(webClients.keys.toList()) { item ->
                    WebAppRow(
                        onOpenWebApp = {
                            val browserIntent = Intent(
                                Intent.ACTION_VIEW,
                                "http://${item.removePrefix("/")}.localhost:${Settings.port}".toUri(),
                            )
                            browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            Citrine.instance.startActivity(browserIntent)
                        },
                        onDelete = { webClients = webClients - item },
                        item = item,
                    )
                }
            }

            SettingsApplyBar(enabled = !isLoading, onApply = applyChanges)
        }
    }
}
