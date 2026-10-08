package com.spendwise.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema per ADR-004. Invariants Room cannot express (sign ⇔ kind, transfer legs in different accounts,
 * refund same account / opposite sign) are SQL triggers in DatabaseSetup.
 */

@Entity(
    tableName = "account",
    indices = [Index(value = ["bankCode", "last4"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Detection key, e.g. INDBNK, HDFCBK, CASH. Banks are data — never hardcoded in logic. */
    val bankCode: String,
    /** Display name, e.g. "Indian Bank". */
    val bankName: String,
    /** Digits only; "" for the Cash wallet. */
    val last4: String,
    val nickname: String,
    val type: AccountType,
    val createdAt: Long,
    val archivedAt: Long? = null,
)

/** Account identifiers the user marked "Not mine" — never asked about again (ADR-005 §7). */
@Entity(tableName = "not_mine", primaryKeys = ["bankCode", "last4"])
data class NotMineEntity(
    val bankCode: String,
    val last4: String,
    val createdAt: Long,
)

@Entity(
    tableName = "raw_message",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["parseStatus"]),
        Index(value = ["sentAt"]),
    ],
)
data class RawMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: MessageSource,
    val sender: String,
    /** Redacted body; null once the message is non-financial, ignored, not-mine or purged (ADR-005 §10). */
    val body: String?,
    val sentAt: Long,
    val receivedAt: Long,
    val fingerprint: String,
    val parseStatus: ParseStatus,
    val parserVersion: Int,
    val bankHint: String? = null,
)

@Entity(
    tableName = "category",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    /** Material Symbols name, resolved in the UI layer. */
    val iconKey: String,
    /** Index into the per-theme category palette. */
    val colorIndex: Int,
    val isSystem: Boolean,
    val sortOrder: Int,
    /** Categories are archived, never deleted (ADR-001). */
    val archivedAt: Long? = null,
)

@Entity(
    tableName = "txn",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["refundOfId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["possibleDupOfId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        // Covering index for per-account totals, trends, category breakdown and top merchants (ADR-004).
        Index(value = ["accountId", "status", "localEpochDay", "kind", "amountPaise", "categoryId", "merchantKey"], name = "idx_txn_analytics"),
        // Paged list, newest first.
        Index(value = ["accountId", "occurredAt", "id"], name = "idx_txn_list"),
        // Exactly-once per bank reference. NULL bankRef never conflicts (SQLite treats NULLs as distinct),
        // and the same RRN is allowed on both legs of a self-transfer (different accounts) or a reversal (other direction).
        Index(value = ["accountId", "bankRef", "isCredit"], unique = true, name = "uq_txn_bank_ref"),
        Index(value = ["categoryId"]),
        Index(value = ["refundOfId"]),
        Index(value = ["possibleDupOfId"]),
    ],
)
data class TxnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    /** Signed: negative = money out of this account. Never 0. */
    val amountPaise: Long,
    /** Redundant with the sign, enforced equal by trigger; exists so the dedup index needs no expression. */
    val isCredit: Boolean,
    val kind: TxnKind,
    val status: TxnStatus,
    val confidence: Confidence,
    val occurredAt: Long,
    /** IST day number, so day/week grouping is integer math (ADR-004). */
    val localEpochDay: Long,
    val timeSource: TimeSource,
    /** Null when the transaction is split (see txn_split) or not yet categorised. */
    val categoryId: Long? = null,
    val merchantRaw: String? = null,
    val merchantKey: String? = null,
    val counterpartyKind: CounterpartyKind = CounterpartyKind.UNKNOWN,
    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    /** UPI RRN / UTR / bank reference. */
    val bankRef: String? = null,
    /** "Avl Bal" reported by the bank after this transaction, if any. Never applied automatically. */
    val reportedBalancePaise: Long? = null,
    /** SMS timestamp that reported the balance; required to compare a snapshot with the ledger safely. */
    val reportedBalanceAt: Long? = null,
    val refundOfId: Long? = null,
    val possibleDupOfId: Long? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Every message that contributed to a transaction. A second message about the same transaction attaches here. */
@Entity(
    tableName = "txn_source",
    primaryKeys = ["txnId", "rawMessageId"],
    foreignKeys = [
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["txnId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RawMessageEntity::class, parentColumns = ["id"], childColumns = ["rawMessageId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["rawMessageId"], unique = true)],
)
data class TxnSourceEntity(
    val txnId: Long,
    val rawMessageId: Long,
)

/**
 * Links the two legs of a transfer once both are known (ADR-004). Each leg is an independent txn
 * row in its own account; this row is the only link between them.
 */
@Entity(
    tableName = "transfer",
    foreignKeys = [
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["fromTxnId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["toTxnId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["fromTxnId"], unique = true),
        Index(value = ["toTxnId"], unique = true),
    ],
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromTxnId: Long,
    val toTxnId: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "txn_split",
    foreignKeys = [
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["txnId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["txnId"]), Index(value = ["categoryId"])],
)
data class TxnSplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txnId: Long,
    val categoryId: Long,
    /** Same sign as the parent. Σ splits == parent, enforced in the SetSplits ledger command. */
    val amountPaise: Long,
)

/**
 * Append-only balance truth points (ADR-004). Displayed balance = latest anchor + Σ confirmed legs after it.
 * Editing or reconciling a balance appends an anchor; transactions are never modified.
 */
@Entity(
    tableName = "balance_anchor",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["accountId", "effectiveAt"])],
)
data class BalanceAnchorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val effectiveAt: Long,
    val balancePaise: Long,
    val source: AnchorSource,
    val note: String? = null,
    val createdAt: Long,
)

/** Per-account visibility/ordering over the global category catalogue (ADR-001). */
@Entity(
    tableName = "account_category",
    primaryKeys = ["accountId", "categoryId"],
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["categoryId"])],
)
data class AccountCategoryEntity(
    val accountId: Long,
    val categoryId: Long,
    val hidden: Boolean,
    val sortOrder: Int,
)

/**
 * Learned merchant → category rules (ADR-001). accountId = [GLOBAL_ACCOUNT] (0) is the global rule;
 * no FK on accountId because 0 is a sentinel, not an account.
 */
@Entity(
    tableName = "merchant_rule",
    primaryKeys = ["accountId", "merchantKey"],
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["categoryId"])],
)
data class MerchantRuleEntity(
    val accountId: Long,
    val merchantKey: String,
    val categoryId: Long,
    val updatedAt: Long,
) {
    companion object {
        const val GLOBAL_ACCOUNT = 0L
    }
}

@Entity(
    tableName = "budget",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["accountId", "categoryId", "periodStartEpochDay"], unique = true),
        Index(value = ["categoryId"]),
    ],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val categoryId: Long,
    /** First day (IST epoch day) of the budget month. */
    val periodStartEpochDay: Long,
    val limitPaise: Long,
)

@Entity(
    tableName = "recurring_rule",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["accountId", "merchantKey"])],
)
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val merchantKey: String,
    val displayName: String,
    /** Expected amount (signed, like txn). */
    val amountPaise: Long,
    val cadence: Cadence,
    val kind: RecurringKind,
    val nextDueAt: Long,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true,
    val createdAt: Long,
)
