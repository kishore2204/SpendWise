package com.spendwise.app.feature.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.account.SelectedAccountStore
import com.spendwise.app.data.db.dao.TxnDao
import com.spendwise.app.data.db.dao.TxnRow
import com.spendwise.app.feature.accounts.AccountVisuals
import com.spendwise.app.ui.components.EmptyState
import com.spendwise.app.ui.components.AccountContextPill
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.components.TransactionRowView
import com.spendwise.app.ui.components.TxnText
import com.spendwise.app.ui.components.rowSegmentShape
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    txnDao: TxnDao,
    repository: AccountRepository,
    selected: SelectedAccountStore,
) : ViewModel() {
    val accountName = selected.selectedAccountId.filterNotNull()
        .flatMapLatest { repository.observeAccount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Paged, newest first, for the SELECTED account only. Re-created on every account switch. */
    val pages: Flow<PagingData<TxnRow>> = selected.selectedAccountId.flatMapLatest { id ->
        if (id == null) flowOf(PagingData.empty())
        else Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) { txnDao.pagedForAccount(id) }.flow
    }.cachedIn(viewModelScope)
}

/**
 * All transactions of the selected account, grouped by day. Rows sit on solid segments (never glass per row —
 * ADR-011 audit); Paging keeps thousands of rows cheap.
 */
@Composable
fun TransactionsScreen(
    onClose: () -> Unit,
    onOpen: (Long) -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val items = viewModel.pages.collectAsLazyPagingItems()
    val account by viewModel.accountName.collectAsStateWithLifecycle()
    val colors = SpendWise.colors
    val today = IndiaTime.localDate(System.currentTimeMillis())
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = "Transactions", titleBottomSpacing = Spacing.xs, reserveBottomBar = false) {
            account?.let { current ->
                item(key = "account-context") { AccountContextPill(AccountVisuals.short(current)) }
            }
            if (items.itemCount == 0) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        title = "No transactions yet",
                        body = "Add one with the + button. SMS detection arrives in the next phase.",
                    )
                }
            }
            items(
                count = items.itemCount,
                key = items.itemKey { it.id },
                contentType = items.itemContentType { "txn" },
            ) { index ->
                val row = items[index] ?: return@items
                val day = IndiaTime.localDate(row.occurredAt)
                val prev = if (index > 0) items.peek(index - 1) else null
                val next = if (index < items.itemCount - 1) items.peek(index + 1) else null
                val first = prev == null || IndiaTime.localDate(prev.occurredAt) != day
                val last = next == null || IndiaTime.localDate(next.occurredAt) != day
                Column {
                    if (first) SectionLabel(TxnText.day(day, today))
                    val shape = rowSegmentShape(first, last)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.screen)
                            .clip(shape)
                            .background(colors.surface1.copy(alpha = if (colors.isDark) 0.92f else 0.96f))
                            .border(1.dp, colors.glassHairline, shape),
                    ) {
                        TransactionRowView(row, onClick = { onOpen(row.id) })
                        if (!last) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    }
                }
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = Spacing.xs),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.text1)
        }
    }
}
