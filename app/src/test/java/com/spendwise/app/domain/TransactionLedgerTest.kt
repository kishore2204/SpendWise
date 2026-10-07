package com.spendwise.app.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.spendwise.app.core.time.Clock
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.db.DatabaseSetup
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

/** Phase 4: manual entries, transfers, cash, refunds, splits, delete — and that none of it breaks isolation. */
@RunWith(AndroidJUnit4::class)
class TransactionLedgerTest {

    private lateinit var db: SpendWiseDatabase
    private var now = 1_759_800_000_000L
    private val clock = Clock { now }
    private lateinit var accounts: AccountLedger
    private lateinit var ledger: TransactionLedger
    private lateinit var repo: AccountRepository
    private var indian = 0L
    private var hdfc = 0L
    private var cash = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SpendWiseDatabase::class.java)
            .addCallback(SpendWiseDatabase.SetupCallback()).allowMainThreadQueries().build()
        accounts = AccountLedger(db, clock)
        ledger = TransactionLedger(db, clock)
        repo = AccountRepository(db.accountDao(), db.ledgerDao())
        indian = (accounts.createAccount(NewAccount("INDBNK", "Indian Bank", "0000", "Indian", AccountType.SAVINGS, 1_000_000)) as CreateAccountResult.Created).accountId
        hdfc = (accounts.createAccount(NewAccount("HDFCBK", "HDFC Bank", "1111", "HDFC", AccountType.SAVINGS, 5_000_000)) as CreateAccountResult.Created).accountId
        cash = (accounts.createAccount(NewAccount("CASH", "Cash", "", "Wallet", AccountType.CASH, 0)) as CreateAccountResult.Created).accountId
        now += 60_000
    }

    @After fun tearDown() = db.close()

    private suspend fun balance(id: Long) = repo.observeBalance(id).first().displayedPaise
    private suspend fun month(id: Long) = repo.observeThisMonth(id, now).first()
    private suspend fun expense(account: Long, paise: Long, merchant: String? = "Swiggy", category: Long? = 10) =
        (ledger.addEntry(EntryInput(account, TxnKind.EXPENSE, paise, category, merchant, null, now, PaymentMethod.UPI)) as LedgerResult.Ok).id

    @Test fun `expense and income move only their own account`() = runTest {
        expense(indian, 45_000)
        ledger.addEntry(EntryInput(hdfc, TxnKind.INCOME, 5_000_000, 50, "Salary", null, now))
        assertThat(balance(indian)).isEqualTo(955_000)
        assertThat(balance(hdfc)).isEqualTo(10_000_000)
        assertThat(month(indian).spentPaise).isEqualTo(45_000)
        assertThat(month(indian).incomePaise).isEqualTo(0)
        assertThat(month(hdfc).incomePaise).isEqualTo(5_000_000)
    }

    @Test fun `a transfer moves money between accounts and is never income or expense`() = runTest {
        ledger.addTransfer(hdfc, indian, 1_000_000, now, "Top up")
        assertThat(balance(hdfc)).isEqualTo(4_000_000)
        assertThat(balance(indian)).isEqualTo(2_000_000)
        for (id in listOf(hdfc, indian)) {
            assertThat(month(id).incomePaise).isEqualTo(0)
            assertThat(month(id).spentPaise).isEqualTo(0)
        }
    }

    @Test fun `ATM withdrawal fills the cash wallet, cash spending empties it`() = runTest {
        ledger.addTransfer(indian, cash, 500_000, now, null)
        assertThat(balance(indian)).isEqualTo(500_000)
        assertThat(balance(cash)).isEqualTo(500_000)
        val row = db.txnDao().observeRecent(indian, 5).first().single()
        assertThat(row.kind).isEqualTo(TxnKind.ATM_WITHDRAWAL)
        assertThat(row.counterpartyAccount).isEqualTo("Wallet")
        expense(cash, 12_000, "Tea stall", 10)
        assertThat(balance(cash)).isEqualTo(488_000)
        assertThat(month(indian).spentPaise).isEqualTo(0) // the withdrawal is not spending
        assertThat(month(cash).spentPaise).isEqualTo(12_000)
    }

    @Test fun `editing a transfer updates both legs, deleting removes both`() = runTest {
        val t = ledger.addTransfer(hdfc, indian, 1_000_000, now, null) as LedgerResult.Ok
        val leg = db.txnDao().observeRecent(indian, 1).first().single().id
        ledger.editTransfer(leg, 250_000, now, "less")
        assertThat(balance(hdfc)).isEqualTo(4_750_000)
        assertThat(balance(indian)).isEqualTo(1_250_000)
        ledger.delete(leg)
        assertThat(balance(hdfc)).isEqualTo(5_000_000)
        assertThat(balance(indian)).isEqualTo(1_000_000)
        assertThat(t.id).isGreaterThan(0)
    }

    @Test fun `refund links to the original, nets spending and cannot exceed it`() = runTest {
        val purchase = expense(hdfc, 464_000, "Amazon", 12)
        assertThat(ledger.addRefund(purchase, 100_000, now, null)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(month(hdfc).spentPaise).isEqualTo(364_000)
        assertThat(ledger.addRefund(purchase, 400_000, now, null)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.addRefund(purchase, 364_000, now, null)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(month(hdfc).spentPaise).isEqualTo(0)
        // The original can't be deleted while refunds point at it, nor edited below what was refunded.
        assertThat(ledger.delete(purchase)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.editEntry(purchase, 100_000, 12, "Amazon", null, now, PaymentMethod.UPI)).isInstanceOf(LedgerResult.Rejected::class.java)
    }

    @Test fun `only spending can be refunded`() = runTest {
        ledger.addTransfer(hdfc, indian, 1_000, now, null)
        val leg = db.txnDao().observeRecent(hdfc, 1).first().single().id
        assertThat(ledger.addRefund(leg, 1_000, now, null)).isInstanceOf(LedgerResult.Rejected::class.java)
    }

    @Test fun `splits must add up exactly and replace the parent category`() = runTest {
        val id = expense(indian, 200_000, "DMart", 11)
        assertThat(ledger.setSplits(id, listOf(SplitPart(11, 140_000), SplitPart(21, 40_000), SplitPart(22, 19_999))))
            .isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.setSplits(id, listOf(SplitPart(11, 140_000), SplitPart(21, 40_000), SplitPart(22, 20_000))))
            .isInstanceOf(LedgerResult.Ok::class.java)
        val splits = db.txnDao().getSplits(id)
        assertThat(splits.map { it.amountPaise }).containsExactly(-140_000L, -40_000L, -20_000L)
        assertThat(db.txnDao().getById(id)!!.categoryId).isNull()
        assertThat(balance(indian)).isEqualTo(800_000) // splitting never changes the balance
        // Changing the amount clears splits (they would no longer add up).
        ledger.editEntry(id, 210_000, 11, "DMart", null, now, PaymentMethod.UPI)
        assertThat(db.txnDao().getSplits(id)).isEmpty()
        assertThat(db.txnDao().getById(id)!!.categoryId).isEqualTo(11)
    }

    @Test fun `a refund of a split purchase takes the largest part's category`() = runTest {
        val id = expense(indian, 200_000, "DMart", 11)
        ledger.setSplits(id, listOf(SplitPart(21, 60_000), SplitPart(11, 140_000)))
        val refund = (ledger.addRefund(id, 10_000, now, null) as LedgerResult.Ok).id
        assertThat(db.txnDao().getById(refund)!!.categoryId).isEqualTo(11)
    }

    @Test fun `category is learned for every account, and the dictionary covers first use`() = runTest {
        assertThat(ledger.suggestCategory(indian, "SWIGGY BANGALORE")).isEqualTo(10L) // dictionary
        expense(hdfc, 30_000, "Lakshmi Store", 11) // user's own choice
        assertThat(ledger.suggestCategory(indian, "LAKSHMI STORE")).isEqualTo(11L) // learned, all accounts
        expense(indian, 30_000, "Lakshmi Store", 21)
        assertThat(ledger.suggestCategory(hdfc, "lakshmi store")).isEqualTo(21L) // latest correction wins
    }

    @Test fun `transfers to the same account and zero amounts are rejected`() = runTest {
        assertThat(ledger.addTransfer(indian, indian, 1_000, now, null)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.addEntry(EntryInput(indian, TxnKind.EXPENSE, 0, null, null, null, now))).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.addEntry(EntryInput(indian, TxnKind.TRANSFER_OUT, 100, null, null, null, now))).isInstanceOf(LedgerResult.Rejected::class.java)
    }

    @Test fun `account lists never contain another account's rows`() = runTest {
        expense(indian, 1_000)
        expense(hdfc, 2_000)
        ledger.addTransfer(hdfc, indian, 3_000, now, null)
        assertThat(db.txnDao().observeRecent(indian, 50).first().map { it.accountId }.toSet()).containsExactly(indian)
        assertThat(db.txnDao().observeRecent(hdfc, 50).first().map { it.accountId }.toSet()).containsExactly(hdfc)
    }

    // ---- Audit follow-ups (Phase 4) ----

    @Test fun `editing a refund is capped against its original and keeps its category`() = runTest {
        val purchase = expense(hdfc, 464_000, "Amazon", 12)
        val refund = (ledger.addRefund(purchase, 100_000, now, null) as LedgerResult.Ok).id
        assertThat(ledger.editEntry(refund, 1_000_000, 52, "Amazon", null, now, PaymentMethod.UPI)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.editEntry(refund, 464_000, 52, "Amazon", null, now, PaymentMethod.UPI)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(db.txnDao().getById(refund)!!.categoryId).isEqualTo(12)
        assertThat(ledger.suggestCategory(hdfc, "Amazon")).isEqualTo(12L)
    }

    @Test fun `a refund needs a confirmed original`() = runTest {
        val pending = db.txnDao().insert(
            com.spendwise.app.data.db.entity.TxnEntity(
                accountId = hdfc, amountPaise = -464_000, isCredit = false, kind = TxnKind.EXPENSE,
                status = com.spendwise.app.data.db.entity.TxnStatus.PENDING, confidence = com.spendwise.app.data.db.entity.Confidence.NEEDS_REVIEW,
                occurredAt = now, localEpochDay = 0, timeSource = com.spendwise.app.data.db.entity.TimeSource.RECEIVED, createdAt = now, updatedAt = now,
            ),
        )
        assertThat(ledger.addRefund(pending, 1_000, now, null)).isInstanceOf(LedgerResult.Rejected::class.java)
    }

    @Test fun `refunds follow the original category when it is recategorised or split`() = runTest {
        val id = expense(indian, 200_000, "DMart", 12)
        val refund = (ledger.addRefund(id, 50_000, now, null) as LedgerResult.Ok).id
        ledger.editEntry(id, 200_000, 11, "DMart", null, now, PaymentMethod.UPI)
        assertThat(db.txnDao().getById(refund)!!.categoryId).isEqualTo(11)
        ledger.setSplits(id, listOf(SplitPart(21, 60_000), SplitPart(22, 140_000)))
        assertThat(db.txnDao().getById(refund)!!.categoryId).isEqualTo(22)
    }

    @Test fun `changing a split purchase amount keeps its largest category instead of uncategorised`() = runTest {
        val id = expense(indian, 200_000, "DMart", 11)
        ledger.setSplits(id, listOf(SplitPart(21, 60_000), SplitPart(11, 140_000)))
        ledger.editEntry(id, 250_000, null, "DMart", null, now, PaymentMethod.UPI)
        assertThat(db.txnDao().getById(id)!!.categoryId).isEqualTo(11)
        assertThat(db.txnDao().getSplits(id)).isEmpty()
    }

    @Test fun `unchanged amount keeps splits, income splits are positive, refunds cannot be split`() = runTest {
        val id = expense(indian, 200_000, "DMart", 11)
        ledger.setSplits(id, listOf(SplitPart(21, 60_000), SplitPart(11, 140_000)))
        ledger.editEntry(id, 200_000, null, "DMart", "same amount", now, PaymentMethod.UPI)
        assertThat(db.txnDao().getSplits(id)).hasSize(2)
        val salary = (ledger.addEntry(EntryInput(hdfc, TxnKind.INCOME, 100_000, 50, "Employer", null, now)) as LedgerResult.Ok).id
        ledger.setSplits(salary, listOf(SplitPart(50, 90_000), SplitPart(52, 10_000)))
        assertThat(db.txnDao().getSplits(salary).all { it.amountPaise > 0 }).isTrue()
        val refund = (ledger.addRefund(id, 1_000, now, null) as LedgerResult.Ok).id
        assertThat(ledger.setSplits(refund, listOf(SplitPart(11, 500), SplitPart(21, 500)))).isInstanceOf(LedgerResult.Rejected::class.java)
    }

    @Test fun `deleting a split purchase removes its parts, deleting its refund frees it`() = runTest {
        val id = expense(indian, 200_000, "DMart", 11)
        ledger.setSplits(id, listOf(SplitPart(21, 60_000), SplitPart(11, 140_000)))
        val refund = (ledger.addRefund(id, 1_000, now, null) as LedgerResult.Ok).id
        assertThat(ledger.delete(id)).isInstanceOf(LedgerResult.Rejected::class.java)
        ledger.delete(refund)
        assertThat(ledger.delete(id)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(db.txnDao().getSplits(id)).isEmpty()
        assertThat(balance(indian)).isEqualTo(1_000_000)
    }

    @Test fun `transfer legs cannot be edited as entries and removeSplits never recategorises them`() = runTest {
        ledger.addTransfer(hdfc, indian, 10_000, now, null)
        val leg = db.txnDao().observeRecent(indian, 1).first().single()
        assertThat(ledger.editEntry(leg.id, 5_000, 10, "x", null, now, PaymentMethod.UPI)).isInstanceOf(LedgerResult.Rejected::class.java)
        ledger.removeSplits(leg.id, 10)
        assertThat(db.txnDao().getById(leg.id)!!.categoryId).isEqualTo(DatabaseSetup.CATEGORY_TRANSFER)
        assertThat(leg.counterpartyAccount).isEqualTo("HDFC")
        assertThat(leg.amountPaise).isEqualTo(10_000)
        assertThat(db.txnDao().getById(leg.id)!!.merchantRaw).isNull()
    }

    @Test fun `cash deposit into the bank is never income`() = runTest {
        ledger.addTransfer(indian, cash, 50_000, now, null)
        ledger.addTransfer(cash, indian, 20_000, now, null)
        assertThat(month(indian).incomePaise).isEqualTo(0)
        assertThat(month(cash).spentPaise).isEqualTo(0)
        assertThat(balance(cash)).isEqualTo(30_000)
    }

    @Test fun `an account-only merchant rule never reaches another account`() = runTest {
        db.merchantRuleDao().upsert(com.spendwise.app.data.db.entity.MerchantRuleEntity(hdfc, "LAKSHMI STORE", 12, now))
        db.merchantRuleDao().upsert(com.spendwise.app.data.db.entity.MerchantRuleEntity(0, "LAKSHMI STORE", 11, now))
        assertThat(ledger.suggestCategory(hdfc, "Lakshmi Store")).isEqualTo(12L)
        assertThat(ledger.suggestCategory(indian, "Lakshmi Store")).isEqualTo(11L)
    }

    @Test fun `hiding a category in one account leaves it visible in another`() = runTest {
        db.openHelper.writableDatabase.execSQL("UPDATE account_category SET hidden = 1 WHERE accountId = $indian AND categoryId = 16")
        val kind = com.spendwise.app.data.db.entity.CategoryKind.EXPENSE
        assertThat(db.categoryDao().observeVisible(indian, kind).first().map { it.id }).doesNotContain(16L)
        assertThat(db.categoryDao().observeVisible(hdfc, kind).first().map { it.id }).contains(16L)
    }

    @Test fun `the paged list for one account contains only that account`() = runTest {
        expense(indian, 1_000)
        expense(hdfc, 2_000)
        ledger.addTransfer(hdfc, indian, 3_000, now, null)
        val page = db.txnDao().pagedForAccount(indian).load(
            androidx.paging.PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false),
        ) as androidx.paging.PagingSource.LoadResult.Page
        assertThat(page.data.map { it.accountId }.toSet()).containsExactly(indian)
        assertThat(page.data).hasSize(2)
    }

    @Test fun `a refund changes only its own account`() = runTest {
        val purchase = expense(hdfc, 464_000, "Amazon", 12)
        val indianBefore = balance(indian)
        ledger.addRefund(purchase, 100_000, now, null)
        assertThat(balance(indian)).isEqualTo(indianBefore)
        assertThat(month(indian).spentPaise).isEqualTo(0)
        assertThat(month(indian).incomePaise).isEqualTo(0)
    }
}
