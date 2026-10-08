package com.spendwise.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.app.data.preferences.UserPreferencesRepository
import com.spendwise.app.data.preferences.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    preferences: UserPreferencesRepository,
    private val selected: com.spendwise.app.data.account.SelectedAccountStore,
) : ViewModel() {
    /** A notification about account X opens X — never X's rows under another account's header. */
    suspend fun select(accountId: Long) = selected.select(accountId)

    // Eagerly so the first frame already has the stored theme (default: Dark AMOLED).
    val themeMode: StateFlow<ThemeMode> =
        preferences.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.Dark)

    val reduceTransparency: StateFlow<Boolean> =
        preferences.reduceTransparency.stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
