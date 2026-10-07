package com.spendwise.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.dao.AccountCategoryDao
import com.spendwise.app.data.db.dao.AccountDao
import com.spendwise.app.data.db.dao.LedgerDao
import com.spendwise.app.data.db.dao.CategoryDao
import com.spendwise.app.data.db.dao.TxnDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SpendWiseDatabase =
        Room.databaseBuilder(context, SpendWiseDatabase::class.java, SpendWiseDatabase.NAME)
            // ADR-002 seam: the single place SQLCipher's SupportOpenHelperFactory would be plugged in.
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .addCallback(SpendWiseDatabase.SetupCallback())
            // Never fallbackToDestructiveMigration: financial history must survive every upgrade (ADR-004).
            .build()

    @Provides fun accountDao(db: SpendWiseDatabase): AccountDao = db.accountDao()
    @Provides fun categoryDao(db: SpendWiseDatabase): CategoryDao = db.categoryDao()
    @Provides fun txnDao(db: SpendWiseDatabase): TxnDao = db.txnDao()
    @Provides fun ledgerDao(db: SpendWiseDatabase): LedgerDao = db.ledgerDao()
    @Provides fun accountCategoryDao(db: SpendWiseDatabase): AccountCategoryDao = db.accountCategoryDao()
    @Provides fun merchantRuleDao(db: SpendWiseDatabase): com.spendwise.app.data.db.dao.MerchantRuleDao = db.merchantRuleDao()

    @Provides
    @Singleton
    fun provideClock(): com.spendwise.app.core.time.Clock = com.spendwise.app.core.time.Clock { System.currentTimeMillis() }

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("user_prefs") })
}
