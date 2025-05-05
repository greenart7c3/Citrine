package com.greenart7c3.citrine.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.server.Settings

class BootBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Settings.startOnBoot) {
            Log.d(Citrine.TAG, "Start on boot is disabled")
            return
        }
        if (intent.action == Intent.ACTION_PACKAGE_REPLACED && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && intent.dataString?.contains("com.greenart7c3.citrine") == true) {
            Citrine.getInstance().startService()
        } else if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Citrine.getInstance().startService()
        } else if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Citrine.getInstance().startService()
        }
    }
}
