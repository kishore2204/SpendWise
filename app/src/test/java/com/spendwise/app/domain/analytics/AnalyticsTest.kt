package com.spendwise.app.domain.analytics

import com.google.common.truth.Truth.assertThat
import com.spendwise.app.data.db.entity.Cadence
import com.spendwise.app.data.db.entity.RecurringKind
import com.spendwise.app.data.db.entity.TxnKind
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

/** Phase 6 engine: every calculation, including the invariant Σ categories == Spent under every kind of row. */
class AnalyticsTest {

    private val oct1 = LocalDate.of(2026, 10, 1).toEpochDay()
    private var nextId = 1L
    private fun t(kind: TxnKind, paise: Long, day: Long = oct1, cat: Long? = 10, merchant: String? = null) =
        AnaTxn(nextId++, kind, paise, day, cat, merchant?.uppercase(), merchant)

    @Test fun `spent and income follow the ADR-004 and ADR-011 definitions`() {
        val bill = t(TxnKind.EXPENSE, -3_000, merchant = "Tea Point")
        val input = AnaInput(
            listOf(
                bill,
                t(TxnKind.FEE, -500, cat = 13),
                t(TxnKind.REFUND, 200, cat = 10),
                t(TxnKind.REVERSAL, 100, cat = null), // money that went out came back
                t(TxnKind.REVERSAL, -50, cat = null), // money that came in went back
                t(TxnKind.INCOME, 100_000, cat = 50),
                t(TxnKind.INTEREST, 300, cat = 51),
                t(TxnKind.TRANSFER_OUT, -40_000, cat = 1), // never spending
                t(TxnKind.ATM_WITHDRAWAL, -5_000, cat = 1),
                t(TxnKind.REPAYMENT, 1_500, cat = 4), // never income
            ),
            sharesExcluded = mapOf(bill.id to 1_500L),
        )
        assertThat(Analytics.spent(input)).isEqualTo(3_000 + 500 - 200 - 100 - 1_500)
        assertThat(Analytics.income(input)).isEqualTo(100_000 + 300 - 50)
    }

    @Test fun `a shared split bill takes the friend's part from each category in proportion, exactly`() {
        val bill = t(TxnKind.EXPENSE, -1_000, cat = null)
        val input = AnaInput(
            listOf(bill),
            splits = mapOf(bill.id to listOf(AnaSplit(bill.id, 10, -700), AnaSplit(bill.id, 11, -300))),
            sharesExcluded = mapOf(bill.id to 333L),
        )
        val byCat = Analytics.spendByCategory(input).toMap()
        assertThat(byCat.values.sum()).isEqualTo(667)
        // Own part 667 spread 70/30: floor(466.9)=466 and floor(200.1)=200; the leftover paisa goes to the largest part.
        assertThat(byCat[10]).isEqualTo(467)
        assertThat(byCat[11]).isEqualTo(200)
    }

    @Test fun `categories, days and merchants always add up to Spent - fuzzed`() {
        val rnd = Random(7)
        repeat(300) {
            val rows = (1..rnd.nextInt(1, 40)).map {
                val kind = listOf(TxnKind.EXPENSE, TxnKind.EXPENSE, TxnKind.FEE, TxnKind.REFUND, TxnKind.REVERSAL, TxnKind.INCOME, TxnKind.TRANSFER_OUT).random(rnd)
                val mag = rnd.nextLong(1, 500_000)
                val signed = when (kind) {
                    TxnKind.REFUND, TxnKind.INCOME -> mag
                    TxnKind.REVERSAL -> if (rnd.nextBoolean()) mag else -mag
                    else -> -mag
                }
                t(kind, signed, day = oct1 + rnd.nextLong(0, 31), cat = listOf(10L, 11L, 12L, null).random(rnd), merchant = listOf("Swiggy", "DMart", null).random(rnd))
            }
            val splits = rows.filter { it.kind == TxnKind.EXPENSE && rnd.nextInt(4) == 0 }.associate { r ->
                val a = (-r.amountPaise) / 3
                r.id to listOf(AnaSplit(r.id, 10, -a), AnaSplit(r.id, 12, r.amountPaise + a))
            }
            val shares = rows.filter { it.kind == TxnKind.EXPENSE && rnd.nextInt(3) == 0 }.associate { it.id to rnd.nextLong(0, -it.amountPaise + 1) }
            val input = AnaInput(rows, splits, shares)
            val spent = Analytics.spent(input)
            assertThat(Analytics.spendByCategory(input).sumOf { it.second }).isEqualTo(spent)
            assertThat(Analytics.dailySpend(input, LocalDate.ofEpochDay(oct1), LocalDate.ofEpochDay(oct1 + 31)).sum()).isEqualTo(spent)
            assertThat(Analytics.incomeByCategory(input).sumOf { it.second }).isEqualTo(Analytics.income(input))
            val months = Analytics.monthly(input, listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 11)))
            assertThat(months.sumOf { it.second }).isEqualTo(spent)
        }
    }

    @Test fun `a refund of a split purchase nets every part in proportion - even when the purchase is in another month`() {
        val bill = t(TxnKind.EXPENSE, -100_000, day = oct1 - 20, cat = null)
        val parts = listOf(AnaSplit(bill.id, 11, -60_000), AnaSplit(bill.id, 21, -40_000))
        val refund = AnaTxn(nextId++, TxnKind.REFUND, 100_000, oct1, 11, null, null, refundOfId = bill.id)
        // Only the refund is in this window; its purchase's split comes in refundTargets.
        val oct = AnaInput(listOf(refund), refundTargets = mapOf(bill.id to parts))
        assertThat(Analytics.spendByCategory(oct).toMap()).containsExactly(11L, -60_000L, 21L, -40_000L)
        // Both in the window: categories net to zero, never +₹400 on Household.
        val both = AnaInput(listOf(bill, refund), splits = mapOf(bill.id to parts))
        assertThat(Analytics.spendByCategory(both)).isEmpty()
    }

    @Test fun `insight percentages never pass 100 when refunds of an earlier month land in this one`() {
        val oct = YearMonth.of(2026, 10)
        val cur = AnaInput(
            listOf(
                t(TxnKind.EXPENSE, -100_000, day = oct1, cat = 10),
                t(TxnKind.REFUND, 60_000, day = oct1 + 1, cat = 12),
                t(TxnKind.INCOME, 5_000_000, day = oct1, cat = 50),
                t(TxnKind.TRANSFER_OUT, -100, day = oct1, cat = 1),
                t(TxnKind.TRANSFER_OUT, -100, day = oct1, cat = 1),
            ),
        )
        val out = Insights.forMonth(oct, LocalDate.of(2026, 10, 20), cur, AnaInput(emptyList()), { "X" }) { "₹${it / 100}" }
        assertThat(out.map { it.body }.filter { it.contains("%") }.all { b -> Regex("(\\d+)%").findAll(b).all { it.groupValues[1].toInt() <= 100 } }).isTrue()
        assertThat(out.map { it.title }.none { it.startsWith("One payment was") }).isTrue() // only 1 spending row
    }

    @Test fun `a fully refunded charge isn't a payment, and a tracked bill only counts payments of its usual size`() {
        fun day(m: Int, d: Int) = LocalDate.of(2026, m, d).toEpochDay()
        val ok = (6..9).map { m -> t(TxnKind.EXPENSE, -149_900, day = day(m, 5), merchant = "Prime") }
        val failed = t(TxnKind.EXPENSE, -149_900, day = day(10, 2), merchant = "Prime")
        val reversed = AnaTxn(nextId++, TxnKind.REFUND, 149_900, day(10, 2), null, "PRIME", "Prime", refundOfId = failed.id)
        val retry = t(TxnKind.EXPENSE, -149_900, day = day(10, 5), merchant = "Prime")
        val order = t(TxnKind.EXPENSE, -30_000, day = day(10, 20), merchant = "Prime")
        val all = ok + failed + reversed + retry
        assertThat(Recurring.detect(all).single().occurrences).isEqualTo(5) // the failed one doesn't break the rhythm
        assertThat(LocalDate.ofEpochDay(Recurring.lastPayment(all + order, "PRIME", 149_900, day(10, 31)) ?: 0)).isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(LocalDate.ofEpochDay(Recurring.rollForward(day(1, 5), com.spendwise.app.data.db.entity.Cadence.MONTHLY, day(10, 8))))
            .isEqualTo(LocalDate.of(2026, 10, 5))
    }

    @Test fun `quarterly bills are found over a year of history`() {
        fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
        val ins = listOf(day(2025, 10, 10), day(2026, 1, 10), day(2026, 4, 10), day(2026, 7, 10)).map { t(TxnKind.EXPENSE, -250_000, day = it, merchant = "LIC") }
        assertThat(Recurring.detect(ins).single().cadence).isEqualTo(Cadence.QUARTERLY)
    }

    @Test fun `top merchants are net of refunds and friends' shares, and count visits`() {
        val a = t(TxnKind.EXPENSE, -45_000, merchant = "Swiggy")
        val input = AnaInput(
            listOf(a, t(TxnKind.EXPENSE, -30_000, merchant = "Swiggy"), t(TxnKind.REFUND, 5_000, merchant = "Swiggy"), t(TxnKind.EXPENSE, -90_000, merchant = "DMart")),
            sharesExcluded = mapOf(a.id to 20_000L),
        )
        val top = Analytics.topMerchants(input)
        assertThat(top.map { it.name to it.paise }).containsExactly("DMart" to 90_000L, "Swiggy" to 50_000L).inOrder()
        assertThat(top.last().count).isEqualTo(2)
    }

    @Test fun `budgets - near at 80 percent, over above the limit, total first`() {
        val byCat = listOf<Pair<Long?, Long>>(10L to 8_000L, 11L to 12_000L)
        val p = Budgets.progress(mapOf(10L to 10_000L, 11L to 10_000L, 5L to 50_000L, 12L to 0L), byCat, totalSpent = 20_000, totalId = 5)
        assertThat(p.map { it.categoryId }).containsExactly(5L, 11L, 10L).inOrder()
        assertThat(p.first { it.categoryId == 10L }.state).isEqualTo(BudgetState.NEAR)
        assertThat(p.first { it.categoryId == 11L }.state).isEqualTo(BudgetState.OVER)
        assertThat(p.first { it.categoryId == 5L }.let { it.state to it.percent }).isEqualTo(BudgetState.OK to 40)
        assertThat(p.first { it.categoryId == 11L }.leftPaise).isEqualTo(-2_000)
    }

    @Test fun `recurring - a steady monthly bill is found, irregular or varying ones are not`() {
        fun day(m: Int, d: Int) = LocalDate.of(2026, m, d).toEpochDay()
        val netflix = (5..9).map { m -> t(TxnKind.EXPENSE, -49_900, day = day(m, 5), cat = 17, merchant = "Netflix") }
        val swiggy = listOf(day(5, 2), day(5, 9), day(6, 30), day(7, 3)).map { t(TxnKind.EXPENSE, -30_000, day = it, merchant = "Swiggy") }
        val rent = (5..9).map { m -> t(TxnKind.EXPENSE, if (m == 7) -900_000 else -1_500_000, day = day(m, 1), cat = 23, merchant = "Landlord") }
        val gym = (0..3).map { w -> t(TxnKind.EXPENSE, -20_000, day = day(9, 1) + w * 7, merchant = "Gym") }
        val found = Recurring.detect(netflix + swiggy + rent + gym) { if (it == 17L) RecurringKind.SUBSCRIPTION else RecurringKind.OTHER }
        assertThat(found.map { it.name }).containsExactly("Gym", "Netflix")
        val n = found.first { it.name == "Netflix" }
        assertThat(n.cadence).isEqualTo(Cadence.MONTHLY)
        assertThat(n.amountPaise).isEqualTo(49_900)
        assertThat(n.kind).isEqualTo(RecurringKind.SUBSCRIPTION)
        assertThat(LocalDate.ofEpochDay(n.nextDueDay)).isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(found.first { it.name == "Gym" }.cadence).isEqualTo(Cadence.WEEKLY)
    }

    @Test fun `monthly next-due keeps the day of month`() {
        assertThat(LocalDate.ofEpochDay(Recurring.nextDue(LocalDate.of(2026, 1, 31).toEpochDay(), Cadence.MONTHLY))).isEqualTo(LocalDate.of(2026, 2, 28))
        assertThat(Recurring.cadenceFor(30)).isEqualTo(Cadence.MONTHLY)
        assertThat(Recurring.cadenceFor(15)).isNull()
    }

    @Test fun `insights - pace, comparison, biggest category, frequent merchant, one big payment`() {
        val oct = YearMonth.of(2026, 10)
        val cur = AnaInput(
            listOf(
                t(TxnKind.EXPENSE, -20_000, day = oct1, cat = 10, merchant = "Swiggy"),
                t(TxnKind.EXPENSE, -20_000, day = oct1 + 1, cat = 10, merchant = "Swiggy"),
                t(TxnKind.EXPENSE, -20_000, day = oct1 + 2, cat = 10, merchant = "Swiggy"),
                t(TxnKind.EXPENSE, -140_000, day = oct1 + 3, cat = 12, merchant = "Croma"),
            ),
        )
        val sep = AnaInput(listOf(t(TxnKind.EXPENSE, -400_000, day = oct1 - 10, cat = 12)))
        val names = mapOf(10L to "Food", 12L to "Shopping")
        val out = Insights.forMonth(oct, LocalDate.of(2026, 10, 10), cur, sep, { names[it] ?: "Other" }) { "₹${it / 100}" }
        val titles = out.map { it.title }
        assertThat(titles).contains("On pace for ₹6200") // 2000 over 10 days × 31
        assertThat(titles).contains("50% of last month already")
        assertThat(titles).contains("Shopping is your biggest category")
        assertThat(titles).contains("Swiggy: 3 times")
        assertThat(titles).contains("One payment was 70% of the month")
        // Nothing to say about an empty month.
        assertThat(Insights.forMonth(oct, LocalDate.of(2026, 10, 10), AnaInput(emptyList()), AnaInput(emptyList()), { "" }) { "" }).isEmpty()
    }
}
