package com.greenart7c3.citrine.ui.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.greenart7c3.citrine.R

@Composable
fun ImportEventsDialog(
    onClose: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(context.getString(R.string.import_events))
        },
        text = {
            Text(context.getString(R.string.import_events_warning))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) {
                Text(context.getString(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text(context.getString(R.string.no))
            }
        },
    )
}
