package com.greenart7c3.citrine.provider

import android.net.Uri
import android.provider.BaseColumns

/**
 * Contract class for Citrine Content Provider.
 * Defines URIs and column names for accessing Nostr events and tags.
 */
object CitrineContract {
    const val AUTHORITY = "com.greenart7c3.citrine.provider"
    private val BASE_URI = Uri.parse("content://$AUTHORITY")

    /**
     * Events table contract
     */
    object Events : BaseColumns {
        const val TABLE_NAME = "EventEntity"
        val CONTENT_URI: Uri = Uri.withAppendedPath(BASE_URI, "events")
        const val CONTENT_TYPE = "vnd.android.cursor.dir/vnd.citrine.event"
        const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.citrine.event"

        // Column names matching EventEntity
        const val COLUMN_ID = "id"
        const val COLUMN_PUBKEY = "pubkey"
        const val COLUMN_CREATED_AT = "createdAt"
        const val COLUMN_KIND = "kind"
        const val COLUMN_CONTENT = "content"
        const val COLUMN_SIG = "sig"

        // Query parameters
        const val PARAM_PUBKEY = "pubkey"
        const val PARAM_KIND = "kind"
        const val PARAM_CREATED_AT_FROM = "createdAt_from"
        const val PARAM_CREATED_AT_TO = "createdAt_to"
        const val PARAM_LIMIT = "limit"
        const val PARAM_OFFSET = "offset"

        // URI paths
        const val PATH_BY_PUBKEY = "by_pubkey"
        const val PATH_BY_KIND = "by_kind"
    }

    /**
     * Tags table contract
     */
    object Tags : BaseColumns {
        const val TABLE_NAME = "TagEntity"
        val CONTENT_URI: Uri = Uri.withAppendedPath(BASE_URI, "tags")
        const val CONTENT_TYPE = "vnd.android.cursor.dir/vnd.citrine.tag"
        const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.citrine.tag"

        // Column names matching TagEntity
        const val COLUMN_PK = "pk"
        const val COLUMN_PK_EVENT = "pkEvent"
        const val COLUMN_POSITION = "position"
        const val COLUMN_COL0_NAME = "col0Name"
        const val COLUMN_COL1_VALUE = "col1Value"
        const val COLUMN_COL2_DIFFERENTIATOR = "col2Differentiator"
        const val COLUMN_COL3_AMOUNT = "col3Amount"
        const val COLUMN_COL4_PLUS = "col4Plus"
        const val COLUMN_KIND = "kind"

        // Query parameters
        const val PARAM_EVENT_ID = "event_id"

        // URI paths
        const val PATH_BY_EVENT = "by_event"
    }
}
