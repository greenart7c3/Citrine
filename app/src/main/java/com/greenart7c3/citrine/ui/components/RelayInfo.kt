package com.greenart7c3.citrine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun RelayInfo(
    modifier: Modifier = Modifier,
    connections: Int,
    subscriptions: Int,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Relay",
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.padding(4.dp))

        Text("Connections: $connections")
        Text("Subscriptions: $subscriptions")
    }
}
