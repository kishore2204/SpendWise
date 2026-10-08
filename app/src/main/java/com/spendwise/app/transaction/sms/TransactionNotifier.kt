package com.spendwise.app.transaction.sms

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.spendwise.app.MainActivity
import com.spendwise.app.R
import com.spendwise.app.core.merchant.Merchants
import com.spendwise.app.core.money.Money
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.Confidence
import com.spendwise.app.navigation.AppLink
import com.spendwise.app.transaction.ingest.createdPerAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * "New transactions" (ADR-005 §8). VISIBILITY_PRIVATE with a public version that has no amount, merchant or
 * account, so a locked screen only ever shows "New transaction · Unlock to view". Confirm requires unlocking.
 */
@Singleton
class TransactionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SpendWiseDatabase,
) {
    private val manager get() = NotificationManagerCompat.from(context)

    private fun allowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, "New transactions", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Bank SMS detected and waiting for your review"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    suspend fun notifyNew(txnIds: List<Long>) {
        if (txnIds.isEmpty() || !allowed()) return
        ensureChannel()
        // One account per notification: a burst (or an import) becomes one summary PER account, never a mixed count.
        val individual = mutableListOf<Long>()
        for ((account, ids) in db.createdPerAccount(txnIds)) {
            if (ids.size > MAX_INDIVIDUAL) {
                val code = SUMMARY_BASE - account.id.toInt()
                post(code, base("${ids.size} new transactions", "${account.nickname} ••${account.last4} · Tap to review", openIntent(AppLink.Review(account.id), code)).build())
            } else {
                individual += ids
            }
        }
        for (id in individual) {
            val t = db.txnDao().getById(id) ?: continue
            val account = db.accountDao().getById(t.accountId) ?: continue
            val who = t.merchantRaw?.let(Merchants::display) ?: if (t.amountPaise < 0) "Money out" else "Money in"
            val title = "${Money.format(abs(t.amountPaise))} · $who"
            val text = "${account.nickname} ••${account.last4} · " + if (t.confidence == Confidence.HIGH) "Confirm or edit" else "Needs your review"
            val builder = base(title, text, openIntent(AppLink.Transaction(id), id.toInt()))
            if (t.confidence == Confidence.HIGH && t.possibleDupOfId == null) {
                builder.addAction(
                    NotificationCompat.Action.Builder(0, "Confirm", actionIntent(ACTION_CONFIRM, id))
                        .setAuthenticationRequired(true) // never confirm money from a locked phone
                        .build(),
                )
                builder.addAction(NotificationCompat.Action.Builder(0, "Ignore", actionIntent(ACTION_IGNORE, id)).setAuthenticationRequired(true).build())
            }
            post(id.toInt(), builder.build())
        }
    }

    fun cancel(txnId: Long) = manager.cancel(txnId.toInt())

    private fun base(title: String, text: String, tap: PendingIntent): NotificationCompat.Builder {
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_spendwise)
            .setContentTitle("New transaction")
            .setContentText("Unlock to view")
            .build()
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_spendwise)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
    }

    @android.annotation.SuppressLint("MissingPermission") // checked in allowed()
    private fun post(id: Int, notification: Notification) = manager.notify(id, notification)

    private fun openIntent(link: AppLink, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .also(link::writeTo)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(action: String, txnId: Long): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_TXN, txnId)
        val code = (txnId * 2 + if (action == ACTION_CONFIRM) 0 else 1).toInt()
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val CHANNEL = "new_transactions"
        private const val GROUP = "com.spendwise.NEW_TRANSACTIONS"
        private const val SUMMARY_BASE = -1
        private const val MAX_INDIVIDUAL = 3
        const val ACTION_CONFIRM = "com.spendwise.action.CONFIRM"
        const val ACTION_IGNORE = "com.spendwise.action.IGNORE"
        const val EXTRA_TXN = "txnId"
    }
}

/** Confirm / Ignore from the notification (not exported; only our own PendingIntents reach it). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(TransactionNotifier.EXTRA_TXN, -1L).takeIf { it > 0 } ?: return
        val action = intent.action ?: return
        val ep = SmsEntryPoint.get(context)
        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    TransactionNotifier.ACTION_CONFIRM -> ep.ledger().confirm(id)
                    TransactionNotifier.ACTION_IGNORE -> ep.ledger().ignore(id)
                }
                ep.notifier().cancel(id)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
