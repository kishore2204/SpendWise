package com.spendwise.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.spendwise.app.data.db.dao.AccountCategoryDao
import com.spendwise.app.data.db.dao.AccountDao
import com.spendwise.app.data.db.dao.LedgerDao
import com.spendwise.app.data.db.dao.CategoryDao
import com.spendwise.app.data.db.dao.TxnDao
import com.spendwise.app.data.db.entity.AccountCategoryEntity
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.db.entity.BalanceAnchorEntity
import com.spendwise.app.data.db.entity.BudgetEntity
import com.spendwise.app.data.db.entity.CategoryEntity
import com.spendwise.app.data.db.entity.ConfirmedTxnView
import com.spendwise.app.data.db.entity.MerchantRuleEntity
import com.spendwise.app.data.db.entity.NotMineEntity
import com.spendwise.app.data.db.entity.RawMessageEntity
import com.spendwise.app.data.db.entity.RecurringRuleEntity
import com.spendwise.app.data.db.entity.TransferEntity
import com.spendwise.app.data.db.entity.TxnEntity
import com.spendwise.app.data.db.entity.TxnSourceEntity
import com.spendwise.app.data.db.entity.TxnSplitEntity

@Database(
    version = 1,
    exportSchema = true,
    entities = [
        AccountEntity::class,
        NotMineEntity::class,
        RawMessageEntity::class,
        CategoryEntity::class,
        TxnEntity::class,
        TxnSourceEntity::class,
        TransferEntity::class,
        TxnSplitEntity::class,
        BalanceAnchorEntity::class,
        AccountCategoryEntity::class,
        MerchantRuleEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
    ],
    views = [ConfirmedTxnView::class],
)
abstract class SpendWiseDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun txnDao(): TxnDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun accountCategoryDao(): AccountCategoryDao
    abstract fun merchantRuleDao(): com.spendwise.app.data.db.dao.MerchantRuleDao
    abstract fun rawMessageDao(): com.spendwise.app.data.db.dao.RawMessageDao
    abstract fun notMineDao(): com.spendwise.app.data.db.dao.NotMineDao
    abstract fun reviewDao(): com.spendwise.app.data.db.dao.ReviewDao

    companion object {
        const val NAME = "spendwise.db"
    }

    /** Seeds on create; re-asserts triggers on every open so no migration can drop them. */
    class SetupCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            DatabaseSetup.createTriggers(db)
            DatabaseSetup.seed(db)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            DatabaseSetup.createTriggers(db)
            DatabaseSetup.seedOnOpen(db)
        }
    }
}
