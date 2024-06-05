package com.greenart7c3.citrine.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.greenart7c3.citrine.R

@Composable
fun DatabaseButtons(
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Row {
        ElevatedButton(
            onClick = onExport,
        ) {
            Text(stringResource(R.string.export_database))
        }
        Spacer(modifier = Modifier.padding(4.dp))
        ElevatedButton(
            onClick = onImport,
        ) {
            Text(stringResource(R.string.import_database))
        }
    }
}
