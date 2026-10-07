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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kyant.shapes.Capsule
import com.spendwise.app.core.money.Money
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
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
    clock: Clock,
) : ViewModel() {

    private val selectedView: Flow<AccountView?> = selectedStore.selectedAccountId.flatMapLatest { id ->
        if (id == null) {
            flowOf(null)
        } else {
            // Re-subscribes per account, so a switch can never show the previous account's numbers.
            combine(
                repository.observeAccount(id),
                repository.observeBalance(id),
                repository.observeThisMonth(id, clock.now()),
            ) { account, balance, month -> account?.let { AccountView(id, it, balance, month) } }
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
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val greeting = IndiaTime.greetingFor(System.currentTimeMillis()).text
    var switcherOpen by rememberSaveable { mutableStateOf(false) }

    LargeTitleScreen(title = greeting, collapsedTitle = "Home") {
        when (val s = state) {
            HomeUiState.Loading -> Unit
            HomeUiState.NoAccounts -> item(key = "start", contentType = "hero") { StartCard(onAddAccount) }
            is HomeUiState.Ready -> {
                item(key = "switcher") {
                    AccountSwitcher(
                        selected = s.selected.account,
                        accounts = s.accounts,
                        open = switcherOpen,
                        onToggle = { switcherOpen = !switcherOpen },
                        onSelect = { id ->
                            viewModel.select(id)
                            switcherOpen = false
                        },
                        onManage = onManageAccounts,
                    )
                }
                item(key = "hero", contentType = "hero") { BalanceHero(s.selected) }
                item(key = "recent-label") { SectionLabel("Recent transactions") }
                item(key = "recent") {
                    EmptyState(
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        title = "No transactions yet",
                        body = "Transactions for ${s.selected.account.nickname} will appear here.",
                    )
                }
            }
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
                Text(label, style = MaterialTheme.typography.titleSmall, color = colors.text1)
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

/** The selected account's balance and this month; crossfades when the account changes. */
@Composable
private fun BalanceHero(view: AccountView) {
    val colors = SpendWise.colors
    val motion = SpendWise.motion
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
            MoneyText(v.balance.displayedPaise, style = SpendWise.money.hero)
            Text("Current balance", style = MaterialTheme.typography.labelMedium, color = colors.text2)
            Spacer(Modifier.height(Spacing.lg))
            Text("This month", style = MaterialTheme.typography.labelMedium, color = colors.text2)
            Spacer(Modifier.height(Spacing.xs))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Column(Modifier.weight(1f)) {
                    Text("Income", style = MaterialTheme.typography.bodySmall, color = colors.text2)
                    Text(Money.format(v.month.incomePaise, Money.SignStyle.Always), style = SpendWise.money.large, color = colors.income)
                }
                Column(Modifier.weight(1f)) {
                    Text("Spent", style = MaterialTheme.typography.bodySmall, color = colors.text2)
                    // Spent is a net figure: refunds above spending show as a credit ("+₹500"), never "−−₹500".
                    Text(Money.format(-v.month.spentPaise, Money.SignStyle.Always), style = SpendWise.money.large, color = colors.expense)
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
