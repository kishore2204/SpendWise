package com.spendwise.app.data.db

import androidx.room.AutoMigration
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
    version = 3,
    exportSchema = true,
    // 1 → 2: shared expenses (ADR-011) — new tables and a view only.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
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
        com.spendwise.app.data.db.entity.FriendEntity::class,
        com.spendwise.app.data.db.entity.FriendAliasEntity::class,
        com.spendwise.app.data.db.entity.SharedShareEntity::class,
        com.spendwise.app.data.db.entity.SettlementEntity::class,
    ],
    views = [ConfirmedTxnView::class, com.spendwise.app.data.db.entity.ShareExcludedView::class],
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
    abstract fun friendDao(): com.spendwise.app.data.db.dao.FriendDao
    abstract fun analyticsDao(): com.spendwise.app.data.db.dao.AnalyticsDao
    abstract fun exportDao(): com.spendwise.app.security.ExportDao

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
