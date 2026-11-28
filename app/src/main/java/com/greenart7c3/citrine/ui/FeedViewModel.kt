package com.greenart7c3.citrine.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventPagingSource
import com.greenart7c3.citrine.database.toEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The stored events of one [kind], newest first, paged from the database. */
class FeedViewModel(database: AppDatabase, kind: Int) : ViewModel() {
    val events: Flow<PagingData<Event>> = Pager(
        config = PagingConfig(
            pageSize = 20,
            initialLoadSize = 40,
            prefetchDistance = 5,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { EventPagingSource(dao = database.eventDao(), kind = kind) },
    ).flow
        .map { page -> page.map { it.toEvent() } }
        .cachedIn(viewModelScope)

    companion object {
        fun factory(appContext: Context, kind: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer { FeedViewModel(AppDatabase.getDatabase(appContext), kind) }
        }
    }
}
