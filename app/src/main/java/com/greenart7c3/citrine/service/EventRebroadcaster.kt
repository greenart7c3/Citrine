package com.greenart7c3.citrine.service

import androidx.sqlite.db.SimpleSQLiteQuery
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.utils.KINDS_PRIVATE_EVENTS
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Publishes events already stored in the local database to a set of
 * user-selected external relays, tracking per relay/event OK responses.
 *
 * Results are counted per (event, relay) pair: a relay answering
 * ["OK", id, true] counts as accepted (or duplicate when the reason starts
 * with "duplicate"), ["OK", id, false] as rejected, and no answer before the
 * batch timeout as a failure.
 */
object EventRebroadcaster {
    enum class Status { IDLE, RUNNING, FINISHED, CANCELLED, FAILED }

    data class State(
        val status: Status = Status.IDLE,
        val totalEvents: Int = 0,
        val processedEvents: Int = 0,
        val accepted: Int = 0,
        val duplicates: Int = 0,
        val rejected: Int = 0,
        val failed: Int = 0,
        val skippedRelays: List<String> = emptyList(),
        val recentRejections: List<String> = emptyList(),
    )

    val state = MutableStateFlow(State())

    private const val DB_CHUNK_SIZE = 500
    private const val EVENT_BATCH_SIZE = 20
    private const val BATCH_TIMEOUT_MS = 30_000L
    private const val POLL_INTERVAL_MS = 250L
    private const val MAX_CONNECT_FAILURES = 3
    private const val MAX_REPORTED_REJECTIONS = 20

    suspend fun rebroadcast(
        database: AppDatabase,
        relays: List<NormalizedRelayUrl>,
        authorPubKey: String?,
        kinds: Set<Int>,
        since: Long?,
        until: Long?,
        includePrivateKinds: Boolean,
    ) {
        val ids = database.eventDao()
            .getIdsAndCreatedAt(buildIdsQuery(authorPubKey, kinds, since, until, includePrivateKinds))
            .map { it.id }

        state.value = State(status = Status.RUNNING, totalEvents = ids.size)

        if (ids.isEmpty()) {
            state.value = state.value.copy(status = Status.FINISHED)
            EventDownloader.setProgress("No events to rebroadcast")
            return
        }

        val client = Citrine.instance.client
        val collector = OkCollector()
        client.addConnectionListener(collector)
        if (!client.isActive()) {
            client.connect()
        }

        val activeRelays = relays.toMutableSet()
        try {
            EventDownloader.setProgress("Rebroadcasting ${ids.size} events to ${activeRelays.size} relays")

            ids.chunked(DB_CHUNK_SIZE).forEach { chunk ->
                val events = database.eventDao().getByIds(chunk).map { it.toEvent() }
                events.chunked(EVENT_BATCH_SIZE).forEach { batch ->
                    currentCoroutineContext().ensureActive()
                    if (activeRelays.isEmpty()) {
                        error("No reachable relays left")
                    }

                    val targets = activeRelays.toSet()
                    batch.forEach { event ->
                        client.publish(event, targets)
                    }
                    awaitOkResponses(batch, targets, collector)
                    tallyBatch(batch, targets, collector)
                    dropUnreachableRelays(activeRelays, collector)

                    val current = state.value
                    EventDownloader.setProgress(
                        "Rebroadcast ${current.processedEvents}/${current.totalEvents} events " +
                            "(${current.accepted} accepted, ${current.duplicates} duplicates, " +
                            "${current.rejected} rejected, ${current.failed} failed)",
                    )
                }
            }

            state.value = state.value.copy(status = Status.FINISHED)
            val result = state.value
            EventDownloader.setProgress(
                "Rebroadcast finished: ${result.accepted} accepted, ${result.duplicates} duplicates, " +
                    "${result.rejected} rejected, ${result.failed} failed",
            )
        } catch (e: CancellationException) {
            state.value = state.value.copy(status = Status.CANCELLED)
            throw e
        } catch (e: Exception) {
            Log.e(Citrine.TAG, "EventRebroadcaster: rebroadcast failed", e)
            state.value = state.value.copy(status = Status.FAILED)
            EventDownloader.setProgress("Rebroadcast failed: ${e.message}")
        } finally {
            client.removeConnectionListener(collector)
            client.disconnect()
        }
    }

    private fun buildIdsQuery(
        authorPubKey: String?,
        kinds: Set<Int>,
        since: Long?,
        until: Long?,
        includePrivateKinds: Boolean,
    ): SimpleSQLiteQuery {
        val where = StringBuilder("1 = 1")
        val params = mutableListOf<Any>()

        if (!authorPubKey.isNullOrBlank()) {
            where.append(" AND pubkey = ?")
            params.add(authorPubKey)
        }
        if (kinds.isNotEmpty()) {
            where.append(" AND kind IN (${kinds.joinToString(",") { "?" }})")
            params.addAll(kinds)
        }
        if (!includePrivateKinds) {
            where.append(" AND kind NOT IN (${KINDS_PRIVATE_EVENTS.joinToString(",") { "?" }})")
            params.addAll(KINDS_PRIVATE_EVENTS)
        }
        since?.let {
            where.append(" AND createdAt >= ?")
            params.add(it)
        }
        until?.let {
            where.append(" AND createdAt <= ?")
            params.add(it)
        }

        return SimpleSQLiteQuery(
            "SELECT id, createdAt FROM EventEntity WHERE $where ORDER BY createdAt ASC, id ASC",
            params.toTypedArray(),
        )
    }

    private suspend fun awaitOkResponses(
        batch: List<Event>,
        relays: Set<NormalizedRelayUrl>,
        collector: OkCollector,
    ) {
        val deadline = System.currentTimeMillis() + BATCH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val allResolved = batch.all { event ->
                relays.all { relay -> collector.result(event.id, relay) != null }
            }
            if (allResolved) return
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun tallyBatch(
        batch: List<Event>,
        relays: Set<NormalizedRelayUrl>,
        collector: OkCollector,
    ) {
        var accepted = 0
        var duplicates = 0
        var rejected = 0
        var failed = 0
        val rejections = mutableListOf<String>()

        batch.forEach { event ->
            relays.forEach { relay ->
                val ok = collector.result(event.id, relay)
                when {
                    ok == null -> failed++
                    ok.message.startsWith("duplicate", ignoreCase = true) -> duplicates++
                    ok.success -> accepted++
                    else -> {
                        rejected++
                        val reason = ok.message.ifBlank { "rejected" }
                        rejections.add("${relay.displayUrl()} · kind ${event.kind} · $reason")
                    }
                }
            }
        }

        state.value = state.value.let { current ->
            current.copy(
                processedEvents = current.processedEvents + batch.size,
                accepted = current.accepted + accepted,
                duplicates = current.duplicates + duplicates,
                rejected = current.rejected + rejected,
                failed = current.failed + failed,
                recentRejections = (current.recentRejections + rejections).takeLast(MAX_REPORTED_REJECTIONS),
            )
        }
    }

    private fun dropUnreachableRelays(
        activeRelays: MutableSet<NormalizedRelayUrl>,
        collector: OkCollector,
    ) {
        val unreachable = activeRelays.filter { relay ->
            collector.connectFailures(relay) >= MAX_CONNECT_FAILURES && !collector.hasResponded(relay)
        }
        if (unreachable.isNotEmpty()) {
            activeRelays.removeAll(unreachable.toSet())
            state.value = state.value.copy(
                skippedRelays = state.value.skippedRelays + unreachable.map { it.displayUrl() },
            )
            Log.d(Citrine.TAG, "EventRebroadcaster: skipping unreachable relays $unreachable")
        }
    }

    /** Collects ["OK", ...] responses and connection failures across all relays. */
    private class OkCollector : RelayConnectionListener {
        private val oks = ConcurrentHashMap<String, OkMessage>()
        private val failures = ConcurrentHashMap<NormalizedRelayUrl, Int>()
        private val responded = ConcurrentHashMap.newKeySet<NormalizedRelayUrl>()

        fun result(eventId: String, relay: NormalizedRelayUrl): OkMessage? = oks[key(eventId, relay)]

        fun connectFailures(relay: NormalizedRelayUrl): Int = failures[relay] ?: 0

        fun hasResponded(relay: NormalizedRelayUrl): Boolean = responded.contains(relay)

        override fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
            if (msg is OkMessage) {
                oks[key(msg.eventId, relay.url)] = msg
                responded.add(relay.url)
                failures.remove(relay.url)
            }
        }

        override fun onConnected(relay: IRelayClient, pingInMs: Int, usingCompression: Boolean) {
            failures.remove(relay.url)
        }

        override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
            failures.merge(relay.url, 1, Int::plus)
        }

        private fun key(eventId: String, relay: NormalizedRelayUrl) = "$eventId|${relay.url}"
    }
}
