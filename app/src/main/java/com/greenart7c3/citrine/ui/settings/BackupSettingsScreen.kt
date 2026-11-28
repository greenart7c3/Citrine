package com.greenart7c3.citrine.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.ui.StoragePickerHelper
import com.greenart7c3.citrine.ui.components.SwitchSettingRow

@Composable
fun BackupSettingsScreen(
    storageHelper: StoragePickerHelper,
    onApplyChanges: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupSettingsViewModel = viewModel(factory = BackupSettingsViewModel.factory(LocalContext.current.applicationContext)),
) {
    Surface(modifier) {
        storageHelper.onFolderSelected = { _, folder ->
            viewModel.enableAutoBackup(folder.uri.toString())
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.auto_backup),
                        description = stringResource(R.string.auto_backup_description),
                        checked = viewModel.autoBackup,
                        onCheckedChange = {
                            if (!viewModel.autoBackup) {
                                storageHelper.openFolderPicker()
                            } else {
                                viewModel.disableAutoBackup()
                            }
                        },
                    )
                }
            }

            SettingsApplyBar(enabled = !viewModel.isLoading, onApply = { viewModel.applyChanges(onApplyChanges) })
        }
    }
}
