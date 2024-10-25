package com.greenart7c3.citrine.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_REPLACED && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && intent.dataString?.contains("com.greenart7c3.citrine") == true) {
            val serviceIntent = Intent(context, WebSocketServerService::class.java)
            context.startService(serviceIntent)
        } else if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val serviceIntent = Intent(context, WebSocketServerService::class.java)
            context.startService(serviceIntent)
        } else if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, WebSocketServerService::class.java)
            context.startService(serviceIntent)
        }
    }
}
