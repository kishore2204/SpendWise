package com.spendwise.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendwise.app.data.db.entity.FriendAliasEntity
import com.spendwise.app.data.db.entity.FriendEntity
import com.spendwise.app.data.db.entity.SettlementEntity
import com.spendwise.app.data.db.entity.SharedShareEntity
import kotlinx.coroutines.flow.Flow

/**
 * Shared expenses (ADR-011). Amounts owed are reported per friend AND per paying account — never one total across
 * accounts (CLAUDE.md rule #1).
 */
@Dao
interface FriendDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFriend(friend: FriendEntity): Long

    @Query("SELECT * FROM friend WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun friendByName(name: String): FriendEntity?

    @Query("SELECT * FROM friend WHERE id = :id")
    suspend fun friend(id: Long): FriendEntity?

    @Query("SELECT * FROM friend WHERE id = :id")
    fun observeFriend(id: Long): Flow<FriendEntity?>

    /** Most recently used first, so the friend you split with yesterday is the first chip. */
    @Query(
        """
        SELECT f.* FROM friend f
        ORDER BY COALESCE((SELECT MAX(s.createdAt) FROM shared_share s WHERE s.friendId = f.id), f.createdAt) DESC, f.name
        """,
    )
    fun observeFriends(): Flow<List<FriendEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlias(alias: FriendAliasEntity)

    @Query("SELECT friendId FROM friend_alias WHERE normalized = :normalized")
    suspend fun friendForAlias(normalized: String): Long?

    // ---- shares

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertShare(share: SharedShareEntity): Long

    @Query("SELECT * FROM shared_share WHERE id = :id")
    suspend fun share(id: Long): SharedShareEntity?

    @Query("DELETE FROM shared_share WHERE id = :id")
    suspend fun deleteShare(id: Long)

    @Query("UPDATE shared_share SET forgivenAt = :at WHERE id = :id")
    suspend fun forgive(id: Long, at: Long)

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM shared_share WHERE txnId = :txnId")
    suspend fun sharedOn(txnId: Long): Long

    @Query("SELECT COUNT(*) FROM shared_share WHERE txnId = :txnId")
    suspend fun shareCount(txnId: Long): Int

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM settlement WHERE shareId = :shareId")
    suspend fun settledOn(shareId: Long): Long

    @Query(SHARE_SELECT + " WHERE s.txnId = :txnId ORDER BY s.id")
    fun observeSharesOfBill(txnId: Long): Flow<List<ShareRow>>

    @Query(SHARE_SELECT + " WHERE s.friendId = :friendId ORDER BY t.occurredAt DESC, s.id DESC")
    fun observeSharesOfFriend(friendId: Long): Flow<List<ShareRow>>

    /** A friend's shares that still have something unpaid, oldest bill first — the order repayments are applied in. */
    @Query(
        SHARE_SELECT + """
        WHERE s.friendId = :friendId AND s.forgivenAt IS NULL AND t.status = 'CONFIRMED'
          AND s.amountPaise > COALESCE((SELECT SUM(x.amountPaise) FROM settlement x WHERE x.shareId = s.id), 0)
        ORDER BY t.occurredAt, s.id
        """,
    )
    suspend fun openSharesOf(friendId: Long): List<ShareRow>

    /** Every open share (any friend), for repayment suggestions. Rows only — never summed across accounts here. */
    @Query(
        SHARE_SELECT + """
        WHERE s.forgivenAt IS NULL AND t.status = 'CONFIRMED'
          AND s.amountPaise > COALESCE((SELECT SUM(x.amountPaise) FROM settlement x WHERE x.shareId = s.id), 0)
        ORDER BY t.occurredAt, s.id
        """,
    )
    suspend fun allOpenShares(): List<ShareRow>

    /** What each friend still owes, PER PAYING ACCOUNT (rule #1: no cross-account total). */
    @Query(
        """
        SELECT s.friendId AS friendId, f.name AS friendName, t.accountId AS accountId, a.nickname AS accountName,
               SUM(s.amountPaise - COALESCE((SELECT SUM(x.amountPaise) FROM settlement x WHERE x.shareId = s.id), 0)) AS outstandingPaise,
               COUNT(*) AS bills, MIN(t.occurredAt) AS oldestAt
        FROM shared_share s
        JOIN txn t ON t.id = s.txnId
        JOIN friend f ON f.id = s.friendId
        JOIN account a ON a.id = t.accountId
        WHERE s.forgivenAt IS NULL AND t.status = 'CONFIRMED'
          AND s.amountPaise > COALESCE((SELECT SUM(x.amountPaise) FROM settlement x WHERE x.shareId = s.id), 0)
        GROUP BY s.friendId, t.accountId
        ORDER BY f.name, a.createdAt
        """,
    )
    fun observeOutstanding(): Flow<List<OutstandingRow>>

    // ---- settlements

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSettlement(settlement: SettlementEntity): Long

    @Query("SELECT COUNT(*) FROM settlement x JOIN shared_share s ON s.id = x.shareId WHERE x.creditTxnId = :creditTxnId AND s.forgivenAt IS NOT NULL")
    suspend fun forgivenSharesPaidBy(creditTxnId: Long): Int

    @Query("DELETE FROM settlement WHERE creditTxnId = :creditTxnId")
    suspend fun deleteSettlementsOf(creditTxnId: Long)

    @Query(
        """
        SELECT x.id AS settlementId, x.amountPaise AS amountPaise, s.id AS shareId, f.name AS friendName,
               t.id AS billTxnId, t.merchantRaw AS billMerchant, t.occurredAt AS billAt, a.nickname AS billAccount
        FROM settlement x
        JOIN shared_share s ON s.id = x.shareId
        JOIN friend f ON f.id = s.friendId
        JOIN txn t ON t.id = s.txnId
        JOIN account a ON a.id = t.accountId
        WHERE x.creditTxnId = :creditTxnId
        ORDER BY t.occurredAt
        """,
    )
    fun observeSettlementsOfCredit(creditTxnId: Long): Flow<List<SettlementRow>>

    companion object {
        const val SHARE_SELECT = """
            SELECT s.id AS shareId, s.txnId AS txnId, s.friendId AS friendId, f.name AS friendName, s.amountPaise AS amountPaise,
                   COALESCE((SELECT SUM(x.amountPaise) FROM settlement x WHERE x.shareId = s.id), 0) AS settledPaise,
                   s.forgivenAt AS forgivenAt, t.accountId AS accountId, a.nickname AS accountName,
                   t.merchantRaw AS billMerchant, t.amountPaise AS billPaise, t.occurredAt AS billAt
            FROM shared_share s
            JOIN friend f ON f.id = s.friendId
            JOIN txn t ON t.id = s.txnId
            JOIN account a ON a.id = t.accountId"""
    }
}

/** One friend's part of one bill. [billPaise] is the bill's own signed amount (its account only). */
data class ShareRow(
    val shareId: Long,
    val txnId: Long,
    val friendId: Long,
    val friendName: String,
    val amountPaise: Long,
    val settledPaise: Long,
    val forgivenAt: Long?,
    val accountId: Long,
    val accountName: String,
    val billMerchant: String?,
    val billPaise: Long,
    val billAt: Long,
) {
    val openPaise: Long get() = if (forgivenAt != null) 0 else (amountPaise - settledPaise).coerceAtLeast(0)
}

data class OutstandingRow(
    val friendId: Long,
    val friendName: String,
    val accountId: Long,
    val accountName: String,
    val outstandingPaise: Long,
    val bills: Int,
    val oldestAt: Long,
)

data class SettlementRow(
    val settlementId: Long,
    val amountPaise: Long,
    val shareId: Long,
    val friendName: String,
    val billTxnId: Long,
    val billMerchant: String?,
    val billAt: Long,
    val billAccount: String,
)
