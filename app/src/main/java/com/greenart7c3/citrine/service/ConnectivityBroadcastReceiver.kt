package com.greenart7c3.citrine.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.greenart7c3.citrine.Citrine
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that listens for network connectivity changes and reconnects
 * the Nostr client when connectivity is restored.
 */
class ConnectivityBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) {
            return
        }

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        val isConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities != null &&
                (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected == true
        }

        Log.d(Citrine.TAG, "Network connectivity changed: isConnected=$isConnected")

        if (isConnected) {
            // Connectivity restored - reconnect the client
            Log.d(Citrine.TAG, "Connectivity restored, reconnecting Nostr client")
            Citrine.instance.applicationScope.launch {
                try {
                    if (!Citrine.instance.client.isActive()) {
                        Citrine.instance.client.connect()
                        Log.d(Citrine.TAG, "Nostr client reconnected after connectivity restored")
                    } else {
                        Log.d(Citrine.TAG, "Nostr client already active, skipping reconnect")
                    }
                } catch (e: Exception) {
                    Log.e(Citrine.TAG, "Failed to reconnect Nostr client after connectivity restored", e)
                }
            }
        } else {
            Log.d(Citrine.TAG, "Network disconnected")
        }
    }
}
