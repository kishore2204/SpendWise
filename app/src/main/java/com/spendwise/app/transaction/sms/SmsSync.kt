package com.spendwise.app.transaction.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.spendwise.app.core.time.Clock
import com.spendwise.app.data.db.dao.RawMessageDao
import com.spendwise.app.data.db.entity.MessageSource
import com.spendwise.app.transaction.ingest.IncomingSms
import com.spendwise.app.transaction.ingest.IngestProcessor
import com.spendwise.app.transaction.ingest.IngestSummary
import com.spendwise.app.transaction.ingest.MessageStager
import com.spendwise.app.transaction.ingest.SenderGate
import com.spendwise.app.transaction.parsing.BankRegistry
import com.spendwise.app.transaction.parsing.RawSms
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the SMS inbox — ONLY when the user pulls to refresh or starts the historical import (CLAUDE.md: never on a
 * timer, never continuously). Filters by allowlisted bank senders in the query itself, and never uses `_id` or the
 * read flag (Samsung Messages changes both).
 */
@Singleton
class InboxReader @Inject constructor(@ApplicationContext private val context: Context) {

    fun read(fromMillis: Long, toMillis: Long): List<IncomingSms> {
        val tokens = BankRegistry.allTokens.toList()
        val selection = "date >= ? AND date < ? AND (" + tokens.joinToString(" OR ") { "address LIKE ?" } + ")"
        val args = arrayOf(fromMillis.toString(), toMillis.toString()) + tokens.map { "%-$it%" }
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT)
        val out = mutableListOf<IncomingSms>()
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, selection, args, "date ASC")?.use { c ->
            val iAddress = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iSent = c.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            while (c.moveToNext()) {
                val address = c.getString(iAddress) ?: continue
                if (!SenderGate.allows(address)) continue
                val body = c.getString(iBody) ?: continue
                // date_sent is the service-centre time, identical to the broadcast's timestampMillis (ADR-005 §2).
                out += IncomingSms(RawSms(address, body, c.getLong(iSent)), receivedAt = c.getLong(iDate))
            }
        }
        return out
    }
}

sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val done: Int, val total: Int) : ImportState
    data class Finished(val summary: IngestSummary) : ImportState
    data object Failed : ImportState
}

/** Pull-to-refresh and the historical import. Both stage messages and drain them through the one processor. */
@Singleton
class SmsSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: InboxReader,
    private val stager: MessageStager,
    private val processor: IngestProcessor,
    private val rawDao: RawMessageDao,
    private val clock: Clock,
    private val gate: com.spendwise.app.transaction.ingest.IngestGate,
) {
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    fun canReadSms(): Boolean = granted(Manifest.permission.READ_SMS)
    fun canReceiveSms(): Boolean = granted(Manifest.permission.RECEIVE_SMS)

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Re-reads allowlisted senders from (newest message seen − 72h). The overlap catches anything the receiver missed
     * (reboot, deep sleep); fingerprints make the repeat harmless. Returns null when SMS access is off.
     */
    suspend fun refresh(): IngestSummary? = withContext(Dispatchers.IO) {
        if (!canReadSms()) return@withContext null
        val now = clock.now()
        val since = (rawDao.latestSentAt() ?: now) - OVERLAP_MS
        val messages = runCatching { reader.read(since, now + 60_000) }.getOrElse { return@withContext null }
        gate.withLock {
            stager.stageLocked(messages, MessageSource.SMS_INBOX)
            processor.drainLocked()
        }
    }

    /** One-time import over [days] (30/90/180/365), in 7-day windows so progress is real and a rerun is idempotent. */
    suspend fun importHistory(days: Int) = withContext(Dispatchers.IO) {
        if (!canReadSms()) {
            _importState.value = ImportState.Failed
            return@withContext
        }
        val now = clock.now()
        val start = now - days * DAY_MS
        val windows = ((now - start + WINDOW_MS - 1) / WINDOW_MS).toInt()
        var summary = IngestSummary()
        try {
            for (w in 0 until windows) {
                _importState.value = ImportState.Running(w, windows)
                val from = start + w * WINDOW_MS
                val to = minOf(from + WINDOW_MS, now + 60_000)
                summary += gate.withLock {
                    stager.stageLocked(reader.read(from, to), MessageSource.SMS_INBOX)
                    processor.drainLocked()
                }
            }
            _importState.value = ImportState.Finished(summary)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _importState.value = ImportState.Failed
        }
    }

    fun clearImportState() {
        if (_importState.value !is ImportState.Running) _importState.value = ImportState.Idle
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
        const val WINDOW_MS = 7 * DAY_MS
        const val OVERLAP_MS = 72 * 3_600_000L
    }
}
