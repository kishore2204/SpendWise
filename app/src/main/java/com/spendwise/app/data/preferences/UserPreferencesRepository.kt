package com.spendwise.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { Dark, Light, System }

/** Non-financial UI preferences. Financial data never lives here — it belongs in Room. */
@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val prefs: Flow<Preferences> = dataStore.data.catch { e ->
        // A corrupt or unreadable prefs file must never crash the app; fall back to defaults.
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val themeMode: Flow<ThemeMode> = prefs.map { p ->
        p[KEY_THEME]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } } ?: ThemeMode.Dark
    }.distinctUntilChanged()

    val reduceTransparency: Flow<Boolean> = prefs.map { it[KEY_REDUCE_TRANSPARENCY] ?: false }.distinctUntilChanged()

    // ---- Phase 7: app lock + backup reminder (no financial data — ADR-002)
    val appLockEnabled: Flow<Boolean> = prefs.map { it[KEY_LOCK] ?: false }.distinctUntilChanged()
    /** Seconds away before the app locks again: 0 = immediately. */
    val appLockTimeoutSec: Flow<Int> = prefs.map { it[KEY_LOCK_TIMEOUT] ?: 60 }.distinctUntilChanged()
    val lastBackupAt: Flow<Long?> = prefs.map { it[KEY_LAST_BACKUP] }.distinctUntilChanged()

    suspend fun setAppLock(enabled: Boolean) { dataStore.edit { it[KEY_LOCK] = enabled } }
    suspend fun setAppLockTimeout(seconds: Int) { dataStore.edit { it[KEY_LOCK_TIMEOUT] = seconds } }
    suspend fun setLastBackupAt(at: Long) { dataStore.edit { it[KEY_LAST_BACKUP] = at } }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setReduceTransparency(enabled: Boolean) {
        dataStore.edit { it[KEY_REDUCE_TRANSPARENCY] = enabled }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_REDUCE_TRANSPARENCY = booleanPreferencesKey("reduce_transparency")
        val KEY_LOCK = booleanPreferencesKey("app_lock")
        val KEY_LOCK_TIMEOUT = androidx.datastore.preferences.core.intPreferencesKey("app_lock_timeout_sec")
        val KEY_LAST_BACKUP = androidx.datastore.preferences.core.longPreferencesKey("last_backup_at")
    }
}
