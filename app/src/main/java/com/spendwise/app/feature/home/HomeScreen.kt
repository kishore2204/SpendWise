package com.spendwise.app.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.spendwise.app.data.db.dao.ReviewDao
import com.spendwise.app.transaction.ingest.DetectedAccount
import com.spendwise.app.transaction.ingest.DetectedAccounts
import com.spendwise.app.transaction.sms.SmsSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.spendwise.app.transaction.ingest.createdPerAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kyant.shapes.Capsule
import com.spendwise.app.core.money.Money
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.domain.analytics.ReportPeriod
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.account.BalanceBreakdown
import com.spendwise.app.data.account.SelectedAccountStore
import com.spendwise.app.data.db.dao.AccountWithBalance
import com.spendwise.app.data.db.dao.PeriodSummary
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.feature.accounts.AccountVisuals
import com.spendwise.app.ui.components.EmptyState
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.LiquidPrimaryButton
import com.spendwise.app.ui.components.LiquidPeriodPickerOverlay
import com.spendwise.app.ui.components.LiquidPeriodPickerTrigger
import com.spendwise.app.ui.components.ListRow
import com.spendwise.app.ui.components.MoneyText
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.glass.GlassKind
import com.spendwise.app.ui.glass.liquidGlass
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything Home shows is for ONE account; [accountId] stamps which (ADR-003). */
data class AccountView(
    val accountId: Long,
    val account: AccountEntity,
    val balance: BalanceBreakdown,
    val month: PeriodSummary,
    val recent: List<com.spendwise.app.data.db.dao.TxnRow>,
)

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object NoAccounts : HomeUiState
    data class Ready(val selected: AccountView, val accounts: List<AccountWithBalance>) : HomeUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    repository: AccountRepository,
    private val selectedStore: SelectedAccountStore,
    private val clock: Clock,
    txnDao: com.spendwise.app.data.db.dao.TxnDao,
    reviewDao: ReviewDao,
    private val db: com.spendwise.app.data.db.SpendWiseDatabase,
    private val sync: SmsSync,
    private val detectedAccounts: DetectedAccounts,
) : ViewModel() {

    /** Pending SMS transactions of the SELECTED account only. */
    val pendingCount: StateFlow<Int> = selectedStore.selectedAccountId.flatMapLatest { id ->
        if (id == null) flowOf(0) else reviewDao.observePendingCount(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val detected: StateFlow<List<DetectedAccount>> =
        detectedAccounts.detected.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _period = MutableStateFlow(ReportPeriod.MONTH)
    val period: StateFlow<ReportPeriod> = _period.asStateFlow()
    private val _periodRefresh = MutableStateFlow(0)
    fun selectPeriod(value: ReportPeriod) { _period.value = value }
    fun refreshPeriod() { _periodRefresh.value += 1 }
    fun millisToNextIndiaMidnight(): Long {
        val now = clock.now()
        val tomorrow = IndiaTime.localDate(now).plusDays(1)
        return (IndiaTime.startOfDay(tomorrow) - now).coerceAtLeast(1_000)
    }

    private val _smsOn = MutableStateFlow(sync.canReceiveSms())
    val smsOn: StateFlow<Boolean> = _smsOn.asStateFlow()
    fun recheckSms() { _smsOn.value = sync.canReceiveSms() }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _refreshMessage = MutableStateFlow<String?>(null)
    val refreshMessage: StateFlow<String?> = _refreshMessage.asStateFlow()

    /** Pull-to-refresh (ADR-005 §3): re-reads bank SMS from the watermark − 72h. Never on a timer. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val summary = sync.refresh()
            val perAccount = summary?.let { db.createdPerAccount(it.created) }.orEmpty()
            _refreshing.value = false
            // Counts per account by name, the selected one first — never one figure across accounts.
            val selectedId = selectedStore.selectedAccountId.first()
            _refreshMessage.value = when {
                summary == null -> "Turn on SMS detection to refresh"
                perAccount.isEmpty() -> "You're up to date"
                else -> perAccount.sortedBy { if (it.first.id == selectedId) 0 else 1 }
                    .joinToString(" · ") { (a, ids) -> "${ids.size} new in ${a.nickname}" }
            }
            delay(3_500)
            _refreshMessage.value = null
        }
    }

    fun notMine(account: DetectedAccount) = viewModelScope.launch { detectedAccounts.markNotMine(account.bankCode, account.last4) }

    private val selectedView: Flow<AccountView?> = combine(selectedStore.selectedAccountId, _period, _periodRefresh) { id, p, _ -> id to p }
        .flatMapLatest { (id, p) ->
        if (id == null) {
            flowOf(null)
        } else {
            val range = p.range(ReportPeriod.today(clock.now()))
            // Re-subscribes per account, so a switch can never show the previous account's numbers.
            combine(
                repository.observeAccount(id),
                repository.observeBalance(id),
                repository.observePeriod(id, range),
                txnDao.observeRecent(id, 5),
            ) { account, balance, summary, recent -> account?.let { AccountView(id, it, balance, summary, recent) } }
        }
    }

    val state: StateFlow<HomeUiState> = combine(selectedView, repository.observeAccountsWithBalances()) { view, accounts ->
        when {
            accounts.isEmpty() -> HomeUiState.NoAccounts
            view == null -> HomeUiState.Loading
            else -> HomeUiState.Ready(view, accounts)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    fun select(accountId: Long) = viewModelScope.launch { selectedStore.select(accountId) }
}

@Composable
fun HomeScreen(
    onAddAccount: () -> Unit,
    onManageAccounts: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onReview: () -> Unit,
    onSmsSetup: () -> Unit,
    onAddDetected: (bankCode: String, last4: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val detected by viewModel.detected.collectAsStateWithLifecycle()
    val smsOn by viewModel.smsOn.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val refreshMessage by viewModel.refreshMessage.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.period.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.recheckSms()
        viewModel.refreshPeriod()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        while (true) {
            delay(viewModel.millisToNextIndiaMidnight())
            viewModel.refreshPeriod()
        }
    }
    val pullState = rememberPullToRefreshState()
    val greeting = IndiaTime.greetingFor(System.currentTimeMillis()).text
    var switcherOpen by rememberSaveable { mutableStateOf(false) }
    var periodPickerOpen by rememberSaveable { mutableStateOf(false) }
    var periodAnchor by remember { mutableStateOf<Rect?>(null) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        state = pullState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                containerColor = SpendWise.colors.surface2,
                color = SpendWise.colors.primary,
            )
        },
    ) {
    LargeTitleScreen(title = greeting, collapsedTitle = "Home") {
        refreshMessage?.let { m ->
            item(key = "refresh-msg") {
                Text(
                    m,
                    style = MaterialTheme.typography.labelLarge,
                    color = SpendWise.colors.text1,
                    modifier = Modifier
                        .padding(horizontal = Spacing.screen, vertical = Spacing.xs)
                        .liquidGlass(Capsule(), GlassKind.Card) // content glass: Control here would sample its own layer
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
        }
        // New accounts seen in SMS: never created automatically — the user decides (CLAUDE.md rule #1).
        detected.forEach { d ->
            item(key = "detected-${d.bankCode}-${d.last4}") {
                DetectedAccountCard(d, onAdd = { onAddDetected(d.bankCode, d.last4) }, onNotMine = { viewModel.notMine(d) })
            }
        }
        when (val s = state) {
            HomeUiState.Loading -> Unit
            HomeUiState.NoAccounts -> item(key = "start", contentType = "hero") { StartCard(onAddAccount) }
            is HomeUiState.Ready -> {
                item(key = "switcher") {
                    AccountSwitcher(
                        selected = s.selected.account,
                        accounts = s.accounts,
                        open = switcherOpen,
                        onToggle = {
                            if (!switcherOpen) periodPickerOpen = false
                            switcherOpen = !switcherOpen
                        },
                        onSelect = { id ->
                            viewModel.select(id)
                            switcherOpen = false
                        },
                        onManage = onManageAccounts,
                    )
                }
                item(key = "home-period") {
                    val fontScale = LocalDensity.current.fontScale
                    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
                        if (fontScale >= 1.8f) {
                            Text("Period", style = MaterialTheme.typography.labelMedium, color = SpendWise.colors.text2)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (fontScale < 1.8f) {
                                Text("Period", style = MaterialTheme.typography.labelMedium, color = SpendWise.colors.text2, modifier = Modifier.weight(1f))
                            }
                            LiquidPeriodPickerTrigger(
                                selected = selectedPeriod,
                                expanded = periodPickerOpen,
                                onToggle = {
                                    if (periodPickerOpen) {
                                        periodPickerOpen = false
                                    } else {
                                        switcherOpen = false
                                        periodPickerOpen = true
                                    }
                                },
                                onAnchorChanged = { periodAnchor = it },
                                modifier = if (fontScale >= 1.8f) Modifier.fillMaxWidth()
                                else Modifier.width(150.dp * fontScale.coerceAtMost(1.9f)),
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                }
                item(key = "hero", contentType = "hero") { BalanceHero(s.selected) }
                if (pendingCount > 0) {
                    item(key = "pending") {
                        Spacer(Modifier.height(Spacing.sm))
                        com.spendwise.app.ui.components.GroupCard {
                            ListRow(
                                title = "$pendingCount to review",
                                subtitle = "Detected from SMS · not counted until you confirm",
                                leadingIcon = Icons.Rounded.MarkEmailUnread,
                                leadingTint = SpendWise.colors.pending,
                                onClick = onReview,
                            ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = SpendWise.colors.text3) }
                        }
                    }
                }
                if (!smsOn) {
                    item(key = "sms-off") {
                        Spacer(Modifier.height(Spacing.sm))
                        com.spendwise.app.feature.sms.SmsOffCard(onSmsSetup)
                    }
                }
                item(key = "recent-label") { SectionLabel("Recent transactions") }
                item(key = "recent") {
                    val recent = s.selected.recent
                    if (recent.isEmpty()) {
                        EmptyState(
                            icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                            title = "No transactions yet",
                            body = "Add one for ${s.selected.account.nickname} with the + button.",
                        )
                    } else {
                        // One card holds all rows: glass belongs to the container, never to each row.
                        com.spendwise.app.ui.components.GroupCard {
                            recent.forEach { row ->
                                com.spendwise.app.ui.components.TransactionRowView(row, onClick = { onOpenTransaction(row.id) }, showDate = true)
                                HorizontalDivider(color = SpendWise.colors.divider, modifier = Modifier.padding(start = 68.dp))
                            }
                            ListRow(title = "See all transactions", onClick = onSeeAll)
                        }
                    }
                }
            }
        }
    }
    LiquidPeriodPickerOverlay(
        expanded = periodPickerOpen,
        selected = selectedPeriod,
        anchorInWindow = periodAnchor,
        onDismiss = { periodPickerOpen = false },
        onSelect = viewModel::selectPeriod,
    )
    }
}

/** "New account detected · HDFC ••••1234" with Not mine / Add account (ADR-005 §7). Identity only, no amounts. */
@Composable
private fun DetectedAccountCard(d: DetectedAccount, onAdd: () -> Unit, onNotMine: () -> Unit) {
    val colors = SpendWise.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.xs)
            .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
            .padding(Spacing.md),
    ) {
        Text("New account detected", style = MaterialTheme.typography.labelMedium, color = colors.text2)
        Text("${d.bankName} ••••${d.last4}", style = MaterialTheme.typography.titleMedium, color = colors.text1)
        Text(
            "${d.messageCount} SMS waiting. Add it to see them, or tell SpendWise it isn't yours.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.text2,
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextButton(onClick = onNotMine) { Text("Not mine", color = colors.text2) }
            LiquidPrimaryButton(text = "Add account", icon = Icons.Rounded.Add, onClick = onAdd, modifier = Modifier.weight(1f))
        }
    }
}

/** "HDFC ••••4521 ▾" pill that opens the account list. Names only — never a total. */
@Composable
private fun AccountSwitcher(
    selected: AccountEntity,
    accounts: List<AccountWithBalance>,
    open: Boolean,
    onToggle: () -> Unit,
    onSelect: (Long) -> Unit,
    onManage: () -> Unit,
) {
    val colors = SpendWise.colors
    val haptics = LocalHapticFeedback.current
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(SpendWise.motion.duration(SpendWise.motion.medium)), label = "chevron")
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
        Row(
            Modifier
                .widthIn(max = 320.dp)
                .liquidGlass(Capsule(), GlassKind.Card)
                .clip(CircleShape)
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onToggle()
                }
                .padding(horizontal = Spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AccountVisuals.icon(selected.type), contentDescription = null, tint = colors.text2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            AnimatedContent(
                targetState = AccountVisuals.short(selected),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "pill",
            ) { label ->
                Text(label, style = MaterialTheme.typography.titleSmall, color = colors.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(Spacing.xxs))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (open) "Close accounts" else "Switch account",
                tint = colors.text2,
                modifier = Modifier.size(20.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                Spacer(Modifier.height(Spacing.sm))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
                        .clip(RoundedCornerShape(Radius.card)),
                ) {
                    accounts.forEach { row ->
                        val a = row.account
                        ListRow(
                            title = a.nickname,
                            subtitle = AccountVisuals.detail(a),
                            leadingIcon = AccountVisuals.icon(a.type),
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(a.id)
                            },
                        ) {
                            if (a.id == selected.id) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = colors.primary)
                        }
                        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    }
                    ListRow(title = "Manage accounts", leadingIcon = Icons.Rounded.Settings, onClick = onManage)
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

/** The selected account's current balance and selected-period summary; crossfades on account changes. */
@Composable
private fun BalanceHero(view: AccountView) {
    val colors = SpendWise.colors
    val motion = SpendWise.motion
    val heroAmountStyle = SpendWise.money.hero
    val summaryAmountStyle = SpendWise.money.large
    AnimatedContent(
        targetState = view,
        contentKey = { it.accountId },
        transitionSpec = { fadeIn(tween(motion.duration(250))) togetherWith fadeOut(tween(motion.duration(150))) },
        label = "hero",
    ) { v ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
                .padding(Spacing.lg),
        ) {
            MoneyText(v.balance.displayedPaise, style = heroAmountStyle)
            Text("Current balance", style = MaterialTheme.typography.labelMedium, color = colors.text2)
            Spacer(Modifier.height(Spacing.lg))
            Spacer(Modifier.height(Spacing.xs))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Column(Modifier.weight(1f)) {
                    Text("Income", style = MaterialTheme.typography.labelMedium, color = colors.text2)
                    MoneyText(v.month.incomePaise, style = summaryAmountStyle, color = colors.income, sign = Money.SignStyle.Always)
                    if (v.month.repaidPaise > 0) {
                        Text("+ ${Money.format(v.month.repaidPaise)} paid back by friends", style = MaterialTheme.typography.bodySmall, color = colors.text2)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Spent", style = MaterialTheme.typography.labelMedium, color = colors.text2)
                    // Spent is a net figure: refunds above spending show as a credit ("+₹500"), never "−−₹500".
                    MoneyText(-v.month.spentPaise, style = summaryAmountStyle, color = colors.expense, sign = Money.SignStyle.Always)
                    if (v.month.lentPaise > 0) {
                        Text("+ ${Money.format(v.month.lentPaise)} paid for friends", style = MaterialTheme.typography.bodySmall, color = colors.text2)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(Spacing.xs))
}

@Composable
private fun StartCard(onAddAccount: () -> Unit) {
    val colors = SpendWise.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
            .padding(Spacing.lg),
    ) {
        Text("No accounts yet", style = MaterialTheme.typography.titleLarge, color = colors.text1)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Add Indian Bank, HDFC or a Cash wallet. Each account keeps its own balance and history.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text2,
        )
        Spacer(Modifier.height(Spacing.lg))
        LiquidPrimaryButton(text = "Add account", onClick = onAddAccount, icon = Icons.Rounded.Add)
    }
}
