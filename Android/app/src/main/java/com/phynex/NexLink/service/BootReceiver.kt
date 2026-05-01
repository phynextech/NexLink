package com.phynex.NexLink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts NexLink connection service after device reboot */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            NexLinkConnectionService.start(context)
        }
    }
}
