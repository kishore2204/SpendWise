package com.spendwise.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.spendwise.app.core.time.Clock
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.domain.LedgerResult
import com.spendwise.app.domain.SharedExpenseLedger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrading a phone that already has data (ADR-004: never destructive). Builds the exact v1 schema from the exported
 * JSON, fills it like a real install, migrates, and checks nothing was lost and the new features work.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SpendWiseDatabase::class.java)

    @Test fun `version 1 to 2 keeps every account, anchor and transaction and enables shared expenses`() = runTest {
        // Full path on both sides (room-testing compares names literally; a bare name fails on Windows hosts).
        val dbPath = ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration-test.db").path
        helper.createDatabase(dbPath, 1).apply {
            // Only what a v1 install had: the new categories must arrive through seed-on-open.
            execSQL("INSERT INTO category (id, name, kind, iconKey, colorIndex, isSystem, sortOrder, archivedAt) VALUES (10, 'Food & Dining', 'EXPENSE', 'restaurant', 3, 0, 3, NULL)")
            execSQL("INSERT INTO account (id, bankCode, bankName, last4, nickname, type, createdAt, archivedAt) VALUES (1, 'HDFCBK', 'HDFC Bank', '1111', 'HDFC', 'SAVINGS', 1, NULL)")
            execSQL("INSERT INTO balance_anchor (accountId, effectiveAt, balancePaise, source, note, createdAt) VALUES (1, 1, 5000000, 'OPENING', NULL, 1)")
            execSQL(
                """
                INSERT INTO txn (id, accountId, amountPaise, isCredit, kind, status, confidence, occurredAt, localEpochDay, timeSource,
                                 categoryId, counterpartyKind, paymentMethod, createdAt, updatedAt)
                VALUES (1, 1, -3000, 0, 'EXPENSE', 'CONFIRMED', 'HIGH', 1000, 20368, 'MANUAL', 10, 'UNKNOWN', 'UPI', 1, 1)
                """.trimIndent(),
            )
            close()
        }
        helper.runMigrationsAndValidate(dbPath, 2, true).close()

        // Open it the way the app does: callbacks re-create triggers and seed new categories on open.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, SpendWiseDatabase::class.java, dbPath)
            .addCallback(SpendWiseDatabase.SetupCallback()).allowMainThreadQueries().build()
        try {
            val repo = AccountRepository(db.accountDao(), db.ledgerDao())
            assertThat(repo.observeBalance(1).first().displayedPaise).isEqualTo(4_997_000)
            assertThat(db.categoryDao().getById(DatabaseSetup.CATEGORY_REPAYMENT)).isNotNull()
            assertThat(db.categoryDao().getById(28)?.name).isEqualTo("Clothing") // added categories reach old installs

            val shared = SharedExpenseLedger(db, Clock { 2_000 })
            val ravi = (shared.friendNamed("Ravi") as LedgerResult.Ok).id
            assertThat(shared.share(1, ravi, 1_500)).isInstanceOf(LedgerResult.Ok::class.java)
            assertThat(shared.share(1, ravi, 1_501)).isInstanceOf(LedgerResult.Rejected::class.java)
            // The SQL cap exists on the migrated database too, not just the ledger check.
            val sqlCap = runCatching {
                db.openHelper.writableDatabase.execSQL("INSERT INTO shared_share (txnId, friendId, amountPaise, createdAt) VALUES (1, $ravi, 2000, 3)")
            }
            assertThat(sqlCap.isFailure).isTrue()
        } finally {
            db.close()
        }
    }
}
