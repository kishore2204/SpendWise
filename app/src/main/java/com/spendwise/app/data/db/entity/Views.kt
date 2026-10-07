package com.spendwise.app.data.db.entity

import androidx.room.DatabaseView

/**
 * The ONLY source for balances and analytics (ADR-004, CLAUDE.md). Pending and ignored rows cannot leak
 * into a number because DAOs that compute money never read `txn` directly.
 */
@DatabaseView(viewName = "v_confirmed_txn", value = "SELECT * FROM txn WHERE status = 'CONFIRMED'")
data class ConfirmedTxnView(
    val id: Long,
    val accountId: Long,
    val amountPaise: Long,
    val isCredit: Boolean,
    val kind: TxnKind,
    val status: TxnStatus,
    val confidence: Confidence,
    val occurredAt: Long,
    val localEpochDay: Long,
    val timeSource: TimeSource,
    val categoryId: Long?,
    val merchantRaw: String?,
    val merchantKey: String?,
    val counterpartyKind: CounterpartyKind,
    val paymentMethod: PaymentMethod,
    val bankRef: String?,
    val reportedBalancePaise: Long?,
    val refundOfId: Long?,
    val possibleDupOfId: Long?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
