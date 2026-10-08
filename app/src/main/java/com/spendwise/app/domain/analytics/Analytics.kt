package com.spendwise.app.domain.analytics

import com.spendwise.app.data.db.entity.Cadence
import com.spendwise.app.data.db.entity.RecurringKind
import com.spendwise.app.data.db.entity.TxnKind
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/*
 * Phase 6 analytics engine — pure functions over ONE account's confirmed rows (CLAUDE.md rule #1; callers pass
 * one account's data only). Definitions (ADR-004 + ADR-011), identical to LedgerDao.observePeriodSummary:
 *  - Own spending = EXPENSE + FEE − REFUND − positive REVERSAL − friends' shares (v_share_excluded).
 *  - Income       = INCOME + INTEREST + negative REVERSAL. Transfers, ATM/cash moves and REPAYMENT are neither.
 * Split bills spread over their parts; a friend's share is taken from the parts in proportion (exact in paise).
 * Every result is Long paise.
 */

/** One confirmed transaction of one account (amount signed: out = negative). */
data class AnaTxn(
    val id: Long,
    val kind: TxnKind,
    val amountPaise: Long,
    val day: Long,
    val categoryId: Long?,
    val merchantKey: String?,
    val merchantRaw: String?,
    /** For a REFUND: the purchase it pays back (its split decides which categories it nets). */
    val refundOfId: Long? = null,
)

/** A split part (signed like its parent). */
data class AnaSplit(val txnId: Long, val categoryId: Long, val amountPaise: Long)

/** Everything for one account and a day range: rows, their splits, friends' shares excluded per bill. */
data class AnaInput(
    val txns: List<AnaTxn>,
    val splits: Map<Long, List<AnaSplit>> = emptyMap(),
    val sharesExcluded: Map<Long, Long> = emptyMap(),
    /** Split parts of refunded purchases, keyed by the purchase id — even when the purchase is outside the window. */
    val refundTargets: Map<Long, List<AnaSplit>> = emptyMap(),
)

data class MerchantSpend(val key: String, val name: String, val paise: Long, val count: Int)

object Analytics {

    /** Own spending of one transaction, per category (null = uncategorised). Empty for anything that isn't spending. */
    fun spendParts(t: AnaTxn, input: AnaInput): Map<Long?, Long> = when (t.kind) {
        TxnKind.EXPENSE, TxnKind.FEE -> {
            val gross = -t.amountPaise
            val own = gross - (input.sharesExcluded[t.id] ?: 0)
            val parts = input.splits[t.id].orEmpty()
            if (parts.isEmpty()) mapOf(t.categoryId to own) else spread(own, parts.map { it.categoryId to -it.amountPaise })
        }
        TxnKind.REFUND -> {
            // A refund of a split purchase nets each part in proportion (never all on the largest part).
            val parts = t.refundOfId?.let { input.splits[it] ?: input.refundTargets[it] }.orEmpty()
            if (parts.isEmpty()) mapOf(t.categoryId to -t.amountPaise) else spread(-t.amountPaise, parts.map { it.categoryId to -it.amountPaise })
        }
        TxnKind.REVERSAL -> if (t.amountPaise > 0) mapOf(t.categoryId to -t.amountPaise) else emptyMap()
        else -> emptyMap()
    }

    /** Income of one transaction, per category. */
    fun incomeParts(t: AnaTxn, input: AnaInput): Map<Long?, Long> = when (t.kind) {
        TxnKind.INCOME, TxnKind.INTEREST -> {
            val parts = input.splits[t.id].orEmpty()
            if (parts.isEmpty()) mapOf(t.categoryId to t.amountPaise) else parts.groupBy { it.categoryId as Long? }.mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        }
        TxnKind.REVERSAL -> if (t.amountPaise < 0) mapOf(t.categoryId to t.amountPaise) else emptyMap()
        else -> emptyMap()
    }

    fun spent(input: AnaInput): Long = input.txns.sumOf { t -> spendParts(t, input).values.sum() }

    fun income(input: AnaInput): Long = input.txns.sumOf { t -> incomeParts(t, input).values.sum() }

    /** Own spending by category, largest first. Sums EXACTLY to [spent]. */
    fun spendByCategory(input: AnaInput): List<Pair<Long?, Long>> = byCategory(input) { spendParts(it, input) }

    fun incomeByCategory(input: AnaInput): List<Pair<Long?, Long>> = byCategory(input) { incomeParts(it, input) }

    private fun byCategory(input: AnaInput, parts: (AnaTxn) -> Map<Long?, Long>): List<Pair<Long?, Long>> {
        val acc = LinkedHashMap<Long?, Long>()
        for (t in input.txns) for ((c, p) in parts(t)) acc[c] = (acc[c] ?: 0) + p
        return acc.entries.filter { it.value != 0L }.map { it.key to it.value }.sortedByDescending { it.second }
    }

    /** Own spending per day for [from]..[to] (inclusive), index 0 = [from]. Sums to [spent] for rows in range. */
    fun dailySpend(input: AnaInput, from: LocalDate, to: LocalDate): LongArray {
        val start = from.toEpochDay()
        val out = LongArray((to.toEpochDay() - start + 1).toInt().coerceAtLeast(0))
        for (t in input.txns) {
            val i = (t.day - start).toInt()
            if (i in out.indices) out[i] += spendParts(t, input).values.sum()
        }
        return out
    }

    /** Own spending and income per calendar month (oldest first), for trend bars. */
    fun monthly(input: AnaInput, months: List<YearMonth>): List<Triple<YearMonth, Long, Long>> = months.map { ym ->
        val from = ym.atDay(1).toEpochDay()
        val to = ym.atEndOfMonth().toEpochDay()
        val inMonth = input.copy(txns = input.txns.filter { it.day in from..to })
        Triple(ym, spent(inMonth), income(inMonth))
    }

    /** Where the money went, by merchant (own spending, net of refunds and friends' shares). */
    fun topMerchants(input: AnaInput, limit: Int = 5): List<MerchantSpend> {
        data class Acc(var paise: Long = 0, var count: Int = 0, var name: String = "")
        val acc = LinkedHashMap<String, Acc>()
        for (t in input.txns) {
            val key = t.merchantKey ?: continue
            val p = spendParts(t, input).values.sum()
            if (p == 0L) continue
            val a = acc.getOrPut(key) { Acc() }
            a.paise += p
            if (t.kind == TxnKind.EXPENSE || t.kind == TxnKind.FEE) a.count++
            if (a.name.isEmpty() && t.merchantRaw != null) a.name = t.merchantRaw
        }
        return acc.filter { it.value.paise > 0 }
            .map { (k, a) -> MerchantSpend(k, a.name.ifEmpty { k }, a.paise, a.count) }
            .sortedByDescending { it.paise }
            .take(limit)
    }

    /** The largest single own-spend this period (for insights). */
    fun spendingRows(input: AnaInput): Int = input.txns.count { it.kind == TxnKind.EXPENSE || it.kind == TxnKind.FEE }

    fun largestExpense(input: AnaInput): Pair<AnaTxn, Long>? = input.txns
        .filter { it.kind == TxnKind.EXPENSE || it.kind == TxnKind.FEE }
        .map { it to spendParts(it, input).values.sum() }
        .maxByOrNull { it.second }
        ?.takeIf { it.second > 0 }

    /** [total] spread over [weights] (category → positive paise) in proportion; exact in paise, remainder to the largest. */
    internal fun spread(total: Long, weights: List<Pair<Long, Long>>): Map<Long?, Long> {
        val sum = weights.sumOf { it.second }
        if (sum == 0L) return emptyMap()
        val out = LinkedHashMap<Long?, Long>()
        var given = 0L
        for ((c, w) in weights) {
            val share = Math.multiplyExact(total, w) / sum // floor, never Double
            out[c] = (out[c] ?: 0) + share
            given += share
        }
        val largest = weights.maxBy { it.second }.first
        out[largest] = (out[largest] ?: 0) + (total - given)
        return out
    }
}

// ------------------------------------------------------------------------------------------------ budgets

enum class BudgetState { OK, NEAR, OVER }

data class BudgetProgress(val categoryId: Long, val limitPaise: Long, val spentPaise: Long) {
    val state: BudgetState
        get() = when {
            spentPaise > limitPaise -> BudgetState.OVER
            Math.multiplyExact(spentPaise, 100L) >= Math.multiplyExact(limitPaise, NEAR_PERCENT.toLong()) -> BudgetState.NEAR
            else -> BudgetState.OK
        }
    val leftPaise: Long get() = limitPaise - spentPaise
    /** 0..100+ in whole percent, integer math. */
    val percent: Int get() = if (limitPaise <= 0) 0 else (Math.multiplyExact(spentPaise.coerceAtLeast(0), 100L) / limitPaise).coerceAtMost(9_999).toInt()

    companion object {
        const val NEAR_PERCENT = 80
    }
}

object Budgets {
    /** [TOTAL] budgets compare against all own spending; others against that category's own spending. */
    fun progress(limits: Map<Long, Long>, byCategory: List<Pair<Long?, Long>>, totalSpent: Long, totalId: Long): List<BudgetProgress> =
        limits.filter { it.value > 0 }.map { (cat, limit) ->
            val spent = if (cat == totalId) totalSpent else byCategory.firstOrNull { it.first == cat }?.second ?: 0
            BudgetProgress(cat, limit, spent)
        }.sortedWith(compareBy<BudgetProgress> { it.categoryId != totalId }.thenByDescending { it.percent })
}

// ------------------------------------------------------------------------------------------------ recurring

data class RecurringSuggestion(
    /** The account it was detected in (set by the repository; the engine sees one account only). */
    val accountId: Long = 0,
    val merchantKey: String,
    val name: String,
    val amountPaise: Long,
    val cadence: Cadence,
    val lastDay: Long,
    val nextDueDay: Long,
    val occurrences: Int,
    val kind: RecurringKind,
)

object Recurring {
    private const val MIN_OCCURRENCES = 3

    /**
     * Payments to the same merchant at a steady rhythm (weekly / monthly / quarterly / yearly) and a steady amount
     * (within 15% of the typical one). Only a suggestion — the user decides to track it.
     */
    fun detect(expenses: List<AnaTxn>, categoryKind: (Long?) -> RecurringKind = { RecurringKind.OTHER }): List<RecurringSuggestion> {
        // A charge that was refunded in full (e.g. a failed autopay) isn't a payment.
        val refunded = expenses.filter { it.kind == TxnKind.REFUND && it.refundOfId != null }.groupBy { it.refundOfId!! }.mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        return expenses.filter { (it.kind == TxnKind.EXPENSE || it.kind == TxnKind.FEE) && it.merchantKey != null && (refunded[it.id] ?: 0) < -it.amountPaise }
            .groupBy { it.merchantKey!! }
            .mapNotNull { (key, list) -> suggestionFor(key, list.sortedBy { it.day }, categoryKind) }
            .sortedBy { it.nextDueDay }
    }

    /** The latest payment that looks like this bill: same merchant, amount within 25% of the usual. */
    fun lastPayment(rows: List<AnaTxn>, merchantKey: String, usualPaise: Long, upToDay: Long): Long? = rows
        .filter { (it.kind == TxnKind.EXPENSE || it.kind == TxnKind.FEE) && it.merchantKey == merchantKey && it.day <= upToDay }
        .filter { kotlin.math.abs(-it.amountPaise - usualPaise) * 4 <= usualPaise }
        .maxOfOrNull { it.day }

    /** Rolls a stored due day forward by [cadence] until it is no earlier than one period before [today]. */
    fun rollForward(dueDay: Long, cadence: Cadence, today: Long): Long {
        var d = dueDay
        var guard = 0
        while (nextDue(d, cadence) <= today && guard++ < 1_000) d = nextDue(d, cadence)
        return d
    }

    private fun suggestionFor(key: String, rows: List<AnaTxn>, categoryKind: (Long?) -> RecurringKind): RecurringSuggestion? {
        // One payment per day at most (two teas the same day aren't a rhythm).
        val perDay = rows.distinctBy { it.day }
        if (perDay.size < MIN_OCCURRENCES) return null
        val gaps = perDay.zipWithNext { a, b -> b.day - a.day }
        val typicalGap = median(gaps)
        val cadence = cadenceFor(typicalGap) ?: return null
        // Every gap within 25% of the typical one (one missed/late payment breaks the pattern — that's fine).
        if (gaps.any { abs(it - typicalGap) * 4 > typicalGap }) return null
        val amounts = perDay.map { -it.amountPaise }
        val typical = median(amounts)
        if (typical <= 0 || amounts.any { abs(it - typical) * 100 > typical * 15 }) return null
        val last = perDay.last()
        return RecurringSuggestion(
            merchantKey = key,
            name = last.merchantRaw ?: key,
            amountPaise = typical,
            cadence = cadence,
            lastDay = last.day,
            nextDueDay = nextDue(last.day, cadence),
            occurrences = perDay.size,
            kind = categoryKind(last.categoryId),
        )
    }

    fun cadenceFor(gapDays: Long): Cadence? = when (gapDays) {
        in 6..8 -> Cadence.WEEKLY
        in 26..35 -> Cadence.MONTHLY
        in 85..97 -> Cadence.QUARTERLY
        in 350..380 -> Cadence.YEARLY
        else -> null
    }

    /** The next due day after [lastDay] (calendar months, so "the 5th" stays the 5th). */
    fun nextDue(lastDay: Long, cadence: Cadence): Long {
        val d = LocalDate.ofEpochDay(lastDay)
        return when (cadence) {
            Cadence.WEEKLY -> d.plusWeeks(1)
            Cadence.MONTHLY -> d.plusMonths(1)
            Cadence.QUARTERLY -> d.plusMonths(3)
            Cadence.YEARLY -> d.plusYears(1)
        }.toEpochDay()
    }

    private fun median(values: List<Long>): Long = values.sorted().let { s -> if (s.isEmpty()) 0 else s[(s.size - 1) / 2] }
}

// ------------------------------------------------------------------------------------------------ insights

data class Insight(val title: String, val body: String)

object Insights {
    /**
     * Plain-language observations for ONE account's month. [today] decides how much of the month has passed (only for
     * the current month); [previous] is the previous month of the same account. Integer math only.
     */
    fun forMonth(
        month: YearMonth,
        today: LocalDate,
        current: AnaInput,
        previous: AnaInput,
        categoryName: (Long?) -> String,
        format: (Long) -> String,
    ): List<Insight> {
        val out = mutableListOf<Insight>()
        val spent = Analytics.spent(current)
        val before = Analytics.spent(previous)
        val isCurrent = YearMonth.from(today) == month
        val daysPassed = if (isCurrent) today.dayOfMonth else month.lengthOfMonth()

        if (spent > 0 && isCurrent && daysPassed >= 5 && daysPassed < month.lengthOfMonth()) {
            val projected = rupees(Math.multiplyExact(spent, month.lengthOfMonth().toLong()) / daysPassed)
            out += Insight("On pace for ${format(projected)}", "About ${format(rupees(spent / daysPassed))} a day so far this month.")
        }
        if (before > 0 && spent > 0) {
            if (isCurrent) {
                val pct = spent * 100 / before
                out += Insight("${pct}% of last month already", "You spent ${format(before)} in all of last month.")
            } else {
                val diff = spent - before
                val pct = abs(diff) * 100 / before
                if (pct >= 10) out += Insight(if (diff > 0) "Up ${pct}% on the month before" else "Down ${pct}% on the month before", "${format(spent)} vs ${format(before)}.")
            }
        }
        val cats = Analytics.spendByCategory(current)
        // Shares are of GROSS spending (positive categories) — the same base as the chart, so never above 100%.
        val gross = cats.filter { it.second > 0 }.sumOf { it.second }
        cats.firstOrNull()?.let { (cat, paise) ->
            if (gross > 0 && paise > 0) out += Insight("${categoryName(cat)} is your biggest category", "${format(paise)} — ${Math.multiplyExact(paise, 100L) / gross}% of your spending.")
        }
        // A category that jumped compared with the previous month.
        val prevCats = Analytics.spendByCategory(previous).toMap()
        cats.filter { (cat, paise) ->
            val p = prevCats[cat] ?: 0
            p > 0 && paise > p && (paise - p) * 2 >= p && paise - p >= JUMP_MIN_PAISE
        }.maxByOrNull { (cat, paise) -> paise - (prevCats[cat] ?: 0) }?.let { (cat, paise) ->
            out += Insight("More on ${categoryName(cat)}", "${format(paise)} vs ${format(prevCats[cat] ?: 0)} the month before.")
        }
        Analytics.topMerchants(current, 10).maxByOrNull { it.count }?.takeIf { it.count >= 3 }?.let { m ->
            out += Insight("${m.name}: ${m.count} times", "${format(m.paise)} in total this month.")
        }
        Analytics.largestExpense(current)?.let { (t, paise) ->
            if (gross > 0 && paise * 4 >= gross && Analytics.spendingRows(current) > 3) {
                out += Insight("One payment was ${Math.multiplyExact(paise, 100L) / gross}% of the month", "${format(paise)} at ${t.merchantRaw ?: categoryName(t.categoryId)}.")
            }
        }
        return out
    }

    private const val JUMP_MIN_PAISE = 50_000L // ₹500

    /** Rounded to whole rupees (half up), still in paise. */
    private fun rupees(paise: Long) = (paise + 50) / 100 * 100
}
