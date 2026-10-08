package com.spendwise.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendwise.app.data.db.entity.BudgetEntity
import com.spendwise.app.data.db.entity.RecurringRuleEntity
import com.spendwise.app.data.db.entity.TxnKind
import kotlinx.coroutines.flow.Flow

/*
 * Phase 6 analytics. EVERY query takes one accountId and reads v_confirmed_txn / v_share_excluded only
 * (CLAUDE.md rule #1 + "all balance/analytics queries read the view"). Aggregation happens in the pure
 * analytics engine (domain/analytics), which is unit-tested to agree with LedgerDao.observePeriodSummary.
 */

data class AnalyticsRow(
    val id: Long,
    val kind: TxnKind,
    val amountPaise: Long,
    val localEpochDay: Long,
    val categoryId: Long?,
    val merchantKey: String?,
    val merchantRaw: String?,
    val refundOfId: Long?,
)

data class AnalyticsSplit(val txnId: Long, val categoryId: Long, val amountPaise: Long)

data class ExcludedRow(val txnId: Long, val excludedPaise: Long)

@Dao
interface AnalyticsDao {
    @Query(
        """
        SELECT id, kind, amountPaise, localEpochDay, categoryId, merchantKey, merchantRaw, refundOfId FROM v_confirmed_txn
        WHERE accountId = :accountId AND localEpochDay BETWEEN :fromDay AND :toDay
        """,
    )
    fun observeRows(accountId: Long, fromDay: Long, toDay: Long): Flow<List<AnalyticsRow>>

    /** Split parts of the purchases that this window's refunds pay back (the purchase may be older). One account. */
    @Query(
        """
        SELECT s.txnId AS txnId, s.categoryId AS categoryId, s.amountPaise AS amountPaise
        FROM txn_split s JOIN v_confirmed_txn o ON o.id = s.txnId
        WHERE o.accountId = :accountId
          AND s.txnId IN (SELECT r.refundOfId FROM v_confirmed_txn r
                          WHERE r.accountId = :accountId AND r.refundOfId IS NOT NULL AND r.localEpochDay BETWEEN :fromDay AND :toDay)
        """,
    )
    fun observeRefundTargets(accountId: Long, fromDay: Long, toDay: Long): Flow<List<AnalyticsSplit>>

    @Query(
        """
        SELECT s.txnId AS txnId, s.categoryId AS categoryId, s.amountPaise AS amountPaise
        FROM txn_split s JOIN v_confirmed_txn t ON t.id = s.txnId
        WHERE t.accountId = :accountId AND t.localEpochDay BETWEEN :fromDay AND :toDay
        """,
    )
    fun observeSplits(accountId: Long, fromDay: Long, toDay: Long): Flow<List<AnalyticsSplit>>

    @Query(
        """
        SELECT txnId, SUM(excludedPaise) AS excludedPaise FROM v_share_excluded
        WHERE accountId = :accountId AND localEpochDay BETWEEN :fromDay AND :toDay
        GROUP BY txnId
        """,
    )
    fun observeExcluded(accountId: Long, fromDay: Long, toDay: Long): Flow<List<ExcludedRow>>

    // ---- budgets: a row applies from its month until a later row for the same category replaces it

    /** The budgets in force for one account in the month starting [monthStartDay] (limit 0 = removed). */
    @Query(
        """
        SELECT b.* FROM budget b
        WHERE b.accountId = :accountId
          AND b.periodStartEpochDay = (SELECT MAX(b2.periodStartEpochDay) FROM budget b2
                                       WHERE b2.accountId = b.accountId AND b2.categoryId = b.categoryId
                                         AND b2.periodStartEpochDay <= :monthStartDay)
        """,
    )
    fun observeBudgets(accountId: Long, monthStartDay: Long): Flow<List<BudgetEntity>>

    /** Same (account, category, month) replaces — the unique index makes this an upsert. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudget(budget: BudgetEntity)

    // ---- recurring

    @Query("SELECT * FROM recurring_rule WHERE accountId = :accountId AND active = 1 ORDER BY nextDueAt")
    fun observeRecurring(accountId: Long): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rule WHERE accountId = :accountId")
    suspend fun allRecurring(accountId: Long): List<RecurringRuleEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecurring(rule: RecurringRuleEntity): Long

    @Query("UPDATE recurring_rule SET active = 0 WHERE id = :id AND accountId = :accountId")
    suspend fun stopRecurring(accountId: Long, id: Long)

    /** Most recent payment to a merchant in ONE account (for "next due" of a tracked bill). */
    @Query(
        """
        SELECT MAX(localEpochDay) FROM v_confirmed_txn
        WHERE accountId = :accountId AND merchantKey = :merchantKey AND kind IN ('EXPENSE','FEE')
        """,
    )
    fun observeLastPaymentDay(accountId: Long, merchantKey: String): Flow<Long?>
}
