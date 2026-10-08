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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.SmsFailed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.spendwise.app.data.db.dao.RawMessageDao
import com.spendwise.app.transaction.sms.SmsSync

@HiltViewModel
class MoreViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val rawDao: RawMessageDao,
    private val sync: SmsSync,
    private val wiper: com.spendwise.app.data.DataWiper,
) : ViewModel() {
    val wiped = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Irreversible: every account, transaction, SMS record, friend, rule and custom category on this phone. */
    fun wipeEverything() = viewModelScope.launch {
        // The wipe itself is non-cancellable; a failure is reported, never a crash.
        wiped.value = runCatching { wiper.wipeEverything() }.isSuccess
    }

    val unreadableCount: StateFlow<Int> = rawDao.observeUnreadableCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    fun smsOn() = sync.canReceiveSms()

    /** Settings → "Purge raw SMS text" (ADR-005 §10): parsed transactions stay, the message text goes. */
    fun purgeSmsText() = viewModelScope.launch { rawDao.purgeBodies() }

    val themeMode: StateFlow<ThemeMode> =
        preferences.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.Dark)
    val reduceTransparency: StateFlow<Boolean> =
        preferences.reduceTransparency.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferences.setThemeMode(mode) }
    fun setReduceTransparency(enabled: Boolean) = viewModelScope.launch { preferences.setReduceTransparency(enabled) }
}

@Composable
fun MoreScreen(
    onSmsSetup: () -> Unit,
    onUnreadable: () -> Unit,
    onFriends: () -> Unit,
    viewModel: MoreViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val unreadable by viewModel.unreadableCount.collectAsStateWithLifecycle()
    var smsOn by remember { mutableStateOf(viewModel.smsOn()) }
    var confirmPurge by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    val wiped by viewModel.wiped.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        smsOn = viewModel.smsOn()
        onPauseOrDispose { }
    }
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

        item(key = "friends-label") { SectionLabel("Shared bills") }
        item(key = "friends") {
            GroupCard {
                ListRow(
                    title = "Friends",
                    subtitle = "Who owes you for bills you paid",
                    leadingIcon = Icons.Rounded.Group,
                    onClick = onFriends,
                )
            }
        }

        item(key = "sms-label") { SectionLabel("Bank SMS") }
        item(key = "sms") {
            GroupCard {
                ListRow(
                    title = "SMS detection",
                    subtitle = if (smsOn) "On · import past SMS, check status" else "Off · Set up",
                    leadingIcon = Icons.Rounded.Sms,
                    onClick = onSmsSetup,
                )
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                ListRow(
                    title = "Couldn't read",
                    subtitle = if (unreadable == 0) "Every bank SMS so far was understood" else "$unreadable bank SMS need a look",
                    leadingIcon = Icons.Rounded.SmsFailed,
                    leadingTint = if (unreadable > 0) colors.pending else colors.text2,
                    onClick = onUnreadable,
                )
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                ListRow(
                    title = "Delete stored SMS text",
                    subtitle = "Keeps your transactions; removes the message text SpendWise kept",
                    leadingIcon = Icons.Rounded.DeleteSweep,
                    onClick = { confirmPurge = true },
                )
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

        item(key = "danger-label") { SectionLabel("Danger zone") }
        item(key = "danger") {
            GroupCard {
                ListRow(
                    title = "Delete all data",
                    subtitle = if (wiped) "Done — SpendWise is empty" else "Every account, transaction and setting about your money. Can't be undone.",
                    leadingIcon = Icons.Rounded.DeleteForever,
                    leadingTint = colors.danger,
                    onClick = { confirmWipe = true },
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

    if (confirmWipe) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all data?") },
            text = {
                Column {
                    Text(
                        "This deletes every account, transaction, balance history, SMS record, friend and custom category on this phone. " +
                            "There is no backup — it can't be undone. Your SMS in the Messages app are not touched.",
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    com.spendwise.app.ui.components.TextInput(typed, { typed = it }, "Type DELETE to confirm")
                }
            },
            confirmButton = {
                TextButton(enabled = typed.trim() == "DELETE", onClick = { confirmWipe = false; viewModel.wipeEverything() }) {
                    Text("Delete everything", color = if (typed.trim() == "DELETE") colors.danger else colors.text3)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
            containerColor = colors.surface1,
        )
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("Delete stored SMS text?") },
            text = { Text("Your transactions stay. SpendWise forgets the text of the bank SMS they came from. SMS still in your Messages app are not touched.") },
            confirmButton = { TextButton(onClick = { confirmPurge = false; viewModel.purgeSmsText() }) { Text("Delete", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Cancel") } },
            containerColor = colors.surface1,
        )
    }
}
