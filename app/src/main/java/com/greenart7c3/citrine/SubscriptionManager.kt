package com.greenart7c3.citrine

import EOSE
import android.util.Log
import com.vitorpamplona.quartz.utils.TimeUtils
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Timer
import kotlin.concurrent.schedule

@OptIn(DelicateCoroutinesApi::class)
class SubscriptionManager(val subscription: Subscription) {
    private val timer: Timer = Timer()
    private var currentJob: Job? = null

    fun finalize() {
        timer.cancel()
        Log.d("timer", "finalize id: ${subscription.id}")
    }

    init {
        timer.schedule(
            0,
            15000
        ) {
            Log.d("timer", "executed timer id: ${subscription.id}")
            currentJob?.cancel()
            val oneHour = 60 * 60
            if ((TimeUtils.now() - subscription.initialTime) >= oneHour) {
                timer.cancel()
                Log.d("timer", "cancelling subscription after 1 hour id: ${subscription.id}")
                return@schedule
            }

            currentJob = GlobalScope.launch(Dispatchers.IO) {
                for (filter in subscription.filters) {
                    try {
                        runBlocking {
                            EventRepository.subscribe(
                                subscription,
                                filter
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e

                        Log.d("error", "Error reading data from database", e)
                        subscription.connection.session.send(
                            NoticeResult.invalid("Error reading data from database").toJson()
                        )
                    }
                }
                subscription.connection.session.send(EOSE(subscription.id).toJson())
            }
            runBlocking { currentJob?.join() }
        }
    }
}
