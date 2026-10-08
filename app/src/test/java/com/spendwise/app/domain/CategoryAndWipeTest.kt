package com.spendwise.app.domain

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.spendwise.app.core.time.Clock
import com.spendwise.app.data.DataWiper
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.account.SelectedAccountStore
import com.spendwise.app.data.db.DatabaseSetup
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.CategoryKind
import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TxnKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Custom categories typed by the user, and More → "Delete all data". */
@RunWith(AndroidJUnit4::class)
class CategoryAndWipeTest {

    private lateinit var db: SpendWiseDatabase
    private val clock = Clock { 1_759_800_000_000L }
    private lateinit var accounts: AccountLedger
    private lateinit var ledger: TransactionLedger
    private lateinit var categories: CategoryLedger
    private var indian = 0L
    private var hdfc = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SpendWiseDatabase::class.java)
            .addCallback(SpendWiseDatabase.SetupCallback()).allowMainThreadQueries().build()
        accounts = AccountLedger(db, clock)
        ledger = TransactionLedger(db, clock)
        categories = CategoryLedger(db)
        indian = (accounts.createAccount(NewAccount("INDBNK", "Indian Bank", "0000", "Indian", AccountType.SAVINGS, 1_000_000)) as CreateAccountResult.Created).accountId
        hdfc = (accounts.createAccount(NewAccount("HDFCBK", "HDFC Bank", "1111", "HDFC", AccountType.SAVINGS, 5_000_000)) as CreateAccountResult.Created).accountId
    }

    @After fun tearDown() = db.close()

    @Test fun `a typed category appears in every account and is remembered for the merchant`() = runTest {
        val pets = (categories.createCategory("  Pet   care ", CategoryKind.EXPENSE) as LedgerResult.Ok).id
        assertThat(db.categoryDao().getById(pets)!!.name).isEqualTo("Pet care")
        for (a in listOf(indian, hdfc)) {
            assertThat(db.categoryDao().observeVisible(a, CategoryKind.EXPENSE).first().map { it.id }).contains(pets)
        }
        // Accounts added later see it too.
        val cash = (accounts.createAccount(NewAccount("CASH", "Cash", "", "Wallet", AccountType.CASH, 0)) as CreateAccountResult.Created).accountId
        assertThat(db.categoryDao().observeVisible(cash, CategoryKind.EXPENSE).first().map { it.id }).contains(pets)
        // Used on a payment, it's learned like any other category.
        ledger.addEntry(EntryInput(indian, TxnKind.EXPENSE, 50_000, pets, "Happy Paws", null, clock.now(), PaymentMethod.UPI))
        assertThat(ledger.suggestCategory(hdfc, "HAPPY PAWS")).isEqualTo(pets)
        // "Other" stays last.
        assertThat(db.categoryDao().observeVisible(indian, CategoryKind.EXPENSE).first().last().id).isEqualTo(27)
    }

    @Test fun `typing an existing name reuses it, and a clash with the other kind or a system name is refused`() = runTest {
        val a = (categories.createCategory("Pet care", CategoryKind.EXPENSE) as LedgerResult.Ok).id
        assertThat((categories.createCategory("PET CARE", CategoryKind.EXPENSE) as LedgerResult.Ok).id).isEqualTo(a)
        assertThat((categories.createCategory("groceries", CategoryKind.EXPENSE) as LedgerResult.Ok).id).isEqualTo(11)
        assertThat(categories.createCategory("Salary", CategoryKind.EXPENSE)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(categories.createCategory("Transfer", CategoryKind.EXPENSE)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(categories.createCategory("   ", CategoryKind.EXPENSE)).isInstanceOf(LedgerResult.Rejected::class.java)
        val freelance = (categories.createCategory("Freelance", CategoryKind.INCOME) as LedgerResult.Ok).id
        assertThat(db.categoryDao().observeVisible(indian, CategoryKind.INCOME).first().map { it.id }).contains(freelance)
    }

    @Test fun `a hidden category stays hidden in its account when created elsewhere, and re-typing it shows it only there`() = runTest {
        db.openHelper.writableDatabase.execSQL("UPDATE account_category SET hidden = 1 WHERE categoryId = 11 AND accountId = $hdfc")
        categories.createCategory("Groceries", CategoryKind.EXPENSE, forAccountId = indian)
        assertThat(db.categoryDao().observeVisible(hdfc, CategoryKind.EXPENSE).first().map { it.id }).doesNotContain(11L)
        assertThat(db.categoryDao().observeVisible(indian, CategoryKind.EXPENSE).first().map { it.id }).contains(11L)
        categories.createCategory("groceries", CategoryKind.EXPENSE, forAccountId = hdfc)
        assertThat(db.categoryDao().observeVisible(hdfc, CategoryKind.EXPENSE).first().map { it.id }).contains(11L)
    }

    @Test fun `a category of the wrong kind is refused, and suggestions never cross income and spending`() = runTest {
        val freelance = (categories.createCategory("Freelance", CategoryKind.INCOME) as LedgerResult.Ok).id
        assertThat(freelance).isAtLeast(1_000L) // custom ids never collide with built-ins
        ledger.addEntry(EntryInput(indian, TxnKind.INCOME, 500_000, freelance, "Rahul", null, clock.now()))
        assertThat(ledger.suggestCategory(indian, "Rahul", CategoryKind.EXPENSE)).isNull()
        assertThat(ledger.suggestCategory(indian, "Rahul", CategoryKind.INCOME)).isEqualTo(freelance)
        assertThat(ledger.addEntry(EntryInput(indian, TxnKind.EXPENSE, 50_000, freelance, "Rahul", null, clock.now())))
            .isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.addEntry(EntryInput(indian, TxnKind.EXPENSE, 50_000, DatabaseSetup.CATEGORY_TRANSFER, "Rahul", null, clock.now())))
            .isInstanceOf(LedgerResult.Rejected::class.java)
    }

    @Test fun `delete all data leaves an empty, working app with the built-in categories`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceDataStoreFactory.create(produceFile = { File(context.filesDir, "wipe-test.preferences_pb") })
        val selected = SelectedAccountStore(prefs, db.accountDao())
        selected.select(hdfc)
        categories.createCategory("Pet care", CategoryKind.EXPENSE)
        ledger.addEntry(EntryInput(indian, TxnKind.EXPENSE, 45_000, 10, "Swiggy", null, clock.now(), PaymentMethod.UPI))

        val gate = com.spendwise.app.transaction.ingest.IngestGate()
        val stager = com.spendwise.app.transaction.ingest.MessageStager(db.rawMessageDao(), gate)
        val processor = com.spendwise.app.transaction.ingest.IngestProcessor(db, clock, ledger, gate)
        val sync = com.spendwise.app.transaction.sms.SmsSync(
            context, com.spendwise.app.transaction.sms.InboxReader(context), stager, processor, db.rawMessageDao(), clock, gate,
        )
        // Some of everything that must not survive: an SMS (text kept), a learned rule, Not mine, a friend + alias.
        stager.stage(
            listOf(com.spendwise.app.transaction.parsing.RawSms("VM-INDBNK-S", "Sent Rs.20.00 from A/c *9999 on 07-10-26 to Tea Point.RRN 600000000990.Avl Bal Rs.1.00-Indian Bank", clock.now())),
            com.spendwise.app.data.db.entity.MessageSource.SMS_LIVE, clock.now(),
        )
        processor.drain()
        db.notMineDao().insert(com.spendwise.app.data.db.entity.NotMineEntity("HDFCBK", "5678", 1))
        val shared = SharedExpenseLedger(db, clock)
        (shared.friendNamed("Ravi") as LedgerResult.Ok).id.let { db.friendDao().upsertAlias(com.spendwise.app.data.db.entity.FriendAliasEntity("R KUMAR", it)) }

        DataWiper(context, db, selected, sync, gate).wipeEverything()

        val w = db.openHelper.writableDatabase
        for (table in listOf("raw_message", "merchant_rule", "not_mine", "friend", "friend_alias", "txn", "balance_anchor", "account_category")) {
            w.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); assertThat(c.getLong(0)).isEqualTo(0) }
        }

        assertThat(db.accountDao().allIds()).isEmpty()
        assertThat(selected.selectedAccountId.first()).isNull()
        assertThat(db.categoryDao().byName("Pet care")).isNull()
        assertThat(db.categoryDao().getById(10)?.name).isEqualTo("Food & Dining")
        assertThat(db.categoryDao().getById(DatabaseSetup.CATEGORY_REPAYMENT)).isNotNull()
        // Works again straight away, and the append-only balance rule is back.
        val fresh = (accounts.createAccount(NewAccount("INDBNK", "Indian Bank", "0000", "Indian", AccountType.SAVINGS, 200_000)) as CreateAccountResult.Created).accountId
        assertThat(AccountRepository(db.accountDao(), db.ledgerDao()).observeBalance(fresh).first().displayedPaise).isEqualTo(200_000)
        assertThat(runCatching { db.openHelper.writableDatabase.execSQL("DELETE FROM balance_anchor") }.isFailure).isTrue()
    }
}
