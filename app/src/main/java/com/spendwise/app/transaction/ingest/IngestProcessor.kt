package com.spendwise.app.transaction.ingest

import androidx.room.withTransaction
import com.spendwise.app.core.merchant.Merchants
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.db.DatabaseSetup
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.CategoryKind
import com.spendwise.app.data.db.entity.Confidence
import com.spendwise.app.data.db.entity.CounterpartyKind
import com.spendwise.app.data.db.entity.ParseStatus
import com.spendwise.app.data.db.entity.RawMessageEntity
import com.spendwise.app.data.db.entity.TimeSource
import com.spendwise.app.data.db.entity.TxnEntity
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.data.db.entity.TxnSourceEntity
import com.spendwise.app.data.db.entity.TxnStatus
import com.spendwise.app.domain.TransactionLedger
import com.spendwise.app.domain.signedFor
import com.spendwise.app.transaction.parsing.Direction
import com.spendwise.app.transaction.parsing.ParseResult
import com.spendwise.app.transaction.parsing.RawSms
import com.spendwise.app.transaction.parsing.TransactionParser
import com.spendwise.app.transaction.parsing.UnrecognizedCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** What one drain produced. Only IDs — never amounts or text (they may be logged or put in a notification extra). */
data class IngestSummary(
    val created: List<Long> = emptyList(),
    val matchedManual: List<Long> = emptyList(),
    val duplicates: Int = 0,
    val unknownAccount: Int = 0,
    val unreadable: Int = 0,
) {
    operator fun plus(o: IngestSummary) = IngestSummary(
        created + o.created, matchedManual + o.matchedManual, duplicates + o.duplicates,
        unknownAccount + o.unknownAccount, unreadable + o.unreadable,
    )
}

/**
 * New rows grouped by their own account — so messages say "2 new in Indian · 1 in HDFC", never one mixed count.
 */
suspend fun SpendWiseDatabase.createdPerAccount(txnIds: List<Long>): List<Pair<AccountEntity, List<Long>>> =
    txnIds.mapNotNull { id -> txnDao().getById(id)?.let { it.accountId to id } }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (accountId, ids) -> accountDao().getById(accountId)?.let { it to ids } }

/**
 * ProcessIncoming (ADR-003/005): the ONE path from a staged message to a PENDING transaction. Serialized by a
 * mutex; each message is handled in its own database transaction, so a failure marks that message ERROR and the
 * rest continue. Nothing here ever confirms a transaction — the user does (CLAUDE.md "never auto-confirmed").
 */
@Singleton
class IngestProcessor @Inject constructor(
    private val db: SpendWiseDatabase,
    private val clock: Clock,
    private val ledger: TransactionLedger,
) {
    private val mutex = Mutex()
    private val raw get() = db.rawMessageDao()
    private val review get() = db.reviewDao()

    suspend fun drain(): IngestSummary = mutex.withLock {
        var total = IngestSummary()
        while (true) {
            val batch = raw.nextReceived(BATCH)
            if (batch.isEmpty()) break
            for (row in batch) {
                total += try {
                    db.withTransaction { process(row) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Parsing never crashes the app (CLAUDE.md): the message is kept for "Couldn't read".
                    raw.setStatus(row.id, ParseStatus.ERROR, TransactionParser.VERSION, row.bankHint)
                    IngestSummary(unreadable = 1)
                }
            }
        }
        total
    }

    private suspend fun process(row: RawMessageEntity): IngestSummary {
        val body = row.body ?: run {
            raw.setStatusDroppingBody(row.id, ParseStatus.NOT_FINANCIAL, TransactionParser.VERSION, null)
            return IngestSummary()
        }
        return when (val result = TransactionParser.parse(RawSms(row.sender, body, row.sentAt))) {
            is ParseResult.NotTransaction -> {
                raw.setStatusDroppingBody(row.id, ParseStatus.NOT_FINANCIAL, TransactionParser.VERSION, null)
                IngestSummary()
            }
            is ParseResult.Unrecognized -> {
                val status = if (result.cause == UnrecognizedCause.PARSER_ERROR) ParseStatus.ERROR else ParseStatus.FINANCIAL_UNPARSED
                raw.setStatus(row.id, status, TransactionParser.VERSION, result.bankCode)
                IngestSummary(unreadable = 1)
            }
            is ParseResult.Parsed -> processParsed(row, result)
        }
    }

    private suspend fun processParsed(row: RawMessageEntity, result: ParseResult.Parsed): IngestSummary {
        val p = result.txn
        val bank = p.bankCode
        val magnitude = p.amountPaise
        val direction = p.direction
        if (magnitude == null || magnitude <= 0 || direction == null) return unreadable(row, bank)

        // ---- Which account? Never auto-created, never guessed into the wrong one (CLAUDE.md rule #1).
        val account: AccountEntity = if (p.last4 != null) {
            val found = db.accountDao().findByIdentifier(bank, p.last4) ?: run {
                if (db.notMineDao().contains(bank, p.last4)) {
                    raw.setStatusDroppingBody(row.id, ParseStatus.NOT_MINE, TransactionParser.VERSION, bank)
                    return IngestSummary()
                }
                raw.setStatus(row.id, ParseStatus.UNKNOWN_ACCOUNT, TransactionParser.VERSION, bank)
                return IngestSummary(unknownAccount = 1)
            }
            // An archived account is hidden everywhere; a row there could be confirmed unseen. Show it as unreadable.
            if (found.archivedAt != null) return unreadable(row, bank)
            found
        } else {
            // No account digits in the text: only usable when the user has exactly one account at that bank AND no
            // other account of that bank is known to exist (marked Not mine, or waiting as a detected account).
            val ambiguous = db.notMineDao().anyForBank(bank) || raw.unknownAccountCount(bank) > 0
            db.accountDao().activeForBank(bank).singleOrNull()?.takeUnless { ambiguous } ?: return unreadable(row, bank)
        }

        // ---- Kind and sign. A kind that contradicts the text's direction is not trusted.
        val directionOk = when (p.kind.direction) {
            TxnKind.Direction.EITHER -> true
            TxnKind.Direction.OUT -> direction == Direction.OUT
            TxnKind.Direction.IN -> direction == Direction.IN
        }
        val kind = if (directionOk) p.kind else if (direction == Direction.OUT) TxnKind.EXPENSE else TxnKind.INCOME
        val signed = signedFor(kind, magnitude, fallbackNegative = direction == Direction.OUT)
        val isCredit = signed > 0

        // ---- When. A delayed SMS keeps the bank's (earlier) day, placed at the END of that day but never after the
        // send time — so a late-evening debit can't be dated before a balance the user set earlier that day.
        val sentDay = IndiaTime.localDate(row.sentAt)
        val bodyDate = p.date
        val (occurredAt, timeSource) = when {
            bodyDate == null || bodyDate == sentDay -> row.sentAt to TimeSource.RECEIVED
            bodyDate.isBefore(sentDay) && !bodyDate.isBefore(sentDay.minusDays(3)) ->
                minOf(row.sentAt, IndiaTime.endOfDay(bodyDate)) to TimeSource.BODY
            else -> row.sentAt to TimeSource.RECEIVED
        }

        // ---- Dedup 2: the bank reference (also enforced by the unique index) — but only for the SAME amount.
        var refForInsert = p.bankRef
        var refClash: Long? = null
        if (p.bankRef != null) {
            review.byBankRef(account.id, p.bankRef, isCredit)?.let { existing ->
                if (existing.amountPaise == signed) return attach(row, existing, p.availableBalancePaise, bank, duplicate = true)
                // Same reference, different amount (e.g. a fee on the same RRN): never swallowed — review it.
                refForInsert = null
                refClash = existing.id
            }
        }

        // ---- Dedup 3: fuzzy — same account, same signed amount, within 15 minutes, one side without a reference.
        val candidates = review.nearbySameAmount(account.id, signed, occurredAt, occurredAt - FUZZY_WINDOW_MS, occurredAt + FUZZY_WINDOW_MS)
            .filter { it.bankRef == null || refForInsert == null }
        val only = candidates.singleOrNull()
        if (refClash == null && only != null && only.timeSource == TimeSource.MANUAL && review.sourceCount(only.id) == 0) {
            // The user typed it in before the SMS arrived: the SMS becomes evidence, not a second transaction.
            return attach(row, only, p.availableBalancePaise, bank, duplicate = false, fillRef = refForInsert)
        }
        // Never auto-merge two detected transactions (two real ₹20 teas are two teas) — flag and let the user decide.
        val possibleDup = refClash ?: candidates.firstOrNull()?.id

        // ---- Possibly one side of a transfer between the user's own accounts: never confirmed in one tap as
        // spending/income. The other side, if still pending, loses its "high confidence" too.
        val transferLike = !kind.isTransferFamily &&
            review.transferCandidates(account.id, signed, refForInsert, occurredAt, TRANSFER_WINDOW_MS).isNotEmpty()
        if (transferLike) {
            for (o in review.transferCandidates(account.id, signed, refForInsert, occurredAt, TRANSFER_WINDOW_MS)) {
                if (o.status == TxnStatus.PENDING && o.confidence == Confidence.HIGH) {
                    db.txnDao().update(o.copy(confidence = Confidence.NEEDS_REVIEW, updatedAt = clock.now()))
                }
            }
        }

        // ---- Confidence: the parser's verdict, demoted for anything the user must look at (ADR-004 latency risk:
        // "Already included in the balance you entered?").
        var confidence = Confidence.valueOf(result.confidence.name)
        val demote = !directionOk || possibleDup != null || transferLike ||
            kind == TxnKind.REVERSAL || kind == TxnKind.REFUND ||
            (account.type == AccountType.CREDIT_CARD && isCredit) || // card payment ≠ income: pair it as a transfer
            anchorMayCoverIn(account.id, occurredAt, timeSource, row.sentAt)
        if (demote && confidence == Confidence.HIGH) confidence = Confidence.NEEDS_REVIEW

        val merchant = if (kind.isTransferFamily) null else (p.merchant ?: p.vpa)?.trim()?.ifEmpty { null }
        val now = clock.now()
        val txn = TxnEntity(
            accountId = account.id,
            amountPaise = signed,
            isCredit = isCredit,
            kind = kind,
            status = TxnStatus.PENDING,
            confidence = confidence,
            occurredAt = occurredAt,
            localEpochDay = IndiaTime.localEpochDay(occurredAt),
            timeSource = timeSource,
            categoryId = categoryFor(kind, account.id, merchant),
            merchantRaw = merchant,
            merchantKey = Merchants.key(merchant),
            counterpartyKind = if (Merchants.looksLikePerson(merchant)) CounterpartyKind.PERSON else CounterpartyKind.UNKNOWN,
            paymentMethod = p.paymentMethod,
            bankRef = refForInsert,
            reportedBalancePaise = p.availableBalancePaise,
            possibleDupOfId = possibleDup,
            createdAt = now,
            updatedAt = now,
        )
        val id = db.txnDao().insertOrIgnore(txn)
        if (id <= 0) {
            // Lost a race with another path on the bank reference: attach to the winner.
            val existing = refForInsert?.let { review.byBankRef(account.id, it, isCredit) } ?: return unreadable(row, bank)
            return attach(row, existing, p.availableBalancePaise, bank, duplicate = true)
        }
        review.insertSource(TxnSourceEntity(txnId = id, rawMessageId = row.id))
        raw.setStatus(row.id, ParseStatus.PARSED, TransactionParser.VERSION, bank)
        return IngestSummary(created = listOf(id))
    }

    /** Links a message to an existing transaction, filling in a missing reference / reported balance. */
    private suspend fun attach(
        row: RawMessageEntity,
        existing: TxnEntity,
        reportedBalance: Long?,
        bank: String,
        duplicate: Boolean,
        fillRef: String? = null,
    ): IngestSummary {
        val ref = existing.bankRef ?: fillRef
        if (ref != existing.bankRef || (existing.reportedBalancePaise == null && reportedBalance != null)) {
            db.txnDao().update(
                existing.copy(
                    bankRef = ref,
                    reportedBalancePaise = existing.reportedBalancePaise ?: reportedBalance,
                    updatedAt = clock.now(),
                ),
            )
        }
        review.insertSource(TxnSourceEntity(txnId = existing.id, rawMessageId = row.id))
        if (existing.status == TxnStatus.IGNORED) {
            // Ignored transactions keep no SMS text — including repeats that arrive later (ADR-005 §10).
            raw.setStatusDroppingBody(row.id, ParseStatus.PARSED, TransactionParser.VERSION, bank)
        } else {
            raw.setStatus(row.id, ParseStatus.PARSED, TransactionParser.VERSION, bank)
        }
        return if (duplicate) IngestSummary(duplicates = 1) else IngestSummary(matchedManual = listOf(existing.id))
    }

    private suspend fun unreadable(row: RawMessageEntity, bank: String?): IngestSummary {
        raw.setStatus(row.id, ParseStatus.FINANCIAL_UNPARSED, TransactionParser.VERSION, bank)
        return IngestSummary(unreadable = 1)
    }

    private suspend fun anchorMayCoverIn(accountId: Long, occurredAt: Long, timeSource: TimeSource, seenAt: Long): Boolean {
        val anchor = db.ledgerDao().latestAnchor(accountId) ?: return false
        return anchorMayCover(anchor.effectiveAt, occurredAt, timeSource, seenAt)
    }

    /** Suggested category; only one of the right kind (an expense rule never lands on income). */
    private suspend fun categoryFor(kind: TxnKind, accountId: Long, merchant: String?): Long? = when {
        kind.isTransferFamily -> DatabaseSetup.CATEGORY_TRANSFER
        kind == TxnKind.REFUND -> DatabaseSetup.CATEGORY_REFUND
        kind == TxnKind.INTEREST -> INTEREST_CATEGORY
        kind == TxnKind.EXPENSE || kind == TxnKind.FEE || kind == TxnKind.INCOME -> {
            val wanted = if (kind == TxnKind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
            ledger.suggestCategory(accountId, merchant)?.takeIf { db.categoryDao().getById(it)?.kind == wanted }
        }
        else -> null
    }

    companion object {
        private const val BATCH = 50
        private const val TRANSFER_WINDOW_MS = 10 * 60_000L

        /**
         * True when a balance the user entered at [anchorAt] may already include this transaction, so confirming it
         * could count it twice (ADR-004 SMS latency): the anchor sits between the earliest moment the money could
         * have moved (30 min before, or the start of the bank's stated day) and when the SMS was seen.
         */
        fun anchorMayCover(anchorAt: Long, occurredAt: Long, timeSource: TimeSource, seenAt: Long): Boolean {
            val earliest = if (timeSource == TimeSource.BODY) IndiaTime.startOfDay(IndiaTime.localDate(occurredAt)) else occurredAt - ANCHOR_LATENCY_MS
            return anchorAt >= earliest && anchorAt <= maxOf(seenAt, occurredAt) && anchorAt < occurredAt
        }
        const val FUZZY_WINDOW_MS = 15 * 60_000L
        const val ANCHOR_LATENCY_MS = 30 * 60_000L
        private const val INTEREST_CATEGORY = 51L
    }
}
