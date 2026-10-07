package com.spendwise.app.feature.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spendwise.app.core.money.Money
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.account.BalanceBreakdown
import com.spendwise.app.data.account.SelectedAccountStore
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.db.entity.AnchorSource
import com.spendwise.app.domain.AccountLedger
import com.spendwise.app.domain.Adjustment
import com.spendwise.app.ui.components.GroupCard
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.LiquidPrimaryButton
import com.spendwise.app.ui.components.ListRow
import com.spendwise.app.ui.components.MoneyInput
import com.spendwise.app.ui.components.MoneyText
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.components.TextInput
import com.spendwise.app.ui.components.parseSignedRupees
import com.spendwise.app.ui.glass.GlassKind
import com.spendwise.app.ui.glass.liquidGlass
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

data class AccountDetailState(
    val accountId: Long,
    val account: AccountEntity? = null,
    val balance: BalanceBreakdown? = null,
    val history: List<Adjustment> = emptyList(),
    val isSelected: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = AccountDetailViewModel.Factory::class)
class AccountDetailViewModel @AssistedInject constructor(
    @Assisted private val accountId: Long,
    repository: AccountRepository,
    private val ledger: AccountLedger,
    private val selected: SelectedAccountStore,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(accountId: Long): AccountDetailViewModel
    }

    private val history = repository.observeAnchorsChanged(accountId)
        .let { anchors -> combine(anchors, repository.observeBalance(accountId)) { _, _ -> Unit } }
        .mapLatest { ledger.adjustmentHistory(accountId).asReversed() }

    // Every flow here is keyed by this screen's accountId — nothing reads another account.
    val state: StateFlow<AccountDetailState> = combine(
        repository.observeAccount(accountId),
        repository.observeBalance(accountId),
        history,
        selected.selectedAccountId,
    ) { account, balance, hist, sel ->
        AccountDetailState(accountId, account, balance, hist, sel == accountId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailState(accountId))

    fun setBalance(paise: Long, source: AnchorSource, note: String?) =
        viewModelScope.launch { ledger.setBalance(accountId, paise, source, note) }

    fun rename(name: String) = viewModelScope.launch {
        state.value.account?.let { ledger.updateDetails(accountId, name.ifBlank { it.bankName }, it.type) }
    }

    fun showOnHome() = viewModelScope.launch { selected.select(accountId) }

    fun archive(onDone: () -> Unit) = viewModelScope.launch {
        ledger.archive(accountId)
        onDone()
    }
}

private enum class Editor { None, Balance, Reconcile, Rename }

@Composable
fun AccountDetailScreen(
    accountId: Long,
    onClose: () -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel<AccountDetailViewModel, AccountDetailViewModel.Factory>(
        key = "account-$accountId",
    ) { it.create(accountId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = SpendWise.colors
    var editor by remember { mutableStateOf(Editor.None) }
    var confirmArchive by remember { mutableStateOf(false) }
    BackHandler(onBack = { if (editor != Editor.None) editor = Editor.None else onClose() })

    val account = state.account
    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(
            title = account?.nickname.orEmpty(),
            eyebrow = account?.let { AccountVisuals.detail(it) },
            reserveBottomBar = false,
        ) {
            val balance = state.balance
            if (account == null || balance == null) return@LargeTitleScreen

            item(key = "hero", contentType = "hero") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screen)
                        .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
                        .padding(Spacing.lg),
                ) {
                    Text(
                        if (account.type == com.spendwise.app.data.db.entity.AccountType.CREDIT_CARD) "Balance (negative = owed)" else "Current balance",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.text2,
                    )
                    Spacer(Modifier.height(Spacing.xxs))
                    MoneyText(balance.displayedPaise, style = SpendWise.money.hero)
                    Spacer(Modifier.height(Spacing.md))
                    // ADR-004: calculated + adjustment = displayed, always shown honestly.
                    BreakdownLine("From transactions", balance.fromTransactionsPaise)
                    BreakdownLine("Adjustments", balance.adjustmentPaise, signed = true)
                }
            }

            item(key = "actions") {
                Spacer(Modifier.height(Spacing.md))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    LiquidPrimaryButton(text = "Edit balance", onClick = { editor = Editor.Balance }, icon = Icons.Rounded.Edit, modifier = Modifier.weight(1f))
                    LiquidPrimaryButton(text = "Reconcile", onClick = { editor = Editor.Reconcile }, icon = Icons.Rounded.AccountBalance, modifier = Modifier.weight(1f))
                }
            }

            item(key = "editor") {
                AnimatedVisibility(visible = editor == Editor.Balance || editor == Editor.Reconcile) {
                    BalanceEditor(
                        reconcile = editor == Editor.Reconcile,
                        creditCard = account.type == com.spendwise.app.data.db.entity.AccountType.CREDIT_CARD,
                        current = balance.displayedPaise,
                        onCancel = { editor = Editor.None },
                        onSave = { paise, note ->
                            viewModel.setBalance(paise, if (editor == Editor.Reconcile) AnchorSource.RECONCILE else AnchorSource.MANUAL, note)
                            editor = Editor.None
                        },
                    )
                }
                AnimatedVisibility(visible = editor == Editor.Rename) {
                    RenameEditor(account.nickname, onCancel = { editor = Editor.None }) {
                        viewModel.rename(it)
                        editor = Editor.None
                    }
                }
            }

            item(key = "history-label") { SectionLabel("Balance history") }
            item(key = "history") {
                GroupCard {
                    state.history.forEachIndexed { i, adj ->
                        ListRow(
                            title = when (adj.source) {
                                AnchorSource.OPENING -> "Opening balance"
                                AnchorSource.MANUAL -> "Balance edited"
                                AnchorSource.RECONCILE -> "Reconciled with bank"
                            },
                            subtitle = buildString {
                                append(DATE.format(IndiaTime.localDate(adj.at)))
                                adj.note?.let { append(" · ").append(it) }
                            },
                            leadingIcon = if (adj.source == AnchorSource.OPENING) Icons.Rounded.Check else Icons.Rounded.Tune,
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                MoneyText(adj.balancePaise, style = SpendWise.money.row)
                                adj.deltaPaise?.let { d ->
                                    Text(
                                        Money.format(d, Money.SignStyle.Always),
                                        style = SpendWise.money.small,
                                        color = colors.text2,
                                    )
                                }
                            }
                        }
                        if (i < state.history.lastIndex) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    }
                }
            }

            item(key = "manage-label") { SectionLabel("Manage") }
            item(key = "manage") {
                GroupCard {
                    ListRow(
                        title = if (state.isSelected) "Shown on Home" else "Show on Home",
                        subtitle = "Home, Insights and search follow this account",
                        leadingIcon = Icons.Rounded.Home,
                        onClick = if (state.isSelected) null else ({ viewModel.showOnHome() }),
                    ) {
                        if (state.isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.primary)
                    }
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    ListRow(title = "Rename", subtitle = account.nickname, leadingIcon = Icons.Rounded.Edit, onClick = { editor = Editor.Rename })
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    ListRow(
                        title = "Archive account",
                        subtitle = "Hides it everywhere. History is kept.",
                        leadingIcon = Icons.Rounded.Archive,
                        onClick = { confirmArchive = true },
                    )
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = Spacing.xs),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.text1)
        }
    }

    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Archive ${account?.nickname.orEmpty()}?") },
            text = { Text("It disappears from Home and the account list. Its transactions and balance history are kept.") },
            confirmButton = { TextButton(onClick = { confirmArchive = false; viewModel.archive(onClose) }) { Text("Archive") } },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Cancel") } },
            containerColor = colors.surface1,
        )
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
private fun BreakdownLine(label: String, paise: Long, signed: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SpendWise.colors.text2)
        Text(
            Money.format(paise, if (signed) Money.SignStyle.Always else Money.SignStyle.NegativeOnly),
            style = SpendWise.money.small,
            color = SpendWise.colors.text2,
        )
    }
}

@Composable
private fun BalanceEditor(reconcile: Boolean, creditCard: Boolean, current: Long, onCancel: () -> Unit, onSave: (Long, String?) -> Unit) {
    var amount by remember(reconcile) { mutableStateOf("") }
    var note by remember(reconcile) { mutableStateOf("") }
    var error by remember(reconcile) { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(Spacing.md))
    GroupCard {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                when {
                    creditCard && reconcile -> "What does the card statement say you owe?"
                    creditCard -> "Set the amount owed to"
                    reconcile -> "What does the bank show now?"
                    else -> "Set the balance to"
                },
                style = MaterialTheme.typography.titleSmall,
                color = SpendWise.colors.text1,
            )
            Text(
                (if (creditCard) "Owed now ${Money.format(-current)}." else "Now ${Money.format(current)}.") +
                    " Your transactions stay exactly as they are.",
                style = MaterialTheme.typography.bodySmall,
                color = SpendWise.colors.text2,
            )
            Spacer(Modifier.height(Spacing.sm))
            MoneyInput(
                amount,
                { amount = it; error = null },
                when {
                    creditCard -> "Amount owed"
                    reconcile -> "Bank balance"
                    else -> "New balance"
                },
                error = error,
            )
            Spacer(Modifier.height(Spacing.xs))
            TextInput(note, { note = it.take(60) }, "Note (optional)")
            Spacer(Modifier.height(Spacing.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel", color = SpendWise.colors.text2) }
                Spacer(Modifier.width(Spacing.xs))
                TextButton(onClick = {
                    val paise = parseSignedRupees(amount, emptyIsZero = false)
                    if (paise == null) error = "Enter an amount like 2568.44" else onSave(paise, note)
                }) { Text("Save", color = SpendWise.colors.primary) }
            }
        }
    }
}

@Composable
private fun RenameEditor(current: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    Spacer(Modifier.height(Spacing.md))
    GroupCard {
        Column(Modifier.padding(Spacing.md)) {
            TextInput(name, { name = it.take(24) }, "Account name")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel", color = SpendWise.colors.text2) }
                TextButton(onClick = { onSave(name) }) { Text("Save", color = SpendWise.colors.primary) }
            }
        }
    }
}
