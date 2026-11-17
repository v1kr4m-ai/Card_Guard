package com.cursedpirate.cardguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        // Multipart SMS arrive as several parts from one sender: join them.
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val ts = msgs.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { AppDb.get(ctx).dao().ingest(body, ts) } finally { pending.finish() }
        }
    }
}

/** One-shot scan of existing inbox (needs READ_SMS). Returns number of new transactions. */
suspend fun importInbox(ctx: Context): Int {
    val dao = AppDb.get(ctx).dao()
    var n = 0
    ctx.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
        null, null, "${Telephony.Sms.DATE} ASC",
    )?.use { c ->
        while (c.moveToNext()) if (dao.ingest(c.getString(0) ?: "", c.getLong(1))) n++
    }
    return n
}
