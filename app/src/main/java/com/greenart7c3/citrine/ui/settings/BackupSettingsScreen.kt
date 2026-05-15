package com.greenart7c3.citrine.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
import com.anggrayudi.storage.SimpleStorageHelper
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.ui.components.SwitchSettingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BackupSettingsScreen(
    modifier: Modifier = Modifier,
    storageHelper: SimpleStorageHelper,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Surface(modifier) {
        var autoBackup by remember { mutableStateOf(Settings.autoBackup) }
        var autoBackupFolder by remember { mutableStateOf(Settings.autoBackupFolder) }

        storageHelper.onFolderSelected = { _, folder ->
            autoBackup = true
            autoBackupFolder = folder.uri.toString()
        }

        val applyChanges = {
            scope.launch(Dispatchers.IO) {
                isLoading = true
                Settings.autoBackup = autoBackup
                Settings.autoBackupFolder = autoBackupFolder
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
                    SwitchSettingRow(
                        title = stringResource(R.string.auto_backup),
                        description = stringResource(R.string.auto_backup_description),
                        checked = autoBackup,
                        onCheckedChange = {
                            if (!autoBackup) {
                                storageHelper.openFolderPicker()
                            } else {
                                autoBackup = false
                            }
                        },
                    )
                }
            }

            SettingsApplyBar(enabled = !isLoading, onApply = applyChanges)
        }
    }
}
