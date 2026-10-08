package com.spendwise.app.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import com.spendwise.app.data.db.dao.AccountDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of the selected account (ADR-003). Persisted, survives process death, and always resolves
 * to an ACTIVE account: a stored id that was archived or deleted falls back to the first active account.
 * Emits null only when there are no accounts.
 */
@Singleton
class SelectedAccountStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    accountDao: AccountDao,
) {
    private val stored: Flow<Long?> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY] }

    val selectedAccountId: Flow<Long?> = combine(stored, accountDao.observeActive()) { id, accounts ->
        resolveSelected(id, accounts.map { it.id })
    }.distinctUntilChanged()

    suspend fun select(accountId: Long) {
        dataStore.edit { it[KEY] = accountId }
    }

    /** After "Delete all data". */
    suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = longPreferencesKey("selected_account_id")
    }
}

/** Pure resolution rule, unit-tested: stored id if still active, else the first active account, else none. */
internal fun resolveSelected(storedId: Long?, activeIds: List<Long>): Long? =
    if (storedId != null && storedId in activeIds) storedId else activeIds.firstOrNull()
