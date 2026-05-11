package com.greenart7c3.citrine.server

import com.greenart7c3.citrine.utils.KINDS_PRIVATE_EVENTS

object AuthGate {
    enum class Denial { AUTH_REQUIRED, RESTRICTED }

    fun check(filter: EventFilter, connection: Connection): Denial? {
        if (!Settings.authEnabled) return null

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
}
