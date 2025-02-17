package com.greenart7c3.citrine.ui.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.greenart7c3.citrine.R

@Composable
fun DeleteAllDialog(
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(stringResource(R.string.delete_all_events))
        },
        text = {
            Text(stringResource(R.string.delete_all_events_warning))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onClose,
            ) {
                Text(stringResource(R.string.no))
            }
        },
    )
}
