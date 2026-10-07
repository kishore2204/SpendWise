package com.spendwise.app.feature.accounts

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.account.SelectedAccountStore
import com.spendwise.app.data.db.dao.AccountWithBalance
import com.spendwise.app.ui.components.EmptyState
import com.spendwise.app.ui.components.GroupCard
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.LiquidPrimaryButton
import com.spendwise.app.ui.components.ListRow
import com.spendwise.app.ui.components.MoneyText
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AccountsUiState(
    val loading: Boolean = true,
    val accounts: ImmutableList<AccountWithBalance> = kotlinx.collections.immutable.persistentListOf(),
    val selectedId: Long? = null,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    repository: AccountRepository,
    selected: SelectedAccountStore,
) : ViewModel() {
    val state: StateFlow<AccountsUiState> =
        combine(repository.observeAccountsWithBalances(), selected.selectedAccountId) { accounts, sel ->
            AccountsUiState(loading = false, accounts = accounts.toImmutableList(), selectedId = sel)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())
}

/**
 * Every account with ITS OWN balance (CLAUDE.md allows this: it is not a combined figure). There is
 * deliberately no total anywhere on this screen.
 */
@Composable
fun AccountsScreen(
    onAddAccount: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = SpendWise.colors

    LargeTitleScreen(title = "Accounts") {
        if (state.loading) return@LargeTitleScreen
        if (state.accounts.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Rounded.AccountBalanceWallet,
                    title = "No accounts yet",
                    body = "Add Indian Bank, HDFC or a Cash wallet. Each one keeps its own balance and history.",
                )
            }
        } else {
            item(key = "label") { SectionLabel("Your accounts") }
            item(key = "list", contentType = "group") {
                GroupCard {
                    state.accounts.forEachIndexed { index, row ->
                        val account = row.account
                        ListRow(
                            title = account.nickname,
                            subtitle = AccountVisuals.detail(account),
                            leadingIcon = AccountVisuals.icon(account.type),
                            onClick = { onOpenAccount(account.id) },
                        ) {
                            if (account.id == state.selectedId) {
                                Icon(Icons.Rounded.Check, contentDescription = "Shown on Home", tint = colors.primary, modifier = Modifier.padding(end = Spacing.xs))
                            }
                            MoneyText(row.balancePaise, style = SpendWise.money.row)
                        }
                        if (index < state.accounts.lastIndex) {
                            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                        }
                    }
                }
            }
        }
        item(key = "add") {
            Spacer(Modifier.height(Spacing.lg))
            LiquidPrimaryButton(
                text = "Add account",
                onClick = onAddAccount,
                icon = Icons.Rounded.Add,
                modifier = Modifier.padding(horizontal = Spacing.screen),
            )
        }
    }
}
