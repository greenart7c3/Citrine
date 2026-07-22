package com.greenart7c3.citrine

import com.greenart7c3.citrine.server.nip29.GroupState
import com.greenart7c3.citrine.server.nip29.Nip29
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupStateTest {
    private val admin = "a".repeat(64)
    private val member = "b".repeat(64)
    private val stranger = "c".repeat(64)

    private fun group() = GroupState(
        id = "testgrp",
        members = mapOf(
            admin to setOf(Nip29.ROLE_ADMIN),
            member to emptySet(),
        ),
    )

    @Test
    fun membershipAndRoles() {
        val group = group()
        assertTrue(group.isMember(admin))
        assertTrue(group.isMember(member))
        assertFalse(group.isMember(stranger))
        assertTrue(group.isAdmin(admin))
        assertFalse(group.isAdmin(member))
        assertFalse(group.isAdmin(stranger))
    }

    @Test
    fun newGroupDefaults() {
        val group = GroupState(id = "fresh")
        assertFalse(group.isPrivate)
        assertFalse(group.isClosed)
        assertTrue(group.isRestricted)
        assertFalse(group.isHidden)
        assertFalse(group.isDeleted)
    }

    @Test
    fun metadataTagsApplyFieldsAndFlags() {
        val updated = GroupState(id = "g").applyMetadataTags(
            arrayOf(
                arrayOf("h", "g"),
                arrayOf("name", "Pizza Lovers"),
                arrayOf("about", "a group about pizza"),
                arrayOf("picture", "https://example.com/pizza.jpg"),
                arrayOf("private"),
                arrayOf("closed"),
                arrayOf("hidden"),
            ),
        )
        assertEquals("Pizza Lovers", updated.name)
        assertEquals("a group about pizza", updated.about)
        assertEquals("https://example.com/pizza.jpg", updated.picture)
        assertTrue(updated.isPrivate)
        assertTrue(updated.isClosed)
        assertTrue(updated.isHidden)
        assertTrue(updated.isRestricted)
    }

    @Test
    fun oppositeFlagsReverseState() {
        val base = GroupState(id = "g", isPrivate = true, isClosed = true, isRestricted = true, isHidden = true)
        val updated = base.applyMetadataTags(
            arrayOf(
                arrayOf("public"),
                arrayOf("open"),
                arrayOf("unrestricted"),
                arrayOf("unhidden"),
            ),
        )
        assertFalse(updated.isPrivate)
        assertFalse(updated.isClosed)
        assertFalse(updated.isRestricted)
        assertFalse(updated.isHidden)
    }

    @Test
    fun unknownTagsAreIgnored() {
        val base = group()
        val updated = base.applyMetadataTags(arrayOf(arrayOf("previous", "abcd1234"), arrayOf("unknown")))
        assertEquals(base, updated)
    }

    @Test
    fun rolesFromPTag() {
        assertEquals(emptySet<String>(), GroupState.rolesFromPTag(arrayOf("p", member)))
        assertEquals(setOf("admin"), GroupState.rolesFromPTag(arrayOf("p", member, "admin")))
        assertEquals(setOf("admin", "moderator"), GroupState.rolesFromPTag(arrayOf("p", member, "admin", "moderator")))
        assertEquals(emptySet<String>(), GroupState.rolesFromPTag(arrayOf("p", member, "")))
    }

    @Test
    fun groupIdValidation() {
        assertTrue(Nip29.isValidGroupId("abc123"))
        assertTrue(Nip29.isValidGroupId("with-dash_and_underscore"))
        assertFalse(Nip29.isValidGroupId(null))
        assertFalse(Nip29.isValidGroupId(""))
        assertFalse(Nip29.isValidGroupId("has space"))
        assertFalse(Nip29.isValidGroupId("has'quote"))
        assertFalse(Nip29.isValidGroupId("x".repeat(65)))
    }
}
