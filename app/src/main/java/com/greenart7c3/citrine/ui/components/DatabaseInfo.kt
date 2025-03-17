package com.greenart7c3.citrine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
fun DatabaseInfo(
    modifier: Modifier = Modifier,
    database: AppDatabase,
    navController: NavController,
) {
    val countByKind: Flow<List<EventDao.CountResult>> = database.eventDao().countByKind()

    val flow = countByKind.collectAsStateWithLifecycle(initialValue = listOf())
    var wantsToDeleteKind by remember { mutableStateOf<Int?>(null) }

    if (wantsToDeleteKind != null) {
        AlertDialog(
            text = {
                Text(stringResource(R.string.are_you_sure_you_want_to_delete_all_events_of_kind, wantsToDeleteKind!!))
            },
            title = {
                Text(stringResource(R.string.delete), fontWeight = FontWeight.Bold)
            },
            onDismissRequest = {
                wantsToDeleteKind = null
            },
            confirmButton = {
                TextButton(
                    content = {
                        Text(stringResource(R.string.yes))
                    },
                    onClick = {
                        Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
                            database.eventDao().deleteByKind(wantsToDeleteKind!!)
                            wantsToDeleteKind = null
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(
                    content = {
                        Text(stringResource(R.string.no))
                    },
                    onClick = {
                        wantsToDeleteKind = null
                    },
                )
            },
        )
    }

    Column(
        modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.total, flow.value.sumOf { it.count }))
        Spacer(modifier = Modifier.padding(4.dp))
        LazyColumn(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(flow.value) { item ->
                Row(
                    Modifier.fillMaxWidth(),
                ) {
                    ElevatedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            navController.navigate("Feed/${item.kind}")
                        },
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Kind: ${item.kind}")
                            Text("${item.count}")
                        }
                    }
                    IconButton(
                        content = {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                        },
                        onClick = {
                            wantsToDeleteKind = item.kind
                        },
                    )
                }
            }
        }
    }
}
