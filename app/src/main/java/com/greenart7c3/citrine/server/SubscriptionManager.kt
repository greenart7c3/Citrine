package com.greenart7c3.citrine.server

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.nip29.GroupManager
import com.greenart7c3.citrine.service.crashreports.ReportAssembler
import kotlin.time.measureTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(DelicateCoroutinesApi::class)
class SubscriptionManager(
    val subscription: Subscription,
) {
    suspend fun execute() {
        subscription.scope.launch(Dispatchers.IO) {
            if (subscription.connection.session.outgoing.isClosedForSend) {
                EventSubscription.close(subscription.connection, subscription.id)
                Log.d(Citrine.TAG, "cancelling subscription isClosedForSend: ${subscription.id}")
                return@launch
            }

            if (subscription.count) {
                executeCount()
                return@launch
            }

            for (filter in subscription.filters) {
                if (sendClosedIfDenied(filter)) return@launch

                try {
                    val time = measureTime {
                        EventRepository.subscribe(
                            subscription,
                            filter,
                        )
                    }
                    Log.d(Citrine.TAG, "Subscription (${EventSubscription.count()}) ${subscription.id} took $time to execute $filter")
                } catch (e: OutOfMemoryError) {
                    failForOutOfMemory(e)
                    return@launch
                } catch (e: Exception) {
                    if (e is CancellationException) throw e

                    Log.d(Citrine.TAG, "Error reading data from database $filter", e)
                    subscription.connection.send(
                        NoticeResult.invalid("Error reading data from database").toJson(),
                    )
                }
            }

            subscription.connection.send(EOSE(subscription.id).toJson())
        }
    }

    // NIP-45: filters are OR'd into a single aggregated count result. No EOSE and no
    // live registration: a COUNT subscription never receives EVENT fanout.
    private suspend fun executeCount() {
        var total = 0
        for (filter in subscription.filters) {
            if (sendClosedIfDenied(filter)) return

            try {
                total += if (GroupManager.hasPrivateGroups()) {
                    var count = 0
                    EventRepository.batchedQuery(subscription.appDatabase, filter, null) { batch ->
                        count += batch.count {
                            GroupManager.canRead(it, subscription.connection)
                        }
                    }
                    count
                } else {
                    EventRepository.countQuery(subscription.appDatabase, filter)
                }
            } catch (e: OutOfMemoryError) {
                failForOutOfMemory(e)
                return
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                Log.d(Citrine.TAG, "Error reading data from database $filter", e)
                subscription.connection.send(
                    NoticeResult.invalid("Error reading data from database").toJson(),
                )
                return
            }
        }

        subscription.connection.send("[\"COUNT\",${subscription.escapedId},{\"count\":$total}]")
    }

    // Returns true when a CLOSED denial was sent and the caller must stop.
    private suspend fun sendClosedIfDenied(filter: EventFilter): Boolean = when (AuthGate.check(filter, subscription.connection)) {
        AuthGate.Denial.AUTH_REQUIRED -> {
            Log.d(Citrine.TAG, "cancelling subscription auth-required: ${subscription.id}")
            subscription.connection.send(ClosedResult.required(subscription.id).toJson())
            true
        }
        AuthGate.Denial.RESTRICTED -> {
            Log.d(Citrine.TAG, "cancelling subscription restricted: ${subscription.id} filter: $filter authed users: ${subscription.connection.users}")
            subscription.connection.send(ClosedResult.restricted(subscription.id).toJson())
            true
        }
        null -> false
    }

    /**
     * An `OutOfMemoryError` in one request must not take the whole relay down: the process
     * would die and every other client would lose its subscription. Instead cache a copyable
     * diagnostics report, tell the client why, and stop only this subscription.
     */
    private suspend fun failForOutOfMemory(e: OutOfMemoryError) {
        Log.e(Citrine.TAG, "Out of memory answering subscription ${subscription.id}", e)
        runCatching {
            Citrine.instance.crashReportCache.appendReport(
                ReportAssembler().buildReport(e),
            )
        }
        runCatching {
            subscription.connection.send(
                ClosedResult(subscription.id, "error: relay out of memory").toJson(),
            )
        }
        EventSubscription.close(subscription.connection, subscription.id)
    }
}
