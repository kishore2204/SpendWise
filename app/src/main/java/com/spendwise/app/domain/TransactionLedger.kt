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
        categoryProblem(input.categoryId, input.kind)?.let { return LedgerResult.Rejected(it) }
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
            if (t.kind == TxnKind.REPAYMENT) return@withTransaction LedgerResult.Rejected("Undo the repayment first, then edit it")
            if (t.refundOfId == null) categoryProblem(categoryId, t.kind)?.let { return@withTransaction LedgerResult.Rejected(it) }
            val signed = signedFor(t.kind, amountPaise, fallbackNegative = t.amountPaise < 0)
            val isRefund = t.refundOfId != null
            if (isRefund) {
                // A refund's cap is against its ORIGINAL, counting every other refund of it.
                val original = txnDao.getById(t.refundOfId!!) ?: return@withTransaction LedgerResult.Rejected("Original purchase not found")
                val others = txnDao.refundedSoFar(original.id) - abs(t.amountPaise)
                // Friends' shares are part of the cap too (ADR-011).
                val cap = abs(original.amountPaise) - others - db.friendDao().sharedOn(original.id)
                if (amountPaise > cap) return@withTransaction LedgerResult.Rejected("At most ${Money.format(cap)} can be refunded")
            } else {
                val refunded = txnDao.refundedSoFar(txnId)
                if (refunded > amountPaise) return@withTransaction LedgerResult.Rejected("Already refunded ${Money.format(refunded)}; the amount can't be lower")
                val shared = db.friendDao().sharedOn(txnId)
                if (shared > 0 && shared + refunded > amountPaise) {
                    return@withTransaction LedgerResult.Rejected("Friends' shares are ${Money.format(shared)}; the amount can't be lower")
                }
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
            val (outKind, inKind, method) = transferKinds(from.type, to.type)
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
            // Friends' shares (ADR-011) are part of the cap: a refund can't pay back money that a friend owes.
            val remaining = abs(original.amountPaise) - txnDao.refundedSoFar(originalId) - db.friendDao().sharedOn(originalId)
            if (amountPaise > remaining) {
                return@withTransaction LedgerResult.Rejected(
                    if (remaining <= 0) "Nothing left to refund — reduce the friends' shares first" else "At most ${Money.format(remaining)} can still be refunded",
                )
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

    /** Purchases a refund/reversal (money back) may belong to — same account, recent, merchant/amount first. */
    suspend fun refundSuggestions(txnId: Long): List<TxnEntity> {
        val t = txnDao.getById(txnId) ?: return emptyList()
        if (t.amountPaise <= 0 || t.refundOfId != null || (t.kind != TxnKind.REFUND && t.kind != TxnKind.REVERSAL)) return emptyList()
        return db.reviewDao().refundCandidates(t.accountId, t.amountPaise, t.merchantKey, t.occurredAt - REFUND_LOOKBACK_MS, t.occurredAt)
            .filter { it.id != t.id }
    }

    /**
     * Links money that came back (an SMS refund or reversal) to the purchase it pays back, so analytics net it against
     * that purchase's category. Same account only; capped by refunds + friends' shares like any refund.
     */
    suspend fun linkRefund(refundId: Long, originalId: Long): LedgerResult = db.withTransaction {
        val r = txnDao.getById(refundId) ?: return@withTransaction LedgerResult.Rejected("Transaction not found")
        val o = txnDao.getById(originalId) ?: return@withTransaction LedgerResult.Rejected("Purchase not found")
        val problem = when {
            r.amountPaise <= 0 || (r.kind != TxnKind.REFUND && r.kind != TxnKind.REVERSAL) -> "Only money coming back can be a refund"
            r.refundOfId != null -> "Already linked"
            r.accountId != o.accountId -> "The purchase must be in the same account"
            o.kind != TxnKind.EXPENSE && o.kind != TxnKind.FEE -> "Only spending can be refunded"
            o.status != TxnStatus.CONFIRMED -> "Confirm the purchase first"
            txnDao.transferFor(r.id) != null -> "This is a transfer"
            else -> null
        }
        if (problem != null) return@withTransaction LedgerResult.Rejected(problem)
        val remaining = abs(o.amountPaise) - txnDao.refundedSoFar(o.id) - db.friendDao().sharedOn(o.id)
        if (r.amountPaise > remaining) return@withTransaction LedgerResult.Rejected("At most ${Money.format(remaining.coerceAtLeast(0))} of that purchase can still be refunded")
        val category = o.categoryId ?: txnDao.getSplits(o.id).maxByOrNull { abs(it.amountPaise) }?.categoryId
        txnDao.clearSplits(r.id)
        txnDao.update(r.copy(kind = TxnKind.REFUND, refundOfId = o.id, categoryId = category, merchantRaw = r.merchantRaw ?: o.merchantRaw, merchantKey = r.merchantKey ?: o.merchantKey, updatedAt = clock.now()))
        LedgerResult.Ok(r.id)
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
        if (db.friendDao().shareCount(txnId) > 0) return@withTransaction LedgerResult.Rejected("Remove the friends' shares first")
        val link = txnDao.transferFor(txnId)
        if (link != null) {
            txnDao.delete(link.fromTxnId)
            txnDao.delete(link.toTxnId)
        } else {
            txnDao.delete(t.id)
        }
        LedgerResult.Ok(txnId)
    }

    // ---------------------------------------------------------------- review (Phase 5)

    /**
     * PENDING → CONFIRMED, the only way an SMS-detected transaction starts to count. [categoryId], when given,
     * replaces the suggestion and teaches the merchant rule. Confirming an ATM withdrawal (or cash deposit) also
     * books the Cash-wallet leg, so bank → Cash is one transfer with two legs — never an expense.
     */
    suspend fun confirm(txnId: Long, categoryId: Long? = null): LedgerResult = db.withTransaction { confirmInTransaction(txnId, categoryId) }

    /**
     * Confirm without opening it (notification button): refused when the money may be a friend paying back —
     * the user must look first (ADR-011), so it is never booked as income by accident.
     */
    suspend fun confirmOneTap(txnId: Long): LedgerResult = db.withTransaction { confirmInTransaction(txnId, null, oneTap = true) }

    /** Confirms every HIGH-confidence pending row of ONE account that is not a possible duplicate. */
    suspend fun confirmAllHighConfidence(accountId: Long): Int = db.withTransaction {
        db.reviewDao().pendingHighIds(accountId).count { confirmInTransaction(it, null, oneTap = true) is LedgerResult.Ok }
    }

    private suspend fun confirmInTransaction(txnId: Long, categoryId: Long?, oneTap: Boolean = false): LedgerResult {
        val t = txnDao.getById(txnId) ?: return LedgerResult.Rejected("Transaction not found")
        if (t.status != TxnStatus.PENDING) return LedgerResult.Rejected("Already reviewed")
        if (oneTap && t.amountPaise > 0 && (t.kind == TxnKind.INCOME || t.kind == TxnKind.UNKNOWN) &&
            (t.counterpartyKind == CounterpartyKind.PERSON || db.looksLikeRepayment(t.amountPaise, t.merchantRaw))
        ) {
            return LedgerResult.Rejected("Open it to check whether a friend paid you back")
        }
        val cashMove = (t.kind == TxnKind.ATM_WITHDRAWAL || t.kind == TxnKind.CASH_DEPOSIT) && txnDao.transferFor(t.id) == null
        val cash = if (cashMove) db.accountDao().cashWallet()?.takeIf { it.id != t.accountId } else null
        // Bank ↔ Cash is a transfer with two legs; without a wallet it would be money that vanishes from analytics.
        if (cashMove && cash == null) return LedgerResult.Rejected("Add a Cash wallet first, so the cash has somewhere to go")
        val now = clock.now()
        if (categoryId != null) categoryProblem(categoryId, t.kind)?.let { return LedgerResult.Rejected(it) }
        val recategorise = categoryId != null && t.kind in SPLITTABLE && txnDao.getSplits(t.id).isEmpty()
        val category = if (recategorise) categoryId else t.categoryId
        txnDao.update(t.copy(status = TxnStatus.CONFIRMED, categoryId = category, updatedAt = now))
        if (recategorise) {
            learn(t.merchantKey, categoryId, now)
            txnDao.setRefundsCategory(t.id, categoryId)
        }
        if (cash != null) {
            val cashKind = if (t.kind == TxnKind.ATM_WITHDRAWAL) TxnKind.TRANSFER_IN else TxnKind.TRANSFER_OUT
            val signed = signedFor(cashKind, abs(t.amountPaise))
            val cashLeg = txnDao.insert(
                TxnEntity(
                    accountId = cash.id,
                    amountPaise = signed,
                    isCredit = signed > 0,
                    kind = cashKind,
                    status = TxnStatus.CONFIRMED,
                    confidence = Confidence.HIGH,
                    occurredAt = t.occurredAt,
                    localEpochDay = t.localEpochDay,
                    timeSource = t.timeSource,
                    categoryId = DatabaseSetup.CATEGORY_TRANSFER,
                    paymentMethod = t.paymentMethod,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val (fromId, toId) = if (t.kind == TxnKind.ATM_WITHDRAWAL) t.id to cashLeg else cashLeg to t.id
            txnDao.insertTransfer(TransferEntity(fromTxnId = fromId, toTxnId = toId, createdAt = now))
        }
        return LedgerResult.Ok(t.id)
    }

    /** PENDING → IGNORED. The row stays (its dedup keys keep blocking re-imports) but its SMS text is dropped. */
    suspend fun ignore(txnId: Long): LedgerResult = db.withTransaction {
        val t = txnDao.getById(txnId) ?: return@withTransaction LedgerResult.Rejected("Transaction not found")
        if (t.status != TxnStatus.PENDING) return@withTransaction LedgerResult.Rejected("Only a transaction waiting for review can be ignored")
        txnDao.update(t.copy(status = TxnStatus.IGNORED, updatedAt = clock.now()))
        db.rawMessageDao().dropBodiesFor(txnId)
        LedgerResult.Ok(txnId)
    }

    /** Other-account legs that could be the other side of [txnId] (same RRN, or same amount within 10 min). Rows only. */
    suspend fun transferSuggestions(txnId: Long): List<TxnEntity> {
        val t = txnDao.getById(txnId) ?: return emptyList()
        if (t.status == TxnStatus.IGNORED || t.refundOfId != null || t.kind !in PAIRABLE || txnDao.transferFor(txnId) != null) return emptyList()
        return db.reviewDao().transferCandidates(t.accountId, t.amountPaise, t.bankRef, t.occurredAt, PAIR_WINDOW_MS)
    }

    /**
     * The user says two legs are one transfer between their own accounts (ADR-005 §6 — never paired automatically).
     * Both become confirmed transfer legs: no longer income or expense, no merchant, linked by one transfer row.
     */
    suspend fun pairAsTransfer(aId: Long, bId: Long): LedgerResult = db.withTransaction {
        val a = txnDao.getById(aId) ?: return@withTransaction LedgerResult.Rejected("Transaction not found")
        val b = txnDao.getById(bId) ?: return@withTransaction LedgerResult.Rejected("Transaction not found")
        val problem = when {
            a.accountId == b.accountId -> "A transfer is between two different accounts"
            a.amountPaise != -b.amountPaise -> "The amounts don't match"
            a.status == TxnStatus.IGNORED || b.status == TxnStatus.IGNORED -> "One of them was ignored"
            a.kind !in PAIRABLE || b.kind !in PAIRABLE -> "Only payments and receipts can be paired"
            a.refundOfId != null || b.refundOfId != null || txnDao.refundCount(aId) > 0 || txnDao.refundCount(bId) > 0 ->
                "Refunded transactions can't become a transfer"
            txnDao.transferFor(aId) != null || txnDao.transferFor(bId) != null -> "Already part of a transfer"
            db.friendDao().shareCount(aId) > 0 || db.friendDao().shareCount(bId) > 0 -> "A bill shared with friends can't become a transfer"
            else -> null
        }
        if (problem != null) return@withTransaction LedgerResult.Rejected(problem)
        val (out, into) = if (a.amountPaise < 0) a to b else b to a
        val from = db.accountDao().getById(out.accountId) ?: return@withTransaction LedgerResult.Rejected("Account not found")
        val to = db.accountDao().getById(into.accountId) ?: return@withTransaction LedgerResult.Rejected("Account not found")
        val (outKind, inKind, _) = transferKinds(from.type, to.type)
        val now = clock.now()
        for ((leg, kind) in listOf(out to outKind, into to inKind)) {
            txnDao.clearSplits(leg.id)
            txnDao.update(
                leg.copy(
                    kind = kind,
                    status = TxnStatus.CONFIRMED,
                    categoryId = DatabaseSetup.CATEGORY_TRANSFER,
                    merchantRaw = null,
                    merchantKey = null,
                    counterpartyKind = CounterpartyKind.UNKNOWN,
                    possibleDupOfId = null,
                    updatedAt = now,
                ),
            )
        }
        LedgerResult.Ok(txnDao.insertTransfer(TransferEntity(fromTxnId = out.id, toTxnId = into.id, createdAt = now)))
    }

    // ---------------------------------------------------------------- categories

    /** Account rule → global rule → shipped dictionary (ADR-001). */
    suspend fun suggestCategory(accountId: Long, merchant: String?, kind: com.spendwise.app.data.db.entity.CategoryKind? = null): Long? {
        val key = Merchants.key(merchant) ?: return null
        val id = db.merchantRuleDao().lookup(accountId, key) ?: MerchantDictionary.categoryFor(key) ?: return null
        // A rule learned on income never lands on spending (and vice versa).
        return if (kind == null || db.categoryDao().getById(id)?.kind == kind) id else null
    }

    /** The category must exist, be live, and match the money's direction; null (uncategorised) is always fine. */
    private suspend fun categoryProblem(categoryId: Long?, kind: TxnKind): String? {
        if (categoryId == null) return null
        val c = db.categoryDao().getById(categoryId) ?: return "Category not found"
        val wanted = when (kind) {
            TxnKind.EXPENSE, TxnKind.FEE -> com.spendwise.app.data.db.entity.CategoryKind.EXPENSE
            TxnKind.INCOME, TxnKind.INTEREST -> com.spendwise.app.data.db.entity.CategoryKind.INCOME
            else -> return null // transfers, refunds, repayments: set by the ledger itself
        }
        return when {
            c.archivedAt != null -> "That category was archived"
            c.kind != wanted -> if (wanted == com.spendwise.app.data.db.entity.CategoryKind.EXPENSE) "Pick a spending category" else "Pick an income category"
            else -> null
        }
    }

    /**
     * Corrections teach every account by default (ADR-001, user decision): the next payment to this merchant gets
     * this category, and SMS rows of the same merchant still waiting for review take it now.
     */
    private suspend fun learn(merchantKey: String?, categoryId: Long?, now: Long, exceptId: Long = 0) {
        if (merchantKey == null || categoryId == null || categoryId < 10) return // never learn system categories
        db.merchantRuleDao().upsert(MerchantRuleEntity(MerchantRuleEntity.GLOBAL_ACCOUNT, merchantKey, categoryId, now))
        val kinds = when (db.categoryDao().getById(categoryId)?.kind) {
            com.spendwise.app.data.db.entity.CategoryKind.EXPENSE -> listOf(TxnKind.EXPENSE, TxnKind.FEE)
            com.spendwise.app.data.db.entity.CategoryKind.INCOME -> listOf(TxnKind.INCOME)
            else -> return
        }
        txnDao.categorisePendingFor(merchantKey, categoryId, kinds, exceptId)
    }

    private fun transferKinds(from: AccountType, to: AccountType): Triple<TxnKind, TxnKind, PaymentMethod> = when {
        to == AccountType.CASH && from != AccountType.CASH -> Triple(TxnKind.ATM_WITHDRAWAL, TxnKind.TRANSFER_IN, PaymentMethod.ATM)
        from == AccountType.CASH && to != AccountType.CASH -> Triple(TxnKind.TRANSFER_OUT, TxnKind.CASH_DEPOSIT, PaymentMethod.CASH)
        else -> Triple(TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN, PaymentMethod.UNKNOWN)
    }

    private companion object {
        const val PAIR_WINDOW_MS = 10 * 60_000L
        const val REFUND_LOOKBACK_MS = 120L * 24 * 3_600_000
        val PAIRABLE = setOf(
            TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.UNKNOWN, TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN,
            TxnKind.ATM_WITHDRAWAL, TxnKind.CASH_DEPOSIT,
        )
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
