package com.spendwise.app.domain

import androidx.room.withTransaction
import com.spendwise.app.core.merchant.MerchantDictionary
import com.spendwise.app.core.merchant.Merchants
import com.spendwise.app.core.money.Money
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.db.DatabaseSetup
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.Confidence
import com.spendwise.app.data.db.entity.CounterpartyKind
import com.spendwise.app.data.db.entity.MerchantRuleEntity
import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TimeSource
import com.spendwise.app.data.db.entity.TransferEntity
import com.spendwise.app.data.db.entity.TxnEntity
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.data.db.entity.TxnSplitEntity
import com.spendwise.app.data.db.entity.TxnStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Manual money in or out of ONE account. [amountPaise] is always positive; the ledger applies the sign. */
data class EntryInput(
    val accountId: Long,
    val kind: TxnKind,
    val amountPaise: Long,
    val categoryId: Long?,
    val merchant: String?,
    val note: String?,
    val occurredAt: Long,
    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
)

data class SplitPart(val categoryId: Long, val amountPaise: Long)

sealed interface LedgerResult {
    data class Ok(val id: Long) : LedgerResult
    data class Rejected(val reason: String) : LedgerResult
}

/**
 * Transaction ledger commands (ADR-003/ADR-004) — the only writers of `txn`, `transfer` and `txn_split`.
 * Each command runs in one database transaction; SQL triggers back up every rule enforced here.
 */
@Singleton
class TransactionLedger @Inject constructor(
    private val db: SpendWiseDatabase,
    private val clock: Clock,
) {
    private val txnDao get() = db.txnDao()

    // ---------------------------------------------------------------- entries

    suspend fun addEntry(input: EntryInput): LedgerResult {
        if (input.kind !in ENTRY_KINDS) return LedgerResult.Rejected("Use a transfer for money between your accounts")
        if (input.amountPaise <= 0) return LedgerResult.Rejected("Enter an amount above zero")
        val now = clock.now()
        val signed = signedFor(input.kind, input.amountPaise)
        val key = Merchants.key(input.merchant)
        return db.withTransaction {
            db.accountDao().getById(input.accountId) ?: return@withTransaction LedgerResult.Rejected("Account not found")
            val id = txnDao.insert(
                TxnEntity(
                    accountId = input.accountId,
                    amountPaise = signed,
                    isCredit = signed > 0,
                    kind = input.kind,
                    status = TxnStatus.CONFIRMED, // the user entered it: confirmed by definition
                    confidence = Confidence.HIGH,
                    occurredAt = input.occurredAt,
                    localEpochDay = IndiaTime.localEpochDay(input.occurredAt),
                    timeSource = TimeSource.MANUAL,
                    categoryId = input.categoryId,
                    merchantRaw = input.merchant?.trim()?.ifEmpty { null },
                    merchantKey = key,
                    counterpartyKind = if (Merchants.looksLikePerson(input.merchant)) CounterpartyKind.PERSON else CounterpartyKind.UNKNOWN,
                    paymentMethod = input.paymentMethod,
                    note = input.note?.trim()?.ifEmpty { null },
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            learn(key, input.categoryId, now)
            LedgerResult.Ok(id)
        }
    }

    /**
     * Edits an expense/income-type entry. Changing the amount clears its splits (they would no longer add up);
     * it may not drop below what has already been refunded.
     */
    suspend fun editEntry(txnId: Long, amountPaise: Long, categoryId: Long?, merchant: String?, note: String?, occurredAt: Long, paymentMethod: PaymentMethod): LedgerResult {
        if (amountPaise <= 0) return LedgerResult.Rejected("Enter an amount above zero")
        return db.withTransaction {
            val t = txnDao.getById(txnId) ?: return@withTransaction LedgerResult.Rejected("Transaction not found")
            if (t.kind.isTransferFamily) return@withTransaction LedgerResult.Rejected("Edit transfers from the transfer itself")
            val signed = signedFor(t.kind, amountPaise, fallbackNegative = t.amountPaise < 0)
            val isRefund = t.refundOfId != null
            if (isRefund) {
                // A refund's cap is against its ORIGINAL, counting every other refund of it.
                val original = txnDao.getById(t.refundOfId!!) ?: return@withTransaction LedgerResult.Rejected("Original purchase not found")
                val others = txnDao.refundedSoFar(original.id) - abs(t.amountPaise)
                val cap = abs(original.amountPaise) - others
                if (amountPaise > cap) return@withTransaction LedgerResult.Rejected("At most ${Money.format(cap)} can be refunded")
            } else {
                val refunded = txnDao.refundedSoFar(txnId)
                if (refunded > amountPaise) return@withTransaction LedgerResult.Rejected("Already refunded ${Money.format(refunded)}; the amount can't be lower")
            }
            val oldSplits = txnDao.getSplits(txnId)
            val amountChanged = signed != t.amountPaise
            if (amountChanged) txnDao.clearSplits(txnId)
            val hasSplits = !amountChanged && oldSplits.isNotEmpty()
            // A split parent whose amount changed falls back to its largest part's category, never "Uncategorised".
            val resolvedCategory = when {
                isRefund -> t.categoryId // refunds keep the original's category
                hasSplits -> null
                categoryId != null -> categoryId
                oldSplits.isNotEmpty() -> oldSplits.maxByOrNull { abs(it.amountPaise) }?.categoryId
                else -> null
            }
            val key = Merchants.key(merchant)
            val now = clock.now()
            txnDao.update(
                t.copy(
                    amountPaise = signed,
                    isCredit = signed > 0,
                    categoryId = resolvedCategory,
                    merchantRaw = merchant?.trim()?.ifEmpty { null },
                    merchantKey = key,
                    note = note?.trim()?.ifEmpty { null },
                    occurredAt = occurredAt,
                    localEpochDay = IndiaTime.localEpochDay(occurredAt),
                    paymentMethod = paymentMethod,
                    updatedAt = now,
                ),
            )
            if (!isRefund && !hasSplits) {
                learn(key, resolvedCategory, now)
                txnDao.setRefundsCategory(txnId, resolvedCategory)
            }
            LedgerResult.Ok(txnId)
        }
    }

    // ---------------------------------------------------------------- transfers

    /**
     * Money between two of the user's own accounts: one record, two linked legs (ADR-004). Bank → Cash is an
     * ATM withdrawal and Cash → bank a cash deposit; neither is ever income or expense.
     */
    suspend fun addTransfer(fromAccountId: Long, toAccountId: Long, amountPaise: Long, occurredAt: Long, note: String?): LedgerResult {
        if (fromAccountId == toAccountId) return LedgerResult.Rejected("Choose two different accounts")
        if (amountPaise <= 0) return LedgerResult.Rejected("Enter an amount above zero")
        val now = clock.now()
        return db.withTransaction {
            val from = db.accountDao().getById(fromAccountId) ?: return@withTransaction LedgerResult.Rejected("Account not found")
            val to = db.accountDao().getById(toAccountId) ?: return@withTransaction LedgerResult.Rejected("Account not found")
            val (outKind, inKind, method) = when {
                to.type == AccountType.CASH && from.type != AccountType.CASH -> Triple(TxnKind.ATM_WITHDRAWAL, TxnKind.TRANSFER_IN, PaymentMethod.ATM)
                from.type == AccountType.CASH && to.type != AccountType.CASH -> Triple(TxnKind.TRANSFER_OUT, TxnKind.CASH_DEPOSIT, PaymentMethod.CASH)
                else -> Triple(TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN, PaymentMethod.UNKNOWN)
            }
            fun leg(accountId: Long, signed: Long, kind: TxnKind) = TxnEntity(
                accountId = accountId,
                amountPaise = signed,
                isCredit = signed > 0,
                kind = kind,
                status = TxnStatus.CONFIRMED,
                confidence = Confidence.HIGH,
                occurredAt = occurredAt,
                localEpochDay = IndiaTime.localEpochDay(occurredAt),
                timeSource = TimeSource.MANUAL,
                categoryId = DatabaseSetup.CATEGORY_TRANSFER,
                // No merchant: the other side is shown by account name (TxnRow.counterpartyAccount), and a transfer
                // must never appear as a "merchant" in analytics or search.
                paymentMethod = method,
                note = note?.trim()?.ifEmpty { null },
                createdAt = now,
                updatedAt = now,
            )
            val outId = txnDao.insert(leg(from.id, signedFor(outKind, amountPaise), outKind))
            val inId = txnDao.insert(leg(to.id, signedFor(inKind, amountPaise), inKind))
            LedgerResult.Ok(txnDao.insertTransfer(TransferEntity(fromTxnId = outId, toTxnId = inId, createdAt = now)))
        }
    }

    /** Edits both legs of a transfer together, so they can never disagree. */
    suspend fun editTransfer(anyLegId: Long, amountPaise: Long, occurredAt: Long, note: String?): LedgerResult {
        if (amountPaise <= 0) return LedgerResult.Rejected("Enter an amount above zero")
        return db.withTransaction {
            val link = txnDao.transferFor(anyLegId) ?: return@withTransaction LedgerResult.Rejected("Not a transfer")
            val now = clock.now()
            for (legId in listOf(link.fromTxnId, link.toTxnId)) {
                val leg = txnDao.getById(legId) ?: continue
                val signed = signedFor(leg.kind, amountPaise)
                txnDao.update(
                    leg.copy(
                        amountPaise = signed,
                        isCredit = signed > 0,
                        occurredAt = occurredAt,
                        localEpochDay = IndiaTime.localEpochDay(occurredAt),
                        note = note?.trim()?.ifEmpty { null },
                        updatedAt = now,
                    ),
                )
            }
            LedgerResult.Ok(link.id)
        }
    }

    // ---------------------------------------------------------------- refunds

    /** A refund linked to its original spending in the same account; total refunds never exceed the original. */
    suspend fun addRefund(originalId: Long, amountPaise: Long, occurredAt: Long, note: String?): LedgerResult {
        if (amountPaise <= 0) return LedgerResult.Rejected("Enter an amount above zero")
        val now = clock.now()
        return db.withTransaction {
            val original = txnDao.getById(originalId) ?: return@withTransaction LedgerResult.Rejected("Transaction not found")
            if (original.kind != TxnKind.EXPENSE && original.kind != TxnKind.FEE) {
                return@withTransaction LedgerResult.Rejected("Only spending can be refunded")
            }
            if (original.status != TxnStatus.CONFIRMED) {
                return@withTransaction LedgerResult.Rejected("Confirm the purchase before adding a refund")
            }
            val remaining = abs(original.amountPaise) - txnDao.refundedSoFar(originalId)
            if (amountPaise > remaining) {
                return@withTransaction LedgerResult.Rejected("At most ${Money.format(remaining)} can still be refunded")
            }
            // Refund nets against the original's category — or its largest split.
            val category = original.categoryId ?: txnDao.getSplits(originalId).maxByOrNull { abs(it.amountPaise) }?.categoryId
            val id = txnDao.insert(
                TxnEntity(
                    accountId = original.accountId,
                    amountPaise = amountPaise,
                    isCredit = true,
                    kind = TxnKind.REFUND,
                    status = TxnStatus.CONFIRMED,
                    confidence = Confidence.HIGH,
                    occurredAt = occurredAt,
                    localEpochDay = IndiaTime.localEpochDay(occurredAt),
                    timeSource = TimeSource.MANUAL,
                    categoryId = category,
                    merchantRaw = original.merchantRaw,
                    merchantKey = original.merchantKey,
                    paymentMethod = original.paymentMethod,
                    refundOfId = originalId,
                    note = note?.trim()?.ifEmpty { null },
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            LedgerResult.Ok(id)
        }
    }

    // ---------------------------------------------------------------- splits

    /** Replaces a transaction's splits. Parts must add up EXACTLY to the transaction, in paise. */
    suspend fun setSplits(txnId: Long, parts: List<SplitPart>): LedgerResult {
        if (parts.size < 2) return LedgerResult.Rejected("A split needs at least two parts")
        if (parts.any { it.amountPaise <= 0 }) return LedgerResult.Rejected("Every part needs an amount")
        return db.withTransaction {
            val t = txnDao.getById(txnId) ?: return@withTransaction LedgerResult.Rejected("Transaction not found")
            if (t.kind !in SPLITTABLE) return@withTransaction LedgerResult.Rejected("Only spending or income can be split")
            val total = parts.sumOf { it.amountPaise }
            if (total != abs(t.amountPaise)) {
                return@withTransaction LedgerResult.Rejected("Parts add up to ${Money.format(total)} but the transaction is ${Money.format(abs(t.amountPaise))}")
            }
            val sign = if (t.amountPaise < 0) -1L else 1L
            txnDao.clearSplits(txnId)
            txnDao.insertSplits(parts.map { TxnSplitEntity(txnId = txnId, categoryId = it.categoryId, amountPaise = sign * it.amountPaise) })
            txnDao.update(t.copy(categoryId = null, updatedAt = clock.now()))
            // Refunds of a split purchase net against its largest part.
            txnDao.setRefundsCategory(txnId, parts.maxByOrNull { it.amountPaise }?.categoryId)
            LedgerResult.Ok(txnId)
        }
    }

    suspend fun removeSplits(txnId: Long, categoryId: Long?) = db.withTransaction {
        val t = txnDao.getById(txnId) ?: return@withTransaction
        if (t.kind !in SPLITTABLE) return@withTransaction // never recategorise a transfer leg or refund
        txnDao.clearSplits(txnId)
        txnDao.update(t.copy(categoryId = categoryId, updatedAt = clock.now()))
        txnDao.setRefundsCategory(txnId, categoryId)
    }

    // ---------------------------------------------------------------- delete

    /** Deletes a transaction. A transfer deletes both legs; spending with refunds must lose its refunds first. */
    suspend fun delete(txnId: Long): LedgerResult = db.withTransaction {
        val t = txnDao.getById(txnId) ?: return@withTransaction LedgerResult.Rejected("Transaction not found")
        if (txnDao.refundCount(txnId) > 0) return@withTransaction LedgerResult.Rejected("Delete its refunds first")
        val link = txnDao.transferFor(txnId)
        if (link != null) {
            txnDao.delete(link.fromTxnId)
            txnDao.delete(link.toTxnId)
        } else {
            txnDao.delete(t.id)
        }
        LedgerResult.Ok(txnId)
    }

    // ---------------------------------------------------------------- categories

    /** Account rule → global rule → shipped dictionary (ADR-001). */
    suspend fun suggestCategory(accountId: Long, merchant: String?): Long? {
        val key = Merchants.key(merchant) ?: return null
        return db.merchantRuleDao().lookup(accountId, key) ?: MerchantDictionary.categoryFor(key)
    }

    /** Corrections teach every account by default (ADR-001, user decision). */
    private suspend fun learn(merchantKey: String?, categoryId: Long?, now: Long) {
        if (merchantKey == null || categoryId == null || categoryId < 10) return // never learn system categories
        db.merchantRuleDao().upsert(MerchantRuleEntity(MerchantRuleEntity.GLOBAL_ACCOUNT, merchantKey, categoryId, now))
    }

    private companion object {
        val ENTRY_KINDS = setOf(TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.FEE, TxnKind.INTEREST)
        val SPLITTABLE = setOf(TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.FEE)
    }
}

/**
 * The ONE place a magnitude becomes a signed leg (invariant 2): money out of the account is negative.
 * Kinds that can go either way (REVERSAL/UNKNOWN) keep the direction of the row being edited.
 */
internal fun signedFor(kind: TxnKind, magnitude: Long, fallbackNegative: Boolean = false): Long = when (kind.direction) {
    TxnKind.Direction.OUT -> -magnitude
    TxnKind.Direction.IN -> magnitude
    TxnKind.Direction.EITHER -> if (fallbackNegative) -magnitude else magnitude
}
