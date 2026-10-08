package com.spendwise.app.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.spendwise.app.core.time.Clock
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.domain.AccountLedger
import com.spendwise.app.domain.CreateAccountResult
import com.spendwise.app.domain.EntryInput
import com.spendwise.app.domain.LedgerResult
import com.spendwise.app.domain.NewAccount
import com.spendwise.app.domain.SplitPart
import com.spendwise.app.domain.TransactionLedger
import com.spendwise.app.transaction.ingest.IngestGate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Phase 7: encrypted backup format, restore validation, CSV export, lock timing (ADR-002). */
@RunWith(AndroidJUnit4::class)
class SecurityTest {

    // Fewer iterations keep the tests fast; the format records the count, so production uses 600 000.
    private val fast = 10_000

    @Test fun `a backup round-trips only with the right passphrase, and any tampering is caught`() {
        val plain = "SQLite format 3 … pretend database".repeat(50).toByteArray()
        val file = BackupCrypto.encrypt(plain, "correct horse".toCharArray(), schemaVersion = 2, iterations = fast)
        val (schema, back) = BackupCrypto.decrypt(file, "correct horse".toCharArray())
        assertThat(schema).isEqualTo(2)
        assertThat(back).isEqualTo(plain)

        fun reason(bytes: ByteArray, pass: String) = runCatching { BackupCrypto.decrypt(bytes, pass.toCharArray()) }.exceptionOrNull()?.let { (it as BackupException).reason }
        assertThat(reason(file, "Correct horse")).isEqualTo(BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED)
        // A flipped bit in the content, or an edited header (e.g. schema version), fails the same way.
        assertThat(reason(file.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }, "correct horse")).isEqualTo(BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED)
        assertThat(reason(file.copyOf().also { it[8] = (it[8] + 1).toByte() }, "correct horse")).isEqualTo(BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED)
        assertThat(reason("not a backup at all, just some text".toByteArray(), "x")).isEqualTo(BackupException.Reason.NOT_A_BACKUP)
        assertThat(reason(file.copyOf().also { it[4] = 9 }, "correct horse")).isEqualTo(BackupException.Reason.NEWER_FORMAT)
        // Same input, different file every time (fresh salt + nonce).
        assertThat(BackupCrypto.encrypt(plain, "correct horse".toCharArray(), 2, fast)).isNotEqualTo(file)
    }

    @Test fun `the default key derivation is the ADR-002 strength`() {
        assertThat(BackupCrypto.ITERATIONS).isAtLeast(600_000)
        assertThat(Passphrase.problem("short".toCharArray(), "short".toCharArray())).isNotNull()
        assertThat(Passphrase.problem("long enough".toCharArray(), "long enougH".toCharArray())).isNotNull()
        assertThat(Passphrase.problem("aaaaaaaaaa".toCharArray(), "aaaaaaaaaa".toCharArray())).isNotNull()
        assertThat(Passphrase.problem("my tea money 42".toCharArray(), "my tea money 42".toCharArray())).isNull()
    }

    @Test fun `the lock comes back on a cold start or after the chosen time away`() {
        assertThat(AppLockPolicy.shouldLock(enabled = false, leftAt = null, now = 0, timeoutMs = 0)).isFalse()
        assertThat(AppLockPolicy.shouldLock(true, null, 1_000, 60_000)).isTrue()
        assertThat(AppLockPolicy.shouldLock(true, 1_000, 30_000, 60_000)).isFalse()
        assertThat(AppLockPolicy.shouldLock(true, 1_000, 61_000, 60_000)).isTrue()
        assertThat(AppLockPolicy.shouldLock(true, 1_000, 1_000, 0)).isTrue()
    }

    @Test fun `CSV amounts are exact and formula injection is neutralised`() {
        assertThat(CsvExporter.rupees(-45_050)).isEqualTo("-450.50")
        assertThat(CsvExporter.rupees(5)).isEqualTo("0.05")
        assertThat(CsvExporter.rupees(100_000_000_00)).isEqualTo("100000000.00")
        assertThat(CsvExporter.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"")
        assertThat(CsvExporter.cell("+91 phone")).isEqualTo("'+91 phone")
        assertThat(CsvExporter.cell("Lakshmi, Store")).isEqualTo("\"Lakshmi, Store\"")
        assertThat(CsvExporter.cell("plain")).isEqualTo("plain")
    }

    // ---------------------------------------------------------------- with a real database

    private lateinit var db: SpendWiseDatabase
    private lateinit var context: Context
    private val clock = Clock { 1_759_800_000_000L }
    private var hdfc = 0L
    private var indian = 0L

    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("security-test.db")
        db = Room.databaseBuilder(context, SpendWiseDatabase::class.java, "security-test.db")
            .addCallback(SpendWiseDatabase.SetupCallback()).allowMainThreadQueries().build()
        val accounts = AccountLedger(db, clock)
        hdfc = (accounts.createAccount(NewAccount("HDFCBK", "HDFC Bank", "1111", "HDFC", AccountType.SAVINGS, 5_000_000)) as CreateAccountResult.Created).accountId
        indian = (accounts.createAccount(NewAccount("INDBNK", "Indian Bank", "0000", "Indian", AccountType.SAVINGS, 1_000_000)) as CreateAccountResult.Created).accountId
        val ledger = TransactionLedger(db, clock)
        val bill = (ledger.addEntry(EntryInput(hdfc, TxnKind.EXPENSE, 200_000, 11, "DMart, Anna Nagar", "=SUM(A1)", clock.now(), PaymentMethod.UPI)) as LedgerResult.Ok).id
        ledger.setSplits(bill, listOf(SplitPart(11, 150_000), SplitPart(21, 50_000)))
        ledger.addEntry(EntryInput(indian, TxnKind.EXPENSE, 12_000, 10, "Tea Point", null, clock.now(), PaymentMethod.UPI))
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase("security-test.db")
    }

    @Test fun `a backup of the live database validates and summarises, and a broken one is refused`() = runTest {
        val service = BackupService(context, db, IngestGate())
        val out = ByteArrayOutputStream()
        val summary = service.backup(out, "my tea money 42".toCharArray())
        assertThat(summary.accounts).isEqualTo(2)
        assertThat(summary.transactions).isEqualTo(2)

        val candidate = service.open(ByteArrayInputStream(out.toByteArray()), "my tea money 42".toCharArray())
        assertThat(candidate.summary.accounts).isEqualTo(2)
        service.discard(candidate)

        assertThat(runCatching { service.open(ByteArrayInputStream(out.toByteArray()), "wrong passphrase".toCharArray()) }.exceptionOrNull())
            .isInstanceOf(BackupException::class.java)

        // A backup whose money rules are broken never gets to replace anything.
        val snap = File(context.cacheDir, "broken.db")
        snap.delete()
        db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(snap.path))
        android.database.sqlite.SQLiteDatabase.openDatabase(snap.path, null, 0).use {
            it.execSQL("DROP TRIGGER IF EXISTS trg_txn_check_UPDATE")
            it.execSQL("UPDATE txn_split SET amountPaise = amountPaise - 1 WHERE id = (SELECT MIN(id) FROM txn_split)")
        }
        val broken = BackupCrypto.encrypt(snap.readBytes(), "pass phrase 1".toCharArray(), db.openHelper.readableDatabase.version, fast)
        val error = runCatching { service.open(ByteArrayInputStream(broken), "pass phrase 1".toCharArray()) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error!!.message).contains("split")
    }

    @Test fun `CSV export holds ONE account, exact amounts, splits spelled out`() = runTest {
        val out = ByteArrayOutputStream()
        val n = CsvExporter(db).export(hdfc, out)
        assertThat(n).isEqualTo(1)
        val text = out.toString(Charsets.UTF_8.name())
        assertThat(text).contains("-2000.00")
        assertThat(text).contains("Split: Groceries -1500.00; Household -500.00")
        assertThat(text).contains("\"DMart, Anna Nagar\"")
        assertThat(text).contains("'=SUM(A1)")
        assertThat(text).doesNotContain("Tea Point") // the other account
    }
}
