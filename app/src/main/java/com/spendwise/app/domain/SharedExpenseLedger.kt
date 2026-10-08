package com.spendwise.app.domain

import androidx.room.withTransaction
import com.spendwise.app.core.merchant.Merchants
import com.spendwise.app.core.money.Money
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.db.DatabaseSetup
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.dao.ShareRow
import com.spendwise.app.data.db.entity.Confidence
import com.spendwise.app.data.db.entity.CounterpartyKind
import com.spendwise.app.data.db.entity.FriendAliasEntity
import com.spendwise.app.data.db.entity.FriendEntity
import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.SettlementEntity
import com.spendwise.app.data.db.entity.SharedShareEntity
import com.spendwise.app.data.db.entity.TimeSource
import com.spendwise.app.data.db.entity.TxnEntity
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.data.db.entity.TxnStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** "Paid back by Ravi?" — a suggestion only; the user decides (ADR-011). */
data class RepaymentSuggestion(val friendId: Long, val friendName: String, val reason: String, val strong: Boolean)

/**
 * Shared-expense ledger commands (ADR-011) — the only writer of `friend`, `shared_share`, `settlement`, and of a
 * transaction's REPAYMENT kind. Each command is one database transaction; SQL triggers back up every cap.
 */
@Singleton
class SharedExpenseLedger @Inject constructor(
    private val db: SpendWiseDatabase,
    private val clock: Clock,
) {
    private val friends get() = db.friendDao()
    private val txns get() = db.txnDao()

    /** Finds or creates a friend by name (case-insensitive). */
    suspend fun friendNamed(name: String): LedgerResult {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return LedgerResult.Rejected("Enter your friend's name")
        return db.withTransaction {
            friends.friendByName(clean)?.let { return@withTransaction LedgerResult.Ok(it.id) }
            LedgerResult.Ok(friends.insertFriend(FriendEntity(name = clean.take(40), createdAt = clock.now())))
        }
    }

    /** Part of a confirmed expense is a friend's. From now on only the user's own part counts as their spending. */
    suspend fun share(txnId: Long, friendId: Long, amountPaise: Long): LedgerResult {
        if (amountPaise <= 0) return LedgerResult.Rejected("Enter an amount above zero")
        return db.withTransaction {
            val t = txns.getById(txnId) ?: return@withTransaction LedgerResult.Rejected("Transaction not found")
            if (t.kind != TxnKind.EXPENSE) return@withTransaction LedgerResult.Rejected("Only spending can be shared with a friend")
            if (t.status != TxnStatus.CONFIRMED) return@withTransaction LedgerResult.Rejected("Confirm it first, then share it")
            friends.friend(friendId) ?: return@withTransaction LedgerResult.Rejected("Friend not found")
            val room = abs(t.amountPaise) - friends.sharedOn(txnId) - txns.refundedSoFar(txnId)
            if (amountPaise > room) {
                return@withTransaction LedgerResult.Rejected(
                    if (room <= 0) "Nothing left to share on this bill" else "At most ${Money.format(room)} can be shared",
                )
            }
            LedgerResult.Ok(friends.insertShare(SharedShareEntity(txnId = txnId, friendId = friendId, amountPaise = amountPaise, createdAt = clock.now())))
        }
    }

    /** Takes a share back (a mistake). Not once anything was paid against it — undo that repayment first. */
    suspend fun removeShare(shareId: Long): LedgerResult = db.withTransaction {
        friends.share(shareId) ?: return@withTransaction LedgerResult.Rejected("Not found")
        if (friends.settledOn(shareId) > 0) return@withTransaction LedgerResult.Rejected("Part of it was paid back — undo that repayment first")
        friends.deleteShare(shareId)
        LedgerResult.Ok(shareId)
    }

    /** "They won't pay": what's still unpaid becomes the user's own spending again (on the bill's date). */
    suspend fun forgive(shareId: Long): LedgerResult = db.withTransaction {
        val s = friends.share(shareId) ?: return@withTransaction LedgerResult.Rejected("Not found")
        if (s.forgivenAt != null) return@withTransaction LedgerResult.Rejected("Already forgiven")
        friends.forgive(shareId, clock.now())
        LedgerResult.Ok(shareId)
    }

    /**
     * An incoming credit is [friendId] paying back. It becomes a confirmed REPAYMENT (never income) applied to their
     * open shares, oldest bill first, in any of the user's accounts. It must be fully covered by what they owe —
     * an overpayment stays income. Teaches the payer's name/VPA as this friend's alias (suggestions only).
     */
    suspend fun markAsRepayment(creditTxnId: Long, friendId: Long): LedgerResult = db.withTransaction {
        val c = txns.getById(creditTxnId) ?: return@withTransaction LedgerResult.Rejected("Transaction not found")
        val problem = when {
            c.amountPaise <= 0 -> "Only money coming in can be a repayment"
            c.kind !in REPAYABLE -> "This can't be a repayment"
            c.status == TxnStatus.IGNORED -> "This was ignored"
            c.refundOfId != null -> "This is a refund"
            c.possibleDupOfId != null -> "This may be a duplicate — check that first"
            txns.transferFor(c.id) != null -> "This is a transfer between your accounts"
            txns.getSplits(c.id).isNotEmpty() -> "Remove its split first"
            else -> null
        }
        if (problem != null) return@withTransaction LedgerResult.Rejected(problem)
        val friend = friends.friend(friendId) ?: return@withTransaction LedgerResult.Rejected("Friend not found")
        val open = friends.openSharesOf(friendId)
        // Internal check only — never shown: a figure across accounts would break rule #1 (ADR-011).
        val owed = open.sumOf { it.openPaise }
        if (owed < c.amountPaise) {
            return@withTransaction LedgerResult.Rejected(
                if (owed == 0L) "${friend.name} doesn't owe you anything" else "This is more than ${friend.name} owes — keep it as income",
            )
        }
        val now = clock.now()
        txns.update(c.copy(kind = TxnKind.REPAYMENT, status = TxnStatus.CONFIRMED, categoryId = DatabaseSetup.CATEGORY_REPAYMENT, updatedAt = now))
        allocate(c.id, c.amountPaise, open, now)
        // Learn who this payer is, so the next credit from them is suggested first. Never applied on its own.
        (c.merchantRaw ?: c.merchantKey)?.let(::aliasKey)?.let { friends.upsertAlias(FriendAliasEntity(it, friendId)) }
        LedgerResult.Ok(c.id)
    }

    /** "Not a repayment after all": the credit goes back to plain income, and what it paid is owed again. */
    suspend fun undoRepayment(creditTxnId: Long): LedgerResult = db.withTransaction {
        val c = txns.getById(creditTxnId) ?: return@withTransaction LedgerResult.Rejected("Transaction not found")
        if (c.kind != TxnKind.REPAYMENT) return@withTransaction LedgerResult.Rejected("Not a repayment")
        if (friends.forgivenSharesPaidBy(c.id) > 0) {
            return@withTransaction LedgerResult.Rejected("A bill it paid was forgiven later — it can't be undone now")
        }
        if (db.reviewDao().sourceCount(c.id) == 0) {
            // Recorded by hand (e.g. cash): there's no income to go back to — delete it instead.
            return@withTransaction LedgerResult.Rejected("Recorded by hand — delete it instead")
        }
        friends.deleteSettlementsOf(c.id)
        // Back to review, never straight to income: the user decides again what this money is.
        txns.update(
            c.copy(
                kind = TxnKind.INCOME,
                status = TxnStatus.PENDING,
                confidence = Confidence.NEEDS_REVIEW,
                categoryId = null,
                updatedAt = clock.now(),
            ),
        )
        LedgerResult.Ok(c.id)
    }

    /** A repayment with no SMS (cash, or paid into an account the app doesn't read): a confirmed REPAYMENT row. */
    suspend fun recordRepayment(accountId: Long, friendId: Long, amountPaise: Long, occurredAt: Long): LedgerResult {
        if (amountPaise <= 0) return LedgerResult.Rejected("Enter an amount above zero")
        return db.withTransaction {
            val account = db.accountDao().getById(accountId) ?: return@withTransaction LedgerResult.Rejected("Account not found")
            if (account.archivedAt != null) return@withTransaction LedgerResult.Rejected("That account is archived")
            val friend = friends.friend(friendId) ?: return@withTransaction LedgerResult.Rejected("Friend not found")
            val open = friends.openSharesOf(friendId)
            // Internal check only — never shown (rule #1).
            if (amountPaise > open.sumOf { it.openPaise }) return@withTransaction LedgerResult.Rejected("That's more than ${friend.name} owes")
            val now = clock.now()
            val id = txns.insert(
                TxnEntity(
                    accountId = accountId,
                    amountPaise = amountPaise,
                    isCredit = true,
                    kind = TxnKind.REPAYMENT,
                    status = TxnStatus.CONFIRMED,
                    confidence = Confidence.HIGH,
                    occurredAt = occurredAt,
                    localEpochDay = IndiaTime.localEpochDay(occurredAt),
                    timeSource = TimeSource.MANUAL,
                    categoryId = DatabaseSetup.CATEGORY_REPAYMENT,
                    merchantRaw = friend.name,
                    counterpartyKind = CounterpartyKind.PERSON,
                    paymentMethod = PaymentMethod.CASH,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            allocate(id, amountPaise, open, now)
            LedgerResult.Ok(id)
        }
    }

    /**
     * Who might this credit be from? A learned payer name ranks first; otherwise a friend whose oldest open shares
     * add up to exactly this amount. Suggestions only — and none for transfers, refunds or anything not money in.
     */
    suspend fun suggestionsFor(txnId: Long): List<RepaymentSuggestion> {
        val c = txns.getById(txnId) ?: return emptyList()
        if (c.amountPaise <= 0 || c.kind !in REPAYABLE || c.status == TxnStatus.IGNORED || c.refundOfId != null) return emptyList()
        if (txns.transferFor(c.id) != null) return emptyList()
        val open = friends.allOpenShares().groupBy { it.friendId }
        if (open.isEmpty()) return emptyList()
        val aliasFriend = (c.merchantRaw ?: c.merchantKey)?.let(::aliasKey)?.let { friends.friendForAlias(it) }
        val out = mutableListOf<RepaymentSuggestion>()
        for ((friendId, shares) in open) {
            val name = shares.first().friendName
            if (shares.sumOf { it.openPaise } < c.amountPaise) continue // internal check: can't be fully their repayment
            // Amounts are matched within ONE paying account's bills — never a sum across accounts (rule #1).
            val exact = shares.groupBy { it.accountId }.values.maxOf { exactPrefix(it, c.amountPaise) }
            when {
                friendId == aliasFriend -> out += RepaymentSuggestion(friendId, name, "Paid you back before from this name", strong = true)
                exact > 0 -> out += RepaymentSuggestion(
                    friendId, name,
                    if (exact == 1) "Matches what they owe for one bill" else "Matches what they owe for $exact bills",
                    strong = false,
                )
            }
        }
        return out.sortedByDescending { it.strong }
    }

    private suspend fun allocate(creditTxnId: Long, amountPaise: Long, open: List<ShareRow>, now: Long) {
        var left = amountPaise
        for (s in open) {
            if (left == 0L) break
            val part = minOf(left, s.openPaise)
            if (part <= 0) continue
            friends.insertSettlement(SettlementEntity(creditTxnId = creditTxnId, shareId = s.shareId, amountPaise = part, createdAt = now))
            left -= part
        }
        check(left == 0L) { "repayment not fully allocated" } // guarded by the owed check above
    }


    private companion object {
        val REPAYABLE = setOf(TxnKind.INCOME, TxnKind.UNKNOWN)
    }
}

/** How many oldest-first open shares add up to exactly [amount] (0 = none). */
internal fun exactPrefix(shares: List<ShareRow>, amount: Long): Int {
    var sum = 0L
    shares.forEachIndexed { i, s ->
        sum += s.openPaise
        if (sum == amount) return i + 1
        if (sum > amount) return 0
    }
    return 0
}

internal fun aliasKey(raw: String): String? = Merchants.key(raw)

/**
 * True when an incoming amount looks like a friend paying back — a learned payer name, or exactly what one friend
 * owes for their oldest bills. Such credits are never HIGH confidence, so one-tap confirm can't book them as income.
 */
suspend fun SpendWiseDatabase.looksLikeRepayment(amountPaise: Long, payer: String?): Boolean {
    if (amountPaise <= 0) return false
    val open = friendDao().allOpenShares()
    if (open.isEmpty()) return false
    if (payer?.let(::aliasKey)?.let { friendDao().friendForAlias(it) } != null) return true
    return open.groupBy { it.friendId to it.accountId }.values.any { exactPrefix(it, amountPaise) > 0 }
}
