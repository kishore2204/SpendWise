package com.spendwise.app.data.db.entity

import androidx.room.DatabaseView
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Shared expenses (ADR-011): "I paid the whole bill; a friend owes me part of it". A share is a part of a confirmed
 * EXPENSE owed by a friend; settlements apply repayment credits (kind REPAYMENT, never income) to shares.
 * Caps (Σ shares + refunds ≤ bill, Σ settlements ≤ share and ≤ credit) are SQL triggers in DatabaseSetup.
 */

@Entity(tableName = "friend", indices = [Index(value = ["name"], unique = true)])
data class FriendEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/** A payer name / VPA learned when the user marked a credit as this friend's repayment. Only ranks suggestions. */
@Entity(
    tableName = "friend_alias",
    foreignKeys = [ForeignKey(entity = FriendEntity::class, parentColumns = ["id"], childColumns = ["friendId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["friendId"])],
)
data class FriendAliasEntity(
    @PrimaryKey val normalized: String,
    val friendId: Long,
)

@Entity(
    tableName = "shared_share",
    foreignKeys = [
        // The bill can't be deleted while friends owe part of it.
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["txnId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FriendEntity::class, parentColumns = ["id"], childColumns = ["friendId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["txnId"]), Index(value = ["friendId"])],
)
data class SharedShareEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txnId: Long,
    val friendId: Long,
    /** Positive: the friend's part of the bill. */
    val amountPaise: Long,
    /** Set when the user gives up on the unpaid rest; that rest becomes their own spending again. */
    val forgivenAt: Long? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "settlement",
    foreignKeys = [
        // Deleting the repayment credit simply re-opens what it paid.
        ForeignKey(entity = TxnEntity::class, parentColumns = ["id"], childColumns = ["creditTxnId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SharedShareEntity::class, parentColumns = ["id"], childColumns = ["shareId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["creditTxnId"]), Index(value = ["shareId"])],
)
data class SettlementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val creditTxnId: Long,
    val shareId: Long,
    /** Positive. */
    val amountPaise: Long,
    val createdAt: Long,
)

/**
 * How much of each share is NOT the user's own spending, on the bill's day and in the bill's account (ADR-011):
 * the whole share while it's open, only what was actually paid back once it's forgiven. Confirmed bills only.
 */
@DatabaseView(
    viewName = "v_share_excluded",
    value = """
        SELECT s.id AS shareId, s.friendId AS friendId, t.id AS txnId, t.accountId AS accountId, t.localEpochDay AS localEpochDay,
               CASE WHEN s.forgivenAt IS NULL THEN s.amountPaise
                    ELSE COALESCE((SELECT SUM(x.amountPaise) FROM settlement x WHERE x.shareId = s.id), 0) END AS excludedPaise
        FROM shared_share s JOIN v_confirmed_txn t ON t.id = s.txnId
    """,
)
data class ShareExcludedView(
    val shareId: Long,
    val friendId: Long,
    val txnId: Long,
    val accountId: Long,
    val localEpochDay: Long,
    val excludedPaise: Long,
)
