package com.greenart7c3.citrine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.greenart7c3.citrine.database.EventDao

@Composable
fun DatabaseInfo(
    modifier: Modifier = Modifier,
    flow: State<List<EventDao.CountResult>>?,
    navController: NavController,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Database", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.padding(4.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Kind", fontWeight = FontWeight.Bold)
            Text("Count", fontWeight = FontWeight.Bold)
        }

        flow?.value?.forEach { item ->
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
        Text("Total: ${flow?.value?.sumOf { it.count }}")
    }
}
