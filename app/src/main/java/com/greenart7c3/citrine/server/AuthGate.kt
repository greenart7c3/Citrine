package com.greenart7c3.citrine.server

import com.greenart7c3.citrine.server.nip29.GroupManager
import com.greenart7c3.citrine.server.nip29.Nip29
import com.greenart7c3.citrine.utils.KINDS_PRIVATE_EVENTS

object AuthGate {
    enum class Denial { AUTH_REQUIRED, RESTRICTED }

    fun check(filter: EventFilter, connection: Connection): Denial? {
        if (Settings.nip29Enabled) {
            nip29Check(filter, connection)?.let { return it }
        }

        for (kind in filter.kinds) {
            if (KINDS_PRIVATE_EVENTS.contains(kind)) {
                if (connection.users.isEmpty()) return Denial.AUTH_REQUIRED

                val senders = filter.authors
                val receivers = filter.tags.filter { it.key == "p" }
                if (!senders.any { connection.users.contains(it) } &&
                    !receivers.any { entry -> entry.value.any { receiver -> connection.users.contains(receiver) } }
                ) {
                    return Denial.RESTRICTED
                }
            }
        }

        if (filter.kinds.isEmpty() && (filter.tags.contains("p") || filter.authors.isNotEmpty())) {
            if (connection.users.isEmpty()) return Denial.AUTH_REQUIRED

            val senders = filter.authors
            val receivers = filter.tags.filter { it.key == "#p" }
            if (!senders.any { connection.users.contains(it) } &&
                !receivers.any { entry -> entry.value.any { receiver -> connection.users.contains(receiver) } }
            ) {
                return Denial.RESTRICTED
            }
        }

        return null
    }

    /**
     * Early CLOSED for filters that explicitly target a managed private group's content
     * (`#h`) or a hidden group's metadata (`#d` + 39xxx kinds) without being
     * authenticated as a member. Active regardless of the global auth toggle. This is a
     * courtesy signal — the per-event [GroupManager.canRead] filter in
     * [EventRepository.subscribe] and the live fanout remain the actual boundary for
     * filters that never mention the group id.
     */
    private fun nip29Check(filter: EventFilter, connection: Connection): Denial? {
        if (!GroupManager.hasGroups()) return null

        val contentGroups = filter.tags["h"].orEmpty().mapNotNull { GroupManager.get(it) }
            .filter { it.isPrivate && !it.isDeleted }
        val metadataGroups = if (filter.kinds.any { it in Nip29.METADATA_KINDS }) {
            filter.tags["d"].orEmpty().mapNotNull { GroupManager.get(it) }
                .filter { it.isHidden && !it.isDeleted }
        } else {
            emptyList()
        }

        val protectedGroups = contentGroups + metadataGroups
        if (protectedGroups.isEmpty()) return null
        if (connection.users.isEmpty()) return Denial.AUTH_REQUIRED

        val allowed = protectedGroups.all { group ->
            connection.users.any { user -> GroupManager.isPrivileged(group, user) || group.isMember(user) }
        }
        return if (allowed) null else Denial.RESTRICTED
    }
}
