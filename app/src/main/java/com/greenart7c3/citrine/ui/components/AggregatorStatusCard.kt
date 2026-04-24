package com.greenart7c3.citrine.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.service.AggregatorPhase
import com.greenart7c3.citrine.service.AggregatorStatus

@Composable
fun AggregatorStatusCard(
    status: AggregatorStatus,
    modifier: Modifier = Modifier,
) {
    val phaseText = when (status.phase) {
        AggregatorPhase.IDLE -> stringResource(R.string.relay_aggregator_phase_idle)
        AggregatorPhase.BOOTSTRAPPING -> stringResource(R.string.relay_aggregator_phase_bootstrapping)
        AggregatorPhase.REFRESHING -> stringResource(R.string.relay_aggregator_phase_refreshing)
        AggregatorPhase.LISTENING -> stringResource(R.string.relay_aggregator_phase_listening)
    }

    val lastRefresh = if (status.lastRefreshEpoch <= 0L) {
        stringResource(R.string.relay_aggregator_never_refreshed)
    } else {
        val ageSeconds = (System.currentTimeMillis() / 1000L) - status.lastRefreshEpoch
        stringResource(R.string.relay_aggregator_last_refresh, formatAge(ageSeconds))
    }

    Card(
        modifier = modifier.padding(vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(R.string.relay_aggregator),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(phaseText, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    R.string.relay_aggregator_counts,
                    status.authors,
                    status.relaysConnected,
                    status.relaysConfigured,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.relay_aggregator_events_received, status.eventsReceived),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                lastRefresh,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatAge(ageSeconds: Long): String = when {
    ageSeconds < 0L -> "0s"
    ageSeconds < 60L -> "${ageSeconds}s"
    ageSeconds < 3600L -> "${ageSeconds / 60L}m"
    ageSeconds < 86_400L -> "${ageSeconds / 3600L}h"
    else -> "${ageSeconds / 86_400L}d"
}
