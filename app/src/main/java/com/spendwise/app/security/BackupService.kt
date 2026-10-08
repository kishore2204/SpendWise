package com.spendwise.app.security

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.work.WorkManager
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.transaction.ingest.IngestGate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/** What a backup contains — shown before the user confirms a restore. Counts only, no amounts. */
data class BackupSummary(val schemaVersion: Int, val accounts: Int, val transactions: Int, val lastTransactionAt: Long?)

/** A decrypted, VALIDATED backup waiting for the user's final "Restore". */
class RestoreCandidate internal constructor(internal val file: File, val summary: BackupSummary)

/**
 * Encrypted backup and restore (ADR-002 rule 7, ADR-004 "Restore"). Backups are consistent snapshots
 * (`VACUUM INTO`) taken under the [IngestGate]. A restore decrypts to a private temp file, runs `integrity_check`,
 * `foreign_key_check` and the money invariants, and only then — after the user confirms — swaps the database file,
 * keeping a copy of the current data first.
 */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SpendWiseDatabase,
    private val gate: IngestGate,
) {
    private val schemaVersion get() = db.openHelper.readableDatabase.version

    /** Writes an encrypted backup to [out] (a file the user picked through the system file picker). */
    suspend fun backup(out: OutputStream, passphrase: CharArray): BackupSummary = withContext(Dispatchers.IO) {
        val snapshot = File(context.cacheDir, "backup-snapshot.db")
        try {
            gate.withLock { snapshot(snapshot) }
            val summary = inspect(snapshot, schemaVersion)
            val encrypted = BackupCrypto.encrypt(snapshot.readBytes(), passphrase, schemaVersion)
            out.use { it.write(encrypted) }
            summary
        } finally {
            snapshot.delete()
            passphrase.fill('\u0000')
        }
    }

    /**
     * Opens and checks a backup without touching the current data. Throws [BackupException] for a wrong passphrase /
     * damaged / foreign file, or [IllegalStateException] with a plain message when the contents fail validation.
     */
    suspend fun open(input: InputStream, passphrase: CharArray): RestoreCandidate = withContext(Dispatchers.IO) {
        val (schema, bytes) = try {
            input.use { BackupCrypto.decrypt(it.readBytes(), passphrase) }
        } finally {
            passphrase.fill('\u0000')
        }
        check(schema <= schemaVersion) { "This backup was made by a newer SpendWise. Update the app first." }
        val candidate = File(context.noBackupFilesDir, "restore-candidate.db")
        candidate.delete()
        candidate.writeBytes(bytes)
        try {
            RestoreCandidate(candidate, inspect(candidate, schema))
        } catch (e: Exception) {
            candidate.delete()
            throw e
        }
    }

    /**
     * Replaces ALL current data with [candidate] and restarts the app (Room re-opens it, migrating an older backup).
     * The current database is kept as `pre-restore.db` first.
     */
    suspend fun restore(candidate: RestoreCandidate): Unit = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { WorkManager.getInstance(context).cancelAllWork().result.get() }
        gate.withLock {
            snapshot(File(context.noBackupFilesDir, "pre-restore.db"))
            val path = context.getDatabasePath(SpendWiseDatabase.NAME)
            db.close()
            listOf("-wal", "-shm", "-journal").forEach { File(path.path + it).delete() }
            candidate.file.copyTo(path, overwrite = true)
            candidate.file.delete()
        }
        restartApp()
    }

    fun discard(candidate: RestoreCandidate) {
        candidate.file.delete()
    }

    private fun snapshot(target: File) {
        target.delete()
        db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(target.path))
    }

    /** The checks a backup must pass before it may replace anything (ADR-004). */
    internal fun inspect(file: File, schema: Int): BackupSummary {
        val s = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            s.rawQuery("PRAGMA integrity_check", null).use { c -> check(c.moveToFirst() && c.getString(0) == "ok") { "The backup file is damaged." } }
            s.rawQuery("PRAGMA foreign_key_check", null).use { c -> check(!c.moveToFirst()) { "The backup's data doesn't hang together." } }
            check(s.version == schema && schema >= 1) { "The backup doesn't match its own header." }
            for (table in REQUIRED_TABLES) {
                s.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { c -> check(c.moveToFirst()) { "This isn't a SpendWise backup." } }
            }
            for ((sql, problem) in INVARIANTS) {
                s.rawQuery(sql, null).use { c -> check(c.moveToFirst() && c.getLong(0) == 0L) { problem } }
            }
            fun count(sql: String) = s.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
            val last = s.rawQuery("SELECT MAX(occurredAt) FROM txn", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            return BackupSummary(schema, count("SELECT COUNT(*) FROM account").toInt(), count("SELECT COUNT(*) FROM txn WHERE status != 'IGNORED'").toInt(), last)
        } finally {
            s.close()
        }
    }

    private fun restartApp(): Nothing {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        exitProcess(0)
    }

    private companion object {
        val REQUIRED_TABLES = listOf("account", "txn", "balance_anchor", "category", "transfer", "txn_split")

        /** Each query counts violations of a money rule; all must be 0 (CLAUDE.md "Transaction integrity"). */
        val INVARIANTS = listOf(
            "SELECT COUNT(*) FROM txn WHERE amountPaise = 0" to "The backup has a transaction of ₹0.",
            "SELECT COUNT(*) FROM txn WHERE isCredit != (amountPaise > 0)" to "The backup's amounts are inconsistent.",
            """SELECT COUNT(*) FROM txn t WHERE EXISTS (SELECT 1 FROM txn_split s WHERE s.txnId = t.id)
               AND (SELECT SUM(s.amountPaise) FROM txn_split s WHERE s.txnId = t.id) != t.amountPaise""" to "A split in the backup doesn't add up.",
            """SELECT COUNT(*) FROM transfer tr JOIN txn a ON a.id = tr.fromTxnId JOIN txn b ON b.id = tr.toTxnId
               WHERE a.accountId = b.accountId OR a.amountPaise >= 0 OR -a.amountPaise != b.amountPaise""" to "A transfer in the backup is broken.",
            """SELECT COUNT(*) FROM txn o WHERE o.kind IN ('EXPENSE','FEE')
               AND COALESCE((SELECT SUM(r.amountPaise) FROM txn r WHERE r.refundOfId = o.id AND r.status != 'IGNORED'), 0) > -o.amountPaise""" to
                "Refunds in the backup exceed their purchase.",
            "SELECT COUNT(*) FROM account a WHERE NOT EXISTS (SELECT 1 FROM balance_anchor b WHERE b.accountId = a.id)" to "An account in the backup has no balance.",
        )
    }
}
