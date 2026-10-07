package com.spendwise.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.Confidence
import com.spendwise.app.data.db.entity.TimeSource
import com.spendwise.app.data.db.entity.TransferEntity
import com.spendwise.app.data.db.entity.TxnEntity
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.data.db.entity.TxnStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the invariants that the database itself enforces (ADR-004), independent of app code:
 * a bug in a ledger command must still be stopped here.
 */
@RunWith(AndroidJUnit4::class)
class SchemaInvariantsTest {

    private lateinit var db: SpendWiseDatabase
    private var indianBank = 0L
    private var hdfc = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SpendWiseDatabase::class.java)
            .addCallback(SpendWiseDatabase.SetupCallback())
            .allowMainThreadQueries()
            .build()
        indianBank = db.accountDao().insert(account("INDBNK", "Indian Bank", "0000"))
        hdfc = db.accountDao().insert(account("HDFCBK", "HDFC Bank", "1111"))
    }

    @After fun tearDown() = db.close()

    @Test fun `system and default categories are seeded`() = runTest {
        val names = db.categoryDao().getAll().map { it.name }
        assertThat(names).containsAtLeast("Transfer", "Refund", "Adjustment", "Food & Dining", "Salary")
    }

    @Test fun `expense must be negative and income positive`() = runTest {
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { db.txnDao().insert(txn(indianBank, +2300, TxnKind.EXPENSE)) }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { db.txnDao().insert(txn(indianBank, -2300, TxnKind.INCOME)) }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { db.txnDao().insert(txn(indianBank, 0, TxnKind.UNKNOWN)) }
        }
        assertThat(db.txnDao().insert(txn(indianBank, -2300, TxnKind.EXPENSE))).isGreaterThan(0)
    }

    @Test fun `isCredit must agree with the sign`() = runTest {
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { db.txnDao().insert(txn(indianBank, -2300, TxnKind.EXPENSE).copy(isCredit = true)) }
        }
    }

    @Test fun `pending and ignored never reach the confirmed view`() = runTest {
        db.txnDao().insert(txn(indianBank, -10000, TxnKind.EXPENSE, TxnStatus.CONFIRMED))
        db.txnDao().insert(txn(indianBank, -50000, TxnKind.EXPENSE, TxnStatus.PENDING))
        db.txnDao().insert(txn(indianBank, -70000, TxnKind.EXPENSE, TxnStatus.IGNORED))
        assertThat(db.txnDao().confirmedSum(indianBank)).isEqualTo(-10000)
    }

    @Test fun `confirmed sums never mix accounts`() = runTest {
        db.txnDao().insert(txn(indianBank, -10000, TxnKind.EXPENSE))
        db.txnDao().insert(txn(hdfc, 5000000, TxnKind.INCOME))
        assertThat(db.txnDao().confirmedSum(indianBank)).isEqualTo(-10000)
        assertThat(db.txnDao().confirmedSum(hdfc)).isEqualTo(5000000)
    }

    @Test fun `same bank reference in the same account and direction is stored once`() = runTest {
        val first = db.txnDao().insertOrIgnore(txn(indianBank, -2300, TxnKind.EXPENSE, bankRef = "600000000001"))
        val again = db.txnDao().insertOrIgnore(txn(indianBank, -2300, TxnKind.EXPENSE, bankRef = "600000000001"))
        assertThat(first).isGreaterThan(0)
        assertThat(again).isEqualTo(-1)
        assertThat(db.txnDao().confirmedSum(indianBank)).isEqualTo(-2300)
    }

    @Test fun `same RRN is allowed on both legs of a self-transfer and on a reversal`() = runTest {
        val rrn = "600000000009"
        assertThat(db.txnDao().insertOrIgnore(txn(indianBank, -1000000, TxnKind.TRANSFER_OUT, bankRef = rrn))).isGreaterThan(0)
        assertThat(db.txnDao().insertOrIgnore(txn(hdfc, 1000000, TxnKind.TRANSFER_IN, bankRef = rrn))).isGreaterThan(0)
        // A reversal credit in the original account carrying the same RRN is a different event.
        assertThat(db.txnDao().insertOrIgnore(txn(indianBank, 1000000, TxnKind.REVERSAL, bankRef = rrn))).isGreaterThan(0)
    }

    @Test fun `manual entries without a reference never collide`() = runTest {
        // Two genuine ₹20 teas on the same day are two transactions.
        assertThat(db.txnDao().insertOrIgnore(txn(indianBank, -2000, TxnKind.EXPENSE))).isGreaterThan(0)
        assertThat(db.txnDao().insertOrIgnore(txn(indianBank, -2000, TxnKind.EXPENSE))).isGreaterThan(0)
        assertThat(db.txnDao().confirmedSum(indianBank)).isEqualTo(-4000)
    }

    @Test fun `transfer legs must be in different accounts with matching amounts`() = runTest {
        val out = db.txnDao().insert(txn(indianBank, -1000000, TxnKind.TRANSFER_OUT))
        val sameAccountIn = db.txnDao().insert(txn(indianBank, 1000000, TxnKind.TRANSFER_IN))
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { db.txnDao().insertTransfer(TransferEntity(fromTxnId = out, toTxnId = sameAccountIn, createdAt = 0)) }
        }

        val wrongAmountIn = db.txnDao().insert(txn(hdfc, 999900, TxnKind.TRANSFER_IN))
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { db.txnDao().insertTransfer(TransferEntity(fromTxnId = out, toTxnId = wrongAmountIn, createdAt = 0)) }
        }

        val goodIn = db.txnDao().insert(txn(hdfc, 1000000, TxnKind.TRANSFER_IN))
        assertThat(db.txnDao().insertTransfer(TransferEntity(fromTxnId = out, toTxnId = goodIn, createdAt = 0))).isGreaterThan(0)
    }

    @Test fun `refund must be in the same account with the opposite sign`() = runTest {
        val purchase = db.txnDao().insert(txn(hdfc, -464000, TxnKind.EXPENSE))
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { db.txnDao().insert(txn(indianBank, 464000, TxnKind.REFUND).copy(refundOfId = purchase)) }
        }
        assertThat(db.txnDao().insert(txn(hdfc, 464000, TxnKind.REFUND).copy(refundOfId = purchase))).isGreaterThan(0)
    }

    @Test fun `duplicate account identifiers are rejected`() = runTest {
        assertThrows(SQLiteConstraintException::class.java) {
            kotlinx.coroutines.runBlocking { db.accountDao().insert(account("HDFCBK", "HDFC Bank", "1111")) }
        }
    }

    private fun account(code: String, name: String, last4: String) = AccountEntity(
        bankCode = code, bankName = name, last4 = last4, nickname = name, type = AccountType.SAVINGS, createdAt = 0,
    )

    private fun txn(
        accountId: Long,
        amount: Long,
        kind: TxnKind,
        status: TxnStatus = TxnStatus.CONFIRMED,
        bankRef: String? = null,
    ) = TxnEntity(
        accountId = accountId,
        amountPaise = amount,
        isCredit = amount > 0,
        kind = kind,
        status = status,
        confidence = Confidence.HIGH,
        occurredAt = 1_759_800_000_000,
        localEpochDay = 20_368,
        timeSource = TimeSource.MANUAL,
        bankRef = bankRef,
        createdAt = 0,
        updatedAt = 0,
    )
}
