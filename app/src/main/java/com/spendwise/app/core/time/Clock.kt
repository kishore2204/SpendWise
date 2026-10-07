package com.spendwise.app.core.time

/** Injectable "now" so ledger logic and tests never depend on the wall clock directly. */
fun interface Clock {
    fun now(): Long
}
