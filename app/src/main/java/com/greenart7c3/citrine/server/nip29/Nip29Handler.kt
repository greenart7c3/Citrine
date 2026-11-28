package com.greenart7c3.citrine.server.nip29

import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.server.CustomWebSocketServer
import com.greenart7c3.citrine.service.RelayIdentity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.utils.TimeUtils

sealed class Nip29Result {
    /** Not a managed-group event — continue down the normal processing path. */
    object NotApplicable : Nip29Result()

    /** Refuse the event. [message] is the full NIP-01 OK reason, prefix included. */
    data class Reject(val message: String) : Nip29Result()

    /** Store the event normally, then run [postSave] (membership changes, regens). */
    data class Accept(val postSave: (suspend () -> Unit)? = null) : Nip29Result()
}

/**
 * NIP-29 write-path rules, applied by [CustomWebSocketServer.innerProcessEvent] after an
 * event passes normal verification. Events for group ids this relay doesn't manage
 * always return [Nip29Result.NotApplicable] so mirrored/backed-up group content from
 * other relays keeps flowing unmanaged.
 */
object Nip29Handler {
    private val HEX_KEY_REGEX = Regex("^[0-9a-f]{64}$")

    suspend fun process(event: Event, server: CustomWebSocketServer, db: AppDatabase): Nip29Result {
        if (event.kind in Nip29.METADATA_KINDS) return metadataEvent(event)

        val groupId = event.tags.firstOrNull { it.size > 1 && it[0] == "h" }?.get(1)
            ?: return Nip29Result.NotApplicable

        if (event.kind == Nip29.KIND_CREATE_GROUP) return createGroup(event, groupId, server, db)

        val group = GroupManager.get(groupId) ?: return Nip29Result.NotApplicable
        return if (group.isDeleted) {
            Nip29Result.Reject("blocked: group '$groupId' has been deleted")
        } else {
            groupEvent(event, group, groupId, server, db)
        }
    }

    private fun metadataEvent(event: Event): Nip29Result = when {
        event.pubKey == GroupManager.relayPubKey -> Nip29Result.NotApplicable
        GroupManager.get(GroupManager.groupIdOf(event)) == null -> Nip29Result.NotApplicable
        else -> Nip29Result.Reject("blocked: group metadata events can only be published by this relay")
    }

    private fun createGroup(event: Event, groupId: String, server: CustomWebSocketServer, db: AppDatabase): Nip29Result = when {
        !Nip29.isValidGroupId(groupId) -> Nip29Result.Reject("invalid: group id must be 1-64 characters of a-z, A-Z, 0-9, - or _")
        GroupManager.get(groupId) != null -> Nip29Result.Reject("duplicate: group '$groupId' already exists on this relay")
        else -> Nip29Result.Accept {
            if (GroupManager.createGroup(db, groupId, event) != null) {
                GroupManager.scheduleRegen(server, groupId)
            }
        }
    }

    private fun groupEvent(event: Event, group: GroupState, groupId: String, server: CustomWebSocketServer, db: AppDatabase): Nip29Result = when (event.kind) {
        Nip29.KIND_PUT_USER,
        Nip29.KIND_REMOVE_USER,
        Nip29.KIND_EDIT_METADATA,
        Nip29.KIND_DELETE_EVENT,
        Nip29.KIND_DELETE_GROUP,
        Nip29.KIND_CREATE_INVITE,
        Nip29.KIND_UPDATE_PIN_LIST,
        -> moderate(event, group, server, db)

        Nip29.KIND_JOIN_REQUEST -> joinRequest(event, group, server)

        Nip29.KIND_LEAVE_REQUEST -> leaveRequest(event, group, server)

        else -> {
            if (isRefusedPoster(group, event.pubKey)) {
                Nip29Result.Reject("restricted: not a member of group '$groupId'")
            } else {
                Nip29Result.Accept()
            }
        }
    }

    private fun isRefusedPoster(group: GroupState, pubkey: String): Boolean = group.isRestricted && !group.isMember(pubkey) && !GroupManager.isPrivileged(group, pubkey)

    private fun moderate(event: Event, group: GroupState, server: CustomWebSocketServer, db: AppDatabase): Nip29Result {
        if (!GroupManager.isPrivileged(group, event.pubKey)) {
            return Nip29Result.Reject("restricted: missing permission to moderate group '${group.id}'")
        }

        return when (event.kind) {
            Nip29.KIND_PUT_USER -> Nip29Result.Accept { putUsers(event, group, server, db) }

            Nip29.KIND_REMOVE_USER -> Nip29Result.Accept { removeUsers(event, group, server, db) }

            Nip29.KIND_EDIT_METADATA -> Nip29Result.Accept {
                GroupManager.editMetadata(db, group.id, event)
                GroupManager.scheduleRegen(server, group.id)
            }

            Nip29.KIND_DELETE_EVENT -> Nip29Result.Accept { deleteGroupEvents(event, group, db) }

            Nip29.KIND_DELETE_GROUP -> Nip29Result.Accept {
                GroupManager.deleteGroup(db, group.id, keepEventId = event.id)
            }

            Nip29.KIND_CREATE_INVITE -> createInvite(event, group, db)

            // Stored as-is; pin lists are served straight back to clients.
            Nip29.KIND_UPDATE_PIN_LIST -> Nip29Result.Accept()

            else -> Nip29Result.NotApplicable
        }
    }

    private suspend fun putUsers(event: Event, group: GroupState, server: CustomWebSocketServer, db: AppDatabase) {
        taggedPubkeys(event).forEach { tag ->
            GroupManager.putUser(db, group.id, tag[1], GroupState.rolesFromPTag(tag))
        }
        GroupManager.scheduleRegen(server, group.id)
    }

    private suspend fun removeUsers(event: Event, group: GroupState, server: CustomWebSocketServer, db: AppDatabase) {
        taggedPubkeys(event).forEach { tag ->
            GroupManager.removeUser(db, group.id, tag[1])
        }
        GroupManager.scheduleRegen(server, group.id)
    }

    private suspend fun deleteGroupEvents(event: Event, group: GroupState, db: AppDatabase) {
        val targetIds = event.tags
            .filter { it.size > 1 && it[0] == "e" }
            .map { it[1] }
            .filter { it != event.id }
        targetIds.forEach { targetId ->
            val target = db.eventDao().getById(targetId) ?: return@forEach
            val belongsToGroup = target.tags.any { it.col0Name == "h" && it.col1Value == group.id }
            if (belongsToGroup) {
                db.eventDao().delete(listOf(targetId))
            }
        }
    }

    private fun createInvite(event: Event, group: GroupState, db: AppDatabase): Nip29Result {
        val code = event.tags.firstOrNull { it.size > 1 && it[0] == "code" }?.get(1)
        return if (code.isNullOrBlank()) {
            Nip29Result.Reject("invalid: create-invite requires a code tag")
        } else {
            Nip29Result.Accept {
                GroupManager.addInvite(db, group.id, code, event.createdAt)
            }
        }
    }

    private fun joinRequest(event: Event, group: GroupState, server: CustomWebSocketServer): Nip29Result {
        if (group.isMember(event.pubKey)) {
            return Nip29Result.Reject("duplicate: already a member of group '${group.id}'")
        }
        if (group.isClosed) {
            val code = event.tags.firstOrNull { it.size > 1 && it[0] == "code" }?.get(1)
            if (code == null || code !in group.invites) {
                return Nip29Result.Reject("blocked: group '${group.id}' is closed, a valid invite code is required")
            }
        }
        return Nip29Result.Accept {
            issueMembershipEvent(server, group.id, event.pubKey, add = true)
        }
    }

    private fun leaveRequest(event: Event, group: GroupState, server: CustomWebSocketServer): Nip29Result {
        if (!group.isMember(event.pubKey)) {
            // Nothing to remove; store the request and move on.
            return Nip29Result.Accept()
        }
        return Nip29Result.Accept {
            issueMembershipEvent(server, group.id, event.pubKey, add = false)
        }
    }

    /**
     * Signs a kind 9000 (add) or 9001 (remove) with the relay's key and runs it through
     * the normal pipeline: its own moderation flow (the relay key is always privileged)
     * applies the membership change and regenerates the 39xxx metadata.
     */
    private suspend fun issueMembershipEvent(server: CustomWebSocketServer, groupId: String, pubkey: String, add: Boolean) {
        val signer = RelayIdentity.signer()
        val kind = if (add) Nip29.KIND_PUT_USER else Nip29.KIND_REMOVE_USER
        val tags = if (add) {
            arrayOf(arrayOf("h", groupId), arrayOf("p", pubkey, Nip29.ROLE_MEMBER))
        } else {
            arrayOf(arrayOf("h", groupId), arrayOf("p", pubkey))
        }
        val signed: Event = signer.sign(
            createdAt = TimeUtils.now(),
            kind = kind,
            tags = tags,
            content = "",
        )
        server.innerProcessEvent(signed, connection = null, shouldVerify = false)
    }

    private fun taggedPubkeys(event: Event): List<Array<String>> = event.tags.filter {
        it.size > 1 && it[0] == "p" && HEX_KEY_REGEX.matches(it[1])
    }
}
