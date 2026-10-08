package com.spendwise.app.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.TableView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.preferences.UserPreferencesRepository
import com.spendwise.app.security.AppLock
import com.spendwise.app.security.BackupException
import com.spendwise.app.security.BackupService
import com.spendwise.app.security.CsvExporter
import com.spendwise.app.security.Passphrase
import com.spendwise.app.security.RestoreCandidate
import com.spendwise.app.ui.components.ChipRow
import com.spendwise.app.ui.components.GroupCard
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.ListRow
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.components.SegmentedChoice
import com.spendwise.app.ui.components.TextInput
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
    private val backups: BackupService,
    private val csv: CsvExporter,
    val appLock: AppLock,
    accounts: AccountRepository,
    private val clock: Clock,
) : ViewModel() {
    val lockEnabled = prefs.appLockEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val lockTimeout = prefs.appLockTimeoutSec.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 60)
    val lastBackupAt = prefs.lastBackupAt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val accounts: StateFlow<List<AccountEntity>> = accounts.observeAccountsWithBalances().map { l -> l.map { it.account } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val busy = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    val candidate = MutableStateFlow<RestoreCandidate?>(null)

    /** Held only between "choose passphrase" and "choose file", then wiped. Never stored. */
    private var pendingPassphrase: CharArray? = null

    fun setLock(enabled: Boolean) {
        appLock.setLockEnabled(enabled)
        viewModelScope.launch { prefs.setAppLock(enabled) }
    }
    fun setTimeout(seconds: Int) = viewModelScope.launch { prefs.setAppLockTimeout(seconds) }

    fun holdPassphrase(p: CharArray) { pendingPassphrase?.fill('\u0000'); pendingPassphrase = p }
    fun dropPassphrase() { pendingPassphrase?.fill('\u0000'); pendingPassphrase = null }

    fun backupTo(uri: Uri) = viewModelScope.launch {
        val pass = pendingPassphrase ?: return@launch
        pendingPassphrase = null
        busy.value = "Encrypting your backup…"
        message.value = runCatching {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't write there")
            val s = backups.backup(out, pass)
            prefs.setLastBackupAt(clock.now())
            "Backed up ${s.accounts} account${if (s.accounts == 1) "" else "s"} and ${s.transactions} transactions. Keep the passphrase safe — without it the file can't be opened."
        }.getOrElse { "Backup failed: ${it.message ?: "unknown error"}" }
        busy.value = null
    }

    fun openBackup(uri: Uri, pass: CharArray) = viewModelScope.launch {
        busy.value = "Checking the backup…"
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't read that file")
            backups.open(input, pass)
        }.onSuccess { candidate.value = it }.onFailure { e ->
            message.value = when ((e as? BackupException)?.reason) {
                BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED -> "Wrong passphrase, or the file is damaged."
                BackupException.Reason.NOT_A_BACKUP -> "That isn't a SpendWise backup file."
                BackupException.Reason.NEWER_FORMAT -> "This backup was made by a newer SpendWise. Update the app first."
                null -> e.message ?: "The backup couldn't be checked."
            }
        }
        busy.value = null
    }

    fun cancelRestore() { candidate.value?.let(backups::discard); candidate.value = null }

    /** Replaces everything and restarts the app. */
    fun restore() = viewModelScope.launch {
        val c = candidate.value ?: return@launch
        busy.value = "Restoring…"
        backups.restore(c)
    }

    fun exportCsv(accountId: Long, uri: Uri) = viewModelScope.launch {
        busy.value = "Exporting…"
        message.value = runCatching {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't write there")
            "Exported ${csv.export(accountId, out)} transactions. This file is NOT encrypted — keep it private."
        }.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
        busy.value = null
    }

    fun today(): String = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(IndiaTime.localDate(clock.now()))
}

private enum class LockAfter(val seconds: Int, val label: String) { Now(0, "Immediately"), OneMin(60, "1 min"), FiveMin(300, "5 min") }

@Composable
fun SecurityScreen(onClose: () -> Unit, viewModel: SecurityViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val colors = SpendWise.colors
    val lockEnabled by viewModel.lockEnabled.collectAsStateWithLifecycle()
    val lockTimeout by viewModel.lockTimeout.collectAsStateWithLifecycle()
    val lastBackup by viewModel.lastBackupAt.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val candidate by viewModel.candidate.collectAsStateWithLifecycle()
    var askBackupPass by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var csvAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var csvWarn by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.backupTo(uri) else viewModel.dropPassphrase()
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val a = csvAccount
        if (uri != null && a != null) viewModel.exportCsv(a.id, uri)
    }

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = "Security & backup", reserveBottomBar = false) {
            item(key = "lock-label") { SectionLabel("App lock") }
            item(key = "lock") {
                GroupCard {
                    ListRow(
                        title = "Lock SpendWise",
                        subtitle = "Fingerprint, face or your phone's PIN to open the app",
                        leadingIcon = Icons.Rounded.Fingerprint,
                        onClick = null,
                    ) {
                        Switch(
                            checked = lockEnabled,
                            onCheckedChange = { want ->
                                if (!want) {
                                    viewModel.setLock(false)
                                } else if (activity == null || !AppLock.available(activity)) {
                                    viewModel.message.value = "Set up a screen lock (PIN, fingerprint or face) in Android Settings first."
                                } else {
                                    // Prove it works before turning it on, so nobody locks themselves out.
                                    viewModel.appLock.prompt(activity, "Turn on app lock") { ok -> if (ok) viewModel.setLock(true) }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary, uncheckedTrackColor = colors.surface3, uncheckedBorderColor = colors.surface3, uncheckedThumbColor = colors.text2),
                        )
                    }
                    if (lockEnabled) {
                        HorizontalDivider(color = colors.divider)
                        Column(Modifier.padding(Spacing.sm)) {
                            Text("Lock again after leaving for", style = MaterialTheme.typography.labelMedium, color = colors.text2, modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs))
                            SegmentedChoice(LockAfter.entries, LockAfter.entries.firstOrNull { it.seconds == lockTimeout } ?: LockAfter.OneMin, { it.label }, { viewModel.setTimeout(it.seconds) })
                        }
                    }
                }
            }
            item(key = "lock-note") {
                Text(
                    "The lock only guards the screen — it never locks your data itself, so a new fingerprint can't lock you out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text2,
                    modifier = Modifier.padding(horizontal = Spacing.screen + Spacing.xxs, vertical = Spacing.xs),
                )
            }

            item(key = "backup-label") { SectionLabel("Encrypted backup") }
            item(key = "backup") {
                GroupCard {
                    ListRow(
                        title = "Back up now",
                        subtitle = lastBackup?.let { "Last backup: ${DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").format(Instant.ofEpochMilli(it).atZone(IndiaTime.ZONE))}" }
                            ?: "Never backed up. Save a file to Drive, a USB stick or your Downloads",
                        leadingIcon = Icons.Rounded.Backup,
                        onClick = { askBackupPass = true },
                    )
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    ListRow(
                        title = "Restore from a backup",
                        subtitle = "Replaces everything on this phone with the backup",
                        leadingIcon = Icons.Rounded.Restore,
                        onClick = {
                            openBackup.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
            item(key = "backup-note") {
                Text(
                    "Backups are encrypted with a passphrase only you know (AES-256). They open on any phone with that passphrase — and never without it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text2,
                    modifier = Modifier.padding(horizontal = Spacing.screen + Spacing.xxs, vertical = Spacing.xs),
                )
            }

            if (accounts.isNotEmpty()) {
                item(key = "csv-label") { SectionLabel("Export to a spreadsheet") }
                item(key = "csv") {
                    GroupCard {
                        Column(Modifier.padding(vertical = Spacing.sm)) {
                            Text("One account per file", style = MaterialTheme.typography.labelMedium, color = colors.text2, modifier = Modifier.padding(horizontal = Spacing.md))
                            Spacer(Modifier.height(Spacing.xs))
                            ChipRow(options = accounts, selected = csvAccount, label = { "${it.nickname} ${it.last4}".trim() }, onSelect = { csvAccount = it })
                        }
                        ListRow(
                            title = "Export CSV",
                            subtitle = csvAccount?.let { "All of ${it.nickname}'s transactions" } ?: "Pick an account above",
                            leadingIcon = Icons.Rounded.TableView,
                            onClick = { if (csvAccount != null) csvWarn = true },
                        )
                    }
                }
            }

            busy?.let { b -> item(key = "busy") { Text(b, color = colors.text2, modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm)) } }
            message?.let { m -> item(key = "msg") { Text(m, color = colors.text1, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm)) } }
            item(key = "end") { Spacer(Modifier.height(Spacing.xl)) }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = Spacing.xs)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.text1)
        }
    }

    if (askBackupPass) {
        PassphraseDialog(
            title = "Choose a backup passphrase",
            confirm = true,
            onCancel = { askBackupPass = false },
            onOk = { pass ->
                askBackupPass = false
                viewModel.holdPassphrase(pass)
                createBackup.launch("SpendWise-${viewModel.today()}.swbackup")
            },
        )
    }
    restoreUri?.let { uri ->
        PassphraseDialog(
            title = "Backup passphrase",
            confirm = false,
            onCancel = { restoreUri = null },
            onOk = { pass -> restoreUri = null; viewModel.openBackup(uri, pass) },
        )
    }
    candidate?.let { c -> RestoreConfirmDialog(c, onCancel = viewModel::cancelRestore, onRestore = viewModel::restore) }
    if (csvWarn) {
        AlertDialog(
            onDismissRequest = { csvWarn = false },
            title = { Text("Export without encryption?") },
            text = { Text("A CSV file is plain text. Anyone who gets the file can read every transaction in it, with amounts and names. Use the encrypted backup to keep a safe copy.") },
            confirmButton = {
                TextButton(onClick = {
                    csvWarn = false
                    val a = csvAccount ?: return@TextButton
                    createCsv.launch("SpendWise-${a.nickname.filter { it.isLetterOrDigit() }}-${viewModel.today()}.csv")
                }) { Text("Export", color = colors.primary) }
            },
            dismissButton = { TextButton(onClick = { csvWarn = false }) { Text("Cancel") } },
            containerColor = colors.surface1,
        )
    }
}

@Composable
private fun PassphraseDialog(title: String, confirm: Boolean, onCancel: () -> Unit, onOk: (CharArray) -> Unit) {
    val colors = SpendWise.colors
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                if (confirm) {
                    Text("You'll need it to restore. SpendWise can't recover it for you.", style = MaterialTheme.typography.bodySmall, color = colors.text2)
                    Spacer(Modifier.height(Spacing.sm))
                }
                PasswordField(pass, { pass = it; error = null }, "Passphrase")
                if (confirm) {
                    Spacer(Modifier.height(Spacing.xs))
                    PasswordField(again, { again = it; error = null }, "Type it again")
                }
                error?.let { Text(it, color = colors.danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = pass.toCharArray()
                val problem = if (confirm) Passphrase.problem(p, again.toCharArray()) else if (p.isEmpty()) "Enter the passphrase" else null
                if (problem != null) error = problem else onOk(p)
            }) { Text("Continue", color = colors.primary) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = colors.surface1,
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RestoreConfirmDialog(c: RestoreCandidate, onCancel: () -> Unit, onRestore: () -> Unit) {
    val colors = SpendWise.colors
    var typed by remember { mutableStateOf("") }
    val last = c.summary.lastTransactionAt?.let { DateTimeFormatter.ofPattern("d MMM yyyy").format(Instant.ofEpochMilli(it).atZone(IndiaTime.ZONE)) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Restore this backup?") },
        text = {
            Column {
                Text(
                    "It has ${c.summary.accounts} account${if (c.summary.accounts == 1) "" else "s"} and ${c.summary.transactions} transactions" +
                        (last?.let { ", the latest on $it" } ?: "") + ". Everything now on this phone will be replaced, and SpendWise will restart.",
                )
                Spacer(Modifier.height(Spacing.sm))
                TextInput(typed, { typed = it }, "Type RESTORE to confirm")
            }
        },
        confirmButton = {
            TextButton(enabled = typed.trim() == "RESTORE", onClick = onRestore) {
                Text("Restore", color = if (typed.trim() == "RESTORE") colors.danger else colors.text3)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = colors.surface1,
    )
}

/** Covers the app while the lock decision is pending or locked; hidden screens have no accessibility actions. */
@Composable
fun LockGate(locked: Boolean?, onUnlock: () -> Unit, content: @Composable () -> Unit) {
    val covered = locked != false
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (covered) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            content()
        }
        if (covered) LockOverlay(locked, onUnlock)
    }
}

/** Covers the whole app while locked (and before the lock state is known). Solid — nothing shows through. */
@Composable
fun LockOverlay(locked: Boolean?, onUnlock: () -> Unit) {
    val colors = SpendWise.colors
    val activity = LocalContext.current as? android.app.Activity
    // While locked, Back leaves the app instead of navigating the hidden screens behind the cover.
    BackHandler { activity?.moveTaskToBack(true) }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // Swallow every touch: nothing behind the cover can be tapped.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center,
    ) {
        if (locked == null) {
            Text("Checking app lock…", style = MaterialTheme.typography.bodyMedium, color = colors.text2, textAlign = TextAlign.Center)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.padding(Spacing.xl)) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.primary, modifier = Modifier.size(48.dp))
                Text("SpendWise is locked", style = MaterialTheme.typography.titleLarge, color = colors.text1, textAlign = TextAlign.Center)
                TextButton(onClick = onUnlock) { Text("Unlock", color = colors.primary, style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}
