package com.spendwise.app.feature.sms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.SmsFailed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.account.SelectedAccountStore
import com.spendwise.app.data.db.dao.RawMessageDao
import com.spendwise.app.data.db.dao.ReviewDao
import com.spendwise.app.data.db.dao.TxnRow
import com.spendwise.app.data.db.entity.Confidence
import com.spendwise.app.data.db.entity.RawMessageEntity
import com.spendwise.app.domain.TransactionLedger
import com.spendwise.app.transaction.parsing.BankRegistry
import com.spendwise.app.transaction.parsing.SenderIds
import com.spendwise.app.transaction.sms.ImportState
import com.spendwise.app.transaction.sms.SmsImportWorker
import com.spendwise.app.transaction.sms.SmsSync
import com.spendwise.app.ui.components.EmptyState
import com.spendwise.app.ui.components.GroupCard
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.LiquidPrimaryButton
import com.spendwise.app.ui.components.ListRow
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.components.SegmentedChoice
import com.spendwise.app.ui.components.TransactionRowView
import com.spendwise.app.ui.glass.GlassKind
import com.spendwise.app.ui.glass.liquidGlass
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import com.spendwise.app.transaction.ingest.createdPerAccount
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** "2 h ago" etc. — for the receiver health line. */
internal fun ago(then: Long, now: Long): String {
    val minutes = (now - then) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

internal fun Context.openAppInfo() = startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

internal fun Context.hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

// ------------------------------------------------------------------------------------------------ setup

@HiltViewModel
class SmsSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: SmsSync,
    rawDao: RawMessageDao,
    private val clock: Clock,
    private val db: com.spendwise.app.data.db.SpendWiseDatabase,
) : ViewModel() {
    val importState: StateFlow<ImportState> = sync.importState

    /** "12 in Indian · 4 in HDFC" — per account, never one combined count. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val importBreakdown: StateFlow<String?> = sync.importState.mapLatest { s ->
        if (s !is ImportState.Finished) return@mapLatest null
        db.createdPerAccount(s.summary.created).joinToString(" · ") { (a, ids) -> "${ids.size} in ${a.nickname}" }.ifEmpty { null }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lastLive: StateFlow<Long?> = rawDao.observeLastLiveReceivedAt().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun now() = clock.now()

    fun startImport(days: Int) {
        if (sync.importState.value is ImportState.Running) return
        SmsImportWorker.enqueue(context, days)
    }

    fun dismissImportResult() = sync.clearImportState()
}

private enum class ImportRange(val days: Int, val label: String) { D30(30, "30 days"), D90(90, "90 days"), D180(180, "6 months"), D365(365, "1 year") }

/**
 * Onboarding for SMS detection (ADR-005 §9): rationale → SMS permission → notifications → optional import of past
 * SMS. Sideloaded apps on Android 16 get an instant denial until "Allow restricted settings" is turned on, so a
 * denial shows the step-by-step guide. Everything else in the app works without any of this.
 */
@Composable
fun SmsSetupScreen(onClose: () -> Unit, viewModel: SmsSetupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val colors = SpendWise.colors
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val importBreakdown by viewModel.importBreakdown.collectAsStateWithLifecycle()
    val lastLive by viewModel.lastLive.collectAsStateWithLifecycle()
    var smsGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.RECEIVE_SMS) && context.hasPermission(Manifest.permission.READ_SMS)) }
    var notifGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) }
    var smsDenied by rememberSaveable { mutableStateOf(false) }
    var range by rememberSaveable { mutableStateOf(ImportRange.D90) }
    BackHandler(onBack = onClose)

    // Re-check on return from Settings (restricted settings / app info).
    LifecycleResumeEffect(Unit) {
        smsGranted = context.hasPermission(Manifest.permission.RECEIVE_SMS) && context.hasPermission(Manifest.permission.READ_SMS)
        notifGranted = context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (smsGranted) smsDenied = false
        onPauseOrDispose { }
    }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        smsGranted = result.values.all { it }
        smsDenied = !smsGranted
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = "SMS detection", eyebrow = if (smsGranted) "On" else "Off", reserveBottomBar = false) {
            item(key = "intro") {
                Text(
                    "SpendWise reads only your banks' transaction SMS, on this phone. Nothing is sent anywhere — the app has no internet " +
                        "permission. Every detected transaction waits for you to confirm it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text2,
                    modifier = Modifier.padding(horizontal = Spacing.screen + Spacing.xxs),
                )
            }

            item(key = "steps-label") { SectionLabel("Permissions") }
            item(key = "steps") {
                GroupCard {
                    ListRow(
                        title = "SMS access",
                        subtitle = if (smsGranted) "Allowed" else "Needed to detect bank SMS",
                        leadingIcon = Icons.Rounded.Sms,
                        onClick = if (smsGranted) null else ({ smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) }),
                    ) { StepState(smsGranted) }
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    ListRow(
                        title = "Notifications",
                        subtitle = if (notifGranted) "Allowed" else "To tell you when a transaction is detected",
                        leadingIcon = Icons.Rounded.Notifications,
                        onClick = if (notifGranted) null else ({ notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }),
                    ) { StepState(notifGranted) }
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    ListRow(
                        title = "Battery: Unrestricted",
                        subtitle = "App info → Battery → Unrestricted, so Samsung doesn't put SpendWise to sleep",
                        leadingIcon = Icons.Rounded.BatteryChargingFull,
                        onClick = { context.openAppInfo() },
                    )
                }
            }

            if (smsDenied && !smsGranted) {
                item(key = "restricted") { RestrictedSettingsGuide(onOpenAppInfo = { context.openAppInfo() }) }
            }

            if (smsGranted) {
                item(key = "health-label") { SectionLabel("Status") }
                item(key = "health") {
                    GroupCard {
                        ListRow(
                            title = lastLive?.let { "Last bank SMS detected ${ago(it, viewModel.now())}" } ?: "Waiting for your next bank SMS",
                            subtitle = "Pull down on Home to catch up on anything missed",
                            leadingIcon = Icons.Rounded.CheckCircle,
                            leadingTint = colors.income,
                        )
                    }
                }

                item(key = "import-label") { SectionLabel("Import past SMS") }
                item(key = "import") {
                    GroupCard {
                        Column(Modifier.padding(Spacing.md)) {
                            Text(
                                "Read bank SMS already on this phone. They arrive as transactions to review — nothing is confirmed for you, and your current balances don't change.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.text2,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            when (val s = importState) {
                                is ImportState.Running -> {
                                    Text("Reading week ${s.done + 1} of ${s.total}…", style = MaterialTheme.typography.bodyMedium, color = colors.text1)
                                    Spacer(Modifier.height(Spacing.xs))
                                    LinearProgressIndicator(
                                        progress = { if (s.total == 0) 0f else s.done / s.total.toFloat() },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = colors.primary,
                                        trackColor = colors.surface3,
                                    )
                                }
                                is ImportState.Finished -> {
                                    Text(
                                        importBreakdown?.let { "To review: $it" } ?: "No new transactions found.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.text1,
                                    )
                                    if (s.summary.unknownAccount > 0) {
                                        Text("Some SMS are from accounts not in SpendWise — see Home.", style = MaterialTheme.typography.bodySmall, color = colors.text2)
                                    }
                                    TextButton(onClick = viewModel::dismissImportResult) { Text("Done", color = colors.primary) }
                                }
                                ImportState.Failed -> {
                                    Text("Couldn't read SMS. Check that SMS access is allowed.", style = MaterialTheme.typography.bodyMedium, color = colors.danger)
                                    TextButton(onClick = viewModel::dismissImportResult) { Text("OK", color = colors.primary) }
                                }
                                ImportState.Idle -> {
                                    SegmentedChoice(ImportRange.entries, range, { it.label }, { range = it })
                                    Spacer(Modifier.height(Spacing.md))
                                    LiquidPrimaryButton(text = "Import ${range.label}", icon = Icons.Rounded.History, onClick = { viewModel.startImport(range.days) })
                                }
                            }
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(Spacing.xl)) }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = Spacing.xs)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.text1)
        }
    }
}

@Composable
private fun StepState(done: Boolean) {
    val colors = SpendWise.colors
    if (done) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = "Done", tint = colors.income)
    } else {
        Text("Allow", color = colors.primary, style = MaterialTheme.typography.labelLarge)
    }
}

/** Android 16 blocks SMS permissions for sideloaded apps until the user allows restricted settings. */
@Composable
private fun RestrictedSettingsGuide(onOpenAppInfo: () -> Unit) {
    val colors = SpendWise.colors
    Spacer(Modifier.height(Spacing.md))
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
            .padding(Spacing.lg),
    ) {
        Text("Android blocked SMS access", style = MaterialTheme.typography.titleSmall, color = colors.text1)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Apps installed outside the Play Store need one extra step:\n" +
                "1. Open App info below\n" +
                "2. Tap ⋮ (top right) → Allow restricted settings, and confirm\n" +
                "3. Come back here and tap SMS access again",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text2,
        )
        Spacer(Modifier.height(Spacing.md))
        LiquidPrimaryButton(text = "Open App info", onClick = onOpenAppInfo)
    }
}

// ------------------------------------------------------------------------------------------------ review

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReviewViewModel @Inject constructor(
    selected: SelectedAccountStore,
    reviewDao: ReviewDao,
    private val ledger: TransactionLedger,
) : ViewModel() {
    /** Pending rows for the SELECTED account only (CLAUDE.md: the selected account is the single source of accountId). */
    val pending: StateFlow<List<TxnRow>> = selected.selectedAccountId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else reviewDao.observePending(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** txnId → the bank SMS it came from (first message if several). */
    val smsText: StateFlow<Map<Long, String>> = selected.selectedAccountId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else reviewDao.observePendingSmsText(id)
    }.mapLatest { rows -> rows.filter { it.body != null }.groupBy { it.txnId }.mapValues { it.value.first().body!! } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val message = MutableStateFlow<String?>(null)
    private var busy = false

    fun confirmAllHigh() {
        // The account of the rows on screen — never a different one selected in between.
        val id = pending.value.firstOrNull()?.accountId ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val n = ledger.confirmAllHighConfidence(id)
                message.value = if (n == 0) "Nothing to confirm automatically — check each one." else "Confirmed $n."
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun ReviewScreen(onClose: () -> Unit, onOpen: (Long) -> Unit, viewModel: ReviewViewModel = hiltViewModel()) {
    val rows by viewModel.pending.collectAsStateWithLifecycle()
    val smsText by viewModel.smsText.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val colors = SpendWise.colors
    val high = rows.count { it.confidence == Confidence.HIGH && it.possibleDupOfId == null }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = "To review", eyebrow = if (rows.isEmpty()) null else "${rows.size} waiting", reserveBottomBar = false) {
            if (rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.DoneAll,
                        title = "All caught up",
                        body = "Transactions detected from SMS appear here until you confirm or ignore them.",
                    )
                }
                return@LargeTitleScreen
            }
            if (high > 0) {
                item(key = "all") {
                    Column(Modifier.padding(horizontal = Spacing.screen)) {
                        LiquidPrimaryButton(text = "Confirm all high-confidence ($high)", icon = Icons.Rounded.DoneAll, onClick = viewModel::confirmAllHigh)
                        Text(
                            "Only SMS read with a verified format and no possible duplicate. Others need a look.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text2,
                            modifier = Modifier.padding(top = Spacing.xs, start = Spacing.xxs),
                        )
                    }
                }
            }
            message?.let { m -> item(key = "msg") { Text(m, color = colors.text2, modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs)) } }
            item(key = "list-label") { SectionLabel("Tap one to check, categorise and confirm") }
            item(key = "list") {
                GroupCard {
                    rows.forEachIndexed { i, row ->
                        TransactionRowView(row, onClick = { onOpen(row.id) }, showDate = true)
                        smsText[row.id]?.let { text ->
                            Text(
                                text,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.text2,
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 68.dp, end = Spacing.md, bottom = Spacing.sm),
                            )
                        }
                        if (i < rows.lastIndex) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(Spacing.xl)) }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = Spacing.xs)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.text1)
        }
    }
}

// ------------------------------------------------------------------------------------------------ couldn't read

@HiltViewModel
class UnreadableViewModel @Inject constructor(private val rawDao: RawMessageDao) : ViewModel() {
    val rows: StateFlow<List<RawMessageEntity>> = rawDao.observeUnreadable().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun dismiss(id: Long) = viewModelScope.launch { rawDao.dropBody(id) }
}

private val SMS_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a")

/**
 * "Couldn't read" (ADR-005 §5): bank SMS that looked financial but matched no format. Never dropped silently —
 * the user can add them by hand. Several from one bank means its format probably changed.
 */
@Composable
fun UnreadableScreen(onClose: () -> Unit, onAddManually: () -> Unit, viewModel: UnreadableViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val colors = SpendWise.colors
    BackHandler(onBack = onClose)
    val byBank = rows.groupBy { it.bankHint ?: SenderIds.token(it.sender) }

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = "Couldn't read", reserveBottomBar = false) {
            if (rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(icon = Icons.AutoMirrored.Rounded.ReceiptLong, title = "Nothing here", body = "Every bank SMS so far was understood.")
                }
                return@LargeTitleScreen
            }
            byBank.filter { it.value.size >= 3 }.keys.forEach { bank ->
                item(key = "warn-$bank") {
                    Text(
                        "${bank?.let(BankRegistry::displayName) ?: "A bank"} may have changed its SMS format.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.pending,
                        modifier = Modifier.padding(horizontal = Spacing.screen + Spacing.xxs, vertical = Spacing.xs),
                    )
                }
            }
            item(key = "hint") {
                Text(
                    "These look like bank transactions but no known format matched. Add any that matter by hand, then dismiss them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text2,
                    modifier = Modifier.padding(horizontal = Spacing.screen + Spacing.xxs),
                )
            }
            rows.forEach { r ->
                item(key = "sms-${r.id}") {
                    Spacer(Modifier.height(Spacing.sm))
                    GroupCard {
                        Column(Modifier.padding(Spacing.md)) {
                            Text(
                                "${r.bankHint?.let(BankRegistry::displayName) ?: r.sender} · ${SMS_TIME.format(Instant.ofEpochMilli(r.sentAt).atZone(IndiaTime.ZONE))}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.text2,
                            )
                            Spacer(Modifier.height(Spacing.xxs))
                            Text(r.body.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.text1)
                            androidx.compose.foundation.layout.Row {
                                TextButton(onClick = onAddManually) { Text("Add by hand", color = colors.primary) }
                                TextButton(onClick = { viewModel.dismiss(r.id) }) { Text("Dismiss", color = colors.text2) }
                            }
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(Spacing.xl)) }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = Spacing.xs)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.text1)
        }
    }
}

/**
 * "Original SMS": the bank message(s) a transaction came from, so the user can see what it was about while
 * reviewing or editing. Stored on this phone only (redacted of OTPs and links); gone once ignored or purged.
 */
@Composable
fun OriginalSmsCard(messages: List<RawMessageEntity>) {
    if (messages.isEmpty()) return
    val colors = SpendWise.colors
    SectionLabel(if (messages.size == 1) "Original SMS" else "Original SMS (${messages.size})")
    GroupCard {
        messages.forEachIndexed { i, m ->
            Column(Modifier.padding(Spacing.md)) {
                Text(
                    "${m.bankHint?.let(BankRegistry::displayName) ?: m.sender} · ${SMS_TIME.format(Instant.ofEpochMilli(m.sentAt).atZone(IndiaTime.ZONE))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.text2,
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    m.body ?: "Text not kept (ignored, or deleted in More → Delete stored SMS text).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (m.body != null) colors.text1 else colors.text3,
                )
            }
            if (i < messages.lastIndex) HorizontalDivider(color = colors.divider)
        }
    }
}

/** Shown on Home when the SMS permission is off. */
@Composable
fun SmsOffCard(onSetUp: () -> Unit) {
    GroupCard {
        ListRow(
            title = "Detect transactions from SMS",
            subtitle = "Off · Set up",
            leadingIcon = Icons.Rounded.SmsFailed,
            onClick = onSetUp,
        )
    }
}
