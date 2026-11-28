package com.greenart7c3.citrine.server.nip29

import android.content.Context
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.GroupEntity
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Connection
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.RelayIdentity
import com.vitorpamplona.quartz.nip01Core.core.Event
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex

/**
 * Registry of the NIP-29 groups managed by this relay. The Room tables are the source of
 * truth; this cache mirrors them so hot paths (write gate, query post-filter, live
 * fanout) never touch the database. Only group ids created here via kind 9007 appear in
 * the cache — h-tagged events for any other group id are stored unmanaged.
 */
object GroupManager {
    internal lateinit var context: Context
        private set
    internal lateinit var appScope: CoroutineScope
        private set

    /** Hands the group store the application context (read only once it is attached) and the app scope; called once from the Citrine constructor. */
    fun install(context: Context, appScope: CoroutineScope) {
        this.context = context
        this.appScope = appScope
    }
    internal val groups = ConcurrentHashMap<String, GroupState>()
    internal val locks = ConcurrentHashMap<String, Mutex>()

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
