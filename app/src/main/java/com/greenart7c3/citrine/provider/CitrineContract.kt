package com.greenart7c3.citrine.provider

import android.net.Uri
import android.provider.BaseColumns
import androidx.core.net.toUri
import com.greenart7c3.citrine.BuildConfig

/**
 * Contract class for Citrine Content Provider.
 * Defines URIs and column names for accessing Nostr events and tags.
 */
object CitrineContract {
    /**
     * Follows the applicationId so debug (.debug) and release builds can be installed side by side.
     */
    const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.provider"
    private val BASE_URI = "content://$AUTHORITY".toUri()

    /**
     * Events table contract.
     *
     * Column names (`COLUMN_*`) match [com.greenart7c3.citrine.database.EventEntity]; query
     * parameters are the `PARAM_*` constants and sub-paths of [CONTENT_URI] the `PATH_*` ones.
     * An insert takes the event columns only: the stored event has no tags.
     */
    object Events : BaseColumns {
        val CONTENT_URI: Uri = Uri.withAppendedPath(BASE_URI, "events")
        const val CONTENT_TYPE = "vnd.android.cursor.dir/vnd.citrine.event"
        const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.citrine.event"

        const val COLUMN_ID = "id"
        const val COLUMN_PUBKEY = "pubkey"
        const val COLUMN_CREATED_AT = "createdAt"
        const val COLUMN_KIND = "kind"
        const val COLUMN_CONTENT = "content"
        const val COLUMN_SIG = "sig"

        const val PARAM_PUBKEY = "pubkey"
        const val PARAM_KIND = "kind"
        const val PARAM_CREATED_AT_FROM = "createdAt_from"
        const val PARAM_CREATED_AT_TO = "createdAt_to"
        const val PARAM_LIMIT = "limit"
        const val PARAM_OFFSET = "offset"
        const val PARAM_AUTH_PUBKEY = "auth_pubkey"

        const val PATH_BY_PUBKEY = "by_pubkey"
        const val PATH_BY_KIND = "by_kind"
    }

    /**
     * Tags table contract.
     *
     * Column names (`COLUMN_*`) match [com.greenart7c3.citrine.database.TagEntity]. Tags are read
     * per event through [PATH_BY_EVENT] with [PARAM_EVENT_ID]; [CONTENT_URI] itself returns no rows.
     */
    object Tags : BaseColumns {
        val CONTENT_URI: Uri = Uri.withAppendedPath(BASE_URI, "tags")
        const val CONTENT_TYPE = "vnd.android.cursor.dir/vnd.citrine.tag"
        const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.citrine.tag"

        const val COLUMN_PK = "pk"
        const val COLUMN_PK_EVENT = "pkEvent"
        const val COLUMN_POSITION = "position"
        const val COLUMN_COL0_NAME = "col0Name"
        const val COLUMN_COL1_VALUE = "col1Value"
        const val COLUMN_COL2_DIFFERENTIATOR = "col2Differentiator"
        const val COLUMN_COL3_AMOUNT = "col3Amount"
        const val COLUMN_COL4_PLUS = "col4Plus"
        const val COLUMN_KIND = "kind"

        const val PARAM_EVENT_ID = "event_id"

        const val PATH_BY_EVENT = "by_event"
    }
}
