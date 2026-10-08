package com.spendwise.app.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.AnchorSource
import com.spendwise.app.data.db.entity.Confidence
import com.spendwise.app.data.db.entity.MessageSource
import com.spendwise.app.data.db.entity.ParseStatus
import com.spendwise.app.data.db.entity.RawMessageEntity
import com.spendwise.app.data.db.entity.TimeSource
import com.spendwise.app.data.db.entity.TxnEntity
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.data.db.entity.TxnSourceEntity
import com.spendwise.app.data.db.entity.TxnStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 3: accounts, anchor-based balances (ADR-004) and account isolation. */
@RunWith(AndroidJUnit4::class)
class AccountLedgerTest {

    private lateinit var db: SpendWiseDatabase
    private var now = 1_759_800_000_000L // 2025-10-07 IST-ish; only ordering matters
    private val clock = Clock { now }
    private lateinit var ledger: AccountLedger
    private lateinit var repo: AccountRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SpendWiseDatabase::class.java)
            .addCallback(SpendWiseDatabase.SetupCallback())
            .allowMainThreadQueries()
            .build()
        ledger = AccountLedger(db, clock)
        repo = AccountRepository(db.accountDao(), db.ledgerDao())
    }

    @After fun tearDown() = db.close()

    private suspend fun create(code: String, last4: String, opening: Long, type: AccountType = AccountType.SAVINGS): Long {
        val r = ledger.createAccount(NewAccount(code, "$code Bank", last4, "", type, opening))
        return (r as CreateAccountResult.Created).accountId
    }

    private suspend fun txn(
        accountId: Long,
        amount: Long,
        kind: TxnKind,
        at: Long,
        status: TxnStatus = TxnStatus.CONFIRMED,
        reportedBalance: Long? = null,
        reportedAt: Long? = null,
    ): Long = db.txnDao().insert(
            TxnEntity(
                accountId = accountId, amountPaise = amount, isCredit = amount > 0, kind = kind, status = status,
                confidence = Confidence.HIGH, occurredAt = at, localEpochDay = IndiaTime.localEpochDay(at),
                timeSource = TimeSource.MANUAL, reportedBalancePaise = reportedBalance, reportedBalanceAt = reportedAt,
                createdAt = at, updatedAt = at,
            ),
        )

    @Test fun `new account shows its opening balance as an adjustment over zero transactions`() = runTest {
        val id = create("INDBNK", "8625", 284444)
        val b = repo.observeBalance(id).first()
        assertThat(b.displayedPaise).isEqualTo(284444)
        assertThat(b.fromTransactionsPaise).isEqualTo(0)
        assertThat(b.adjustmentPaise).isEqualTo(284444)
    }

    @Test fun `confirmed transactions after the anchor move the balance, pending and ignored do not`() = runTest {
        val id = create("INDBNK", "8625", 100_000)
        now += 1_000
        txn(id, -2_300, TxnKind.EXPENSE, now)
        txn(id, -50_000, TxnKind.EXPENSE, now, TxnStatus.PENDING)
        txn(id, -70_000, TxnKind.EXPENSE, now, TxnStatus.IGNORED)
        txn(id, 10_000, TxnKind.INCOME, now)
        assertThat(repo.observeBalance(id).first().displayedPaise).isEqualTo(100_000 - 2_300 + 10_000)
    }

    @Test fun `history imported from before the anchor never changes today's balance`() = runTest {
        val id = create("HDFCBK", "1111", 2_845_000)
        // A 90-day historical import lands transactions BEFORE the opening anchor.
        txn(id, -464_000, TxnKind.EXPENSE, now - 30L * 86_400_000)
        txn(id, 5_000_000, TxnKind.INCOME, now - 20L * 86_400_000)
        val b = repo.observeBalance(id).first()
        assertThat(b.displayedPaise).isEqualTo(2_845_000)
        // …but the breakdown still accounts for them: calculated + adjustment = displayed.
        assertThat(b.fromTransactionsPaise + b.adjustmentPaise).isEqualTo(b.displayedPaise)
    }

    @Test fun `editing the balance appends an anchor and never touches transactions`() = runTest {
        val id = create("INDBNK", "8625", 100_000)
        now += 1_000; txn(id, -20_000, TxnKind.EXPENSE, now)
        now += 1_000; ledger.setBalance(id, 75_000, AnchorSource.MANUAL, "Cash I forgot")
        now += 1_000; txn(id, -5_000, TxnKind.EXPENSE, now)

        assertThat(repo.observeBalance(id).first().displayedPaise).isEqualTo(70_000)
        assertThat(db.txnDao().confirmedSum(id)).isEqualTo(-25_000) // history intact

        val history = ledger.adjustmentHistory(id)
        assertThat(history.map { it.source }).containsExactly(AnchorSource.OPENING, AnchorSource.MANUAL).inOrder()
        assertThat(history[0].deltaPaise).isNull()
        // Before the edit the balance was 100,000 − 20,000 = 80,000 → the edit moved it by −5,000.
        assertThat(history[1].deltaPaise).isEqualTo(-5_000)
        assertThat(history[1].note).isEqualTo("Cash I forgot")
    }

    @Test fun `reconcile is recorded as its own anchor source`() = runTest {
        val id = create("HDFCBK", "1111", 0)
        now += 1_000; ledger.setBalance(id, 256_844, AnchorSource.RECONCILE)
        assertThat(ledger.adjustmentHistory(id).last().source).isEqualTo(AnchorSource.RECONCILE)
        assertThat(repo.observeBalance(id).first().displayedPaise).isEqualTo(256_844)
    }

    @Test fun `SMS balance reconciliation preserves confirmed transactions after the message`() = runTest {
        val id = create("INDBNK", "8625", 100_000)
        val other = create("HDFCBK", "1111", 300_000)
        now += 1_000
        val txnId = txn(id, -20_000, TxnKind.EXPENSE, now, reportedBalance = 75_000, reportedAt = now + 1_000)
        val smsAt = now + 1_000
        val rawId = db.rawMessageDao().insertOrIgnore(
            RawMessageEntity(
                source = MessageSource.SMS_LIVE, sender = "VM-INDBNK-S", body = "anonymized bank message",
                sentAt = smsAt, receivedAt = smsAt, fingerprint = "balance-test-$txnId",
                parseStatus = ParseStatus.PARSED, parserVersion = 1, bankHint = "INDBNK",
            ),
        )
        db.reviewDao().insertSource(TxnSourceEntity(txnId, rawId))
        txn(id, -2_000, TxnKind.EXPENSE, smsAt + 1_000)
        now = smsAt + 2_000

        val comparison = ledger.compareSmsBalance(id, 75_000, smsAt)!!
        assertThat(comparison.displayedNowPaise).isEqualTo(78_000)
        assertThat(comparison.estimatedAtSmsPaise).isEqualTo(80_000)
        assertThat(comparison.adjustmentPaise).isEqualTo(-5_000)
        assertThat(ledger.reconcileSmsBalance(txnId, id, 75_000, smsAt)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(repo.observeBalance(id).first().displayedPaise).isEqualTo(73_000)
        assertThat(repo.observeBalance(other).first().displayedPaise).isEqualTo(300_000)
        assertThat(ledger.adjustmentHistory(other).map { it.source }).containsExactly(AnchorSource.OPENING)
        assertThat(ledger.adjustmentHistory(id).last().source).isEqualTo(AnchorSource.RECONCILE)
        assertThat(db.txnDao().confirmedSum(id)).isEqualTo(-22_000)
    }

    @Test fun `SMS balance comparison refuses stale anchors, old messages and credit cards`() = runTest {
        val savings = create("INDBNK", "8625", 100_000)
        val card = create("HDFCBK", "1111", 50_000, AccountType.CREDIT_CARD)
        val smsAt = now
        now += 1_000
        ledger.setBalance(savings, 90_000, AnchorSource.MANUAL)
        assertThat(ledger.compareSmsBalance(savings, 80_000, smsAt)).isNull()
        assertThat(ledger.compareSmsBalance(card, 40_000, now)).isNull()
        assertThat(ledger.compareSmsBalance(savings, 80_000, now - AccountLedger.SMS_BALANCE_MAX_AGE_MS - 1)).isNull()

        val another = create("AXIS", "2222", 20_000)
        txn(another, -1_000, TxnKind.EXPENSE, now + 1)
        assertThat(ledger.compareSmsBalance(another, 10_000, now)).isNull() // future-dated activity cannot be safely rebased
    }

    @Test fun `SMS reconciliation command rejects a still-pending transaction`() = runTest {
        val account = create("INDBNK", "8625", 100_000)
        val pendingAt = now + 1_000
        val txnId = txn(account, -1_000, TxnKind.EXPENSE, pendingAt, TxnStatus.PENDING, reportedBalance = 90_000, reportedAt = pendingAt)
        val rawId = db.rawMessageDao().insertOrIgnore(
            RawMessageEntity(
                source = MessageSource.SMS_LIVE, sender = "VM-INDBNK-S", body = "anonymized bank message",
                sentAt = pendingAt, receivedAt = pendingAt, fingerprint = "pending-balance-$txnId",
                parseStatus = ParseStatus.PARSED, parserVersion = 1, bankHint = "INDBNK",
            ),
        )
        db.reviewDao().insertSource(TxnSourceEntity(txnId, rawId))
        assertThat(ledger.reconcileSmsBalance(txnId, account, 90_000, pendingAt)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(repo.observeBalance(account).first().displayedPaise).isEqualTo(100_000)
    }

    @Test fun `SMS reconciliation rejects mismatched account balance and timestamp`() = runTest {
        val account = create("INDBNK", "8625", 100_000)
        val other = create("HDFCBK", "1111", 200_000)
        val smsAt = now + 1_000
        val txnId = txn(account, -1_000, TxnKind.EXPENSE, smsAt, reportedBalance = 90_000, reportedAt = smsAt)
        val rawId = db.rawMessageDao().insertOrIgnore(
            RawMessageEntity(
                source = MessageSource.SMS_LIVE, sender = "VM-INDBNK-S", body = "anonymized bank message",
                sentAt = smsAt, receivedAt = smsAt, fingerprint = "mismatch-balance-$txnId",
                parseStatus = ParseStatus.PARSED, parserVersion = 1, bankHint = "INDBNK",
            ),
        )
        db.reviewDao().insertSource(TxnSourceEntity(txnId, rawId))
        now = smsAt + 1_000

        assertThat(ledger.reconcileSmsBalance(txnId, other, 90_000, smsAt)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.reconcileSmsBalance(txnId, account, 89_999, smsAt)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(ledger.reconcileSmsBalance(txnId, account, 90_000, smsAt + 1)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(repo.observeBalance(account).first().displayedPaise).isEqualTo(99_000)
        assertThat(repo.observeBalance(other).first().displayedPaise).isEqualTo(200_000)
    }

    @Test fun `SMS reconciliation rejects a dialog made stale by a later balance anchor`() = runTest {
        val account = create("INDBNK", "8625", 100_000)
        val smsAt = now + 1_000
        val txnId = txn(account, -1_000, TxnKind.EXPENSE, smsAt, reportedBalance = 90_000, reportedAt = smsAt)
        val rawId = db.rawMessageDao().insertOrIgnore(
            RawMessageEntity(
                source = MessageSource.SMS_LIVE, sender = "VM-INDBNK-S", body = "anonymized bank message",
                sentAt = smsAt, receivedAt = smsAt, fingerprint = "stale-dialog-$txnId",
                parseStatus = ParseStatus.PARSED, parserVersion = 1, bankHint = "INDBNK",
            ),
        )
        db.reviewDao().insertSource(TxnSourceEntity(txnId, rawId))
        now = smsAt + 1_000
        assertThat(ledger.compareSmsBalance(account, 90_000, smsAt)).isNotNull()

        now += 2_000
        ledger.setBalance(account, 120_000, AnchorSource.MANUAL)
        assertThat(ledger.reconcileSmsBalance(txnId, account, 90_000, smsAt)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(repo.observeBalance(account).first().displayedPaise).isEqualTo(120_000)
        assertThat(ledger.adjustmentHistory(account).map { it.source }).containsExactly(AnchorSource.OPENING, AnchorSource.MANUAL).inOrder()
    }

    @Test fun `balances and month summaries never mix accounts`() = runTest {
        val indian = create("INDBNK", "8625", 100_000)
        val hdfc = create("HDFCBK", "1111", 2_000_000)
        now += 1_000
        txn(indian, -2_300, TxnKind.EXPENSE, now)
        txn(hdfc, 5_000_000, TxnKind.INCOME, now)
        txn(hdfc, -464_000, TxnKind.EXPENSE, now)

        assertThat(repo.observeBalance(indian).first().displayedPaise).isEqualTo(97_700)
        assertThat(repo.observeBalance(hdfc).first().displayedPaise).isEqualTo(2_000_000 + 5_000_000 - 464_000)

        val list = repo.observeAccountsWithBalances().first().associate { it.account.id to it.balancePaise }
        assertThat(list).containsExactly(indian, 97_700L, hdfc, 6_536_000L)

        val indianMonth = repo.observeThisMonth(indian, now).first()
        assertThat(indianMonth.incomePaise).isEqualTo(0)
        assertThat(indianMonth.spentPaise).isEqualTo(2_300)
        val hdfcMonth = repo.observeThisMonth(hdfc, now).first()
        assertThat(hdfcMonth.incomePaise).isEqualTo(5_000_000)
        assertThat(hdfcMonth.spentPaise).isEqualTo(464_000)
    }

    @Test fun `transfers are never income or expense in the month summary, refunds net spending`() = runTest {
        val id = create("INDBNK", "8625", 1_000_000)
        now += 1_000
        txn(id, -500_000, TxnKind.TRANSFER_OUT, now)
        txn(id, -300_000, TxnKind.ATM_WITHDRAWAL, now)
        txn(id, 200_000, TxnKind.TRANSFER_IN, now)
        txn(id, -40_000, TxnKind.EXPENSE, now)
        txn(id, 10_000, TxnKind.REFUND, now)
        val m = repo.observeThisMonth(id, now).first()
        assertThat(m.incomePaise).isEqualTo(0)
        assertThat(m.spentPaise).isEqualTo(30_000)
    }

    @Test fun `duplicate bank and last4 is rejected, cash needs no digits`() = runTest {
        create("HDFCBK", "1111", 0)
        assertThat(ledger.createAccount(NewAccount("HDFCBK", "HDFC Bank", "1111", "", AccountType.SAVINGS, 0)))
            .isEqualTo(CreateAccountResult.Duplicate)
        assertThat(ledger.createAccount(NewAccount("HDFCBK", "HDFC Bank", "11", "", AccountType.SAVINGS, 0)))
            .isInstanceOf(CreateAccountResult.Invalid::class.java)
        assertThat(ledger.createAccount(NewAccount("CASH", "Cash", "", "Wallet", AccountType.CASH, 50_000)))
            .isInstanceOf(CreateAccountResult.Created::class.java)
    }

    @Test fun `new account gets its own visible category overlay`() = runTest {
        val id = create("INDBNK", "8625", 0)
        assertThat(db.accountCategoryDao().countFor(id)).isGreaterThan(10)
    }

    @Test fun `archived accounts disappear from the list but keep their history`() = runTest {
        val id = create("INDBNK", "8625", 100_000)
        now += 1_000; txn(id, -2_300, TxnKind.EXPENSE, now)
        ledger.archive(id)
        assertThat(repo.observeAccountsWithBalances().first()).isEmpty()
        assertThat(db.txnDao().confirmedSum(id)).isEqualTo(-2_300)
    }

    // ---- Audit follow-ups ----

    @Test fun `credit card figures are entered as amount owed for create, edit and reconcile alike`() = runTest {
        val card = (ledger.createAccount(NewAccount("HDFCBK", "HDFC Bank", "9999", "Card", AccountType.CREDIT_CARD, 1_200_000))
            as CreateAccountResult.Created).accountId
        assertThat(repo.observeBalance(card).first().displayedPaise).isEqualTo(-1_200_000)
        now += 1_000; ledger.setBalance(card, 1_250_000, AnchorSource.RECONCILE)
        assertThat(repo.observeBalance(card).first().displayedPaise).isEqualTo(-1_250_000)
        assertThat(ledger.adjustmentHistory(card).last().deltaPaise).isEqualTo(-50_000)
    }

    @Test fun `a balance edit still wins when the device clock went backwards`() = runTest {
        val id = create("INDBNK", "8625", 100_000)
        now -= 5 * 60_000 // network time correction
        ledger.setBalance(id, 80_000, AnchorSource.MANUAL)
        assertThat(repo.observeBalance(id).first().displayedPaise).isEqualTo(80_000)
        assertThat(ledger.adjustmentHistory(id).map { it.source }).containsExactly(AnchorSource.OPENING, AnchorSource.MANUAL).inOrder()
    }

    @Test fun `a reversed payment nets out of spending, reversed income nets out of income`() = runTest {
        val id = create("INDBNK", "8625", 1_000_000)
        now += 1_000
        txn(id, -200_000, TxnKind.EXPENSE, now)
        txn(id, 200_000, TxnKind.REVERSAL, now) // failed UPI payment returned
        txn(id, 500_000, TxnKind.INCOME, now)
        txn(id, -100_000, TxnKind.REVERSAL, now) // part of that credit pulled back
        val m = repo.observeThisMonth(id, now).first()
        assertThat(m.spentPaise).isEqualTo(0)
        assertThat(m.incomePaise).isEqualTo(400_000)
    }

    @Test fun `refunds above spending give a net credit instead of a negative-negative`() = runTest {
        val id = create("INDBNK", "8625", 0)
        now += 1_000
        txn(id, 50_000, TxnKind.REFUND, now)
        assertThat(repo.observeThisMonth(id, now).first().spentPaise).isEqualTo(-50_000)
        assertThat(com.spendwise.app.core.money.Money.format(50_000, com.spendwise.app.core.money.Money.SignStyle.Always)).isEqualTo("+₹\u2009500")
    }

    @Test fun `balance history is append-only in the database`() = runTest {
        val id = create("INDBNK", "8625", 100_000)
        val anchorId = db.ledgerDao().getAnchors(id).single().id
        val sql = db.openHelper.writableDatabase
        assertThrows { sql.execSQL("UPDATE balance_anchor SET balancePaise = 1 WHERE id = $anchorId") }
        assertThrows { sql.execSQL("DELETE FROM balance_anchor WHERE id = $anchorId") }
    }

    @Test fun `a refund cannot be linked to a transfer`() = runTest {
        val a = create("INDBNK", "8625", 1_000_000)
        now += 1_000
        txn(a, -100_000, TxnKind.TRANSFER_OUT, now)
        val transferId = db.openHelper.readableDatabase.query("SELECT id FROM txn WHERE kind = 'TRANSFER_OUT'").use { it.moveToFirst(); it.getLong(0) }
        assertThrows {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO txn (accountId, amountPaise, isCredit, kind, status, confidence, occurredAt, localEpochDay, timeSource, " +
                    "counterpartyKind, paymentMethod, refundOfId, createdAt, updatedAt) VALUES " +
                    "($a, 100000, 1, 'REFUND', 'CONFIRMED', 'HIGH', $now, 0, 'MANUAL', 'UNKNOWN', 'UNKNOWN', $transferId, 0, 0)",
            )
        }
    }

    @Test fun `each account's list balance uses its own anchor even when anchors are at different times`() = runTest {
        val indian = create("INDBNK", "8625", 100_000)
        now += 1_000; txn(indian, -2_300, TxnKind.EXPENSE, now)
        now += 60_000
        val hdfc = create("HDFCBK", "1111", 500_000) // later anchor
        now += 1_000; ledger.setBalance(hdfc, 450_000, AnchorSource.MANUAL)
        val list = repo.observeAccountsWithBalances().first().associate { it.account.id to it.balancePaise }
        assertThat(list[indian]).isEqualTo(97_700)
        assertThat(list[hdfc]).isEqualTo(450_000)
        // A's history is untouched by B's anchors.
        assertThat(ledger.adjustmentHistory(indian).map { it.source }).containsExactly(AnchorSource.OPENING)
    }

    @Test fun `opening row explains history imported from before it`() = runTest {
        val id = create("HDFCBK", "1111", 2_845_000)
        txn(id, -4_640_000, TxnKind.EXPENSE, now - 10L * 86_400_000)
        val opening = ledger.adjustmentHistory(id).single()
        val b = repo.observeBalance(id).first()
        assertThat(opening.deltaPaise).isEqualTo(b.adjustmentPaise)
    }

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try { block() } catch (e: Exception) { threw = true }
        assertThat(threw).isTrue()
    }
}
