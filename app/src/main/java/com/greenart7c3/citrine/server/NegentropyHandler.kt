package com.greenart7c3.citrine.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.nip29.GroupManager
import com.vitorpamplona.negentropy.Negentropy
import com.vitorpamplona.negentropy.storage.StorageVector

@OptIn(ExperimentalStdlibApi::class)
object NegentropyHandler {
    private const val FRAME_SIZE_LIMIT = 50_000L
    private const val MAX_EVENTS = 250_000
    private const val MAX_SESSIONS_PER_CONNECTION = 4

    suspend fun handleOpen(
        connection: Connection,
        subId: String,
        filterNode: JsonNode,
        initialMsgHex: String,
        appDatabase: AppDatabase,
        objectMapper: ObjectMapper,
    ) {
        val filter = parseFilter(filterNode, objectMapper)

        val refusal = openRefusal(connection, subId, filter, appDatabase, objectMapper)
        if (refusal != null) {
            connection.trySend(refusal)
            return
        }

        val ne = Negentropy(buildStorage(connection, filter, appDatabase), FRAME_SIZE_LIMIT)
        connection.negentropySessions.put(subId, ne)

        reconcileAndReply(connection, subId, ne, initialMsgHex, "NEG-OPEN", objectMapper)
    }

    private fun openRefusal(
        connection: Connection,
        subId: String,
        filter: EventFilter,
        appDatabase: AppDatabase,
        objectMapper: ObjectMapper,
    ): String? = when (AuthGate.check(filter, connection)) {
        AuthGate.Denial.AUTH_REQUIRED -> negError(objectMapper, subId, "auth-required: we only accept events from registered users")
        AuthGate.Denial.RESTRICTED -> negError(objectMapper, subId, "restricted: we only accept events from registered users")
        null -> capacityRefusal(connection, subId, filter, appDatabase, objectMapper)
    }

    private fun capacityRefusal(
        connection: Connection,
        subId: String,
        filter: EventFilter,
        appDatabase: AppDatabase,
        objectMapper: ObjectMapper,
    ): String? = when {
        EventRepository.countQuery(appDatabase, filter) > MAX_EVENTS -> objectMapper.writeValueAsString(
            listOf("NEG-ERR", subId, "blocked: query too big", MAX_EVENTS),
        )

        // Each open session holds a materialized id set plus reconciliation state until
        // the client finishes or closes it; abandoned sessions must not accumulate.
        connection.negentropySessions.size >= MAX_SESSIONS_PER_CONNECTION -> negError(objectMapper, subId, "blocked: too many concurrent sessions")

        else -> null
    }

    private suspend fun buildStorage(connection: Connection, filter: EventFilter, appDatabase: AppDatabase): StorageVector {
        // NIP-29: with private groups on the relay, a broad filter's id set could reveal
        // private-group event ids, so the storage is built from readable events only.
        val nip29Gate = GroupManager.hasPrivateGroups()
        return StorageVector().apply {
            if (nip29Gate) {
                EventRepository.batchedQuery(appDatabase, filter, null) { batch ->
                    for (row in batch) {
                        val event = row.toEvent()
                        if (GroupManager.canRead(event, connection)) {
                            insert(event.createdAt, event.id)
                        }
                    }
                }
            } else {
                EventRepository.batchedIdsAndCreatedAt(appDatabase, filter) { batch ->
                    batch.forEach { insert(it.createdAt, it.id) }
                }
            }
            seal()
        }
    }

    fun handleMsg(
        connection: Connection,
        subId: String,
        msgHex: String,
        objectMapper: ObjectMapper,
    ) {
        val ne = connection.negentropySessions[subId]
        if (ne == null) {
            connection.trySend(negError(objectMapper, subId, "closed: unknown subscription"))
            return
        }

        reconcileAndReply(connection, subId, ne, msgHex, "NEG-MSG", objectMapper)
    }

    private fun reconcileAndReply(
        connection: Connection,
        subId: String,
        ne: Negentropy,
        msgHex: String,
        frameType: String,
        objectMapper: ObjectMapper,
    ) {
        val response = try {
            ne.reconcile(msgHex.hexToByteArray())
        } catch (e: Exception) {
            Log.d(Citrine.TAG, "negentropy reconcile failed on $frameType $subId", e)
            connection.negentropySessions.remove(subId)
            connection.trySend(negError(objectMapper, subId, "error: ${e.message ?: "reconcile failed"}"))
            return
        }

        val msg = response.msg
        if (msg == null) {
            connection.negentropySessions.remove(subId)
        }
        connection.trySend(
            objectMapper.writeValueAsString(
                listOf("NEG-MSG", subId, (msg ?: ByteArray(0)).toHexString()),
            ),
        )
    }

    fun handleClose(connection: Connection, subId: String) {
        connection.negentropySessions.remove(subId)
    }

    private fun parseFilter(node: JsonNode, objectMapper: ObjectMapper): EventFilter {
        val tags = node.properties().asSequence()
            .filter { it.key.startsWith("#") }
            .associate { entry -> entry.key.substringAfter("#") to entry.value.map { it.asText() }.toSet() }
        val base = objectMapper.treeToValue(node, EventFilter::class.java)
        return base.copy(tags = tags)
    }

    private fun negError(objectMapper: ObjectMapper, subId: String, reason: String): String = objectMapper.writeValueAsString(listOf("NEG-ERR", subId, reason))
}
