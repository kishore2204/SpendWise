package com.spendwise.app.domain.analytics

import androidx.room.withTransaction
import com.spendwise.app.core.money.Money
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.db.DatabaseSetup
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.BudgetEntity
import com.spendwise.app.data.db.entity.CategoryEntity
import com.spendwise.app.data.db.entity.CategoryKind
import com.spendwise.app.data.db.entity.RecurringKind
import com.spendwise.app.data.db.entity.RecurringRuleEntity
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.domain.LedgerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** A tracked recurring payment with its next due day worked out from the latest actual payment. */
data class TrackedBill(val rule: RecurringRuleEntity, val nextDueDay: Long, val lastPaidDay: Long?)

/** Everything the Insights tab shows for ONE account and ONE selected calendar range (ADR-003). */
data class MonthAnalytics(
    val accountId: Long,
    val month: YearMonth,
    val period: ReportPeriod = ReportPeriod.MONTH,
    val rangeStart: LocalDate = month.atDay(1),
    val rangeEnd: LocalDate = month.atEndOfMonth(),
    val periodLabel: String = ReportPeriod.MONTH.label(ReportDateRange(month.atDay(1), month.atEndOfMonth())),
    val previousLabel: String = "last month",
    val spentPaise: Long,
    val incomePaise: Long,
    val lentPaise: Long,
    val repaidPaise: Long,
    val previousSpentPaise: Long,
    val spendByCategory: List<Pair<Long?, Long>>,
    val incomeByCategory: List<Pair<Long?, Long>>,
    val daily: LongArray,
    /** Last six months ending with [month]: (month, spent, income). */
    val trend: List<Triple<YearMonth, Long, Long>>,
    val topMerchants: List<MerchantSpend>,
    val insights: List<Insight>,
    val budgets: List<BudgetProgress>,
    val recurring: List<RecurringSuggestion>,
    val tracked: List<TrackedBill>,
    val categories: Map<Long, CategoryEntity>,
)

@Singleton
class AnalyticsRepository @Inject constructor(private val db: SpendWiseDatabase, private val clock: Clock) {

    fun observe(accountId: Long, month: YearMonth): Flow<MonthAnalytics> =
        observeRange(accountId, ReportDateRange(month.atDay(1), month.atEndOfMonth()), ReportPeriod.MONTH)

    fun observeRange(accountId: Long, range: ReportDateRange, period: ReportPeriod): Flow<MonthAnalytics> {
        val previous = period.previous(range)
        val month = YearMonth.from(range.start)
        val endMonth = YearMonth.from(range.endInclusive)
        val trendMonths = if (period == ReportPeriod.YEAR) YEAR_TREND_MONTHS else TREND_MONTHS
        val trendStart = endMonth.minusMonths(trendMonths - 1L).atDay(1)
        val from = minOf(trendStart, range.start, previous.start).toEpochDay()
        val to = maxOf(range.endInclusive, previous.endInclusive).toEpochDay()
        val dao = db.analyticsDao()
        val data = combine(
            dao.observeRows(accountId, from, to),
            dao.observeSplits(accountId, from, to),
            dao.observeExcluded(accountId, from, to),
            dao.observeRefundTargets(accountId, from, to),
        ) { rows, splits, excluded, targets ->
            AnaInput(
                txns = rows.map(::toAna),
                splits = splits.groupBy { it.txnId }.mapValues { (_, l) -> l.map { AnaSplit(it.txnId, it.categoryId, it.amountPaise) } },
                sharesExcluded = excluded.associate { it.txnId to it.excludedPaise },
                refundTargets = targets.groupBy { it.txnId }.mapValues { (_, l) -> l.map { AnaSplit(it.txnId, it.categoryId, it.amountPaise) } },
            )
        }
        // Regular payments look at the last 13 months up to TODAY, whatever month is being viewed.
        val today = IndiaTime.localDate(clock.now())
        val bills = dao.observeRows(accountId, today.minusMonths(BILL_MONTHS).toEpochDay(), today.toEpochDay())
        return combine(
            data,
            bills,
            dao.observeBudgets(accountId, month.atDay(1).toEpochDay()),
            dao.observeRecurring(accountId),
            db.categoryDao().observeAll(),
        ) { all, billRows, budgets, rules, categories ->
            build(accountId, range, previous, period, all, billRows.map(::toAna), budgets, rules, categories.associateBy { it.id }, trendMonths)
        }.flowOn(Dispatchers.Default)
    }

    private fun toAna(r: com.spendwise.app.data.db.dao.AnalyticsRow) =
        AnaTxn(r.id, r.kind, r.amountPaise, r.localEpochDay, r.categoryId, r.merchantKey, r.merchantRaw, r.refundOfId)

    private fun build(
        accountId: Long,
        range: ReportDateRange,
        previous: ReportDateRange,
        period: ReportPeriod,
        all: AnaInput,
        billRows: List<AnaTxn>,
        budgets: List<BudgetEntity>,
        rules: List<RecurringRuleEntity>,
        categories: Map<Long, CategoryEntity>,
        trendMonths: Int,
    ): MonthAnalytics {
        val month = YearMonth.from(range.start)
        val today = IndiaTime.localDate(clock.now())
        fun within(from: LocalDate, to: LocalDate) = all.copy(txns = all.txns.filter { it.day in from.toEpochDay()..to.toEpochDay() })
        val cur = within(range.start, range.endInclusive)
        val prev = within(previous.start, previous.endInclusive)
        val byCategory = Analytics.spendByCategory(cur)
        val spent = Analytics.spent(cur)
        val name = { id: Long? -> id?.let { categories[it]?.name } ?: "Uncategorised" }
        val todayDay = today.toEpochDay()
        val tracked = rules.map { r ->
            val last = Recurring.lastPayment(billRows, r.merchantKey, -r.amountPaise, todayDay)
            val next = last?.let { Recurring.nextDue(it, r.cadence) } ?: Recurring.rollForward(IndiaTime.localEpochDay(r.nextDueAt), r.cadence, todayDay)
            TrackedBill(r, next, last)
        }.sortedBy { it.nextDueDay }
        val trackedKeys = rules.map { it.merchantKey }.toSet()
        return MonthAnalytics(
            accountId = accountId,
            month = month,
            period = period,
            rangeStart = range.start,
            rangeEnd = range.endInclusive,
            periodLabel = period.label(range),
            previousLabel = if (period == ReportPeriod.MONTH) "last month" else "previous ${period.name.lowercase()}",
            spentPaise = spent,
            incomePaise = Analytics.income(cur),
            lentPaise = cur.sharesExcluded.filterKeys { id -> cur.txns.any { it.id == id } }.values.sum(),
            repaidPaise = cur.txns.filter { it.kind == TxnKind.REPAYMENT }.sumOf { it.amountPaise },
            previousSpentPaise = Analytics.spent(prev),
            spendByCategory = byCategory,
            incomeByCategory = Analytics.incomeByCategory(cur),
            daily = Analytics.dailySpend(cur, range.start, range.endInclusive),
            trend = Analytics.monthly(all, (trendMonths - 1 downTo 0).map { YearMonth.from(range.endInclusive).minusMonths(it.toLong()) }),
            topMerchants = Analytics.topMerchants(cur),
            insights = if (period == ReportPeriod.MONTH) {
                Insights.forMonth(month, today, cur, prev, name) { Money.format(it) }
            } else {
                Insights.forPeriod(cur, prev, range.dayCount, "${period.name.lowercase()}", name) { Money.format(it) }
            },
            budgets = Budgets.progress(budgets.associate { it.categoryId to it.limitPaise }, byCategory, spent, DatabaseSetup.CATEGORY_ALL_SPENDING),
            recurring = Recurring.detect(billRows) { kindFor(it) }
                .filter { it.merchantKey !in trackedKeys }
                .map { it.copy(accountId = accountId) },
            tracked = tracked,
            categories = categories,
        )
    }

    private fun kindFor(categoryId: Long?): RecurringKind = when (categoryId) {
        17L -> RecurringKind.SUBSCRIPTION
        13L -> RecurringKind.BILL
        23L -> RecurringKind.RENT
        24L -> RecurringKind.EMI
        25L -> RecurringKind.INSURANCE
        else -> RecurringKind.OTHER
    }

    // ---------------------------------------------------------------- budgets (one account each)

    /**
     * Sets a monthly budget for ONE account from [month] on (it carries forward until changed). [categoryId] is an
     * expense category or [DatabaseSetup.CATEGORY_ALL_SPENDING]; a limit of 0 removes it from [month] on.
     */
    suspend fun setBudget(accountId: Long, categoryId: Long, limitPaise: Long, month: YearMonth): LedgerResult {
        if (limitPaise < 0) return LedgerResult.Rejected("Enter an amount")
        if (limitPaise > MAX_BUDGET_PAISE) return LedgerResult.Rejected("That's more than any monthly budget needs")
        return db.withTransaction {
            db.accountDao().getById(accountId) ?: return@withTransaction LedgerResult.Rejected("Account not found")
            val c = db.categoryDao().getById(categoryId) ?: return@withTransaction LedgerResult.Rejected("Category not found")
            if (categoryId != DatabaseSetup.CATEGORY_ALL_SPENDING && c.kind != CategoryKind.EXPENSE) {
                return@withTransaction LedgerResult.Rejected("Budgets are for spending categories")
            }
            db.analyticsDao().upsertBudget(BudgetEntity(accountId = accountId, categoryId = categoryId, periodStartEpochDay = month.atDay(1).toEpochDay(), limitPaise = limitPaise))
            LedgerResult.Ok(categoryId)
        }
    }

    // ---------------------------------------------------------------- recurring (one account each)

    suspend fun track(accountId: Long, s: RecurringSuggestion): LedgerResult = db.withTransaction {
        if (s.accountId != accountId) return@withTransaction LedgerResult.Rejected("That was detected in another account")
        db.accountDao().getById(accountId) ?: return@withTransaction LedgerResult.Rejected("Account not found")
        if (db.analyticsDao().allRecurring(accountId).any { it.active && it.merchantKey == s.merchantKey }) {
            return@withTransaction LedgerResult.Rejected("Already tracked")
        }
        val id = db.analyticsDao().insertRecurring(
            RecurringRuleEntity(
                accountId = accountId,
                merchantKey = s.merchantKey,
                displayName = s.name,
                amountPaise = -s.amountPaise,
                cadence = s.cadence,
                kind = s.kind,
                nextDueAt = IndiaTime.startOfDay(LocalDate.ofEpochDay(s.nextDueDay)),
                createdAt = clock.now(),
            ),
        )
        LedgerResult.Ok(id)
    }

    suspend fun stopTracking(accountId: Long, ruleId: Long) = db.analyticsDao().stopRecurring(accountId, ruleId)

    companion object {
        const val TREND_MONTHS = 6
        const val YEAR_TREND_MONTHS = 12
        const val BILL_MONTHS = 13L
        const val MAX_BUDGET_PAISE = 10_000_000_000L // ₹10 crore
    }
}
