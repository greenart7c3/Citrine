package com.greenart7c3.citrine.service

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.greenart7c3.citrine.Citrine
import kotlinx.coroutines.launch

class ClipboardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent != null && intent.hasExtra("url")) {
            val url = intent.getStringExtra("url")

            // Copy the URL to the clipboard
            val clipboard: ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Copied URL", url)
            clipboard.setPrimaryClip(clip)

            // Show a toast message
            Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
        } else if (intent != null && intent.hasExtra("job")) {
            Citrine.instance.cancelJob()
            NotificationManagerCompat.from(context).cancel(2)
            Citrine.instance.applicationScope.launch {
                Citrine.instance.client.disconnect()
            }
        }
    }
}
