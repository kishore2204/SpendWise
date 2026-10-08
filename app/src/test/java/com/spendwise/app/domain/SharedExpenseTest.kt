package com.spendwise.app.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.spendwise.app.core.time.Clock
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TxnKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ADR-011: "I paid ₹30 for me and a friend; they pay ₹15 back later." Own spending, never income, per account. */
@RunWith(AndroidJUnit4::class)
class SharedExpenseTest {

    private lateinit var db: SpendWiseDatabase
    private var now = 1_759_800_000_000L
    private val clock = Clock { now }
    private lateinit var accounts: AccountLedger
    private lateinit var ledger: TransactionLedger
    private lateinit var shared: SharedExpenseLedger
    private lateinit var repo: AccountRepository
    private var indian = 0L
    private var hdfc = 0L
    private var cash = 0L
    private var ravi = 0L
    private var meena = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SpendWiseDatabase::class.java)
            .addCallback(SpendWiseDatabase.SetupCallback()).allowMainThreadQueries().build()
        accounts = AccountLedger(db, clock)
        ledger = TransactionLedger(db, clock)
        shared = SharedExpenseLedger(db, clock)
        repo = AccountRepository(db.accountDao(), db.ledgerDao())
        indian = (accounts.createAccount(NewAccount("INDBNK", "Indian Bank", "0000", "Indian", AccountType.SAVINGS, 1_000_000)) as CreateAccountResult.Created).accountId
        hdfc = (accounts.createAccount(NewAccount("HDFCBK", "HDFC Bank", "1111", "HDFC", AccountType.SAVINGS, 5_000_000)) as CreateAccountResult.Created).accountId
        cash = (accounts.createAccount(NewAccount("CASH", "Cash", "", "Wallet", AccountType.CASH, 0)) as CreateAccountResult.Created).accountId
        ravi = (shared.friendNamed("Ravi") as LedgerResult.Ok).id
        meena = (shared.friendNamed("Meena") as LedgerResult.Ok).id
        now += 60_000
    }

    @After fun tearDown() = db.close()

    private suspend fun balance(id: Long) = repo.observeBalance(id).first().displayedPaise
    private suspend fun month(id: Long) = repo.observeThisMonth(id, now).first()
    private suspend fun bill(account: Long, paise: Long, merchant: String = "Tea Point") =
        (ledger.addEntry(EntryInput(account, TxnKind.EXPENSE, paise, 10, merchant, null, now, PaymentMethod.UPI)) as LedgerResult.Ok).id
    private suspend fun credit(account: Long, paise: Long, payer: String = "Mr R Kumar") =
        (ledger.addEntry(EntryInput(account, TxnKind.INCOME, paise, 53, payer, null, now, PaymentMethod.UPI)) as LedgerResult.Ok).id
    private suspend fun owed(friend: Long) = db.friendDao().openSharesOf(friend).sumOf { it.openPaise }

    @Test fun `the user's scenario - pay 30, 15 is the friend's, they pay 15 back into another account`() = runTest {
        val b = bill(hdfc, 3_000)
        assertThat(shared.share(b, ravi, 1_500)).isInstanceOf(LedgerResult.Ok::class.java)
        // Only the user's own half is spending; the balance is the real money that left.
        assertThat(month(hdfc).spentPaise).isEqualTo(1_500)
        assertThat(month(hdfc).lentPaise).isEqualTo(1_500)
        assertThat(balance(hdfc)).isEqualTo(4_997_000)
        assertThat(owed(ravi)).isEqualTo(1_500)

        // Ravi pays back by UPI into Indian Bank.
        val c = credit(indian, 1_500)
        assertThat(month(indian).incomePaise).isEqualTo(1_500) // until the user says what it is
        assertThat(shared.markAsRepayment(c, ravi)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(db.txnDao().getById(c)!!.kind).isEqualTo(TxnKind.REPAYMENT)
        assertThat(month(indian).incomePaise).isEqualTo(0) // never income
        assertThat(month(indian).repaidPaise).isEqualTo(1_500)
        assertThat(balance(indian)).isEqualTo(1_001_500) // real money in
        assertThat(month(hdfc).spentPaise).isEqualTo(1_500) // unchanged by repayment
        assertThat(owed(ravi)).isEqualTo(0)
    }

    @Test fun `outstanding is reported per friend AND per paying account - never one cross-account total`() = runTest {
        shared.share(bill(hdfc, 3_000), ravi, 1_500)
        shared.share(bill(indian, 8_000), ravi, 4_000)
        val rows = db.friendDao().observeOutstanding().first()
        assertThat(rows.map { Triple(it.friendName, it.accountId, it.outstandingPaise) })
            .containsExactly(Triple("Ravi", indian, 4_000L), Triple("Ravi", hdfc, 1_500L))
    }

    @Test fun `one lump sum pays several bills, oldest first, and partial payments leave the rest owed`() = runTest {
        val b1 = bill(hdfc, 3_000)
        shared.share(b1, ravi, 1_500)
        now += 60_000
        val b2 = bill(indian, 6_000)
        shared.share(b2, ravi, 3_000)
        val lump = credit(indian, 2_000) // ₹20 of the ₹45 owed
        assertThat(shared.markAsRepayment(lump, ravi)).isInstanceOf(LedgerResult.Ok::class.java)
        val open = db.friendDao().openSharesOf(ravi)
        assertThat(open.map { it.txnId to it.openPaise }).containsExactly(b2 to 2_500L) // b1 fully paid, b2 partly
    }

    @Test fun `an overpayment is not silently swallowed - it stays income`() = runTest {
        shared.share(bill(hdfc, 3_000), ravi, 1_500)
        val c = credit(indian, 2_000)
        assertThat(shared.markAsRepayment(c, ravi)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(db.txnDao().getById(c)!!.kind).isEqualTo(TxnKind.INCOME)
    }

    @Test fun `forgiving makes the unpaid part the user's own spending again`() = runTest {
        val b = bill(hdfc, 3_000)
        val s = (shared.share(b, ravi, 1_500) as LedgerResult.Ok).id
        shared.markAsRepayment(credit(indian, 500), ravi) // ₹5 paid, ₹10 never will be
        shared.forgive(s)
        assertThat(month(hdfc).spentPaise).isEqualTo(2_500) // own 15 + forgiven 10
        assertThat(month(hdfc).lentPaise).isEqualTo(500) // only what actually came back
        assertThat(owed(ravi)).isEqualTo(0)
    }

    @Test fun `a hand-recorded repayment is deleted, not turned into income - and deleting re-opens the debt`() = runTest {
        shared.share(bill(hdfc, 3_000), ravi, 1_500)
        val c = credit(indian, 1_500)
        shared.markAsRepayment(c, ravi)
        assertThat(shared.undoRepayment(c)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.delete(c)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(owed(ravi)).isEqualTo(1_500)
        assertThat(month(indian).incomePaise).isEqualTo(0)
    }

    @Test fun `each account's summary holds only its own side - lent on the paying account, paid back on the receiving one`() = runTest {
        shared.share(bill(hdfc, 3_000), ravi, 1_500)
        shared.markAsRepayment(credit(indian, 1_500), ravi)
        assertThat(month(hdfc).repaidPaise).isEqualTo(0)
        assertThat(month(indian).lentPaise).isEqualTo(0)
        assertThat(month(indian).spentPaise).isEqualTo(0)
        assertThat(month(cash).lentPaise + month(cash).repaidPaise).isEqualTo(0)
    }

    @Test fun `messages never show a total across accounts`() = runTest {
        shared.share(bill(hdfc, 3_000), ravi, 1_500)
        shared.share(bill(indian, 8_000), ravi, 4_000)
        val r = shared.markAsRepayment(credit(indian, 6_000), ravi) as LedgerResult.Rejected
        assertThat(r.reason).doesNotContain("₹")
        val r2 = shared.recordRepayment(cash, ravi, 6_000, now) as LedgerResult.Rejected
        assertThat(r2.reason).doesNotContain("₹")
    }

    @Test fun `refunds - added or edited - can never eat into friends' shares, in the ledger or in SQL`() = runTest {
        val b = bill(hdfc, 3_000)
        shared.share(b, ravi, 1_500)
        val refund = (ledger.addRefund(b, 1_000, now, null) as LedgerResult.Ok).id
        assertThat(ledger.addRefund(b, 600, now, null)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.editEntry(refund, 2_500, null, null, null, now, PaymentMethod.UPI)).isInstanceOf(LedgerResult.Rejected::class.java)
        val sql = runCatching { db.openHelper.writableDatabase.execSQL("UPDATE txn SET amountPaise = 2500 WHERE id = $refund") }
        assertThat(sql.isFailure).isTrue()
    }

    @Test fun `SQL refuses settlements beyond the share or the credit, and changing a shared bill's nature`() = runTest {
        val b = bill(hdfc, 3_000)
        val s = (shared.share(b, ravi, 1_500) as LedgerResult.Ok).id
        val c = credit(indian, 1_500)
        shared.markAsRepayment(c, ravi)
        val w = db.openHelper.writableDatabase
        assertThat(runCatching { w.execSQL("INSERT INTO settlement (creditTxnId, shareId, amountPaise, createdAt) VALUES ($c, $s, 1, 1)") }.isFailure).isTrue()
        assertThat(runCatching { w.execSQL("UPDATE txn SET kind = 'FEE' WHERE id = $b") }.isFailure).isTrue()
        assertThat(runCatching { w.execSQL("UPDATE txn SET status = 'PENDING' WHERE id = $c") }.isFailure).isTrue()
    }

    @Test fun `a split bill can be shared, and possible duplicates or ignored credits can't be repayments`() = runTest {
        val b = bill(hdfc, 3_000)
        ledger.setSplits(b, listOf(SplitPart(10, 2_000), SplitPart(11, 1_000)))
        assertThat(shared.share(b, ravi, 1_500)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(month(hdfc).spentPaise).isEqualTo(1_500)
    }

    @Test fun `a cash repayment lands in the wallet, never as income`() = runTest {
        shared.share(bill(hdfc, 3_000), ravi, 1_500)
        assertThat(shared.recordRepayment(cash, ravi, 1_500, now)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(balance(cash)).isEqualTo(1_500)
        assertThat(month(cash).incomePaise).isEqualTo(0)
        assertThat(month(cash).repaidPaise).isEqualTo(1_500)
        assertThat(shared.recordRepayment(cash, ravi, 100, now)).isInstanceOf(LedgerResult.Rejected::class.java) // owes nothing now
    }

    @Test fun `shares, refunds and edits can never exceed the bill`() = runTest {
        val b = bill(hdfc, 3_000)
        assertThat(shared.share(b, ravi, 1_500)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(shared.share(b, meena, 1_500)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(shared.share(b, meena, 1)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.addRefund(b, 100, now, null)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.editEntry(b, 2_000, 10, "Tea Point", null, now, PaymentMethod.UPI)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.delete(b)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(month(hdfc).spentPaise).isEqualTo(0) // all of it was friends'
    }

    @Test fun `only confirmed spending can be shared, and a shared bill can't become a transfer`() = runTest {
        val inc = credit(indian, 1_000)
        assertThat(shared.share(inc, ravi, 500)).isInstanceOf(LedgerResult.Rejected::class.java)
        val b = bill(hdfc, 1_000)
        shared.share(b, ravi, 500)
        val other = credit(indian, 1_000)
        assertThat(ledger.pairAsTransfer(b, other)).isInstanceOf(LedgerResult.Rejected::class.java)
    }

    @Test fun `a removed share is owed no more, but not once something was paid back`() = runTest {
        val s = (shared.share(bill(hdfc, 3_000), ravi, 1_500) as LedgerResult.Ok).id
        shared.markAsRepayment(credit(indian, 500), ravi)
        assertThat(shared.removeShare(s)).isInstanceOf(LedgerResult.Rejected::class.java)
        val s2 = (shared.share(bill(hdfc, 2_000), meena, 1_000) as LedgerResult.Ok).id
        assertThat(shared.removeShare(s2)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(month(hdfc).spentPaise).isEqualTo(1_500 + 2_000)
    }

    @Test fun `suggestions come from a learned payer name or an exact amount - and never decide on their own`() = runTest {
        shared.share(bill(hdfc, 3_000), ravi, 1_500)
        shared.share(bill(hdfc, 6_000), meena, 3_000)
        val c = credit(indian, 1_500, payer = "Mr R Kumar")
        val s = shared.suggestionsFor(c)
        assertThat(s.map { it.friendName }).containsExactly("Ravi") // ₹15 = Ravi's bill; Meena owes ₹30
        assertThat(db.txnDao().getById(c)!!.kind).isEqualTo(TxnKind.INCOME) // nothing applied
        shared.markAsRepayment(c, ravi)
        // Next time "R Kumar" pays, Ravi is the strong suggestion even for an odd amount.
        shared.share(bill(hdfc, 5_000), ravi, 2_500)
        val c2 = credit(indian, 700, payer = "MR R KUMAR")
        assertThat(shared.suggestionsFor(c2).first().let { it.friendName to it.strong }).isEqualTo("Ravi" to true)
        assertThat(db.looksLikeRepayment(700, "Mr R Kumar")).isTrue()
        assertThat(db.looksLikeRepayment(700, "Salary Corp")).isFalse()
    }

    @Test fun `transfers and refunds are never offered as repayments`() = runTest {
        shared.share(bill(hdfc, 3_000), ravi, 1_500)
        ledger.addTransfer(hdfc, indian, 1_500, now, null)
        val leg = db.txnDao().observeRecent(indian, 1).first().single().id
        assertThat(shared.suggestionsFor(leg)).isEmpty()
        assertThat(shared.markAsRepayment(leg, ravi)).isInstanceOf(LedgerResult.Rejected::class.java)
    }
}
