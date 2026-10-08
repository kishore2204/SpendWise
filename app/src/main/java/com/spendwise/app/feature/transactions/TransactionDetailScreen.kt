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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SwapHoriz
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.spendwise.app.data.db.entity.Confidence
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.data.db.entity.TxnStatus
import com.spendwise.app.transaction.ingest.IngestProcessor
import com.spendwise.app.ui.components.LiquidPrimaryButton
import com.spendwise.app.data.db.entity.TxnSplitEntity
import com.spendwise.app.domain.LedgerResult
import com.spendwise.app.domain.AccountLedger
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
import kotlinx.coroutines.flow.map
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
    /** Phase 5 review: other-account legs that could be the other side of this one (never paired automatically). */
    val pairSuggestions: List<PairSuggestion> = emptyList(),
    /** The SMS landed shortly after a balance the user entered — it may already be included (ADR-004 latency). */
    val nearAnchor: Boolean = false,
    /** For an unlinked refund/reversal: purchases it may pay back. */
    val refundOf: List<com.spendwise.app.data.db.entity.TxnEntity> = emptyList(),
    /** Friends' parts of this bill (ADR-011). */
    val shares: List<com.spendwise.app.data.db.dao.ShareRow> = emptyList(),
    /** The bank SMS this transaction came from (empty for manual entries). */
    val sources: List<com.spendwise.app.data.db.entity.RawMessageEntity> = emptyList(),
    val accountLabel: String? = null,
    val smsBalanceComparison: AccountLedger.SmsBalanceComparison? = null,
    val displayedBalancePaise: Long? = null,
)

data class PairSuggestion(val txnId: Long, val accountName: String, val sharedRef: Boolean, val otherPending: Boolean, val otherAt: Long)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = TransactionDetailViewModel.Factory::class)
class TransactionDetailViewModel @AssistedInject constructor(
    @Assisted private val txnId: Long,
    private val db: SpendWiseDatabase,
    private val ledger: TransactionLedger,
    private val clock: Clock,
    private val shared: com.spendwise.app.domain.SharedExpenseLedger,
    private val categoryLedger: com.spendwise.app.domain.CategoryLedger,
    private val accountLedger: AccountLedger,
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

    private val displayedBalance = row.flatMapLatest { r ->
        if (r == null) flowOf(null) else db.ledgerDao().observeDisplayedBalance(r.accountId).map { it }
    }

    val state: StateFlow<TxnDetailState> = combine(
        combine(row, db.txnDao().observeSplits(txnId), categories, deleted, db.friendDao().observeSharesOfBill(txnId)) { r, s, c, d, sh ->
            TxnDetailState(r, s, c, deleted = d, shares = sh)
        },
        displayedBalance,
    ) { base, balance -> base.copy(displayedBalancePaise = balance) }.mapLatest { st ->
        val r = st.row ?: return@mapLatest st
        // What is neither refunded nor a friend's part: the room left for a refund or another share (ADR-011).
        val refundable = if ((r.kind == TxnKind.EXPENSE || r.kind == TxnKind.FEE) && r.status == com.spendwise.app.data.db.entity.TxnStatus.CONFIRMED) {
            abs(r.amountPaise) - db.txnDao().refundedSoFar(r.id) - st.shares.sumOf { it.amountPaise }
        } else {
            0
        }
        val pending = r.status == TxnStatus.PENDING
        val suggestions = if (r.status != TxnStatus.IGNORED && r.counterpartyAccount == null) {
            val self = db.txnDao().getById(r.id)
            ledger.transferSuggestions(r.id).mapNotNull { o ->
                val account = db.accountDao().getById(o.accountId) ?: return@mapNotNull null
                PairSuggestion(
                    o.id,
                    "${account.nickname} ••${account.last4}".trimEnd(' ', '•'),
                    self?.bankRef != null && self.bankRef == o.bankRef,
                    otherPending = o.status == TxnStatus.PENDING,
                    otherAt = o.occurredAt,
                )
            }
        } else {
            emptyList()
        }
        val entity = db.txnDao().getById(r.id)
        val sources = db.rawMessageDao().sourcesFor(r.id)
        val account = db.accountDao().getById(r.accountId)
        val balanceComparison = if (
            r.status == TxnStatus.CONFIRMED && entity?.reportedBalancePaise != null && entity.reportedBalanceAt != null
        ) {
            accountLedger.compareSmsBalance(r.accountId, entity.reportedBalancePaise, entity.reportedBalanceAt)
        } else null
        val nearAnchor = pending && entity != null && db.ledgerDao().latestAnchor(r.accountId)?.let { a ->
            IngestProcessor.anchorMayCover(a.effectiveAt, entity.occurredAt, entity.timeSource, entity.createdAt)
        } == true
        st.copy(
            refundable = refundable,
            pairSuggestions = suggestions,
            nearAnchor = nearAnchor,
            sources = sources,
            accountLabel = account?.let { "${it.nickname} ••${it.last4}".trimEnd(' ', '•') },
            smsBalanceComparison = balanceComparison,
            refundOf = ledger.refundSuggestions(r.id),
        )
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

    // ---- Shared expenses (ADR-011)
    val friends = db.friendDao().observeFriends().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settlements = db.friendDao().observeSettlementsOfCredit(txnId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Friends who still owe something (names only — amounts are per account, shown on the Friends screen). */
    val owingFriends = db.friendDao().observeOutstanding()
        .map { rows -> rows.distinctBy { it.friendId }.map { it.friendId to it.friendName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val repaymentSuggestions = combine(row, owingFriends) { r, _ -> r }
        .mapLatest { r -> if (r == null) emptyList() else shared.suggestionsFor(r.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun shareWith(friendName: String, paise: Long) = run({
        when (val f = shared.friendNamed(friendName)) {
            is LedgerResult.Ok -> shared.share(txnId, f.id, paise)
            is LedgerResult.Rejected -> f
        }
    }, {})

    /** A category typed while reviewing; [onResult] gets its id, or an error message. */
    fun createCategory(name: String, onResult: (Long?, String?) -> Unit) = viewModelScope.launch {
        val r = state.value.row
        val kind = if ((r?.amountPaise ?: 0) > 0) CategoryKind.INCOME else CategoryKind.EXPENSE
        when (val res = categoryLedger.createCategory(name, kind, r?.accountId)) {
            is LedgerResult.Ok -> onResult(res.id, null)
            is LedgerResult.Rejected -> onResult(null, res.reason)
        }
    }

    fun linkRefund(originalId: Long) = run({ ledger.linkRefund(txnId, originalId) }, {})

    fun removeShare(shareId: Long) = run({ shared.removeShare(shareId) }, {})
    fun forgive(shareId: Long) = run({ shared.forgive(shareId) }, {})
    fun markRepayment(friendId: Long) = run({ shared.markAsRepayment(txnId, friendId) }, {})
    fun undoRepayment() = run({ shared.undoRepayment(txnId) }, {})

    /** Review (Phase 5): the user's decision is the only thing that makes an SMS transaction count. */
    fun confirm(categoryId: Long?) = run({ ledger.confirm(txnId, categoryId) }, {})

    fun reconcileSmsBalance(comparison: AccountLedger.SmsBalanceComparison) {
        val transaction = state.value.row ?: return
        if (comparison.accountId != transaction.accountId) return
        run({ accountLedger.reconcileSmsBalance(transaction.id, comparison.accountId, comparison.reportedPaise, comparison.observedAt) }, {})
    }

    fun ignore(onDone: () -> Unit) = run({ ledger.ignore(txnId) }, onDone)

    fun pair(otherId: Long) = run({ ledger.pairAsTransfer(txnId, otherId) }, {})

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
    onOpenTransaction: (Long) -> Unit = {},
    onOpenFriend: (Long) -> Unit = {},
    viewModel: TransactionDetailViewModel = hiltViewModel<TransactionDetailViewModel, TransactionDetailViewModel.Factory>(
        key = "txn-$txnId",
    ) { it.create(txnId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val settlements by viewModel.settlements.collectAsStateWithLifecycle()
    val owingFriends by viewModel.owingFriends.collectAsStateWithLifecycle()
    val repaymentSuggestions by viewModel.repaymentSuggestions.collectAsStateWithLifecycle()
    val colors = SpendWise.colors
    var panel by remember { mutableStateOf(Panel.None) }
    var confirmDelete by remember { mutableStateOf(false) }
    var dismissSmsBalance by rememberSaveable(state.row?.id) { mutableStateOf(false) }
    BackHandler(onBack = { if (panel != Panel.None) panel = Panel.None else onClose() })
    LaunchedEffect(state.deleted) { if (state.deleted) onClose() }
    LaunchedEffect(state.row?.id, state.row?.status) { dismissSmsBalance = false }

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
                        color = if (row.amountPaise > 0) colors.income else colors.expense,
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
            if (row.status == TxnStatus.PENDING) {
                item(key = "review") {
                    ReviewPanel(
                        row = row,
                        categories = state.categories,
                        nearAnchor = state.nearAnchor,
                        onConfirm = viewModel::confirm,
                        onIgnore = { viewModel.ignore(onClose) },
                        onCreateCategory = viewModel::createCategory,
                    )
                }
            }
            if (state.sources.isNotEmpty()) {
                item(key = "sms") { com.spendwise.app.feature.sms.OriginalSmsCard(state.sources) }
            }
            if (state.pairSuggestions.isNotEmpty()) {
                item(key = "pair-label") { SectionLabel("Is this a transfer between your accounts?") }
                item(key = "pair") {
                    GroupCard {
                        state.pairSuggestions.forEachIndexed { i, s ->
                            ListRow(
                                title = if (row.amountPaise < 0) "Moved to ${s.accountName}" else "Came from ${s.accountName}",
                                subtitle = buildString {
                                    append(if (s.sharedRef) "Same bank reference" else "Same amount, minutes apart")
                                    append(" · ")
                                    append(TxnText.day(IndiaTime.localDate(s.otherAt), IndiaTime.localDate(System.currentTimeMillis())))
                                    if (s.otherPending) append(" · pairing confirms both sides")
                                },
                                leadingIcon = Icons.Rounded.SwapHoriz,
                                onClick = { viewModel.pair(s.txnId) },
                            ) { Text("Pair", color = colors.primary, style = MaterialTheme.typography.labelLarge) }
                            if (i < state.pairSuggestions.lastIndex) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                        }
                    }
                }
            }
            if (state.refundOf.isNotEmpty()) {
                item(key = "refund-of-label") { SectionLabel("Money back for which purchase?") }
                item(key = "refund-of") {
                    GroupCard {
                        state.refundOf.forEachIndexed { i, o ->
                            ListRow(
                                title = "${o.merchantRaw?.let(com.spendwise.app.core.merchant.Merchants::display) ?: "A purchase"} · ${Money.format(-o.amountPaise)}",
                                subtitle = TxnText.day(IndiaTime.localDate(o.occurredAt), IndiaTime.localDate(System.currentTimeMillis())) + " · nets it in that category",
                                leadingIcon = Icons.AutoMirrored.Rounded.Undo,
                                onClick = { viewModel.linkRefund(o.id) },
                            ) { Text("Link", color = colors.primary, style = MaterialTheme.typography.labelLarge) }
                            if (i < state.refundOf.lastIndex) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                        }
                    }
                }
            }
            // ---- Shared expenses (ADR-011)
            if (row.kind == TxnKind.EXPENSE && row.status == TxnStatus.CONFIRMED) {
                item(key = "shares") {
                    com.spendwise.app.feature.friends.BillSharesSection(
                        billPaise = row.amountPaise,
                        shares = state.shares,
                        friends = friends,
                        roomPaise = state.refundable,
                        onShare = viewModel::shareWith,
                        onRemove = viewModel::removeShare,
                        onForgive = viewModel::forgive,
                        onOpenFriend = onOpenFriend,
                    )
                }
            }
            if (row.amountPaise > 0 && (row.kind == TxnKind.INCOME || row.kind == TxnKind.UNKNOWN) && row.status != TxnStatus.IGNORED &&
                row.counterpartyAccount == null && row.refundOfId == null
            ) {
                item(key = "repayment") {
                    com.spendwise.app.feature.friends.RepaymentPrompt(repaymentSuggestions, owingFriends, onMark = viewModel::markRepayment)
                }
            }
            if (row.kind == TxnKind.REPAYMENT) {
                item(key = "repaid") {
                    com.spendwise.app.feature.friends.RepaymentDetails(settlements, fromSms = state.sources.isNotEmpty(), onOpenBill = onOpenTransaction, onUndo = viewModel::undoRepayment)
                }
            }
            item(key = "info-label") { SectionLabel("Details") }
            item(key = "info") {
                GroupCard {
                    if (isTransfer) {
                        InfoRow("Between your accounts", row.counterpartyAccount?.let { if (row.amountPaise < 0) "To $it" else "From $it" } ?: "—")
                        InfoRow("Counts as", "Transfer — never income or spending")
                    } else if (row.kind == TxnKind.REPAYMENT) {
                        InfoRow("Counts as", "A friend paying you back — not income")
                        row.merchantRaw?.let { InfoRow("From", it) }
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
                    if (row.kind != TxnKind.REFUND && row.kind != TxnKind.REPAYMENT) {
                        ListRow(title = "Edit", leadingIcon = Icons.Rounded.Edit, onClick = { onEdit(row.id) })
                        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    }
                    if (!isTransfer && row.kind != TxnKind.REFUND && row.kind != TxnKind.REPAYMENT) {
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

    val comparison = state.smsBalanceComparison
    if (!dismissSmsBalance && comparison != null && row?.status == TxnStatus.CONFIRMED) {
        AlertDialog(
            onDismissRequest = { dismissSmsBalance = true },
            title = { Text("Bank SMS balance differs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(state.accountLabel ?: "This account", style = MaterialTheme.typography.labelLarge, color = colors.text2)
                    Text("SMS reported ${Money.format(comparison.reportedPaise)} at ${FULL_DATE.format(java.time.Instant.ofEpochMilli(comparison.observedAt).atZone(IndiaTime.ZONE))}.")
                    Text("SpendWise estimates ${Money.format(comparison.estimatedAtSmsPaise)} at that time; current balance is ${Money.format(comparison.displayedNowPaise)}.")
                    Text("Reconciliation adds a balance adjustment and keeps later confirmed transactions. Holds or pending bank activity can also cause a difference.", style = MaterialTheme.typography.bodySmall, color = colors.text2)
                }
            },
            confirmButton = {
                TextButton(onClick = { dismissSmsBalance = true; viewModel.reconcileSmsBalance(comparison) }) {
                    Text("Reconcile", color = colors.primary)
                }
            },
            dismissButton = { TextButton(onClick = { dismissSmsBalance = true }) { Text("Not now", color = colors.text2) } },
            containerColor = colors.surface1,
        )
    }
}

/** Waiting for review: keep or change the suggested category, then Confirm — or Ignore. Nothing counts until then. */
@Composable
private fun ReviewPanel(
    row: TxnRow,
    categories: List<CategoryEntity>,
    nearAnchor: Boolean,
    onConfirm: (Long?) -> Unit,
    onIgnore: () -> Unit,
    onCreateCategory: (String, (Long?, String?) -> Unit) -> Unit,
) {
    val colors = SpendWise.colors
    var chosen by remember(row.id) { mutableStateOf(row.categoryId) }
    val categorisable = row.kind == TxnKind.EXPENSE || row.kind == TxnKind.INCOME || row.kind == TxnKind.FEE
    Spacer(Modifier.height(Spacing.md))
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
            .padding(vertical = Spacing.md),
    ) {
        Column(Modifier.padding(horizontal = Spacing.md)) {
            Text("Waiting for your review", style = MaterialTheme.typography.titleSmall, color = colors.pending)
            Spacer(Modifier.height(Spacing.xxs))
            val why = when {
                row.possibleDupOfId != null -> "This may repeat another transaction close in time. Ignore it if it's the same one."
                nearAnchor -> "This arrived just after you set the balance. If that balance already included it, ignore this."
                row.confidence != Confidence.HIGH -> "Read from an SMS format we're less sure about. Check the details."
                else -> "Detected from your bank's SMS. It won't count until you confirm it."
            }
            Text(why, style = MaterialTheme.typography.bodySmall, color = colors.text2)
        }
        if (categorisable && categories.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            com.spendwise.app.ui.components.CategoryPicker(
                categories = categories,
                selectedId = chosen,
                onSelect = { chosen = it },
                onCreate = { name, done -> onCreateCategory(name) { id, error -> if (id != null) chosen = id; done(error) } },
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onIgnore) { Text("Ignore", color = colors.text2) }
            LiquidPrimaryButton(
                text = "Confirm",
                icon = Icons.Rounded.Check,
                onClick = { onConfirm(chosen.takeIf { categorisable && it != row.categoryId }) },
                modifier = Modifier.weight(1f),
            )
        }
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
    var amount by remember { mutableStateOf(Money.format(maxPaise, Money.SignStyle.None).removePrefix("₹").trimStart()) }
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
    val parts = remember { mutableStateListOf(PartDraft(currentCategory, Money.format(totalPaise, Money.SignStyle.None).removePrefix("₹").trimStart()), PartDraft(null, "")) }
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
                TextButton(onClick = { parts.add(PartDraft(null, if (remaining > 0) Money.format(remaining, Money.SignStyle.None).removePrefix("₹").trimStart() else "")) }) {
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
