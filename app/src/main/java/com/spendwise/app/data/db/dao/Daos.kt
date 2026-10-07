package com.spendwise.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.db.entity.CategoryEntity
import com.spendwise.app.data.db.entity.TransferEntity
import com.spendwise.app.data.db.entity.TxnEntity
import kotlinx.coroutines.flow.Flow

/*
 * Phase 2 DAOs: the minimum needed to wire Room and prove the schema invariants.
 * Rule for every financial read added later: it takes an `accountId` and reads `v_confirmed_txn` (CLAUDE.md).
 */

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity): Long

    @Query("SELECT * FROM account WHERE archivedAt IS NULL ORDER BY createdAt")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT COUNT(*) FROM account WHERE archivedAt IS NULL")
    fun observeActiveCount(): Flow<Int>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE archivedAt IS NULL ORDER BY sortOrder")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY sortOrder")
    suspend fun getAll(): List<CategoryEntity>
}

@Dao
interface TxnDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(txn: TxnEntity): Long

    /** Dedup-friendly insert: returns -1 when the (accountId, bankRef, isCredit) unique index rejects it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(txn: TxnEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransfer(transfer: TransferEntity): Long

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun getById(id: Long): TxnEntity?

    /** Σ of confirmed legs for one account — reads the view, never `txn`. */
    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM v_confirmed_txn WHERE accountId = :accountId")
    suspend fun confirmedSum(accountId: Long): Long
}
