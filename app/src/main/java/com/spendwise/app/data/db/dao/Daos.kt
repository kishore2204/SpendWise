package com.spendwise.app.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendwise.app.data.db.entity.AccountCategoryEntity
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.BalanceAnchorEntity
import com.spendwise.app.data.db.entity.CategoryEntity
import com.spendwise.app.data.db.entity.TransferEntity
import com.spendwise.app.data.db.entity.TxnEntity
import kotlinx.coroutines.flow.Flow

/*
 * DAOs. Phase 3 adds accounts, balance anchors and per-account summaries.
 * Rule for every financial read added later: it takes an `accountId` and reads `v_confirmed_txn` (CLAUDE.md).
 */

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity): Long

    @Query("SELECT * FROM account WHERE archivedAt IS NULL ORDER BY createdAt, id")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT COUNT(*) FROM account WHERE archivedAt IS NULL")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM account WHERE id = :accountId")
    suspend fun getById(accountId: Long): AccountEntity?

    @Query("SELECT * FROM account WHERE id = :accountId")
    fun observeById(accountId: Long): Flow<AccountEntity?>

    @Query("UPDATE account SET nickname = :nickname, type = :type WHERE id = :accountId")
    suspend fun updateDetails(accountId: Long, nickname: String, type: AccountType)

    @Query("UPDATE account SET archivedAt = :at WHERE id = :accountId")
    suspend fun archive(accountId: Long, at: Long)

    /**
     * Every active account with ITS OWN balance (ADR-004 anchors), for the account list and switcher.
     * Each balance is a correlated subquery on `acc.id` — balances are never summed across accounts.
     */
    @Query(
        """
        SELECT acc.*, (
            COALESCE((SELECT b.balancePaise FROM balance_anchor b WHERE b.accountId = acc.id
                      ORDER BY b.effectiveAt DESC, b.id DESC LIMIT 1), 0)
          + COALESCE((SELECT SUM(t.amountPaise) FROM v_confirmed_txn t
                      WHERE t.accountId = acc.id
                        AND t.occurredAt > COALESCE((SELECT b2.effectiveAt FROM balance_anchor b2 WHERE b2.accountId = acc.id
                                                     ORDER BY b2.effectiveAt DESC, b2.id DESC LIMIT 1), -9223372036854775807)), 0)
        ) AS balancePaise
        FROM account acc
        WHERE acc.archivedAt IS NULL
        ORDER BY acc.createdAt, acc.id
        """,
    )
    fun observeActiveWithBalances(): Flow<List<AccountWithBalance>>
}

/** An account with its own displayed balance. Never a combined figure. */
data class AccountWithBalance(
    @Embedded val account: AccountEntity,
    val balancePaise: Long,
)

/** Balance math for ONE account (ADR-004). Every query takes `accountId` and reads only `v_confirmed_txn`. */
@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnchor(anchor: BalanceAnchorEntity): Long

    @Query("SELECT * FROM balance_anchor WHERE accountId = :accountId ORDER BY effectiveAt ASC, id ASC")
    fun observeAnchors(accountId: Long): Flow<List<BalanceAnchorEntity>>

    @Query("SELECT * FROM balance_anchor WHERE accountId = :accountId ORDER BY effectiveAt ASC, id ASC")
    suspend fun getAnchors(accountId: Long): List<BalanceAnchorEntity>

    /** Displayed balance = latest anchor + Σ confirmed legs strictly after it. */
    @Query(
        """
        SELECT COALESCE(a.balancePaise, 0)
             + COALESCE((SELECT SUM(t.amountPaise) FROM v_confirmed_txn t
                         WHERE t.accountId = :accountId
                           AND t.occurredAt > COALESCE(a.effectiveAt, -9223372036854775807)), 0)
        FROM (SELECT 1) AS one
        LEFT JOIN (SELECT balancePaise, effectiveAt FROM balance_anchor WHERE accountId = :accountId
                   ORDER BY effectiveAt DESC, id DESC LIMIT 1) AS a ON 1 = 1
        """,
    )
    fun observeDisplayedBalance(accountId: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM v_confirmed_txn WHERE accountId = :accountId")
    fun observeConfirmedTotal(accountId: Long): Flow<Long>

    /** Displayed balance AND Σ all confirmed in ONE query, so the breakdown never flickers between two emissions. */
    @Query(
        """
        SELECT
          COALESCE(a.balancePaise, 0)
            + COALESCE((SELECT SUM(t.amountPaise) FROM v_confirmed_txn t
                        WHERE t.accountId = :accountId
                          AND t.occurredAt > COALESCE(a.effectiveAt, -9223372036854775807)), 0) AS displayedPaise,
          COALESCE((SELECT SUM(amountPaise) FROM v_confirmed_txn WHERE accountId = :accountId), 0) AS fromTransactionsPaise
        FROM (SELECT 1) AS one
        LEFT JOIN (SELECT balancePaise, effectiveAt FROM balance_anchor WHERE accountId = :accountId
                   ORDER BY effectiveAt DESC, id DESC LIMIT 1) AS a ON 1 = 1
        """,
    )
    fun observeBreakdown(accountId: Long): Flow<BalanceTotals>

    @Query("SELECT * FROM balance_anchor WHERE accountId = :accountId ORDER BY effectiveAt DESC, id DESC LIMIT 1")
    suspend fun latestAnchor(accountId: Long): BalanceAnchorEntity?

    /** Σ confirmed legs at or before [toInclusive] for one account. */
    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM v_confirmed_txn WHERE accountId = :accountId AND occurredAt <= :toInclusive")
    suspend fun confirmedUpTo(accountId: Long, toInclusive: Long): Long

    /** Σ confirmed legs in (fromExclusive, toInclusive] for one account. */
    @Query(
        """
        SELECT COALESCE(SUM(amountPaise), 0) FROM v_confirmed_txn
        WHERE accountId = :accountId AND occurredAt > :fromExclusive AND occurredAt <= :toInclusive
        """,
    )
    suspend fun confirmedBetween(accountId: Long, fromExclusive: Long, toInclusive: Long): Long

    /**
     * Month summary for ONE account (net effect).
     *  - Income = INCOME + INTEREST, minus reversals of money that came in (negative REVERSAL).
     *  - Spent  = EXPENSE + FEE, minus REFUNDs and reversals of money that went out (positive REVERSAL).
     *  - Transfer-family kinds (TRANSFER_*, ATM_WITHDRAWAL, CASH_DEPOSIT) are never income or expense.
     * Spent can be negative in a month where refunds exceed spending; the UI shows that as a net credit.
     */
    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN kind IN ('INCOME','INTEREST') THEN amountPaise
                            WHEN kind = 'REVERSAL' AND amountPaise < 0 THEN amountPaise ELSE 0 END), 0) AS incomePaise,
          COALESCE(SUM(CASE WHEN kind IN ('EXPENSE','FEE') THEN -amountPaise
                            WHEN kind = 'REFUND' THEN -amountPaise
                            WHEN kind = 'REVERSAL' AND amountPaise > 0 THEN -amountPaise ELSE 0 END), 0) AS spentPaise
        FROM v_confirmed_txn
        WHERE accountId = :accountId AND localEpochDay BETWEEN :fromDay AND :toDay
        """,
    )
    fun observePeriodSummary(accountId: Long, fromDay: Long, toDay: Long): Flow<PeriodSummary>
}

data class PeriodSummary(val incomePaise: Long, val spentPaise: Long)

data class BalanceTotals(val displayedPaise: Long, val fromTransactionsPaise: Long)

@Dao
interface AccountCategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<AccountCategoryEntity>)

    @Query("SELECT COUNT(*) FROM account_category WHERE accountId = :accountId")
    suspend fun countFor(accountId: Long): Int
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE archivedAt IS NULL ORDER BY sortOrder")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY sortOrder")
    suspend fun getAll(): List<CategoryEntity>
}

@Dao
interface TxnDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(txn: TxnEntity): Long

    /** Dedup-friendly insert: returns -1 when the (accountId, bankRef, isCredit) unique index rejects it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(txn: TxnEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransfer(transfer: TransferEntity): Long

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun getById(id: Long): TxnEntity?

    /** Σ of confirmed legs for one account — reads the view, never `txn`. */
    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM v_confirmed_txn WHERE accountId = :accountId")
    suspend fun confirmedSum(accountId: Long): Long
}
