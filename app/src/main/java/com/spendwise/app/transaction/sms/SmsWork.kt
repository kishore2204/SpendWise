package com.spendwise.app.transaction.sms

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.spendwise.app.domain.TransactionLedger
import com.spendwise.app.transaction.ingest.IngestProcessor
import com.spendwise.app.transaction.ingest.MessageStager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Hilt access for broadcast receivers (they are created by the system, not injected). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmsEntryPoint {
    fun stager(): MessageStager
    fun ledger(): TransactionLedger
    fun notifier(): TransactionNotifier

    companion object {
        fun get(context: Context): SmsEntryPoint = EntryPointAccessors.fromApplication(context.applicationContext, SmsEntryPoint::class.java)
    }
}

/**
 * Drains staged messages (ADR-005: WorkManager runs the processor, it never receives). Unique + APPEND_OR_REPLACE,
 * so bursts of SMS queue behind one another instead of running in parallel.
 */
@HiltWorker
class IngestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: IngestProcessor,
    private val notifier: TransactionNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val summary = processor.drain()
        notifier.notifyNew(summary.created)
        return Result.success()
    }

    companion object {
        private const val NAME = "ingest"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<IngestWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** The one-time historical import the user starts (ADR-005 §4). Keeps running if the user leaves the screen. */
@HiltWorker
class SmsImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: SmsSync,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        sync.importHistory(inputData.getInt(KEY_DAYS, 90))
        return Result.success()
    }

    companion object {
        const val NAME = "sms-import"
        private const val KEY_DAYS = "days"

        fun enqueue(context: Context, days: Int) {
            val request = OneTimeWorkRequestBuilder<SmsImportWorker>().setInputData(workDataOf(KEY_DAYS to days)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
