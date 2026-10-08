package com.spendwise.app.transaction.ingest

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single lock over SMS writes: staging, processing and "Delete all data". Pull-to-refresh and the import hold it
 * across stage + process, so a wipe can never land between them and leave old messages behind. Not re-entrant —
 * code already holding it calls the `…Locked` variants.
 */
@Singleton
class IngestGate @Inject constructor() {
    private val mutex = Mutex()
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
