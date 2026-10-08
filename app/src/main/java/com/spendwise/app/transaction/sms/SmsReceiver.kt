package com.spendwise.app.transaction.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.spendwise.app.data.db.entity.MessageSource
import com.spendwise.app.transaction.ingest.IncomingSms
import com.spendwise.app.transaction.ingest.SenderGate
import com.spendwise.app.transaction.parsing.RawSms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SMS_RECEIVED (ADR-005 §1): event-driven, never a scan. Joins multipart messages, drops anything not from an
 * allowlisted bank sender, stages the rest and hands off to [IngestWorker]. It never parses — a parser bug can't
 * lose a message — and never logs bodies, amounts or account numbers.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val receivedAt = System.currentTimeMillis()
        val messages = parts
            .groupBy { it.originatingAddress.orEmpty() }
            .filterKeys(SenderGate::allows)
            .map { (sender, segments) ->
                IncomingSms(RawSms(sender, segments.joinToString("") { it.messageBody.orEmpty() }, segments.first().timestampMillis), receivedAt)
            }
        if (messages.isEmpty()) return

        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                SmsEntryPoint.get(app).stager().stage(messages, MessageSource.SMS_LIVE)
                IngestWorker.enqueue(app)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
