package com.spendwise.app.data

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.spendwise.app.data.account.SelectedAccountStore
import com.spendwise.app.data.db.DatabaseSetup
import com.spendwise.app.data.db.SpendWiseDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * More → "Delete all data": every account, transaction, balance history, SMS record, friend, learned rule and
 * custom category — gone, irreversibly, on this phone. Appearance settings are kept. The built-in categories are
 * seeded again so the app works straight away.
 */
@Singleton
class DataWiper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SpendWiseDatabase,
    private val selected: SelectedAccountStore,
    private val sync: com.spendwise.app.transaction.sms.SmsSync,
    private val gate: com.spendwise.app.transaction.ingest.IngestGate,
) {
    /** Runs to the end even if the screen goes away. */
    suspend fun wipeEverything() = withContext(NonCancellable + Dispatchers.IO) {
        // Queued import/ingest work is cancelled; anything already running finishes first (it holds the gate).
        runCatching { WorkManager.getInstance(context).cancelAllWork().result.get() }
        gate.withLock {
            // ONE transaction: triggers are dropped (balance history is append-only), every table emptied, triggers
            // and built-in categories restored — no other write can ever see the database without its rules.
            db.runInTransaction {
                val w = db.openHelper.writableDatabase
                DatabaseSetup.dropTriggers(w)
                w.execSQL("PRAGMA defer_foreign_keys = TRUE")
                tables(w).forEach { w.execSQL("DELETE FROM `$it`") }
                DatabaseSetup.createTriggers(w)
                DatabaseSetup.seedOnOpen(w)
            }
            // Deleted pages may still hold old text; rewrite the file without them.
            val w = db.openHelper.writableDatabase
            runCatching { w.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
            runCatching { w.execSQL("VACUUM") }
        }
        selected.clear()
        sync.clearImportState()
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun tables(w: androidx.sqlite.db.SupportSQLiteDatabase): List<String> =
        w.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('android_metadata', 'room_master_table') AND name NOT LIKE 'sqlite_%'")
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
}
