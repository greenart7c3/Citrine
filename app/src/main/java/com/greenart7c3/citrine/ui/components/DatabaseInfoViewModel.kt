package com.greenart7c3.citrine.ui.components

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How many stored events a kind has. */
data class KindCount(val kind: Int, val count: Int)

class DatabaseInfoViewModel(
    private val database: AppDatabase,
    private val appScope: CoroutineScope,
) : ViewModel() {

    @OptIn(FlowPreview::class)
    val countByKind =
        database.eventDao().countByKind()
            .map { rows -> rows.map { KindCount(it.kind, it.count) } }
            .sample(2_000)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

    /** Deletes every stored event of [kind] on the app scope, so leaving the screen does not stop it. */
    fun deleteKind(kind: Int, onDeleted: () -> Unit) {
        appScope.launch(Dispatchers.IO) {
            database.eventDao().deleteByKind(kind)
            onDeleted()
        }
    }

    companion object {
        fun factory(appContext: Context, appScope: CoroutineScope): ViewModelProvider.Factory = viewModelFactory {
            initializer { DatabaseInfoViewModel(AppDatabase.getDatabase(appContext), appScope) }
        }
    }
}
