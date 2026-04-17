package com.greenart7c3.citrine.storage

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.vitorpamplona.quartz.nip01Core.core.Event

data class EventPagingKey(
    val createdAt: Long,
    val id: String,
)

class EventPagingSource(
    private val store: EventStore,
    private val kind: Int,
) : PagingSource<EventPagingKey, Event>() {

    override suspend fun load(
        params: LoadParams<EventPagingKey>,
    ): LoadResult<EventPagingKey, Event> {
        val key = params.key

        val data = store.getByKindKeyset(
            kind = kind,
            createdAt = key?.createdAt,
            id = key?.id,
            limit = params.loadSize,
        )

        val nextKey = data.lastOrNull()?.let {
            EventPagingKey(it.createdAt, it.id)
        }

        return LoadResult.Page(
            data = data,
            prevKey = null,
            nextKey = nextKey,
        )
    }

    override fun getRefreshKey(state: PagingState<EventPagingKey, Event>): EventPagingKey? = null
}
