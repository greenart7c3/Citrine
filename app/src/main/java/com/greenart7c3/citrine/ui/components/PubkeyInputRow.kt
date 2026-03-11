package com.greenart7c3.citrine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.greenart7c3.citrine.R

@Composable
fun PubkeyInputRow(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onPaste: () -> Unit,
    onAdd: () -> Unit,
    label: String = "",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(0.9f),
            value = value,
            label = if (label.isNotEmpty()) {
                { Text(label) }
            } else {
                null
            },
            onValueChange = onValueChange,
            trailingIcon = {
                IconButton(onClick = onPaste) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = stringResource(R.string.paste_from_clipboard),
                    )
                }
            },
            singleLine = true,
        )
        IconButton(onClick = onAdd) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.add),
            )
        }
    }
}

@Composable
fun PubkeyListItem(
    text: String,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.9f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
            )
        }
    }
}
