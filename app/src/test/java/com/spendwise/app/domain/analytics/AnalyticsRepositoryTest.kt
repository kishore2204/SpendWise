package com.spendwise.app.domain.analytics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.db.DatabaseSetup
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.domain.AccountLedger
import com.spendwise.app.domain.CreateAccountResult
import com.spendwise.app.domain.EntryInput
import com.spendwise.app.domain.LedgerResult
import com.spendwise.app.domain.NewAccount
import com.spendwise.app.domain.SharedExpenseLedger
import com.spendwise.app.domain.SplitPart
import com.spendwise.app.domain.TransactionLedger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.YearMonth

/** Phase 6 against a real database: same numbers as the Home summary, per account only, budgets and bills. */
@RunWith(AndroidJUnit4::class)
class AnalyticsRepositoryTest {

    private lateinit var db: SpendWiseDatabase
    private val oct = YearMonth.of(2026, 10)
    private fun at(month: Int, day: Int) = LocalDateTime.of(2026, month, day, 12, 0).atZone(IndiaTime.ZONE).toInstant().toEpochMilli()
    private var now = at(4, 1)
    private val clock = Clock { now }
    private lateinit var ledger: TransactionLedger
    private lateinit var repo: AnalyticsRepository
    private var indian = 0L
    private var hdfc = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SpendWiseDatabase::class.java)
            .addCallback(SpendWiseDatabase.SetupCallback()).allowMainThreadQueries().build()
        val accounts = AccountLedger(db, clock)
        ledger = TransactionLedger(db, clock)
        repo = AnalyticsRepository(db, clock)
        indian = (accounts.createAccount(NewAccount("INDBNK", "Indian Bank", "0000", "Indian", AccountType.SAVINGS, 1_000_000)) as CreateAccountResult.Created).accountId
        hdfc = (accounts.createAccount(NewAccount("HDFCBK", "HDFC Bank", "1111", "HDFC", AccountType.SAVINGS, 5_000_000)) as CreateAccountResult.Created).accountId
        now = at(10, 20)
    }

    @After fun tearDown() = db.close()

    private suspend fun spend(account: Long, paise: Long, merchant: String, cat: Long, whenMs: Long) =
        (ledger.addEntry(EntryInput(account, TxnKind.EXPENSE, paise, cat, merchant, null, whenMs, PaymentMethod.UPI)) as LedgerResult.Ok).id

    @Test fun `analytics Spent equals the Home summary exactly, and the categories add up to it`() = runTest {
        val swiggy = spend(hdfc, 45_000, "Swiggy", 10, at(10, 3))
        val dmart = spend(hdfc, 200_000, "DMart", 11, at(10, 5))
        ledger.setSplits(dmart, listOf(SplitPart(11, 150_000), SplitPart(21, 50_000)))
        ledger.addRefund(swiggy, 5_000, at(10, 6), null)
        val shared = SharedExpenseLedger(db, clock)
        val ravi = (shared.friendNamed("Ravi") as LedgerResult.Ok).id
        shared.share(dmart, ravi, 33_333)
        ledger.addEntry(EntryInput(hdfc, TxnKind.INCOME, 5_000_000, 50, "Salary", null, at(10, 1)))
        ledger.addTransfer(hdfc, indian, 100_000, at(10, 7), null) // neither spending nor income

        val a = repo.observe(hdfc, oct).first()
        val home = AccountRepository(db.accountDao(), db.ledgerDao()).observeThisMonth(hdfc, now).first()
        assertThat(a.spentPaise).isEqualTo(home.spentPaise)
        assertThat(a.incomePaise).isEqualTo(home.incomePaise)
        assertThat(a.lentPaise).isEqualTo(home.lentPaise)
        assertThat(a.spendByCategory.sumOf { it.second }).isEqualTo(a.spentPaise)
        assertThat(a.daily.sum()).isEqualTo(a.spentPaise)
        assertThat(a.trend.last().second).isEqualTo(a.spentPaise)
        assertThat(a.spentPaise).isEqualTo(45_000 - 5_000 + 200_000 - 33_333)
    }

    @Test fun `the receiving side of a transfer is neither income nor spending, and B's splits and shares never touch A`() = runTest {
        ledger.addTransfer(hdfc, indian, 100_000, at(10, 7), null)
        val bill = spend(hdfc, 200_000, "DMart", 11, at(10, 5))
        ledger.setSplits(bill, listOf(SplitPart(11, 150_000), SplitPart(21, 50_000)))
        val shared = SharedExpenseLedger(db, clock)
        shared.share(bill, (shared.friendNamed("Ravi") as LedgerResult.Ok).id, 50_000)
        val i = repo.observe(indian, oct).first()
        assertThat(i.incomePaise).isEqualTo(0)
        assertThat(i.spentPaise).isEqualTo(0)
        assertThat(i.spendByCategory).isEmpty()
        assertThat(i.trend.all { it.second == 0L && it.third == 0L }).isTrue()
        assertThat(i.insights).isEmpty() // nothing raised by HDFC's spending
        assertThat(i.lentPaise).isEqualTo(0)
        assertThat(repo.observe(hdfc, oct).first().spentPaise).isEqualTo(150_000)
    }

    @Test fun `a suggestion from one account can't be tracked into another`() = runTest {
        for (m in 6..10) spend(hdfc, 49_900, "Netflix", 17, at(m, 5))
        val s = repo.observe(hdfc, oct).first().recurring.single()
        assertThat(s.accountId).isEqualTo(hdfc)
        assertThat(repo.track(indian, s)).isInstanceOf(LedgerResult.Rejected::class.java)
    }

    @Test fun `pending and ignored transactions never count`() = runTest {
        spend(hdfc, 45_000, "Swiggy", 10, at(10, 3))
        val pending = com.spendwise.app.data.db.entity.TxnEntity(
            accountId = hdfc, amountPaise = -99_900, isCredit = false, kind = TxnKind.EXPENSE,
            status = com.spendwise.app.data.db.entity.TxnStatus.PENDING, confidence = com.spendwise.app.data.db.entity.Confidence.HIGH,
            occurredAt = at(10, 4), localEpochDay = IndiaTime.localEpochDay(at(10, 4)), timeSource = com.spendwise.app.data.db.entity.TimeSource.RECEIVED,
            categoryId = 10, merchantRaw = "Croma", merchantKey = "CROMA", createdAt = now, updatedAt = now,
        )
        db.txnDao().insert(pending)
        val ignored = db.txnDao().insert(pending.copy(status = com.spendwise.app.data.db.entity.TxnStatus.PENDING, amountPaise = -12_300, merchantKey = "X"))
        ledger.ignore(ignored)
        val a = repo.observe(hdfc, oct).first()
        assertThat(a.spentPaise).isEqualTo(45_000)
        assertThat(a.topMerchants.map { it.name }).containsExactly("Swiggy")
    }

    @Test fun `linking an SMS refund to its purchase nets that purchase's categories`() = runTest {
        val bill = spend(hdfc, 100_000, "DMart", 11, at(9, 20))
        ledger.setSplits(bill, listOf(SplitPart(11, 60_000), SplitPart(21, 40_000)))
        // An unlinked refund as the SMS pipeline would create it.
        val refund = db.txnDao().insert(
            com.spendwise.app.data.db.entity.TxnEntity(
                accountId = hdfc, amountPaise = 100_000, isCredit = true, kind = TxnKind.REFUND,
                status = com.spendwise.app.data.db.entity.TxnStatus.CONFIRMED, confidence = com.spendwise.app.data.db.entity.Confidence.NEEDS_REVIEW,
                occurredAt = at(10, 2), localEpochDay = IndiaTime.localEpochDay(at(10, 2)), timeSource = com.spendwise.app.data.db.entity.TimeSource.RECEIVED,
                categoryId = DatabaseSetup.CATEGORY_REFUND, merchantRaw = "DMart", merchantKey = "DMART", createdAt = now, updatedAt = now,
            ),
        )
        assertThat(ledger.refundSuggestions(refund).map { it.id }).containsExactly(bill)
        assertThat(ledger.linkRefund(refund, bill)).isInstanceOf(LedgerResult.Ok::class.java)
        val byCat = repo.observe(hdfc, oct).first().spendByCategory.toMap()
        assertThat(byCat).containsExactly(11L, -60_000L, 21L, -40_000L) // netted where the money was spent
        assertThat(ledger.linkRefund(refund, bill)).isInstanceOf(LedgerResult.Rejected::class.java)
    }

    @Test fun `regular payments are found from the last year whatever month is viewed`() = runTest {
        for (m in listOf(1, 4, 7, 10)) spend(hdfc, 250_000, "LIC", 25, at(m, 10))
        val viewingJune = repo.observe(hdfc, YearMonth.of(2026, 6)).first()
        assertThat(viewingJune.recurring.map { it.name }).containsExactly("LIC")
    }

    @Test fun `one account's analytics never contain another account's money`() = runTest {
        spend(hdfc, 45_000, "Swiggy", 10, at(10, 3))
        spend(indian, 12_000, "Tea Point", 10, at(10, 3))
        val h = repo.observe(hdfc, oct).first()
        val i = repo.observe(indian, oct).first()
        assertThat(h.spentPaise).isEqualTo(45_000)
        assertThat(i.spentPaise).isEqualTo(12_000)
        assertThat(h.topMerchants.map { it.name }).containsExactly("Swiggy")
        assertThat(i.topMerchants.map { it.name }).containsExactly("Tea Point")
        assertThat(h.accountId).isEqualTo(hdfc)
    }

    @Test fun `day week and year views use selected account and matching calendar ranges`() = runTest {
        spend(hdfc, 10_000, "Monday", 10, at(10, 19))
        spend(hdfc, 20_000, "Tuesday", 10, at(10, 20))
        spend(hdfc, 30_000, "Sunday", 10, at(10, 25))
        spend(hdfc, 40_000, "Next Monday", 10, at(10, 26))
        spend(hdfc, 5_000, "Previous week", 10, at(10, 12))
        spend(hdfc, 50_000, "January", 10, at(1, 10))
        spend(indian, 99_000, "Other account", 10, at(10, 20))
        val today = java.time.LocalDate.of(2026, 10, 20)

        val day = repo.observeRange(hdfc, ReportPeriod.DAY.range(today), ReportPeriod.DAY).first()
        assertThat(day.spentPaise).isEqualTo(20_000)
        assertThat(day.accountId).isEqualTo(hdfc)
        assertThat(day.rangeStart).isEqualTo(today)

        val week = repo.observeRange(hdfc, ReportPeriod.WEEK.range(today), ReportPeriod.WEEK).first()
        assertThat(week.spentPaise).isEqualTo(30_000)
        assertThat(week.previousSpentPaise).isEqualTo(5_000)
        assertThat(week.daily).hasLength(2)

        val year = repo.observeRange(hdfc, ReportPeriod.YEAR.range(today), ReportPeriod.YEAR).first()
        assertThat(year.spentPaise).isEqualTo(85_000)
        assertThat(year.trend).hasSize(12)
        assertThat(year.spendByCategory.sumOf { it.second }).isEqualTo(year.spentPaise)
    }

    @Test fun `a budget carries forward month to month, per account, and 0 removes it`() = runTest {
        repo.setBudget(hdfc, 10, 500_000, YearMonth.of(2026, 9))
        repo.setBudget(hdfc, DatabaseSetup.CATEGORY_ALL_SPENDING, 2_000_000, YearMonth.of(2026, 9))
        spend(hdfc, 450_000, "Swiggy", 10, at(10, 3))
        val b = repo.observe(hdfc, oct).first().budgets
        assertThat(b.map { it.categoryId }).containsExactly(DatabaseSetup.CATEGORY_ALL_SPENDING, 10L).inOrder()
        assertThat(b.first { it.categoryId == 10L }.state).isEqualTo(BudgetState.NEAR)
        assertThat(repo.observe(indian, oct).first().budgets).isEmpty() // never another account's budget
        repo.setBudget(hdfc, 10, 0, oct)
        assertThat(repo.observe(hdfc, oct).first().budgets.map { it.categoryId }).containsExactly(DatabaseSetup.CATEGORY_ALL_SPENDING)
        assertThat(repo.observe(hdfc, YearMonth.of(2026, 9)).first().budgets.map { it.categoryId }).contains(10L) // history kept
        assertThat(repo.setBudget(hdfc, 50, 1_000, oct)).isInstanceOf(LedgerResult.Rejected::class.java) // income category
    }

    @Test fun `a steady bill is suggested, tracked in its own account, and its next due date follows payments`() = runTest {
        for (m in 6..10) spend(hdfc, 49_900, "Netflix", 17, at(m, 5))
        val a = repo.observe(hdfc, oct).first()
        val s = a.recurring.single()
        assertThat(s.name).isEqualTo("Netflix")
        assertThat(repo.observe(indian, oct).first().recurring).isEmpty()
        assertThat(repo.track(hdfc, s)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(repo.track(hdfc, s)).isInstanceOf(LedgerResult.Rejected::class.java)
        val after = repo.observe(hdfc, oct).first()
        assertThat(after.recurring).isEmpty()
        val bill = after.tracked.single()
        assertThat(java.time.LocalDate.ofEpochDay(bill.nextDueDay)).isEqualTo(java.time.LocalDate.of(2026, 11, 5))
        repo.stopTracking(indian, bill.rule.id) // the wrong account can't stop it
        assertThat(repo.observe(hdfc, oct).first().tracked).hasSize(1)
        repo.stopTracking(hdfc, bill.rule.id)
        assertThat(repo.observe(hdfc, oct).first().tracked).isEmpty()
    }
}
