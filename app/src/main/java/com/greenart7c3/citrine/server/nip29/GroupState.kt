package com.greenart7c3.citrine.server.nip29

/**
 * NIP-29 kind numbers handled by the relay. LiveKit/AV kinds are intentionally
 * unsupported.
 */
object Nip29 {
    const val KIND_PUT_USER = 9000
    const val KIND_REMOVE_USER = 9001
    const val KIND_EDIT_METADATA = 9002
    const val KIND_DELETE_EVENT = 9005
    const val KIND_CREATE_GROUP = 9007
    const val KIND_DELETE_GROUP = 9008
    const val KIND_CREATE_INVITE = 9009
    const val KIND_UPDATE_PIN_LIST = 9010
    const val KIND_JOIN_REQUEST = 9021
    const val KIND_LEAVE_REQUEST = 9022
    const val KIND_GROUP_METADATA = 39000
    const val KIND_GROUP_ADMINS = 39001
    const val KIND_GROUP_MEMBERS = 39002
    const val KIND_GROUP_ROLES = 39003

    val MODERATION_KINDS = setOf(
        KIND_PUT_USER,
        KIND_REMOVE_USER,
        KIND_EDIT_METADATA,
        KIND_DELETE_EVENT,
        KIND_CREATE_GROUP,
        KIND_DELETE_GROUP,
        KIND_CREATE_INVITE,
        KIND_UPDATE_PIN_LIST,
    )

    val METADATA_KINDS = setOf(
        KIND_GROUP_METADATA,
        KIND_GROUP_ADMINS,
        KIND_GROUP_MEMBERS,
        KIND_GROUP_ROLES,
    )

    const val ROLE_ADMIN = "admin"
    const val ROLE_MEMBER = "member"

    // Group ids are relay-local random strings; cap length and charset so ids stay usable
    // in d-tags, naddr codes, and SQL without escaping surprises.
    private val GROUP_ID_REGEX = Regex("^[a-zA-Z0-9-_]{1,64}$")

    fun isValidGroupId(id: String?): Boolean = id != null && GROUP_ID_REGEX.matches(id)
}

/**
 * In-memory state of one managed group. Immutable; [GroupManager] swaps whole instances
 * so readers on hot paths (fanout, query post-filters) never see partial updates.
 * [members] maps pubkey to its role set; a pubkey with no explicit role is a plain member.
 */
data class GroupState(
    val id: String,
    val name: String = "",
    val about: String = "",
    val picture: String = "",
    val isPrivate: Boolean = false,
    val isClosed: Boolean = false,
    val isRestricted: Boolean = true,
    val isHidden: Boolean = false,
    val isDeleted: Boolean = false,
    val members: Map<String, Set<String>> = emptyMap(),
    val invites: Set<String> = emptySet(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun isMember(pubkey: String): Boolean = members.containsKey(pubkey)

    fun isAdmin(pubkey: String): Boolean = members[pubkey]?.contains(Nip29.ROLE_ADMIN) == true

    /**
     * Applies the metadata carried by a kind 9007 (create-group) or 9002 (edit-metadata)
     * event: `name`/`about`/`picture` value tags plus the marker flags `private`/`public`,
     * `closed`/`open`, `restricted`/`unrestricted` and `hidden`/`unhidden`.
     */
    fun applyMetadataTags(tags: Array<Array<String>>): GroupState {
        var state = this
        tags.forEach { tag ->
            when (tag.getOrNull(0)) {
                "name" -> state = state.copy(name = tag.getOrNull(1) ?: "")
                "about" -> state = state.copy(about = tag.getOrNull(1) ?: "")
                "picture" -> state = state.copy(picture = tag.getOrNull(1) ?: "")
                "private" -> state = state.copy(isPrivate = true)
                "public" -> state = state.copy(isPrivate = false)
                "closed" -> state = state.copy(isClosed = true)
                "open" -> state = state.copy(isClosed = false)
                "restricted" -> state = state.copy(isRestricted = true)
                "unrestricted" -> state = state.copy(isRestricted = false)
                "hidden" -> state = state.copy(isHidden = true)
                "unhidden" -> state = state.copy(isHidden = false)
            }
        }
        return state
    }

    /** Roles carried by a `["p", pubkey, role...]` tag, defaulting to plain membership. */
    companion object {
        fun rolesFromPTag(tag: Array<String>): Set<String> = if (tag.size > 2) {
            tag.drop(2).filter { it.isNotBlank() }.toSet()
        } else {
            emptySet()
        }
    }
}
