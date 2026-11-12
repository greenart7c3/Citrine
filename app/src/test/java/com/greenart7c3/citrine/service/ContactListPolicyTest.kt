package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.database.IdAndPubkey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contact lists are the largest events the relay stores and kind 3 is exempt from retention
 * deletion, so the aggregator must only fetch the owner's own follow graph — and the one-shot
 * cleanup must never run on an unresolved follow list.
 */
class ContactListPolicyTest {
    private fun rows(vararg pubkeys: String): List<IdAndPubkey> = pubkeys.mapIndexed { i, pk -> IdAndPubkey(id = "id$i", pubkey = pk) }

    @Test
    fun contactListsAreBackfilledOnlyForFollowedAuthors() {
        val followed = setOf("owner", "friend")
        assertTrue(ContactListPolicy.shouldBackfillContactList("friend", followed))
        assertFalse(ContactListPolicy.shouldBackfillContactList("stranger", followed))
    }

    @Test
    fun nothingIsBackfilledWhenNoFollowListIsKnown() {
        // No-pubkey mode: nobody is followed, so no contact lists are fetched at all.
        assertFalse(ContactListPolicy.shouldBackfillContactList("anyone", emptySet()))
    }

    @Test
    fun keepSetIsFollowsPlusProtectedPubkeys() {
        val keep = ContactListPolicy.keepSet(
            aggregatorPubkey = "owner",
            ownerFollows = setOf("owner", "friend"),
            relayOwnerPubkey = "relay-owner",
            neverDeleteFrom = setOf("whitelisted"),
            allowedPubKeys = setOf("allowed", ""),
        )
        assertEquals(setOf("owner", "friend", "relay-owner", "whitelisted", "allowed"), keep)
    }

    @Test
    fun strangersAreDeletedAndProtectedAuthorsSurvive() {
        val all = rows("owner", "friend", "stranger1", "stranger2")
        val keep = ContactListPolicy.keepSet("owner", setOf("owner", "friend"), "", emptySet(), emptySet())
        val ids = ContactListPolicy.strangerContactListIds(all, ownerFollows = setOf("owner", "friend"), keep = keep)
        assertEquals(listOf("id2", "id3"), ids)
    }

    @Test
    fun unresolvedFollowListDeletesNothing() {
        val all = rows("owner", "stranger1", "stranger2")
        val keep = ContactListPolicy.keepSet("owner", emptySet(), "", emptySet(), emptySet())
        assertTrue(ContactListPolicy.strangerContactListIds(all, ownerFollows = emptySet(), keep = keep).isEmpty())
    }
}
