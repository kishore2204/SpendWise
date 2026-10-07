package com.spendwise.app.domain

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.spendwise.app.core.time.Clock
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountCategoryEntity
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.AnchorSource
import com.spendwise.app.data.db.entity.BalanceAnchorEntity
import com.spendwise.app.data.db.entity.CategoryKind
import javax.inject.Inject
import javax.inject.Singleton

/** Input for a new account. Banks are data: [bankCode] is the detection key (e.g. INDBNK), [bankName] is display. */
data class NewAccount(
    val bankCode: String,
    val bankName: String,
    val last4: String,
    val nickname: String,
    val type: AccountType,
    /**
     * What the user entered. For a credit card this is the amount OWED (positive), converted by the ledger;
     * for every other type it is the balance itself.
     */
    val enteredBalancePaise: Long,
)

sealed interface CreateAccountResult {
    data class Created(val accountId: Long) : CreateAccountResult
    data object Duplicate : CreateAccountResult
    data class Invalid(val reason: String) : CreateAccountResult
}

/**
 * Ledger commands for accounts and balances (ADR-003/ADR-004). These are the only writers of `account` and
 * `balance_anchor`; each runs in a single transaction. Balance edits APPEND anchors — transactions are never
 * modified or deleted.
 */
@Singleton
class AccountLedger @Inject constructor(
    private val db: SpendWiseDatabase,
    private val clock: Clock,
) {
    suspend fun createAccount(input: NewAccount): CreateAccountResult {
        val bankCode = input.bankCode.trim().uppercase()
        val last4 = input.last4.filter(Char::isDigit)
        val isCash = input.type == AccountType.CASH
        when {
            bankCode.isEmpty() -> return CreateAccountResult.Invalid("Choose a bank")
            input.bankName.isBlank() -> return CreateAccountResult.Invalid("Enter the bank name")
            !isCash && last4.length != 4 -> return CreateAccountResult.Invalid("Enter the last 4 digits of the account")
        }
        val now = clock.now()
        return try {
            db.withTransaction {
                val id = db.accountDao().insert(
                    AccountEntity(
                        bankCode = bankCode,
                        bankName = input.bankName.trim(),
                        last4 = if (isCash) "" else last4,
                        nickname = input.nickname.trim().ifEmpty { input.bankName.trim() },
                        type = input.type,
                        createdAt = now,
                    ),
                )
                db.ledgerDao().insertAnchor(
                    BalanceAnchorEntity(
                        accountId = id,
                        effectiveAt = now,
                        balancePaise = storedBalance(input.type, input.enteredBalancePaise),
                        source = AnchorSource.OPENING,
                        createdAt = now,
                    ),
                )
                // Every account starts with every non-system category visible (ADR-001 overlay).
                val categories = db.categoryDao().getAll().filter { it.kind != CategoryKind.SYSTEM && it.archivedAt == null }
                db.accountCategoryDao().insertAll(
                    categories.map { AccountCategoryEntity(accountId = id, categoryId = it.id, hidden = false, sortOrder = it.sortOrder) },
                )
                CreateAccountResult.Created(id)
            }
        } catch (e: SQLiteConstraintException) {
            // Only UNIQUE(bankCode, last4) means "already exists"; anything else is a real failure.
            if (e.message?.contains("UNIQUE", ignoreCase = true) == true) CreateAccountResult.Duplicate else throw e
        }
    }

    /**
     * Sets what the balance is *now* by appending an anchor ([AnchorSource.MANUAL] for "Edit balance",
     * [AnchorSource.RECONCILE] for "Bank says"). History is untouched.
     */
    suspend fun setBalance(accountId: Long, enteredPaise: Long, source: AnchorSource, note: String? = null) {
        require(source != AnchorSource.OPENING) { "Opening anchors are created with the account" }
        val now = clock.now()
        db.withTransaction {
            val account = db.accountDao().getById(accountId) ?: return@withTransaction
            // Anchors are ordered by effectiveAt: if the device clock went backwards, a new anchor must still be
            // the latest, or the edit would be silently ignored.
            val latest = db.ledgerDao().latestAnchor(accountId)
            val effectiveAt = maxOf(now, (latest?.effectiveAt ?: Long.MIN_VALUE) + 1)
            db.ledgerDao().insertAnchor(
                BalanceAnchorEntity(
                    accountId = accountId,
                    effectiveAt = effectiveAt,
                    balancePaise = storedBalance(account.type, enteredPaise),
                    source = source,
                    note = note?.trim()?.ifEmpty { null },
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun updateDetails(accountId: Long, nickname: String, type: AccountType) {
        db.accountDao().updateDetails(accountId, nickname.trim(), type)
    }

    /** Accounts are archived, never deleted: their history stays intact. */
    suspend fun archive(accountId: Long) {
        db.accountDao().archive(accountId, clock.now())
    }

    /**
     * Adjustment history for ONE account: each anchor with the amount it moved the balance by
     * (anchor − what the balance would have been just before it).
     */
    suspend fun adjustmentHistory(accountId: Long): List<Adjustment> {
        val anchors = db.ledgerDao().getAnchors(accountId)
        var previous: BalanceAnchorEntity? = null
        return anchors.map { anchor ->
            // Opening: what the anchor adds on top of any history imported from before it, so the list adds up to
            // the hero's "Adjustments" (null when there is no earlier history).
            val before = previous?.let { prev ->
                prev.balancePaise + db.ledgerDao().confirmedBetween(accountId, prev.effectiveAt, anchor.effectiveAt)
            } ?: db.ledgerDao().confirmedUpTo(accountId, anchor.effectiveAt).takeIf { it != 0L }
            previous = anchor
            Adjustment(
                anchorId = anchor.id,
                source = anchor.source,
                at = anchor.effectiveAt,
                balancePaise = anchor.balancePaise,
                deltaPaise = before?.let { anchor.balancePaise - it },
                note = anchor.note,
            )
        }
    }
}

/** One balance anchor as shown in the adjustment history. [deltaPaise] is null for an opening with no prior history. */
data class Adjustment(
    val anchorId: Long,
    val source: AnchorSource,
    val at: Long,
    val balancePaise: Long,
    val deltaPaise: Long?,
    val note: String?,
)

/**
 * The ONE place the credit-card sign rule lives: for a card the user enters what they owe, stored as a negative
 * balance (typing a negative amount records a card in credit). Every other type stores what was entered.
 */
internal fun storedBalance(type: AccountType, enteredPaise: Long): Long =
    if (type == AccountType.CREDIT_CARD) -enteredPaise else enteredPaise

/** Inverse of [storedBalance], for showing a card's figure the way the user enters it. */
internal fun enteredBalance(type: AccountType, storedPaise: Long): Long =
    if (type == AccountType.CREDIT_CARD) -storedPaise else storedPaise
