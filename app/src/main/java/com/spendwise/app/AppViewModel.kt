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
) : ViewModel() {
    // Eagerly so the first frame already has the stored theme (default: Dark AMOLED).
    val themeMode: StateFlow<ThemeMode> =
        preferences.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.Dark)

    val reduceTransparency: StateFlow<Boolean> =
        preferences.reduceTransparency.stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
