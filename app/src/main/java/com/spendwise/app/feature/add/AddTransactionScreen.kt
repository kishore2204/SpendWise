package com.spendwise.app.feature.add

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.spendwise.app.ui.components.EmptyState
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing

/** Phase 4: fast manual entry — expense, income, transfer, cash. */
@Composable
fun AddTransactionScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = "New expense", reserveBottomBar = false) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Rounded.EditNote,
                    title = "Manual entry is coming",
                    body = "Expense, income, transfer and cash entry arrive in Phase 4.",
                )
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = Spacing.xs),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = SpendWise.colors.text1)
        }
    }
}
