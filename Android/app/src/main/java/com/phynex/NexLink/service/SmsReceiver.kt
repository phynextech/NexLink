package com.phynex.NexLink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    companion object {
        var onSmsReceived: ((sender: String, body: String, timestamp: Long) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (msg in messages) {
                Log.d("SmsReceiver", "SMS from ${msg.originatingAddress}: ${msg.messageBody}")
                onSmsReceived?.invoke(
                    msg.originatingAddress ?: "Unknown",
                    msg.messageBody ?: "",
                    msg.timestampMillis
                )
            }
        }
    }
}
