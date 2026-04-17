package com.greenart7c3.citrine.ui.components

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.storage.CountByKindResult
import com.greenart7c3.citrine.storage.EventStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val pieColors = listOf(
    Color(0xFF4E79A7),
    Color(0xFFF28E2B),
    Color(0xFFE15759),
    Color(0xFF76B7B2),
    Color(0xFF59A14F),
    Color(0xFFEDC948),
    Color(0xFFB07AA1),
    Color(0xFFFF9DA7),
    Color(0xFF9C755F),
    Color(0xFFBAB0AC),
    Color(0xFF8CD17D),
)

private data class PieSlice(val label: String, val count: Int, val color: Color)

private fun buildPieSlices(events: List<CountByKindResult>): List<PieSlice> {
    val sorted = events.sortedByDescending { it.count }
    val top = sorted.take(9)
    val otherCount = sorted.drop(9).sumOf { it.count }
    val slices = top.mapIndexed { i, r ->
        PieSlice("Kind ${r.kind}", r.count, pieColors[i % pieColors.size])
    }.toMutableList()
    if (otherCount > 0) {
        slices.add(PieSlice("Other", otherCount, pieColors[10 % pieColors.size]))
    }
    return slices
}

@Composable
private fun PieChart(slices: List<PieSlice>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.count }.toFloat()
    if (total == 0f) return

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val diameter = minOf(size.width, size.height)
            val topLeft = Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f,
            )
            val arcSize = Size(diameter, diameter)
            var startAngle = -90f
            slices.forEach { slice ->
                val sweep = (slice.count / total) * 360f
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                )
                startAngle += sweep
            }
        }
    }
}

@Composable
private fun PieLegend(slices: List<PieSlice>, total: Int) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        slices.forEach { slice ->
            val pct = if (total > 0) slice.count * 100f / total else 0f
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Canvas(modifier = Modifier.size(12.dp)) {
                    drawCircle(color = slice.color)
                }
                Text(
                    text = "${slice.label}: ${slice.count} (${"%.1f".format(pct)}%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface,
                )
            }
        }
    }
}

class DatabaseInfoViewModelFactory(
    private val store: EventStore,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DatabaseInfoViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DatabaseInfoViewModel(store) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class DatabaseInfoViewModel(
    store: EventStore,
) : ViewModel() {

    val countByKind =
        store.countByKind()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

    override fun onCleared() {
        Log.d(Citrine.TAG, "DatabaseInfoViewModel cleared")
        super.onCleared()
    }
}

@Composable
fun DatabaseInfo(
    modifier: Modifier = Modifier,
    store: EventStore,
    navController: NavController,
    viewModel: DatabaseInfoViewModel,
) {
    val events by viewModel.countByKind.collectAsStateWithLifecycle()

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
                        Citrine.instance.applicationScope.launch(Dispatchers.IO) {
                            store.deleteByKind(wantsToDeleteKind!!)
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

    val slices = remember(events) { buildPieSlices(events) }
    val total = events.sumOf { it.count }

    Column(
        modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.total, total))
        Spacer(modifier = Modifier.padding(4.dp))

        if (slices.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PieChart(
                    slices = slices,
                    modifier = Modifier.size(160.dp),
                )
                PieLegend(slices = slices, total = total)
            }
            Spacer(modifier = Modifier.padding(4.dp))
        }

        LazyColumn(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(events) { item ->
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
