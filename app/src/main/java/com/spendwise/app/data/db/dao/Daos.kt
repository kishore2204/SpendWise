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
import com.spendwise.app.data.db.entity.TxnSplitEntity
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

    @Query("SELECT id FROM account")
    suspend fun allIds(): List<Long>

    /** Accounts are identified by (bank, accountIdentifier) — CLAUDE.md. */
    @Query("SELECT * FROM account WHERE bankCode = :bankCode AND last4 = :last4")
    suspend fun findByIdentifier(bankCode: String, last4: String): AccountEntity?

    @Query("SELECT * FROM account WHERE bankCode = :bankCode AND archivedAt IS NULL")
    suspend fun activeForBank(bankCode: String): List<AccountEntity>

    /** The Cash wallet, the destination of an ATM withdrawal (bank → Cash transfer). */
    @Query("SELECT * FROM account WHERE type = 'CASH' AND archivedAt IS NULL ORDER BY createdAt, id LIMIT 1")
    suspend fun cashWallet(): AccountEntity?

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
     *  - Shared bills (ADR-011): Spent is the user's OWN share — friends' parts are reported as "lent"; a friend's
     *    REPAYMENT is reported as "paid back", never income.
     * Spent can be negative in a month where refunds exceed spending; the UI shows that as a net credit.
     */
    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN kind IN ('INCOME','INTEREST') THEN amountPaise
                            WHEN kind = 'REVERSAL' AND amountPaise < 0 THEN amountPaise ELSE 0 END), 0) AS incomePaise,
          COALESCE(SUM(CASE WHEN kind IN ('EXPENSE','FEE') THEN -amountPaise
                            WHEN kind = 'REFUND' THEN -amountPaise
                            WHEN kind = 'REVERSAL' AND amountPaise > 0 THEN -amountPaise ELSE 0 END), 0)
            - (SELECT COALESCE(SUM(e.excludedPaise), 0) FROM v_share_excluded e
               WHERE e.accountId = :accountId AND e.localEpochDay BETWEEN :fromDay AND :toDay) AS spentPaise,
          (SELECT COALESCE(SUM(e.excludedPaise), 0) FROM v_share_excluded e
           WHERE e.accountId = :accountId AND e.localEpochDay BETWEEN :fromDay AND :toDay) AS lentPaise,
          COALESCE(SUM(CASE WHEN kind = 'REPAYMENT' THEN amountPaise ELSE 0 END), 0) AS repaidPaise
        FROM v_confirmed_txn
        WHERE accountId = :accountId AND localEpochDay BETWEEN :fromDay AND :toDay
        """,
    )
    fun observePeriodSummary(accountId: Long, fromDay: Long, toDay: Long): Flow<PeriodSummary>
}

data class PeriodSummary(
    val incomePaise: Long,
    val spentPaise: Long,
    /** Friends' parts of this account's bills (not the user's spending). */
    val lentPaise: Long = 0,
    /** Repayments from friends that landed in this account (not income). */
    val repaidPaise: Long = 0,
)

data class BalanceTotals(val displayedPaise: Long, val fromTransactionsPaise: Long)

@Dao
interface AccountCategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<AccountCategoryEntity>)

    /** Shows a category again in ONE account's overlay — never touches other accounts' choices. */
    @Query("UPDATE account_category SET hidden = 0 WHERE accountId = :accountId AND categoryId = :categoryId")
    suspend fun unhide(accountId: Long, categoryId: Long)

    @Query("SELECT COUNT(*) FROM account_category WHERE accountId = :accountId")
    suspend fun countFor(accountId: Long): Int
}

@Dao
interface CategoryDao {
    /** Categories visible for one account (its overlay), by kind, excluding archived and system ones. */
    @Query(
        """
        SELECT c.* FROM category c JOIN account_category ac ON ac.categoryId = c.id
        WHERE ac.accountId = :accountId AND ac.hidden = 0 AND c.archivedAt IS NULL AND c.kind = :kind
        ORDER BY ac.sortOrder
        """,
    )
    fun observeVisible(accountId: Long, kind: com.spendwise.app.data.db.entity.CategoryKind): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM category WHERE archivedAt IS NULL ORDER BY sortOrder")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY sortOrder")
    suspend fun getAll(): List<CategoryEntity>

    /** Every category incl. archived — names for analytics of older months. */
    @Query("SELECT * FROM category")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity): Long

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Long

    @Query("SELECT COALESCE(MAX(id), 0) FROM category")
    suspend fun maxId(): Long

    @Query("UPDATE category SET archivedAt = NULL WHERE id = :id")
    suspend fun unarchive(id: Long)
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

    @androidx.room.Update
    suspend fun update(txn: TxnEntity)

    @Query("DELETE FROM txn WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM transfer WHERE fromTxnId = :txnId OR toTxnId = :txnId")
    suspend fun transferFor(txnId: Long): TransferEntity?

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM txn WHERE refundOfId = :originalId AND status != 'IGNORED'")
    suspend fun refundedSoFar(originalId: Long): Long

    @Query("SELECT COUNT(*) FROM txn WHERE refundOfId = :originalId")
    suspend fun refundCount(originalId: Long): Int

    /**
     * After the user teaches "merchant → category", pending SMS rows of that merchant (any account — category labels
     * are global, ADR-001) take it too. Confirmed history is never rewritten.
     */
    @Query("UPDATE txn SET categoryId = :categoryId WHERE status = 'PENDING' AND merchantKey = :merchantKey AND kind IN (:kinds) AND id != :exceptId")
    suspend fun categorisePendingFor(merchantKey: String, categoryId: Long, kinds: List<com.spendwise.app.data.db.entity.TxnKind>, exceptId: Long)

    /** Refunds net against their original's category (ADR-004), so they follow it when it changes. */
    @Query("UPDATE txn SET categoryId = :categoryId WHERE refundOfId = :originalId")
    suspend fun setRefundsCategory(originalId: Long, categoryId: Long?)

    // ---- Lists: always ONE account. Pending rows are listed (with a badge); ignored rows are not. ----

    @Query(LIST_SELECT + " WHERE t.accountId = :accountId AND t.status != 'IGNORED' ORDER BY t.occurredAt DESC, t.id DESC")
    fun pagedForAccount(accountId: Long): androidx.paging.PagingSource<Int, TxnRow>

    @Query(LIST_SELECT + " WHERE t.accountId = :accountId AND t.status != 'IGNORED' ORDER BY t.occurredAt DESC, t.id DESC LIMIT :limit")
    fun observeRecent(accountId: Long, limit: Int): Flow<List<TxnRow>>

    @Query(LIST_SELECT + " WHERE t.id = :id")
    fun observeRow(id: Long): Flow<TxnRow?>

    // ---- Splits ----

    @Query("SELECT * FROM txn_split WHERE txnId = :txnId ORDER BY id")
    fun observeSplits(txnId: Long): Flow<List<TxnSplitEntity>>

    @Query("SELECT * FROM txn_split WHERE txnId = :txnId ORDER BY id")
    suspend fun getSplits(txnId: Long): List<TxnSplitEntity>

    @Query("DELETE FROM txn_split WHERE txnId = :txnId")
    suspend fun clearSplits(txnId: Long)

    @Insert
    suspend fun insertSplits(splits: List<TxnSplitEntity>)

    companion object {
        /** Row for lists. The counterparty is the OTHER leg's account NAME only — never its money (isolation). */
        const val LIST_SELECT = """
            SELECT t.id, t.accountId, t.amountPaise, t.kind, t.status, t.confidence, t.occurredAt, t.merchantRaw, t.note,
                   t.categoryId, c.name AS categoryName, c.iconKey AS categoryIcon, t.paymentMethod, t.refundOfId, t.possibleDupOfId,
                   (SELECT a.nickname FROM transfer tr
                      JOIN txn o ON o.id = CASE WHEN tr.fromTxnId = t.id THEN tr.toTxnId ELSE tr.fromTxnId END
                      JOIN account a ON a.id = o.accountId
                     WHERE tr.fromTxnId = t.id OR tr.toTxnId = t.id) AS counterpartyAccount,
                   EXISTS(SELECT 1 FROM txn_split s WHERE s.txnId = t.id) AS hasSplits
            FROM txn t LEFT JOIN category c ON c.id = t.categoryId"""
    }
}

/** A transaction as shown in lists and detail. */
data class TxnRow(
    val id: Long,
    val accountId: Long,
    val amountPaise: Long,
    val kind: com.spendwise.app.data.db.entity.TxnKind,
    val status: com.spendwise.app.data.db.entity.TxnStatus,
    val confidence: com.spendwise.app.data.db.entity.Confidence,
    val occurredAt: Long,
    val merchantRaw: String?,
    val note: String?,
    val categoryId: Long?,
    val categoryName: String?,
    val categoryIcon: String?,
    val paymentMethod: com.spendwise.app.data.db.entity.PaymentMethod,
    val refundOfId: Long?,
    /** Set when an SMS may repeat an existing transaction (ADR-004 fuzzy dedup) — the user decides. */
    val possibleDupOfId: Long?,
    val counterpartyAccount: String?,
    val hasSplits: Boolean,
)

/** Merchant → category rules (ADR-001). accountId 0 = global; account rules win. */
@Dao
interface MerchantRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: com.spendwise.app.data.db.entity.MerchantRuleEntity)

    @Query(
        """
        SELECT categoryId FROM merchant_rule
        WHERE merchantKey = :merchantKey AND accountId IN (:accountId, 0)
        ORDER BY CASE WHEN accountId = :accountId THEN 0 ELSE 1 END LIMIT 1
        """,
    )
    suspend fun lookup(accountId: Long, merchantKey: String): Long?
}
