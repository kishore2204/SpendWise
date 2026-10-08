package com.spendwise.app.feature.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spendwise.app.data.account.SelectedAccountStore
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.domain.AccountLedger
import com.spendwise.app.domain.CreateAccountResult
import com.spendwise.app.domain.NewAccount
import com.spendwise.app.transaction.ingest.DetectedAccounts
import com.spendwise.app.transaction.parsing.BankRegistry
import com.spendwise.app.ui.components.GroupCard
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.LiquidPrimaryButton
import com.spendwise.app.ui.components.MoneyInput
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.components.SegmentedChoice
import com.spendwise.app.ui.components.TextInput
import com.spendwise.app.ui.components.parseSignedRupees
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddAccountForm(
    val bank: BankPreset = BankPreset.IndianBank,
    val otherBankName: String = "",
    val type: AccountType = AccountType.SAVINGS,
    val last4: String = "",
    val nickname: String = "",
    val opening: String = "",
    val error: String? = null,
    val fieldErrorLast4: String? = null,
    val fieldErrorAmount: String? = null,
    val saving: Boolean = false,
    val createdId: Long? = null,
    /** Bank code of an account detected in SMS (e.g. SBI): kept exactly, so its messages match the new account. */
    val detectedCode: String? = null,
) {
    val isCash get() = bank == BankPreset.Cash
    val isCreditCard get() = type == AccountType.CREDIT_CARD
    val bankName get() = if (bank == BankPreset.Other) otherBankName.trim() else bank.displayName
}

@HiltViewModel
class AddAccountViewModel @Inject constructor(
    private val ledger: AccountLedger,
    private val selected: SelectedAccountStore,
    private val detected: DetectedAccounts,
) : ViewModel() {
    private var prefilled = false

    /** "Add account" from a "New account detected" card: bank and digits come from the SMS. */
    fun prefill(bankCode: String?, last4: String?) {
        if (prefilled || bankCode == null) return
        prefilled = true
        val preset = BankPreset.entries.firstOrNull { it.code == bankCode && it != BankPreset.Other }
        _form.update {
            it.copy(
                bank = preset ?: BankPreset.Other,
                otherBankName = if (preset == null) BankRegistry.displayName(bankCode) else "",
                last4 = last4.orEmpty(),
                detectedCode = if (preset == null) bankCode else null,
            )
        }
    }

    private val _form = MutableStateFlow(AddAccountForm())
    val form: StateFlow<AddAccountForm> = _form.asStateFlow()

    fun update(transform: AddAccountForm.() -> AddAccountForm) =
        _form.update { it.transform().copy(error = null, fieldErrorLast4 = null, fieldErrorAmount = null) }

    fun selectBank(bank: BankPreset) = update {
        copy(bank = bank, type = if (bank == BankPreset.Cash) AccountType.CASH else if (type == AccountType.CASH) AccountType.SAVINGS else type)
    }

    fun save() {
        val f = _form.value
        if (f.saving) return
        val amount = parseSignedRupees(f.opening)
        if (amount == null) {
            _form.update { it.copy(fieldErrorAmount = "Enter an amount like 2,844.44 (use 0 if it is empty)") }
            return
        }
        if (!f.isCash && f.last4.length != 4) {
            _form.update { it.copy(fieldErrorLast4 = "Enter exactly 4 digits") }
            return
        }
        val code = when {
            f.detectedCode != null && f.bank == BankPreset.Other -> f.detectedCode
            f.bank == BankPreset.Other -> f.otherBankName.uppercase().filter(Char::isLetterOrDigit).take(8)
            else -> f.bank.code
        }
        _form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = ledger.createAccount(NewAccount(code, f.bankName, f.last4, f.nickname, f.type, amount))
            when (result) {
                is CreateAccountResult.Created -> {
                    // SMS that were waiting for this account (UNKNOWN_ACCOUNT) become pending transactions in it.
                    detected.reprocess()
                    // The first account becomes the one shown on Home.
                    if (selected.selectedAccountId.first() == null || selected.selectedAccountId.first() == result.accountId) {
                        selected.select(result.accountId)
                    }
                    _form.update { it.copy(saving = false, createdId = result.accountId) }
                }
                CreateAccountResult.Duplicate -> _form.update {
                    it.copy(saving = false, error = "${f.bankName} ••••${f.last4} is already in SpendWise.")
                }
                is CreateAccountResult.Invalid -> _form.update { it.copy(saving = false, error = result.reason) }
            }
        }
    }
}

@Composable
fun AddAccountScreen(
    onClose: () -> Unit,
    onCreated: (Long) -> Unit,
    prefillBankCode: String? = null,
    prefillLast4: String? = null,
    viewModel: AddAccountViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    LaunchedEffect(prefillBankCode, prefillLast4) { viewModel.prefill(prefillBankCode, prefillLast4) }
    val colors = SpendWise.colors
    BackHandler(onBack = onClose)
    LaunchedEffect(form.createdId) { form.createdId?.let(onCreated) }

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = "Add account", reserveBottomBar = false) {
            item(key = "bank-label") { SectionLabel("Bank") }
            item(key = "bank") {
                GroupCard {
                    Column(Modifier.padding(Spacing.sm)) {
                        SegmentedChoice(
                            options = BankPreset.entries,
                            selected = form.bank,
                            label = { if (it == BankPreset.IndianBank) "Indian" else if (it == BankPreset.Hdfc) "HDFC" else it.displayName },
                            onSelect = viewModel::selectBank,
                        )
                        if (form.bank == BankPreset.Other) {
                            Spacer(Modifier.height(Spacing.sm))
                            TextInput(form.otherBankName, { v -> viewModel.update { copy(otherBankName = v) } }, "Bank name")
                        }
                    }
                }
            }
            if (!form.isCash) {
                item(key = "type-label") { SectionLabel("Account type") }
                item(key = "type") {
                    GroupCard {
                        SegmentedChoice(
                            options = listOf(AccountType.SAVINGS, AccountType.CURRENT, AccountType.CREDIT_CARD, AccountType.OTHER),
                            selected = form.type,
                            label = { if (it == AccountType.CREDIT_CARD) "Card" else AccountVisuals.typeLabel(it) },
                            onSelect = { t -> viewModel.update { copy(type = t) } },
                            modifier = Modifier.padding(Spacing.sm),
                        )
                    }
                }
            }
            item(key = "details-label") { SectionLabel("Details") }
            item(key = "details") {
                GroupCard {
                    Column(Modifier.padding(Spacing.md)) {
                        if (!form.isCash) {
                            TextInput(
                                value = form.last4,
                                onValueChange = { v -> viewModel.update { copy(last4 = v.filter(Char::isDigit).take(4)) } },
                                label = "Last 4 digits",
                                keyboardType = KeyboardType.Number,
                                error = form.fieldErrorLast4,
                                supporting = "Used to match this bank's SMS to this account",
                            )
                            Spacer(Modifier.height(Spacing.xs))
                        }
                        TextInput(
                            value = form.nickname,
                            onValueChange = { v -> viewModel.update { copy(nickname = v.take(24)) } },
                            label = "Name (optional)",
                            supporting = "Shown in the account switcher, e.g. \"${form.bankName.ifEmpty { "Salary" }}\"",
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        MoneyInput(
                            value = form.opening,
                            onValueChange = { v -> viewModel.update { copy(opening = v) } },
                            label = if (form.isCreditCard) "Amount outstanding" else "Current balance",
                            error = form.fieldErrorAmount,
                            supporting = if (form.isCreditCard) "What you owe on this card today" else "What the bank shows today. You can reconcile any time.",
                        )
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
                    text = if (form.saving) "Creating…" else "Create account",
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
}
