package com.greenart7c3.citrine.ui

import LogcatViewModel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatScreen(
    logcatViewModel: LogcatViewModel = viewModel(),
    onClose: () -> Unit,
) {
    val logMessages = logcatViewModel.logMessages.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        content = {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        },
                    )
                },
                title = { Text("Log Viewer") },
            )
        },
    ) {
        LazyColumn(
            contentPadding = it,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(logMessages.value) { logMessage ->
                Card(
                    modifier = Modifier.padding(4.dp),
                ) {
                    Text(
                        text = logMessage,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}
