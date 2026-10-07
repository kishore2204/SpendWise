package com.spendwise.app.feature.insights

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.runtime.Composable
import com.spendwise.app.ui.components.EmptyState
import com.spendwise.app.ui.components.LargeTitleScreen

/** Phase 6: account-specific summary, categories, trends, merchants, insights. Never combined. */
@Composable
fun InsightsScreen() {
    LargeTitleScreen(title = "Insights") {
        item(key = "empty") {
            EmptyState(
                icon = Icons.Rounded.Insights,
                title = "Nothing to analyse yet",
                body = "Insights appear once an account has transactions — always for one account at a time.",
            )
        }
    }
}
