package com.spendwise.app.transaction.ingest

import com.spendwise.app.core.time.Clock
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.NotMineEntity
import com.spendwise.app.data.db.entity.ParseStatus
import com.spendwise.app.domain.AccountLedger
import com.spendwise.app.domain.CreateAccountResult
import com.spendwise.app.domain.NewAccount
import com.spendwise.app.transaction.parsing.BankRegistry
import com.spendwise.app.transaction.parsing.ParseResult
import com.spendwise.app.transaction.parsing.RawSms
import com.spendwise.app.transaction.parsing.TransactionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A bank account seen in SMS that is not in the app. Only the identity — no amounts. */
data class DetectedAccount(val bankCode: String, val bankName: String, val last4: String, val messageCount: Int, val lastSeenAt: Long)

/**
 * "New account detected · HDFC ••••1234 · Not mine / Add account" (ADR-005 §7). An unknown account is never created
 * automatically; its messages wait as UNKNOWN_ACCOUNT until the user decides, then they are processed again.
 */
@Singleton
class DetectedAccounts @Inject constructor(
    private val db: SpendWiseDatabase,
    private val accounts: AccountLedger,
    private val processor: IngestProcessor,
    private val clock: Clock,
) {
    val detected: Flow<List<DetectedAccount>> = db.rawMessageDao().observeUnknownAccount().map { rows ->
        rows.mapNotNull { row ->
            val parsed = TransactionParser.parse(RawSms(row.sender, row.body ?: return@mapNotNull null, row.sentAt)) as? ParseResult.Parsed
            val last4 = parsed?.txn?.last4 ?: return@mapNotNull null
            Triple(parsed.txn.bankCode, last4, row.sentAt)
        }
            .groupBy { it.first to it.second }
            .map { (key, hits) -> DetectedAccount(key.first, BankRegistry.displayName(key.first), key.second, hits.size, hits.maxOf { it.third }) }
            .sortedByDescending { it.lastSeenAt }
    }.flowOn(Dispatchers.Default)

    /** Remembered forever; those messages lose their text and are never asked about again. */
    suspend fun markNotMine(bankCode: String, last4: String) {
        db.notMineDao().insert(NotMineEntity(bankCode, last4, clock.now()))
        reprocess()
    }

    /** Creates the account the user confirmed, then its waiting messages become pending transactions in it. */
    suspend fun add(bankCode: String, last4: String, nickname: String, type: AccountType, openingPaise: Long): CreateAccountResult {
        val result = accounts.createAccount(NewAccount(bankCode, BankRegistry.displayName(bankCode), last4, nickname, type, openingPaise))
        if (result is CreateAccountResult.Created || result == CreateAccountResult.Duplicate) reprocess()
        return result
    }

    /** Re-runs waiting unknown-account messages, e.g. after any account was added. */
    suspend fun reprocess() {
        db.rawMessageDao().requeue(ParseStatus.UNKNOWN_ACCOUNT)
        processor.drain()
    }
}
