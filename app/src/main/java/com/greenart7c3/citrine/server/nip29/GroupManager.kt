package com.greenart7c3.citrine.server.nip29

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.GroupEntity
import com.greenart7c3.citrine.database.GroupInviteEntity
import com.greenart7c3.citrine.database.GroupMemberEntity
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Connection
import com.greenart7c3.citrine.server.CustomWebSocketServer
import com.greenart7c3.citrine.server.EventFilter
import com.greenart7c3.citrine.server.EventRepository
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.RelayIdentity
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Registry of the NIP-29 groups managed by this relay. The Room tables are the source of
 * truth; this cache mirrors them so hot paths (write gate, query post-filter, live
 * fanout) never touch the database. Only group ids created here via kind 9007 appear in
 * the cache — h-tagged events for any other group id are stored unmanaged.
 */
object GroupManager {
    private val groups = ConcurrentHashMap<String, GroupState>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    // Monotonic created_at for relay-signed metadata so a regenerated 39xxx always
    // replaces the previous version even when several regens land in the same second.
    private val lastMetadataStamp = AtomicLong(0)

    // The relay identity: the owner's key when configured (signed via the Amber
    // external signer), otherwise the relay's generated fallback key.
    val relayPubKey: String
        get() = RelayIdentity.pubKeyHex()

    fun hasGroups(): Boolean = groups.isNotEmpty()

    /** True when at least one live managed group restricts reads (private or hidden). */
    fun hasPrivateGroups(): Boolean = groups.values.any { !it.isDeleted && (it.isPrivate || it.isHidden) }

    suspend fun load(db: AppDatabase) {
        val dao = db.groupDao()
        val membersByGroup = dao.getAllMembers().groupBy { it.groupId }
        val invitesByGroup = dao.getAllInvites().groupBy { it.groupId }
        val loaded = dao.getAllGroups().associate { entity ->
            entity.id to GroupState(
                id = entity.id,
                name = entity.name,
                about = entity.about,
                picture = entity.picture,
                isPrivate = entity.isPrivate,
                isClosed = entity.isClosed,
                isRestricted = entity.isRestricted,
                isHidden = entity.isHidden,
                isDeleted = entity.isDeleted,
                members = membersByGroup[entity.id]?.associate { member ->
                    member.pubkey to member.roles.split(",").filter { it.isNotBlank() }.toSet()
                } ?: emptyMap(),
                invites = invitesByGroup[entity.id]?.map { it.code }?.toSet() ?: emptySet(),
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            )
        }
        groups.clear()
        groups.putAll(loaded)
        Log.d(Citrine.TAG, "Loaded ${groups.size} NIP-29 groups (relay pubkey $relayPubKey)")
    }

    fun clear() {
        groups.clear()
        locks.clear()
    }

    fun get(groupId: String?): GroupState? = groupId?.let { groups[it] }

    /** The group id an event addresses: the `h` tag, or the `d` tag for 39xxx metadata kinds. */
    fun groupIdOf(event: Event): String? = if (event.kind in Nip29.METADATA_KINDS) {
        event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1)
    } else {
        event.tags.firstOrNull { it.size > 1 && it[0] == "h" }?.get(1)
    }

    fun isPrivileged(group: GroupState, pubkey: String): Boolean = pubkey == relayPubKey ||
        (Settings.ownerPubkey.isNotBlank() && pubkey == Settings.ownerPubkey) ||
        group.isAdmin(pubkey)

    /**
     * Read gate shared by REQ streaming, COUNT, and live fanout. Only blocks events of
     * managed `private` groups (and 39xxx metadata of `hidden` groups) from connections
     * that have not NIP-42-authenticated as a member. Internal callers pass
     * `connection = null` and are always allowed.
     */
    fun canRead(event: Event, connection: Connection?): Boolean {
        if (groups.isEmpty() || connection == null) return true
        return canRead(event.kind, groupIdOf(event), connection)
    }

    /**
     * Row-based variant of [canRead] used by [com.greenart7c3.citrine.server.EventRepository.subscribe],
     * whose hot path serializes straight from the database rows without constructing a
     * quartz [Event].
     */
    fun canRead(dbEvent: EventWithTags, connection: Connection?): Boolean {
        if (groups.isEmpty() || connection == null) return true
        val tagName = if (dbEvent.event.kind in Nip29.METADATA_KINDS) "d" else "h"
        val groupId = dbEvent.tags.firstOrNull { it.col0Name == tagName }?.col1Value
        return canRead(dbEvent.event.kind, groupId, connection)
    }

    private fun canRead(kind: Int, groupId: String?, connection: Connection): Boolean {
        val group = get(groupId) ?: return true
        if (group.isDeleted) return true
        val membersOnly = if (kind in Nip29.METADATA_KINDS) group.isHidden else group.isPrivate
        if (!membersOnly) return true
        return connection.users.any { user -> isPrivileged(group, user) || group.isMember(user) }
    }

    private fun lockFor(groupId: String): Mutex = locks.getOrPut(groupId) { Mutex() }

    /** Creates the group with the 9007 author as its first admin. Null if the id is taken. */
    suspend fun createGroup(db: AppDatabase, groupId: String, event: Event): GroupState? = lockFor(groupId).withLock {
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

    suspend fun putUser(db: AppDatabase, groupId: String, pubkey: String, roles: Set<String>) {
        lockFor(groupId).withLock {
            val group = groups[groupId] ?: return
            if (group.isDeleted) return
            db.groupDao().upsertMember(GroupMemberEntity(groupId, pubkey, roles.joinToString(",")))
            groups[groupId] = group.copy(members = group.members + (pubkey to roles))
        }
    }

    suspend fun removeUser(db: AppDatabase, groupId: String, pubkey: String) {
        lockFor(groupId).withLock {
            val group = groups[groupId] ?: return
            if (group.isDeleted) return
            db.groupDao().deleteMember(groupId, pubkey)
            groups[groupId] = group.copy(members = group.members - pubkey)
        }
    }

    suspend fun editMetadata(db: AppDatabase, groupId: String, event: Event) {
        lockFor(groupId).withLock {
            val group = groups[groupId] ?: return
            if (group.isDeleted) return
            val updated = group.applyMetadataTags(event.tags).copy(updatedAt = event.createdAt)
            db.groupDao().upsertGroup(updated.toEntity())
            groups[groupId] = updated
        }
    }

    suspend fun addInvite(db: AppDatabase, groupId: String, code: String, createdAt: Long) {
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
    suspend fun deleteGroup(db: AppDatabase, groupId: String, keepEventId: String) {
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
    fun scheduleRegen(server: CustomWebSocketServer, groupId: String) {
        Citrine.instance.applicationScope.launch(Dispatchers.IO) {
            try {
                regenerateMetadataEvents(server, groupId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(Citrine.TAG, "Failed to regenerate NIP-29 metadata for group $groupId", e)
            }
        }
    }

    suspend fun regenerateMetadataEvents(server: CustomWebSocketServer, groupId: String) {
        val group = groups[groupId] ?: return
        if (group.isDeleted) return

        val signer = RelayIdentity.signer()
        val createdAt = lastMetadataStamp.updateAndGet { previous -> maxOf(TimeUtils.now(), previous + 1) }

        val metadataTags = mutableListOf(arrayOf("d", group.id))
        if (group.name.isNotBlank()) metadataTags.add(arrayOf("name", group.name))
        if (group.about.isNotBlank()) metadataTags.add(arrayOf("about", group.about))
        if (group.picture.isNotBlank()) metadataTags.add(arrayOf("picture", group.picture))
        metadataTags.add(if (group.isPrivate) arrayOf("private") else arrayOf("public"))
        metadataTags.add(if (group.isClosed) arrayOf("closed") else arrayOf("open"))
        if (group.isRestricted) metadataTags.add(arrayOf("restricted"))
        if (group.isHidden) metadataTags.add(arrayOf("hidden"))

        val adminsTags = mutableListOf(arrayOf("d", group.id))
        group.members.forEach { (pubkey, roles) ->
            if (roles.contains(Nip29.ROLE_ADMIN)) {
                adminsTags.add(arrayOf("p", pubkey, Nip29.ROLE_ADMIN))
            }
        }

        val membersTags = mutableListOf(arrayOf("d", group.id))
        group.members.keys.forEach { pubkey ->
            membersTags.add(arrayOf("p", pubkey))
        }

        val rolesTags = mutableListOf(
            arrayOf("d", group.id),
            arrayOf("role", Nip29.ROLE_ADMIN, "can edit metadata and manage users"),
        )

        listOf(
            Nip29.KIND_GROUP_METADATA to metadataTags,
            Nip29.KIND_GROUP_ADMINS to adminsTags,
            Nip29.KIND_GROUP_MEMBERS to membersTags,
            Nip29.KIND_GROUP_ROLES to rolesTags,
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
        val db = AppDatabase.getDatabase(Citrine.instance)
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
}

fun GroupState.toEntity(): GroupEntity = GroupEntity(
    id = id,
    name = name,
    about = about,
    picture = picture,
    isPrivate = isPrivate,
    isClosed = isClosed,
    isRestricted = isRestricted,
    isHidden = isHidden,
    isDeleted = isDeleted,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
