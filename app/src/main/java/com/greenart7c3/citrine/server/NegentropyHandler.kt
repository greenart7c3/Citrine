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
    private const val MAX_EVENTS = 1_000_000

    suspend fun handleOpen(
        connection: Connection,
        subId: String,
        filterNode: JsonNode,
        initialMsgHex: String,
        appDatabase: AppDatabase,
        objectMapper: ObjectMapper,
    ) {
        val filter = parseFilter(filterNode, objectMapper)

        when (AuthGate.check(filter, connection)) {
            AuthGate.Denial.AUTH_REQUIRED -> {
                connection.trySend(negError(objectMapper, subId, "auth-required: we only accept events from registered users"))
                return
            }
            AuthGate.Denial.RESTRICTED -> {
                connection.trySend(negError(objectMapper, subId, "restricted: we only accept events from registered users"))
                return
            }
            null -> Unit
        }

        val matchCount = EventRepository.countQuery(appDatabase, filter)
        if (matchCount > MAX_EVENTS) {
            connection.trySend(
                objectMapper.writeValueAsString(
                    listOf("NEG-ERR", subId, "blocked: query too big", MAX_EVENTS),
                ),
            )
            return
        }

        // NIP-29: with private groups on the relay, a broad filter's id set could reveal
        // private-group event ids, so the storage is built from readable events only.
        val nip29Gate = GroupManager.hasPrivateGroups()
        val storage = StorageVector().apply {
            if (nip29Gate) {
                EventRepository.query(appDatabase, filter).forEach {
                    val event = it.toEvent()
                    if (GroupManager.canRead(event, connection)) {
                        insert(event.createdAt, event.id)
                    }
                }
            } else {
                EventRepository.idsAndCreatedAt(appDatabase, filter).forEach {
                    insert(it.createdAt, it.id)
                }
            }
            seal()
        }

        val ne = Negentropy(storage, FRAME_SIZE_LIMIT)
        connection.negentropySessions.put(subId, ne)

        val response = try {
            ne.reconcile(initialMsgHex.hexToByteArray())
        } catch (e: Exception) {
            Log.d(Citrine.TAG, "negentropy reconcile failed on NEG-OPEN $subId", e)
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

        val response = try {
            ne.reconcile(msgHex.hexToByteArray())
        } catch (e: Exception) {
            Log.d(Citrine.TAG, "negentropy reconcile failed on NEG-MSG $subId", e)
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
