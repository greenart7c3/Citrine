package com.greenart7c3.citrine.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import com.greenart7c3.citrine.R

@Composable
fun WebAppInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(0.9f),
            value = value,
            label = {
                Text(stringResource(R.string.name_access_client_with_name_localhost_port))
            },
            onValueChange = onValueChange,
        )
        IconButton(
            onClick = onAdd,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.add),
            )
        }
    }
}
