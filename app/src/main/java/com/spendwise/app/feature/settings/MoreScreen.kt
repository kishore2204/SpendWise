package com.spendwise.app.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BackupTable
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spendwise.app.BuildConfig
import com.spendwise.app.data.preferences.ThemeMode
import com.spendwise.app.data.preferences.UserPreferencesRepository
import com.spendwise.app.ui.components.GroupCard
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.ListRow
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.components.SegmentedChoice
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MoreViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> =
        preferences.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.Dark)
    val reduceTransparency: StateFlow<Boolean> =
        preferences.reduceTransparency.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferences.setThemeMode(mode) }
    fun setReduceTransparency(enabled: Boolean) = viewModelScope.launch { preferences.setReduceTransparency(enabled) }
}

@Composable
fun MoreScreen(viewModel: MoreViewModel = hiltViewModel()) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val reduceTransparency by viewModel.reduceTransparency.collectAsStateWithLifecycle()
    val colors = SpendWise.colors

    LargeTitleScreen(title = "More") {
        item(key = "appearance-label") { SectionLabel("Appearance") }
        item(key = "appearance") {
            GroupCard {
                SegmentedChoice(
                    options = ThemeMode.entries,
                    selected = themeMode,
                    label = {
                        when (it) {
                            ThemeMode.Dark -> "Dark"
                            ThemeMode.Light -> "Light"
                            ThemeMode.System -> "System"
                        }
                    },
                    onSelect = viewModel::setThemeMode,
                    modifier = Modifier.padding(Spacing.sm),
                )
                HorizontalDivider(color = colors.divider)
                ListRow(
                    title = "Reduce transparency",
                    subtitle = "Solid bars instead of blur. Also automatic in battery saver.",
                    leadingIcon = Icons.Rounded.BlurOff,
                    onClick = { viewModel.setReduceTransparency(!reduceTransparency) },
                ) {
                    Switch(
                        checked = reduceTransparency,
                        onCheckedChange = viewModel::setReduceTransparency,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = colors.primary,
                            checkedThumbColor = colors.onPrimary,
                            uncheckedTrackColor = colors.surface3,
                            uncheckedBorderColor = colors.surface3,
                            uncheckedThumbColor = colors.text2,
                        ),
                    )
                }
            }
        }

        item(key = "privacy-label") { SectionLabel("Privacy") }
        item(key = "privacy") {
            GroupCard {
                ListRow(
                    title = "No internet access",
                    subtitle = "SpendWise has no internet permission. Your data cannot leave this phone.",
                    leadingIcon = Icons.Rounded.CloudOff,
                )
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                ListRow(
                    title = "Excluded from Android backup",
                    subtitle = "Not copied by Google backup or Smart Switch. Encrypted backups are coming in a later update.",
                    leadingIcon = Icons.Rounded.BackupTable,
                )
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                ListRow(
                    title = "Hidden from screenshots",
                    subtitle = if (BuildConfig.DEBUG) {
                        "Blocked in the release build. This test build allows screenshots."
                    } else {
                        "Screens are blocked from screenshots and the recent-apps preview."
                    },
                    leadingIcon = Icons.Rounded.ScreenLockPortrait,
                )
            }
        }

        item(key = "about-label") { SectionLabel("About") }
        item(key = "about") {
            GroupCard {
                ListRow(
                    title = "SpendWise ${BuildConfig.VERSION_NAME}",
                    subtitle = "Local-first expense tracker",
                    leadingIcon = Icons.Rounded.Info,
                )
            }
        }
    }
}
