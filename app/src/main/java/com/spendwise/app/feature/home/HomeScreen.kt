package com.spendwise.app.feature.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.db.dao.AccountDao
import com.spendwise.app.ui.components.GroupCard
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.ListRow
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.glass.liquidGlass
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object NoAccounts : HomeUiState
    /** Phase 3 replaces this with the selected account's balance, month summary and recent transactions. */
    data class HasAccounts(val count: Int) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(accountDao: AccountDao) : ViewModel() {
    val state: StateFlow<HomeUiState> = accountDao.observeActiveCount()
        .map { if (it == 0) HomeUiState.NoAccounts else HomeUiState.HasAccounts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)
}

@Composable
fun HomeScreen(
    onAddAccount: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val greeting = IndiaTime.greetingFor(System.currentTimeMillis()).text

    LargeTitleScreen(title = greeting, collapsedTitle = "Home") {
        when (state) {
            HomeUiState.Loading -> Unit
            HomeUiState.NoAccounts, is HomeUiState.HasAccounts -> {
                item(key = "welcome", contentType = "hero") { WelcomeHero(onAddAccount) }
                item(key = "how-label") { SectionLabel("How SpendWise works") }
                item(key = "how", contentType = "group") { HowItWorks() }
            }
        }
    }
}

@Composable
private fun WelcomeHero(onAddAccount: () -> Unit) {
    val colors = SpendWise.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .liquidGlass()
            .padding(Spacing.lg),
    ) {
        Text("Your money.\nOn this phone only.", style = MaterialTheme.typography.headlineSmall, color = colors.text1)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Add an account to start. Each account keeps its own balance, budgets and insights — nothing is ever combined.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text2,
        )
        Spacer(Modifier.height(Spacing.lg))
        Button(
            onClick = onAddAccount,
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text("Add your first account", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HowItWorks() {
    val colors = SpendWise.colors
    GroupCard {
        ListRow(
            title = "Reads bank SMS as they arrive",
            subtitle = "New transactions wait for your one-tap review",
            leadingIcon = Icons.Rounded.Sms,
            leadingTint = colors.primary,
        )
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
        ListRow(
            title = "Every account is its own space",
            subtitle = "Indian Bank and HDFC never mix",
            leadingIcon = Icons.Rounded.AccountBalance,
            leadingTint = colors.transfer,
        )
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
        ListRow(
            title = "Private by design",
            subtitle = "No internet permission — data can't leave the phone",
            leadingIcon = Icons.Rounded.CloudOff,
            leadingTint = colors.income,
        )
    }
}
