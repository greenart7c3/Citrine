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
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventDao
import kotlinx.coroutines.flow.Flow

@Composable
fun DatabaseInfo(
    modifier: Modifier = Modifier,
    database: AppDatabase,
    navController: NavController,
) {
    val countByKind: Flow<List<EventDao.CountResult>> = database.eventDao().countByKind()

    val flow = countByKind.collectAsStateWithLifecycle(initialValue = listOf())

    Column(
        modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.total, flow.value.sumOf { it.count }))
        Spacer(modifier = Modifier.padding(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.kind), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.count), fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(flow.value) { item ->
                ElevatedButton(
                    modifier = Modifier
                        .fillMaxWidth(),
                    onClick = {
                        navController.navigate("Feed/${item.kind}")
                    },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${item.kind}")
                        Text("${item.count}")
                    }
                }
            }
        }
    }
}
