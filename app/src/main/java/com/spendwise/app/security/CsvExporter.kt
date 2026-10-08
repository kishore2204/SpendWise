package com.spendwise.app.security

import androidx.room.Dao
import androidx.room.Query
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.data.db.entity.TxnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class ExportRow(
    val id: Long,
    val occurredAt: Long,
    val kind: TxnKind,
    val status: TxnStatus,
    val amountPaise: Long,
    val categoryName: String?,
    val merchantRaw: String?,
    val paymentMethod: PaymentMethod,
    val note: String?,
    val bankRef: String?,
)

data class ExportSplit(val txnId: Long, val categoryName: String, val amountPaise: Long)

/** CSV export reads ONE account (CLAUDE.md rule #1: an export never mixes accounts). Ignored rows are left out. */
@Dao
interface ExportDao {
    @Query(
        """
        SELECT t.id, t.occurredAt, t.kind, t.status, t.amountPaise, c.name AS categoryName, t.merchantRaw, t.paymentMethod, t.note, t.bankRef
        FROM txn t LEFT JOIN category c ON c.id = t.categoryId
        WHERE t.accountId = :accountId AND t.status != 'IGNORED'
        ORDER BY t.occurredAt, t.id
        """,
    )
    suspend fun rows(accountId: Long): List<ExportRow>

    @Query(
        """
        SELECT s.txnId AS txnId, c.name AS categoryName, s.amountPaise AS amountPaise
        FROM txn_split s JOIN txn t ON t.id = s.txnId JOIN category c ON c.id = s.categoryId
        WHERE t.accountId = :accountId ORDER BY s.id
        """,
    )
    suspend fun splits(accountId: Long): List<ExportSplit>
}

/**
 * Plain CSV of one account (opt-in, with a warning in the UI: it is NOT encrypted — ADR-002 rule 7). Amounts are exact
 * rupees from paise ("-450.00"); text cells that a spreadsheet would run as a formula are neutralised.
 */
@Singleton
class CsvExporter @Inject constructor(private val db: SpendWiseDatabase) {

    suspend fun export(accountId: Long, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val account = db.accountDao().getById(accountId) ?: error("Account not found")
        val rows = db.exportDao().rows(accountId)
        val splits = db.exportDao().splits(accountId).groupBy { it.txnId }
        val accountName = "${account.nickname} ${account.bankName} ${account.last4}".trim()
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write('﻿'.toString()) // so Excel reads ₹ and names as UTF-8
            w.write(HEADER.joinToString(",") { cell(it) })
            w.write("\r\n")
            for (r in rows) {
                val local = Instant.ofEpochMilli(r.occurredAt).atZone(IndiaTime.ZONE)
                val category = splits[r.id]?.joinToString("; ") { "${it.categoryName} ${rupees(it.amountPaise)}" }?.let { "Split: $it" } ?: r.categoryName.orEmpty()
                val line = listOf(
                    DATE.format(local),
                    TIME.format(local),
                    accountName,
                    r.kind.name.lowercase().replace('_', ' '),
                    if (r.status == TxnStatus.PENDING) "waiting for review" else "confirmed",
                    rupees(r.amountPaise),
                    category,
                    r.merchantRaw.orEmpty(),
                    r.paymentMethod.name.takeIf { r.paymentMethod != PaymentMethod.UNKNOWN }.orEmpty(),
                    r.note.orEmpty(),
                    r.bankRef.orEmpty(),
                )
                w.write(line.mapIndexed { i, v -> if (i == AMOUNT_COLUMN) v else cell(v) }.joinToString(","))
                w.write("\r\n")
            }
        }
        rows.size
    }

    companion object {
        private val HEADER = listOf("Date", "Time", "Account", "Type", "Status", "Amount (INR)", "Category", "Merchant", "Paid by", "Note", "Bank reference")
        private const val AMOUNT_COLUMN = 5
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        /** Exact rupees from paise, never via Double: -45050 → "-450.50". */
        fun rupees(paise: Long): String {
            val sign = if (paise < 0) "-" else ""
            val abs = if (paise == Long.MIN_VALUE) error("out of range") else kotlin.math.abs(paise)
            return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
        }

        /** RFC 4180 quoting, plus a leading ' for anything a spreadsheet would treat as a formula. */
        fun cell(value: String): String {
            val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }
    }
}
