package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.database.IdAndPubkey

/**
 * Which contact lists (kind 3) the relay aggregator is allowed to keep.
 *
 * Every author that showed up in a tagged subscription used to get their contact list fetched
 * and stored. Contact lists are among the largest events on the network, kind 3 is preserved
 * from retention deletion, and they were the data set that drove the REQ path into an
 * `OutOfMemoryError`. The relay only ever needs the owner's follow graph: their own list plus
 * the lists of the authors they follow.
 *
 * Kept free of Android and Quartz types so the rules can be unit tested.
 */
object ContactListPolicy {
    /** Contact lists are fetched for followed authors only, never for strangers. */
    fun shouldBackfillContactList(pubkey: String, followedAuthors: Set<String>): Boolean = pubkey in followedAuthors

    /**
     * Authors whose stored contact list must survive the cleanup: the aggregator owner, everyone
     * they follow, the relay owner, and any pubkey the relay's own settings protect
     * (`neverDeleteFrom`, `allowedPubKeys`).
     */
    fun keepSet(
        aggregatorPubkey: String,
        ownerFollows: Set<String>,
        relayOwnerPubkey: String,
        neverDeleteFrom: Set<String>,
        allowedPubKeys: Set<String>,
    ): Set<String> = buildSet {
        addAll(ownerFollows)
        add(aggregatorPubkey)
        add(relayOwnerPubkey)
        addAll(neverDeleteFrom)
        addAll(allowedPubKeys)
    }.filterTo(mutableSetOf()) { it.isNotBlank() }

    /**
     * Ids of the stored contact lists to delete: every author outside [keep]. Returns nothing when
     * [ownerFollows] is empty — an unresolved contact list would make every stored kind 3 count as
     * a stranger, and deleting then would wipe the whole table on a failed network fetch.
     */
    fun strangerContactListIds(
        rows: List<IdAndPubkey>,
        ownerFollows: Set<String>,
        keep: Set<String>,
    ): List<String> = if (ownerFollows.isEmpty()) emptyList() else rows.filter { it.pubkey !in keep }.map { it.id }
}
