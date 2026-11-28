package com.greenart7c3.citrine.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.logs.Log

private const val TAG = "RelayAggregator"

private val TRACKED_TRANSPORTS = intArrayOf(
    NetworkCapabilities.TRANSPORT_CELLULAR,
    NetworkCapabilities.TRANSPORT_WIFI,
    NetworkCapabilities.TRANSPORT_ETHERNET,
    NetworkCapabilities.TRANSPORT_BLUETOOTH,
    NetworkCapabilities.TRANSPORT_VPN,
)

private fun NetworkCapabilities.toSnapshot(network: Network) = NetworkSnapshot(
    networkId = network.networkHandle,
    transports = TRACKED_TRANSPORTS.filter { hasTransport(it) }.toSet(),
    validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
)

internal fun connectivityManager(context: Context): ConnectivityManager? = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

internal fun isOnMeteredNetwork(context: Context): Boolean {
    val cm = connectivityManager(context) ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    // Treat "no internet capability" as not-metered for our purposes — we'll have
    // bigger problems than bandwidth if there's no internet at all.
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

internal fun isOnLimitedNetwork(context: Context): Boolean {
    val cm = connectivityManager(context) ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    // Same rationale as above: when there's no internet capability at all, don't claim
    // the network is "limited" — let other failure paths handle it.
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
}

/**
 * Sockets opened on the previous network keep looking connected until a ping times
 * out, and Quartz may still hold a long retry backoff from the offline stretch. Drop
 * every socket, clear the backoff and redial; Quartz replays the stored REQs on
 * reconnect, so the aggregator's subscriptions come back on their own.
 */
internal fun resetRelayConnections(relayClient: RelayClient, reason: String) {
    Log.d(TAG, "Resetting relay connections: $reason")
    val client = relayClient.client
    client.disconnect()
    // disconnect() also clears each relay's backoff; this covers relays without a socket.
    client.resetBackoff()
    client.connect()
}

internal class AggregatorNetworkCallback(
    private val context: Context,
    private val relayClient: RelayClient,
    private val networkChangeDetector: () -> NetworkChangeDetector,
    private val scheduleRelayReset: (String) -> Unit,
    private val cancelPendingRelayReset: () -> Unit,
    private val scheduleNetworkEval: () -> Unit,
) : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        // onCapabilitiesChanged follows right away, but read them here too in
        // case it does not, so a new default network is never missed.
        connectivityManager(context)?.getNetworkCapabilities(network)?.let {
            onNetworkState(network, it)
        }
        scheduleNetworkEval()
    }

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        onNetworkState(network, capabilities)
        scheduleNetworkEval()
    }

    override fun onLost(network: Network) {
        Log.d(TAG, "Network lost: ${network.networkHandle}")
        if (networkChangeDetector().onLost(network.networkHandle)) {
            cancelPendingRelayReset()
            relayClient.client.disconnect()
        }
        scheduleNetworkEval()
    }

    private fun onNetworkState(network: Network, capabilities: NetworkCapabilities) {
        val reason = networkChangeDetector().onNetwork(capabilities.toSnapshot(network))
        if (reason != null) {
            Log.d(TAG, "Network ${network.networkHandle} change: $reason")
            scheduleRelayReset(reason)
        } else if (!relayClient.client.isActive()) {
            relayClient.client.connect()
        }
    }
}
