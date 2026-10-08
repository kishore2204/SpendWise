package com.spendwise.app.data.account

import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.db.dao.AccountDao
import com.spendwise.app.data.db.dao.AccountWithBalance
import com.spendwise.app.data.db.dao.LedgerDao
import com.spendwise.app.data.db.dao.PeriodSummary
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.domain.analytics.ReportDateRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Balance broken down per ADR-004: calculated from transactions + adjustment = displayed. One account only. */
data class BalanceBreakdown(
    val accountId: Long,
    val displayedPaise: Long,
    val fromTransactionsPaise: Long,
) {
    val adjustmentPaise: Long get() = displayedPaise - fromTransactionsPaise
}

/** Read side for accounts. Every money read takes one accountId — nothing here ever combines accounts. */
@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val ledgerDao: LedgerDao,
) {
    fun observeAccountsWithBalances(): Flow<List<AccountWithBalance>> = accountDao.observeActiveWithBalances()

    fun observeAccount(accountId: Long): Flow<AccountEntity?> = accountDao.observeById(accountId)

    fun observeBalance(accountId: Long): Flow<BalanceBreakdown> =
        ledgerDao.observeBreakdown(accountId).map { BalanceBreakdown(accountId, it.displayedPaise, it.fromTransactionsPaise) }

    /** This calendar month (IST) for one account. */
    fun observeThisMonth(accountId: Long, nowMillis: Long): Flow<PeriodSummary> {
        val month = YearMonth.from(IndiaTime.localDate(nowMillis))
        return ledgerDao.observePeriodSummary(accountId, month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay())
    }

    /** Confirmed income/spending in a selected date range for ONE account. */
    fun observePeriod(accountId: Long, range: ReportDateRange): Flow<PeriodSummary> =
        ledgerDao.observePeriodSummary(accountId, range.fromEpochDay, range.toEpochDay)

    /** Re-emits whenever this account's anchors change (drives the adjustment-history list). */
    fun observeAnchorsChanged(accountId: Long) = ledgerDao.observeAnchors(accountId)
}
