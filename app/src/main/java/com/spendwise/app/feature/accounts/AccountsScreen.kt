package com.spendwise.app.feature.accounts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.runtime.Composable
import com.spendwise.app.ui.components.EmptyState
import com.spendwise.app.ui.components.LargeTitleScreen

/** Phase 3 builds account creation, switching, balance editing and reconciliation here. */
@Composable
fun AccountsScreen() {
    LargeTitleScreen(title = "Accounts") {
        item(key = "empty") {
            EmptyState(
                icon = Icons.Rounded.AccountBalanceWallet,
                title = "No accounts yet",
                body = "Account setup is coming in the next update.",
            )
        }
    }
}
