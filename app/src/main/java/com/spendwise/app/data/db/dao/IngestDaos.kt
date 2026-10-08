package com.spendwise.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendwise.app.data.db.entity.NotMineEntity
import com.spendwise.app.data.db.entity.ParseStatus
import com.spendwise.app.data.db.entity.RawMessageEntity
import com.spendwise.app.data.db.entity.TxnEntity
import com.spendwise.app.data.db.entity.TxnSourceEntity
import kotlinx.coroutines.flow.Flow

/*
 * Phase 5 — SMS staging (ADR-005) and review (ADR-004 dedup). Message rows carry no money until parsed; every
 * transaction read here is scoped to ONE account, except transfer-pairing candidates, which by definition look at
 * the user's OTHER accounts and return rows (never sums).
 */

@Dao
interface RawMessageDao {
    /** Exactly-once per message: the fingerprint is UNIQUE, so a repeat returns -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(message: RawMessageEntity): Long

    @Query("SELECT * FROM raw_message WHERE parseStatus = 'RECEIVED' ORDER BY sentAt, id LIMIT :limit")
    suspend fun nextReceived(limit: Int): List<RawMessageEntity>

    @Query("UPDATE raw_message SET parseStatus = :status, parserVersion = :parserVersion, bankHint = :bankHint WHERE id = :id")
    suspend fun setStatus(id: Long, status: ParseStatus, parserVersion: Int, bankHint: String?)

    /** Non-financial, ignored and not-mine messages keep their fingerprint only (ADR-005 §10). */
    @Query("UPDATE raw_message SET parseStatus = :status, parserVersion = :parserVersion, bankHint = :bankHint, body = NULL WHERE id = :id")
    suspend fun setStatusDroppingBody(id: Long, status: ParseStatus, parserVersion: Int, bankHint: String?)

    @Query("UPDATE raw_message SET body = NULL WHERE id IN (SELECT rawMessageId FROM txn_source WHERE txnId = :txnId)")
    suspend fun dropBodiesFor(txnId: Long)

    @Query("UPDATE raw_message SET body = NULL WHERE id = :id")
    suspend fun dropBody(id: Long)

    /** Puts every row of [status] back in the queue, e.g. unknown-account rows after the user adds or rejects an account. */
    @Query("UPDATE raw_message SET parseStatus = 'RECEIVED' WHERE parseStatus = :status AND body IS NOT NULL")
    suspend fun requeue(status: ParseStatus): Int

    @Query("SELECT COUNT(*) FROM raw_message WHERE parseStatus = 'UNKNOWN_ACCOUNT' AND bankHint = :bankCode")
    suspend fun unknownAccountCount(bankCode: String): Int

    @Query("SELECT COUNT(*) FROM raw_message WHERE parseStatus = 'RECEIVED'")
    suspend fun receivedCount(): Int

    /** "Couldn't read": financial-looking bank messages no template understood. Never dropped silently. */
    @Query("SELECT * FROM raw_message WHERE parseStatus IN ('FINANCIAL_UNPARSED','ERROR') AND body IS NOT NULL ORDER BY sentAt DESC LIMIT 200")
    fun observeUnreadable(): Flow<List<RawMessageEntity>>

    @Query("SELECT COUNT(*) FROM raw_message WHERE parseStatus IN ('FINANCIAL_UNPARSED','ERROR') AND body IS NOT NULL")
    fun observeUnreadableCount(): Flow<Int>

    @Query("SELECT * FROM raw_message WHERE parseStatus = 'UNKNOWN_ACCOUNT' AND body IS NOT NULL ORDER BY sentAt DESC")
    fun observeUnknownAccount(): Flow<List<RawMessageEntity>>

    @Query("SELECT r.* FROM raw_message r JOIN txn_source s ON s.rawMessageId = r.id WHERE s.txnId = :txnId ORDER BY r.sentAt")
    suspend fun sourcesFor(txnId: Long): List<RawMessageEntity>

    /** The newest message the app has seen — the pull-to-refresh watermark (never the provider's `_id`). */
    @Query("SELECT MAX(sentAt) FROM raw_message WHERE source != 'MANUAL_IMPORT'")
    suspend fun latestSentAt(): Long?

    @Query("SELECT MAX(receivedAt) FROM raw_message WHERE source = 'SMS_LIVE'")
    fun observeLastLiveReceivedAt(): Flow<Long?>

    /** Settings → "Purge raw SMS text": parsed fields stay, the text goes. */
    @Query("UPDATE raw_message SET body = NULL WHERE parseStatus NOT IN ('RECEIVED','UNKNOWN_ACCOUNT')")
    suspend fun purgeBodies(): Int
}

@Dao
interface NotMineDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: NotMineEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM not_mine WHERE bankCode = :bankCode AND last4 = :last4)")
    suspend fun contains(bankCode: String, last4: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM not_mine WHERE bankCode = :bankCode)")
    suspend fun anyForBank(bankCode: String): Boolean
}

@Dao
interface ReviewDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: TxnSourceEntity): Long

    @Query("SELECT * FROM txn WHERE accountId = :accountId AND bankRef = :bankRef AND isCredit = :isCredit")
    suspend fun byBankRef(accountId: Long, bankRef: String, isCredit: Boolean): TxnEntity?

    /** Fuzzy-dedup candidates in ONE account: same signed amount, close in time, not ignored (ADR-004 dedup 3). */
    @Query(
        """
        SELECT * FROM txn WHERE accountId = :accountId AND amountPaise = :amountPaise
          AND occurredAt BETWEEN :from AND :to AND status != 'IGNORED'
        ORDER BY ABS(occurredAt - :at), id
        """,
    )
    suspend fun nearbySameAmount(accountId: Long, amountPaise: Long, at: Long, from: Long, to: Long): List<TxnEntity>

    @Query("SELECT COUNT(*) FROM txn_source WHERE txnId = :txnId")
    suspend fun sourceCount(txnId: Long): Int

    /** The SMS text behind ONE account's pending rows, so the review list can show what each one was about. */
    @Query(
        """
        SELECT s.txnId AS txnId, r.body AS body FROM txn_source s
        JOIN raw_message r ON r.id = s.rawMessageId
        JOIN txn t ON t.id = s.txnId
        WHERE t.accountId = :accountId AND t.status = 'PENDING' AND r.body IS NOT NULL
        ORDER BY r.sentAt
        """,
    )
    fun observePendingSmsText(accountId: Long): Flow<List<SmsText>>

    @Query("SELECT COUNT(*) FROM txn WHERE accountId = :accountId AND status = 'PENDING'")
    fun observePendingCount(accountId: Long): Flow<Int>

    @Query(TxnDao.LIST_SELECT + " WHERE t.accountId = :accountId AND t.status = 'PENDING' ORDER BY t.occurredAt DESC, t.id DESC")
    fun observePending(accountId: Long): Flow<List<TxnRow>>

    /** "Confirm all high-confidence" — never possible duplicates. */
    @Query("SELECT id FROM txn WHERE accountId = :accountId AND status = 'PENDING' AND confidence = 'HIGH' AND possibleDupOfId IS NULL")
    suspend fun pendingHighIds(accountId: Long): List<Long>

    /**
     * Transfer-pairing candidates for a leg in [accountId]: opposite sign and equal size in ANOTHER account, not
     * already paired or ignored, sharing the bank reference or within [windowMs]. Rows only — never summed.
     */
    @Query(
        """
        SELECT o.* FROM txn o
        WHERE o.accountId != :accountId AND o.amountPaise = -:amountPaise AND o.status != 'IGNORED'
          AND o.refundOfId IS NULL
          AND o.kind IN ('EXPENSE','INCOME','UNKNOWN','TRANSFER_OUT','TRANSFER_IN','ATM_WITHDRAWAL','CASH_DEPOSIT')
          AND NOT EXISTS (SELECT 1 FROM transfer tr WHERE tr.fromTxnId = o.id OR tr.toTxnId = o.id)
          AND ((:bankRef IS NOT NULL AND o.bankRef = :bankRef) OR ABS(o.occurredAt - :occurredAt) <= :windowMs)
        ORDER BY CASE WHEN o.bankRef = :bankRef THEN 0 ELSE 1 END, ABS(o.occurredAt - :occurredAt)
        LIMIT 5
        """,
    )
    suspend fun transferCandidates(accountId: Long, amountPaise: Long, bankRef: String?, occurredAt: Long, windowMs: Long): List<TxnEntity>
}

/** One message's text for one transaction. */
data class SmsText(val txnId: Long, val body: String?)
