package com.spendwise.app.feature.transactions

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spendwise.app.core.money.Money
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.dao.TxnRow
import com.spendwise.app.data.db.entity.CategoryEntity
import com.spendwise.app.data.db.entity.CategoryKind
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.data.db.entity.TxnSplitEntity
import com.spendwise.app.domain.LedgerResult
import com.spendwise.app.domain.SplitPart
import com.spendwise.app.domain.TransactionLedger
import com.spendwise.app.ui.components.CategoryIcons
import com.spendwise.app.ui.components.ChipRow
import com.spendwise.app.ui.components.GroupCard
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.ListRow
import com.spendwise.app.ui.components.MoneyInput
import com.spendwise.app.ui.components.MoneyText
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.components.TxnText
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class TxnDetailState(
    val row: TxnRow? = null,
    val splits: List<TxnSplitEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val refundable: Long = 0,
    val deleted: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = TransactionDetailViewModel.Factory::class)
class TransactionDetailViewModel @AssistedInject constructor(
    @Assisted private val txnId: Long,
    private val db: SpendWiseDatabase,
    private val ledger: TransactionLedger,
    private val clock: Clock,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(txnId: Long): TransactionDetailViewModel
    }

    val message = MutableStateFlow<String?>(null)
    private val deleted = MutableStateFlow(false)

    private val row = db.txnDao().observeRow(txnId)

    private val categories = row.flatMapLatest { r ->
        if (r == null) flowOf(emptyList())
        else db.categoryDao().observeVisible(r.accountId, if (r.amountPaise > 0) CategoryKind.INCOME else CategoryKind.EXPENSE)
    }

    val state: StateFlow<TxnDetailState> = combine(row, db.txnDao().observeSplits(txnId), categories, deleted) { r, s, c, d ->
        TxnDetailState(r, s, c, deleted = d)
    }.mapLatest { st ->
        val r = st.row ?: return@mapLatest st
        val refundable = if ((r.kind == TxnKind.EXPENSE || r.kind == TxnKind.FEE) && r.status == com.spendwise.app.data.db.entity.TxnStatus.CONFIRMED) {
            abs(r.amountPaise) - db.txnDao().refundedSoFar(r.id)
        } else {
            0
        }
        st.copy(refundable = refundable)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxnDetailState())

    /** Guards against a double tap creating two refunds (each would fit under the cap on its own). */
    private var busy = false

    private fun run(block: suspend () -> LedgerResult, onDone: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                when (val r = block()) {
                    is LedgerResult.Ok -> { message.value = null; onDone() }
                    is LedgerResult.Rejected -> message.value = r.reason
                }
            } finally {
                busy = false
            }
        }
    }

    fun refund(paise: Long, onDone: () -> Unit) = run({ ledger.addRefund(txnId, paise, clock.now(), null) }, onDone)

    fun split(parts: List<SplitPart>, onDone: () -> Unit) = run({ ledger.setSplits(txnId, parts) }, onDone)

    fun unsplit() = viewModelScope.launch { ledger.removeSplits(txnId, state.value.splits.maxByOrNull { abs(it.amountPaise) }?.categoryId) }

    fun delete() = viewModelScope.launch {
        when (val r = ledger.delete(txnId)) {
            is LedgerResult.Ok -> deleted.value = true
            is LedgerResult.Rejected -> message.value = r.reason
        }
    }
}

private enum class Panel { None, Refund, Split }
private val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy · h:mm a")

@Composable
fun TransactionDetailScreen(
    txnId: Long,
    onClose: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: TransactionDetailViewModel = hiltViewModel<TransactionDetailViewModel, TransactionDetailViewModel.Factory>(
        key = "txn-$txnId",
    ) { it.create(txnId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val colors = SpendWise.colors
    var panel by remember { mutableStateOf(Panel.None) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = { if (panel != Panel.None) panel = Panel.None else onClose() })
    LaunchedEffect(state.deleted) { if (state.deleted) onClose() }

    val row = state.row
    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = row?.let(TxnText::title).orEmpty(), eyebrow = row?.let { TxnText.kindLabel(it.kind) }, reserveBottomBar = false) {
            if (row == null) return@LargeTitleScreen
            val isTransfer = row.kind.isTransferFamily
            item(key = "hero") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screen)
                        .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
                        .padding(Spacing.lg),
                ) {
                    MoneyText(
                        row.amountPaise,
                        style = SpendWise.money.hero,
                        color = when {
                            isTransfer -> colors.transfer
                            row.amountPaise > 0 -> colors.income
                            else -> colors.text1
                        },
                        sign = Money.SignStyle.Always,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        FULL_DATE.format(java.time.Instant.ofEpochMilli(row.occurredAt).atZone(IndiaTime.ZONE)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.text2,
                    )
                }
            }
            item(key = "info-label") { SectionLabel("Details") }
            item(key = "info") {
                GroupCard {
                    if (isTransfer) {
                        InfoRow("Between your accounts", row.counterpartyAccount?.let { if (row.amountPaise < 0) "To $it" else "From $it" } ?: "—")
                        InfoRow("Counts as", "Transfer — never income or spending")
                    } else {
                        InfoRow("Category", if (row.hasSplits) "Split (${state.splits.size} parts)" else row.categoryName ?: "Uncategorised")
                        row.merchantRaw?.let { InfoRow(if (row.amountPaise > 0) "From" else "Paid to", it) }
                    }
                    TxnText.method(row.paymentMethod)?.let { InfoRow("Paid by", it) }
                    row.note?.let { InfoRow("Note", it) }
                    if (row.refundOfId != null) InfoRow("Refund of", "an earlier purchase")
                }
            }
            if (row.hasSplits) {
                item(key = "splits-label") { SectionLabel("Split") }
                item(key = "splits") {
                    GroupCard {
                        val names = state.categories.associateBy { it.id }
                        state.splits.forEachIndexed { i, part ->
                            ListRow(
                                title = names[part.categoryId]?.name ?: "Category",
                                leadingIcon = CategoryIcons.of(names[part.categoryId]?.iconKey),
                            ) { MoneyText(part.amountPaise, style = SpendWise.money.row) }
                            if (i < state.splits.lastIndex) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                        }
                    }
                }
            }

            item(key = "actions-label") { SectionLabel("Actions") }
            item(key = "actions") {
                GroupCard {
                    if (row.kind != TxnKind.REFUND) {
                        ListRow(title = "Edit", leadingIcon = Icons.Rounded.Edit, onClick = { onEdit(row.id) })
                        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    }
                    if (!isTransfer && row.kind != TxnKind.REFUND) {
                        if (row.hasSplits) {
                            ListRow(title = "Remove split", subtitle = "Back to one category", leadingIcon = Icons.Rounded.CallSplit, onClick = viewModel::unsplit)
                        } else {
                            ListRow(title = "Split", subtitle = "Across categories", leadingIcon = Icons.Rounded.CallSplit, onClick = { panel = Panel.Split })
                        }
                        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    }
                    if (state.refundable > 0) {
                        ListRow(
                            title = "Add refund",
                            subtitle = "Up to ${Money.format(state.refundable)}",
                            leadingIcon = Icons.AutoMirrored.Rounded.Undo,
                            onClick = { panel = Panel.Refund },
                        )
                        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    }
                    ListRow(
                        title = "Delete",
                        subtitle = if (isTransfer) "Removes both sides of the transfer" else null,
                        leadingIcon = Icons.Rounded.Delete,
                        leadingTint = colors.danger,
                        onClick = { confirmDelete = true },
                    )
                }
            }
            item(key = "panel") {
                AnimatedVisibility(panel == Panel.Refund) {
                    RefundPanel(state.refundable, onCancel = { panel = Panel.None }) { paise -> viewModel.refund(paise) { panel = Panel.None } }
                }
                AnimatedVisibility(panel == Panel.Split) {
                    SplitPanel(abs(row.amountPaise), row.categoryId, state.categories, onCancel = { panel = Panel.None }) { parts ->
                        viewModel.split(parts) { panel = Panel.None }
                    }
                }
                message?.let {
                    Text(it, color = colors.danger, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = Spacing.screen + Spacing.xxs, vertical = Spacing.sm))
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = Spacing.xs)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.text1)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text("The account balance will change back. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = colors.surface1,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SpendWise.colors.text2)
        Spacer(Modifier.padding(horizontal = Spacing.xs))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = SpendWise.colors.text1)
    }
}

@Composable
private fun RefundPanel(maxPaise: Long, onCancel: () -> Unit, onSave: (Long) -> Unit) {
    var amount by remember { mutableStateOf(Money.format(maxPaise, Money.SignStyle.None).removePrefix("₹")) }
    var error by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(Spacing.md))
    GroupCard {
        Column(Modifier.padding(Spacing.md)) {
            Text("Refund received", style = MaterialTheme.typography.titleSmall, color = SpendWise.colors.text1)
            Spacer(Modifier.height(Spacing.xs))
            MoneyInput(amount, { amount = it; error = null }, "Amount", error = error, supporting = "Up to ${Money.format(maxPaise)}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel", color = SpendWise.colors.text2) }
                TextButton(onClick = {
                    val p = parseSignedRupees(amount)?.takeIf { it in 1..maxPaise }
                    if (p == null) error = "Enter up to ${Money.format(maxPaise)}" else onSave(p)
                }) { Text("Save", color = SpendWise.colors.primary) }
            }
        }
    }
}

private class PartDraft(categoryId: Long?, amount: String) {
    var categoryId by mutableStateOf(categoryId)
    var amount by mutableStateOf(amount)
}

@Composable
private fun SplitPanel(totalPaise: Long, currentCategory: Long?, categories: List<CategoryEntity>, onCancel: () -> Unit, onSave: (List<SplitPart>) -> Unit) {
    val colors = SpendWise.colors
    val parts = remember { mutableStateListOf(PartDraft(currentCategory, Money.format(totalPaise, Money.SignStyle.None).removePrefix("₹")), PartDraft(null, "")) }
    val entered = parts.sumOf { parseSignedRupees(it.amount) ?: 0L }
    val remaining = totalPaise - entered
    Spacer(Modifier.height(Spacing.md))
    GroupCard {
        Column(Modifier.padding(vertical = Spacing.md)) {
            Text(
                "Split ${Money.format(totalPaise)}",
                style = MaterialTheme.typography.titleSmall,
                color = colors.text1,
                modifier = Modifier.padding(horizontal = Spacing.md),
            )
            Text(
                if (remaining == 0L) "Adds up exactly" else if (remaining > 0) "${Money.format(remaining)} left to assign" else "${Money.format(-remaining)} too much",
                style = MaterialTheme.typography.bodySmall,
                color = if (remaining == 0L) colors.income else colors.pending,
                modifier = Modifier.padding(horizontal = Spacing.md),
            )
            parts.forEachIndexed { i, part ->
                Spacer(Modifier.height(Spacing.sm))
                ChipRow(
                    options = categories,
                    selected = categories.firstOrNull { it.id == part.categoryId },
                    label = { it.name },
                    icon = { CategoryIcons.of(it.iconKey) },
                    onSelect = { part.categoryId = it.id },
                )
                Spacer(Modifier.height(Spacing.xs))
                MoneyInput(
                    part.amount,
                    { part.amount = it },
                    "Part ${i + 1}",
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { parts.add(PartDraft(null, if (remaining > 0) Money.format(remaining, Money.SignStyle.None).removePrefix("₹") else "")) }) {
                    Text("Add part", color = colors.primary)
                }
                Row {
                    TextButton(onClick = onCancel) { Text("Cancel", color = colors.text2) }
                    TextButton(
                        enabled = remaining == 0L && parts.all { it.categoryId != null && (parseSignedRupees(it.amount) ?: 0) > 0 },
                        onClick = { onSave(parts.map { SplitPart(it.categoryId!!, parseSignedRupees(it.amount)!!) }) },
                    ) { Text("Save split") }
                }
            }
        }
    }
}
