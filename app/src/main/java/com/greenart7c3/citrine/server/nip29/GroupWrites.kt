package com.greenart7c3.citrine.server.nip29

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.GroupInviteEntity
import com.greenart7c3.citrine.database.GroupMemberEntity
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.CustomWebSocketServer
import com.greenart7c3.citrine.server.EventFilter
import com.greenart7c3.citrine.server.EventRepository
import com.greenart7c3.citrine.service.RelayIdentity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Monotonic created_at for relay-signed metadata so a regenerated 39xxx always
// replaces the previous version even when several regens land in the same second.
private val lastMetadataStamp = AtomicLong(0)

private fun GroupManager.lockFor(groupId: String): Mutex = locks.getOrPut(groupId) { Mutex() }

/** Creates the group with the 9007 author as its first admin. Null if the id is taken. */
suspend fun GroupManager.createGroup(db: AppDatabase, groupId: String, event: Event): GroupState? = lockFor(groupId).withLock {
    if (groups.containsKey(groupId)) return null
    val state = GroupState(
        id = groupId,
        createdAt = event.createdAt,
        updatedAt = event.createdAt,
    )
        .applyMetadataTags(event.tags)
        .copy(members = mapOf(event.pubKey to setOf(Nip29.ROLE_ADMIN)))
    db.groupDao().upsertGroup(state.toEntity())
    db.groupDao().upsertMember(GroupMemberEntity(groupId, event.pubKey, Nip29.ROLE_ADMIN))
    groups[groupId] = state
    state
}

suspend fun GroupManager.putUser(db: AppDatabase, groupId: String, pubkey: String, roles: Set<String>) {
    lockFor(groupId).withLock {
        val group = groups[groupId] ?: return
        if (group.isDeleted) return
        db.groupDao().upsertMember(GroupMemberEntity(groupId, pubkey, roles.joinToString(",")))
        groups[groupId] = group.copy(members = group.members + (pubkey to roles))
    }
}

suspend fun GroupManager.removeUser(db: AppDatabase, groupId: String, pubkey: String) {
    lockFor(groupId).withLock {
        val group = groups[groupId] ?: return
        if (group.isDeleted) return
        db.groupDao().deleteMember(groupId, pubkey)
        groups[groupId] = group.copy(members = group.members - pubkey)
    }
}

suspend fun GroupManager.editMetadata(db: AppDatabase, groupId: String, event: Event) {
    lockFor(groupId).withLock {
        val group = groups[groupId] ?: return
        if (group.isDeleted) return
        val updated = group.applyMetadataTags(event.tags).copy(updatedAt = event.createdAt)
        db.groupDao().upsertGroup(updated.toEntity())
        groups[groupId] = updated
    }
}

suspend fun GroupManager.addInvite(db: AppDatabase, groupId: String, code: String, createdAt: Long) {
    lockFor(groupId).withLock {
        val group = groups[groupId] ?: return
        if (group.isDeleted) return
        db.groupDao().insertInvite(GroupInviteEntity(code, groupId, createdAt))
        groups[groupId] = group.copy(invites = group.invites + code)
    }
}

/**
 * Tombstones the group and purges its content: every stored event carrying the
 * group's `h` tag (except [keepEventId], the kind-9008 audit record) and the
 * relay-signed 39xxx metadata events.
 */
suspend fun GroupManager.deleteGroup(db: AppDatabase, groupId: String, keepEventId: String) {
    lockFor(groupId).withLock {
        val group = groups[groupId] ?: return
        db.groupDao().tombstoneGroup(group.toEntity())
        groups[groupId] = group.copy(isDeleted = true, members = emptyMap(), invites = emptySet())
    }

    val groupEventIds = db.eventDao().getEventIdsByGroupId(groupId).filter { it != keepEventId }
    if (groupEventIds.isNotEmpty()) {
        groupEventIds.chunked(500).forEach { db.eventDao().delete(it) }
    }

    val metadataIds = EventRepository.query(
        db,
        EventFilter(
            authors = setOf(relayPubKey),
            kinds = Nip29.METADATA_KINDS,
            tags = mapOf("d" to setOf(groupId)),
        ),
    ).map { it.event.id }
    if (metadataIds.isNotEmpty()) {
        db.eventDao().delete(metadataIds)
    }
}

/**
 * Rebuilds the four relay-signed metadata events (39000-39003) from the group's
 * current state and feeds them through the normal event pipeline, so the
 * addressable-replaceable path prunes old versions and live subscribers are
 * notified. Fire-and-forget; failures only log.
 */
fun GroupManager.scheduleRegen(server: CustomWebSocketServer, groupId: String) {
    appScope.launch(Dispatchers.IO) {
        try {
            regenerateMetadataEvents(server, groupId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(Citrine.TAG, "Failed to regenerate NIP-29 metadata for group $groupId", e)
        }
    }
}

private suspend fun GroupManager.regenerateMetadataEvents(server: CustomWebSocketServer, groupId: String) {
    val group = groups[groupId] ?: return
    if (group.isDeleted) return

    val signer = RelayIdentity.signer()
    val createdAt = lastMetadataStamp.updateAndGet { previous -> maxOf(TimeUtils.now(), previous + 1) }

    listOf(
        Nip29.KIND_GROUP_METADATA to metadataTags(group),
        Nip29.KIND_GROUP_ADMINS to adminsTags(group),
        Nip29.KIND_GROUP_MEMBERS to membersTags(group),
        Nip29.KIND_GROUP_ROLES to rolesTags(group),
    ).forEach { (kind, tags) ->
        val signed: Event = signer.sign(
            createdAt = createdAt,
            kind = kind,
            tags = tags.toTypedArray(),
            content = "",
        )
        // shouldVerify=false: relay-signed events must not be blocked by the owner's
        // allowlists or rejected-kinds configuration.
        server.innerProcessEvent(signed, connection = null, shouldVerify = false)
    }

    // An Amber login/logout changes the relay identity; metadata signed by the
    // previous identity is a distinct addressable event that would linger next to
    // the fresh copies, so drop it once the new set is stored.
    val db = AppDatabase.getDatabase(context)
    val staleIds = EventRepository.query(
        db,
        EventFilter(
            kinds = Nip29.METADATA_KINDS,
            tags = mapOf("d" to setOf(group.id)),
        ),
    ).filter { it.event.pubkey != relayPubKey }.map { it.event.id }
    if (staleIds.isNotEmpty()) {
        db.eventDao().delete(staleIds)
    }
}

private fun metadataTags(group: GroupState): List<Array<String>> {
    val metadataTags = mutableListOf(arrayOf("d", group.id))
    if (group.name.isNotBlank()) metadataTags.add(arrayOf("name", group.name))
    if (group.about.isNotBlank()) metadataTags.add(arrayOf("about", group.about))
    if (group.picture.isNotBlank()) metadataTags.add(arrayOf("picture", group.picture))
    metadataTags.add(if (group.isPrivate) arrayOf("private") else arrayOf("public"))
    metadataTags.add(if (group.isClosed) arrayOf("closed") else arrayOf("open"))
    if (group.isRestricted) metadataTags.add(arrayOf("restricted"))
    if (group.isHidden) metadataTags.add(arrayOf("hidden"))
    return metadataTags
}

private fun adminsTags(group: GroupState): List<Array<String>> {
    val adminsTags = mutableListOf(arrayOf("d", group.id))
    group.members.forEach { (pubkey, roles) ->
        if (roles.contains(Nip29.ROLE_ADMIN)) {
            adminsTags.add(arrayOf("p", pubkey, Nip29.ROLE_ADMIN))
        }
    }
    return adminsTags
}

private fun membersTags(group: GroupState): List<Array<String>> {
    val membersTags = mutableListOf(arrayOf("d", group.id))
    group.members.keys.forEach { pubkey ->
        membersTags.add(arrayOf("p", pubkey))
    }
    return membersTags
}

private fun rolesTags(group: GroupState): List<Array<String>> = mutableListOf(
    arrayOf("d", group.id),
    arrayOf("role", Nip29.ROLE_ADMIN, "can edit metadata and manage users"),
)
