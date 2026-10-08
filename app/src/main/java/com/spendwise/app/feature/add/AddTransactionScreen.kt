package com.spendwise.app.feature.add

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spendwise.app.core.money.Money
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.account.SelectedAccountStore
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.db.entity.CategoryEntity
import com.spendwise.app.data.db.entity.CategoryKind
import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.domain.EntryInput
import com.spendwise.app.domain.LedgerResult
import com.spendwise.app.domain.TransactionLedger
import com.spendwise.app.feature.accounts.AccountVisuals
import com.spendwise.app.ui.components.CategoryIcons
import com.spendwise.app.ui.components.ChipRow
import com.spendwise.app.ui.components.GroupCard
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.ListRow
import com.spendwise.app.ui.components.LiquidPrimaryButton
import com.spendwise.app.ui.components.MoneyInput
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.components.SegmentedChoice
import com.spendwise.app.ui.components.TextInput
import com.spendwise.app.ui.components.TxnText
import com.spendwise.app.ui.components.parseSignedRupees
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

enum class EntryMode(val label: String) { Expense("Expense"), Income("Income"), Transfer("Transfer") }

data class EntryForm(
    val mode: EntryMode = EntryMode.Expense,
    val amount: String = "",
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val categoryPickedByUser: Boolean = false,
    val merchant: String = "",
    val method: PaymentMethod = PaymentMethod.UPI,
    val note: String = "",
    val occurredAt: Long = 0,
    val amountError: String? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
    /** Edit mode: the transaction being edited (transfer legs edit the whole transfer). */
    val editId: Long? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = AddTransactionViewModel.Factory::class)
class AddTransactionViewModel @AssistedInject constructor(
    @Assisted private val editId: Long?,
    private val ledger: TransactionLedger,
    private val db: SpendWiseDatabase,
    repository: AccountRepository,
    private val selected: SelectedAccountStore,
    private val clock: Clock,
    private val categoryLedger: com.spendwise.app.domain.CategoryLedger,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(editId: Long?): AddTransactionViewModel
    }

    private val _form = MutableStateFlow(EntryForm(occurredAt = clock.now(), editId = editId))
    val form: StateFlow<EntryForm> = _form.asStateFlow()

    val accounts: StateFlow<List<AccountEntity>> = repository.observeAccountsWithBalances()
        .map { list -> list.map { it.account } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Categories visible for the chosen account and kind (its own overlay — ADR-001). */
    val categories: StateFlow<List<CategoryEntity>> = _form
        .map { it.accountId to (if (it.mode == EntryMode.Income) CategoryKind.INCOME else CategoryKind.EXPENSE) }
        .flatMapLatest { (id, kind) -> if (id == null) flowOf(emptyList()) else db.categoryDao().observeVisible(id, kind) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var suggestJob: Job? = null

    /** When editing an SMS-detected transaction: the message(s) it came from, shown for reference. */
    private val _sources = MutableStateFlow<List<com.spendwise.app.data.db.entity.RawMessageEntity>>(emptyList())
    val sources: StateFlow<List<com.spendwise.app.data.db.entity.RawMessageEntity>> = _sources.asStateFlow()

    init {
        viewModelScope.launch {
            if (editId != null) _sources.value = db.rawMessageDao().sourcesFor(editId)
            if (editId != null) loadForEdit(editId) else _form.update { it.copy(accountId = selected.selectedAccountId.first()) }
        }
    }

    private suspend fun loadForEdit(id: Long) {
        val t = db.txnDao().getById(id) ?: return
        val link = db.txnDao().transferFor(id)
        val mode = when {
            link != null -> EntryMode.Transfer
            t.amountPaise > 0 -> EntryMode.Income
            else -> EntryMode.Expense
        }
        val (from, to) = if (link != null) {
            db.txnDao().getById(link.fromTxnId)?.accountId to db.txnDao().getById(link.toTxnId)?.accountId
        } else {
            t.accountId to null
        }
        _form.update {
            it.copy(
                mode = mode,
                amount = Money.format(abs(t.amountPaise), Money.SignStyle.None).removePrefix("₹").trimStart(),
                accountId = from,
                toAccountId = to,
                categoryId = t.categoryId,
                categoryPickedByUser = true,
                merchant = if (link != null) "" else t.merchantRaw.orEmpty(),
                method = t.paymentMethod.takeIf { m -> m != PaymentMethod.UNKNOWN } ?: PaymentMethod.UPI,
                note = t.note.orEmpty(),
                occurredAt = t.occurredAt,
            )
        }
    }

    fun update(transform: EntryForm.() -> EntryForm) = _form.update { it.transform().copy(error = null, amountError = null) }

    fun setMode(mode: EntryMode) = update { copy(mode = mode, categoryId = null, categoryPickedByUser = false) }

    fun pickCategory(id: Long) = update { copy(categoryId = id, categoryPickedByUser = true) }

    /** "+ New" category typed by the user: created for every account, then picked. */
    fun createCategory(name: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        val kind = if (_form.value.mode == EntryMode.Income) CategoryKind.INCOME else CategoryKind.EXPENSE
        when (val r = categoryLedger.createCategory(name, kind, _form.value.accountId)) {
            is com.spendwise.app.domain.LedgerResult.Ok -> { pickCategory(r.id); onResult(null) }
            is com.spendwise.app.domain.LedgerResult.Rejected -> onResult(r.reason)
        }
    }

    /** Categories are per-account (overlay), so switching account clears the choice and re-suggests. */
    fun selectAccount(id: Long) {
        update { copy(accountId = id, toAccountId = toAccountId.takeIf { it != id }, categoryId = null, categoryPickedByUser = false) }
        setMerchant(_form.value.merchant)
    }

    /** As the merchant is typed, suggest a category (learned rule → dictionary) unless the user picked one. */
    fun setMerchant(text: String) {
        update { copy(merchant = text) }
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            delay(250)
            val f = _form.value
            if (f.categoryPickedByUser || f.accountId == null) return@launch
            val wanted = if (f.mode == EntryMode.Income) CategoryKind.INCOME else CategoryKind.EXPENSE
            val suggestion = ledger.suggestCategory(f.accountId, text, wanted)
            // Drop the result if the user changed account or picked a category meanwhile.
            _form.update { if (it.categoryPickedByUser || it.accountId != f.accountId) it else it.copy(categoryId = suggestion) }
        }
    }

    fun setDate(date: LocalDate) {
        // Keep the time of day; only the date changes.
        val time = Instant.ofEpochMilli(_form.value.occurredAt).atZone(IndiaTime.ZONE).toLocalTime()
        update { copy(occurredAt = date.atTime(time).atZone(IndiaTime.ZONE).toInstant().toEpochMilli()) }
    }

    fun save() {
        val f = _form.value
        if (f.saving) return
        val amount = parseSignedRupees(f.amount)?.takeIf { it > 0 }
        if (amount == null) {
            _form.update { it.copy(amountError = "Enter an amount above zero") }
            return
        }
        val account = f.accountId ?: run { _form.update { it.copy(error = "Choose an account") }; return }
        _form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = when {
                f.mode == EntryMode.Transfer -> {
                    val to = f.toAccountId ?: run { _form.update { it.copy(saving = false, error = "Choose where the money went") }; return@launch }
                    if (editId != null) ledger.editTransfer(editId, amount, f.occurredAt, f.note)
                    else ledger.addTransfer(account, to, amount, f.occurredAt, f.note)
                }
                editId != null -> ledger.editEntry(editId, amount, f.categoryId, f.merchant, f.note, f.occurredAt, f.method)
                else -> ledger.addEntry(
                    EntryInput(
                        accountId = account,
                        kind = if (f.mode == EntryMode.Income) TxnKind.INCOME else TxnKind.EXPENSE,
                        amountPaise = amount,
                        categoryId = f.categoryId,
                        merchant = f.merchant,
                        note = f.note,
                        occurredAt = f.occurredAt,
                        paymentMethod = f.method,
                    ),
                )
            }
            _form.update {
                when (result) {
                    is LedgerResult.Ok -> it.copy(saving = false, done = true)
                    is LedgerResult.Rejected -> it.copy(saving = false, error = result.reason)
                }
            }
        }
    }
}

private val METHODS = listOf(PaymentMethod.UPI, PaymentMethod.CARD, PaymentMethod.CASH, PaymentMethod.NETBANKING, PaymentMethod.OTHER)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(
    onClose: () -> Unit,
    editId: Long? = null,
    viewModel: AddTransactionViewModel = hiltViewModel<AddTransactionViewModel, AddTransactionViewModel.Factory>(
        key = "entry-${editId ?: "new"}",
    ) { it.create(editId) },
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val colors = SpendWise.colors
    var showDate by remember { mutableStateOf(false) }
    val amountFocus = remember { FocusRequester() }
    val editing = form.editId != null

    BackHandler(onBack = onClose)
    LaunchedEffect(form.done) { if (form.done) onClose() }
    LaunchedEffect(Unit) { if (!editing) runCatching { amountFocus.requestFocus() } }

    val title = when {
        editing -> "Edit ${form.mode.label.lowercase()}"
        else -> "New ${form.mode.label.lowercase()}"
    }

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = title, reserveBottomBar = false) {
            if (!editing) {
                item(key = "mode") {
                    GroupCard {
                        SegmentedChoice(
                            options = EntryMode.entries,
                            selected = form.mode,
                            label = { it.label },
                            onSelect = viewModel::setMode,
                            modifier = Modifier.padding(Spacing.sm),
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                }
            }
            if (sources.isNotEmpty()) {
                item(key = "sms") {
                    com.spendwise.app.feature.sms.OriginalSmsCard(sources)
                    Spacer(Modifier.height(Spacing.md))
                }
            }
            item(key = "amount") {
                GroupCard {
                    Column(Modifier.padding(Spacing.md)) {
                        MoneyInput(
                            value = form.amount,
                            onValueChange = { v -> viewModel.update { copy(amount = v) } },
                            label = "Amount",
                            error = form.amountError,
                            modifier = Modifier.focusRequester(amountFocus),
                        )
                    }
                }
            }

            // Accounts: never editable on an existing transaction (moving money between accounts is a new entry).
            if (!editing && accounts.size > 1 || form.mode == EntryMode.Transfer && !editing) {
                item(key = "from-label") { SectionLabel(if (form.mode == EntryMode.Transfer) "From" else "Account") }
                item(key = "from") {
                    ChipRow(
                        options = accounts,
                        selected = accounts.firstOrNull { it.id == form.accountId },
                        label = AccountVisuals::short,
                        icon = { AccountVisuals.icon(it.type) },
                        onSelect = { a -> viewModel.selectAccount(a.id) },
                    )
                }
            }
            if (form.mode == EntryMode.Transfer && !editing) {
                item(key = "to-label") { SectionLabel("To") }
                item(key = "to") {
                    ChipRow(
                        options = accounts.filter { it.id != form.accountId },
                        selected = accounts.firstOrNull { it.id == form.toAccountId },
                        label = AccountVisuals::short,
                        icon = { AccountVisuals.icon(it.type) },
                        onSelect = { a -> viewModel.update { copy(toAccountId = a.id) } },
                    )
                }
            }

            if (form.mode != EntryMode.Transfer) {
                item(key = "merchant-label") { SectionLabel(if (form.mode == EntryMode.Income) "From" else "Paid to") }
                item(key = "merchant") {
                    GroupCard {
                        Column(Modifier.padding(Spacing.md)) {
                            TextInput(form.merchant, viewModel::setMerchant, if (form.mode == EntryMode.Income) "Payer (optional)" else "Merchant or person")
                        }
                    }
                }
                item(key = "category-label") { SectionLabel("Category") }
                item(key = "category") {
                    com.spendwise.app.ui.components.CategoryPicker(
                        categories = categories,
                        selectedId = form.categoryId,
                        onSelect = viewModel::pickCategory,
                        onCreate = viewModel::createCategory,
                    )
                }
                item(key = "method-label") { SectionLabel("Paid by") }
                item(key = "method") {
                    ChipRow(
                        options = METHODS,
                        selected = form.method,
                        label = { TxnText.method(it) ?: "Other" },
                        onSelect = { m -> viewModel.update { copy(method = m) } },
                    )
                }
            }

            item(key = "when-label") { SectionLabel("Details") }
            item(key = "details") {
                GroupCard {
                    val date = IndiaTime.localDate(form.occurredAt)
                    ListRow(
                        title = TxnText.day(date, IndiaTime.localDate(System.currentTimeMillis())),
                        subtitle = TxnText.time(form.occurredAt),
                        leadingIcon = Icons.Rounded.CalendarMonth,
                        onClick = { showDate = true },
                    )
                    Column(Modifier.padding(horizontal = Spacing.md).padding(bottom = Spacing.md)) {
                        TextInput(form.note, { v -> viewModel.update { copy(note = v.take(120)) } }, "Note (optional)")
                    }
                }
            }
            if (form.error != null) {
                item(key = "error") {
                    Text(
                        form.error.orEmpty(),
                        color = colors.danger,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = Spacing.screen + Spacing.xxs, vertical = Spacing.sm),
                    )
                }
            }
            item(key = "save") {
                Spacer(Modifier.height(Spacing.lg))
                LiquidPrimaryButton(
                    text = if (form.saving) "Saving…" else if (editing) "Save changes" else "Save ${form.mode.label.lowercase()}",
                    onClick = viewModel::save,
                    icon = Icons.Rounded.Check,
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                )
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

    if (showDate) {
        val initial = IndiaTime.localDate(form.occurredAt).toEpochDay() * 86_400_000L
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { viewModel.setDate(LocalDate.ofEpochDay(it / 86_400_000L)) }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

